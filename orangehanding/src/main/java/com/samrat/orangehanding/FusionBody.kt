package com.samrat.orangehanding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.nio.channels.FileChannel
import kotlin.math.max

/**
 * The whole body: YOLO11 finds the person and follows them, MediaPipe Pose reads 33 landmarks
 * (with metric 3D "world" coordinates) inside a square around that person, and the two are fused:
 * where MediaPipe is unsure of a joint and YOLO is sure, YOLO's keypoint wins. A One Euro filter
 * then keeps the skeleton calm without lag.
 *
 * Seeing the person first is what lets a small figure far away in a mirror still get a full,
 * sharp skeleton: MediaPipe gets them enlarged instead of as a few pixels of a wide frame.
 */
class FusionBody(
    context: Context,
    quality: Quality = Quality.FULL,
    useGpu: Boolean = true,
) : AutoCloseable {
    enum class Quality { FULL, HEAVY }

    /** One body. Arrays hold 33 MediaPipe landmarks; [image] is x, y (0..1), visibility; [world] is x, y, z in metres around the hips. */
    class Pose(
        val timestampMs: Long,
        val image: FloatArray,
        val world: FloatArray,
        /** Where YOLO saw the person (0..1), or null when MediaPipe found them alone. */
        val box: RectF?,
        /** How many landmarks were taken from YOLO11 in this frame. */
        val fromYolo: Int,
    ) {
        fun x(i: Int) = image[i * 3]
        fun y(i: Int) = image[i * 3 + 1]
        fun visibility(i: Int) = image[i * 3 + 2]
        fun wx(i: Int) = world[i * 3]
        fun wy(i: Int) = world[i * 3 + 1]
        fun wz(i: Int) = world[i * 3 + 2]
    }

    private val pose: PoseLandmarker
    private val yolo: Yolo11Pose? = runCatching { Yolo11Pose(context, threads = 2) }.getOrNull()
    private var lastTimestamp = -1L
    private var box: RectF? = null
    private var missed = 0
    private var frames = 0
    /** Whether MediaPipe found the body in the last frame. */
    private var tracking = false
    private val filters = Array(33 * 5) { OneEuro(minCutoff = 1.1f, beta = 4f) }

    val fused get() = yolo != null

    init {
        val base = BaseOptions.builder().setDelegate(if (useGpu) Delegate.GPU else Delegate.CPU)
        val heavy = heavyFile(context)
        if (quality == Quality.HEAVY && heavy.length() > HEAVY_BYTES / 2) {
            RandomAccessFile(heavy, "r").use { file ->
                base.setModelAssetBuffer(file.channel.map(FileChannel.MapMode.READ_ONLY, 0, file.length()))
            }
        } else {
            base.setModelAssetPath(FULL_MODEL)
        }
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(base.build())
            .setRunningMode(RunningMode.VIDEO)
            .setNumPoses(1)
            .setMinPoseDetectionConfidence(.4f)
            .setMinPosePresenceConfidence(.4f)
            .setMinTrackingConfidence(.4f)
            .build()
        pose = PoseLandmarker.createFromOptions(context, options)
    }

    /** The person in [frame], or null when nobody is there. */
    @Synchronized
    fun detect(frame: Bitmap, timestampMs: Long): Pose? {
        val stamp = maxOf(timestampMs, lastTimestamp + 1)
        lastTimestamp = stamp
        // YOLO looks for the person when there is none yet or MediaPipe lost them, and every
        // sixth frame otherwise: MediaPipe follows on its own in between, which keeps it fast.
        frames++
        val look = box == null || !tracking || frames % 6 == 0
        val person = if (look) yolo?.detect(frame, minScore = .5f)?.let(::follow) else null
        if (person != null) {
            missed = 0
            val next = RectF(person.left, person.top, person.right, person.bottom)
            // The crop follows the person smoothly, so MediaPipe's own tracking is not thrown off.
            box = box?.let { lerp(it, next, .5f) } ?: next
        } else if (look && ++missed > 3) box = null

        val area = box
        var crop: Crops.Crop? = null
        val input = if (area != null) {
            val pad = .18f
            val px = RectF(
                (area.left - area.width() * pad) * frame.width, (area.top - area.height() * pad) * frame.height,
                (area.right + area.width() * pad) * frame.width, (area.bottom + area.height() * pad) * frame.height,
            )
            crop = Crops.square(frame, px, 256)
            crop?.bitmap ?: frame
        } else frame
        val image = BitmapImageBuilder(input).build()
        val result = runCatching { pose.detectForVideo(image, stamp) }.getOrNull()
        image.close()
        if (input !== frame) input.recycle()

        val marks = result?.landmarks()?.firstOrNull()
        val worldMarks = result?.worldLandmarks()?.firstOrNull()
        tracking = marks != null && worldMarks != null && marks.size >= 33
        if (marks == null || worldMarks == null || marks.size < 33) {
            return person?.let { fromYoloOnly(it, stamp) }
        }
        val out = FloatArray(33 * 3)
        for (i in 0 until 33) {
            val p = marks[i]
            val (x, y) = crop?.toFrame(p.x(), p.y(), frame.width, frame.height) ?: (p.x() to p.y())
            out[i * 3] = x
            out[i * 3 + 1] = y
            out[i * 3 + 2] = p.visibility().orElse(1f)
        }
        var taken = 0
        if (person != null) {
            for (k in 0 until Yolo11Pose.KEYPOINTS) {
                val i = Yolo11Pose.TO_MEDIAPIPE[k]
                val sure = person.confidence(k)
                val mp = out[i * 3 + 2]
                if (sure < .5f) continue
                if (mp < .5f) {
                    out[i * 3] = person.x(k); out[i * 3 + 1] = person.y(k); out[i * 3 + 2] = sure
                    taken++
                } else {
                    // Both see it: weighted mean, MediaPipe a little ahead for its finer model.
                    val wy = sure * .6f
                    val sum = mp + wy
                    out[i * 3] = (out[i * 3] * mp + person.x(k) * wy) / sum
                    out[i * 3 + 1] = (out[i * 3 + 1] * mp + person.y(k) * wy) / sum
                }
            }
        }
        val world = FloatArray(33 * 3)
        for (i in 0 until 33) {
            world[i * 3] = worldMarks[i].x(); world[i * 3 + 1] = worldMarks[i].y(); world[i * 3 + 2] = worldMarks[i].z()
        }
        return smooth(Pose(stamp, out, world, area?.let { RectF(it) }, taken))
    }

    /** Only YOLO saw the person: its 17 joints, the rest stays unseen, and a flat skeleton for 3D. */
    private fun fromYoloOnly(person: Yolo11Pose.Person, stamp: Long): Pose {
        val out = FloatArray(33 * 3)
        val world = FloatArray(33 * 3)
        val hipX = (person.x(Yolo11Pose.LEFT_HIP) + person.x(Yolo11Pose.RIGHT_HIP)) / 2
        val hipY = (person.y(Yolo11Pose.LEFT_HIP) + person.y(Yolo11Pose.RIGHT_HIP)) / 2
        // Scale from the person's height in the image to about 1.7 m.
        val metres = 1.7f / max(person.height, .05f)
        for (k in 0 until Yolo11Pose.KEYPOINTS) {
            val i = Yolo11Pose.TO_MEDIAPIPE[k]
            out[i * 3] = person.x(k); out[i * 3 + 1] = person.y(k); out[i * 3 + 2] = person.confidence(k)
            world[i * 3] = (person.x(k) - hipX) * metres
            world[i * 3 + 1] = (person.y(k) - hipY) * metres
        }
        return smooth(Pose(stamp, out, world, RectF(person.left, person.top, person.right, person.bottom), Yolo11Pose.KEYPOINTS))
    }

    private fun smooth(pose: Pose): Pose {
        val ns = pose.timestampMs * 1_000_000L
        for (i in 0 until 33) {
            pose.image[i * 3] = filters[i * 5].filter(pose.image[i * 3], ns)
            pose.image[i * 3 + 1] = filters[i * 5 + 1].filter(pose.image[i * 3 + 1], ns)
            for (c in 0..2) pose.world[i * 3 + c] = filters[i * 5 + 2 + c].filter(pose.world[i * 3 + c], ns)
        }
        return pose
    }

    /** The person to follow: the one overlapping the last box most, or the biggest. */
    private fun follow(people: List<Yolo11Pose.Person>): Yolo11Pose.Person? {
        if (people.isEmpty()) return null
        val last = box ?: return people.maxByOrNull { it.width * it.height }
        return people.maxByOrNull { overlap(last, it) + it.score * .1f }
    }

    private fun overlap(a: RectF, b: Yolo11Pose.Person): Float {
        val w = minOf(a.right, b.right) - maxOf(a.left, b.left)
        val h = minOf(a.bottom, b.bottom) - maxOf(a.top, b.top)
        return if (w <= 0f || h <= 0f) 0f else w * h / (a.width() * a.height() + b.width * b.height - w * h)
    }

    private fun lerp(a: RectF, b: RectF, t: Float) = RectF(
        a.left + (b.left - a.left) * t, a.top + (b.top - a.top) * t,
        a.right + (b.right - a.right) * t, a.bottom + (b.bottom - a.bottom) * t,
    )

    fun reset() {
        box = null
        filters.forEach { it.reset() }
    }

    override fun close() {
        pose.close()
        yolo?.close()
    }

    companion object {
        const val FULL_MODEL = "pose_landmarker_full.task"
        private const val HEAVY_URL = "https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_heavy/float16/latest/pose_landmarker_heavy.task"
        const val HEAVY_BYTES = 30_664_242L

        fun heavyFile(context: Context) = File(File(context.filesDir, "models").apply { mkdirs() }, "pose_landmarker_heavy.task")

        fun heavyInstalled(context: Context) = heavyFile(context).length() >= HEAVY_BYTES / 2

        /** Downloads MediaPipe's most accurate pose model (about 30 MB), reporting 0..1. */
        fun downloadHeavy(context: Context, onProgress: (Float) -> Unit) {
            val target = heavyFile(context)
            val partial = File(target.parentFile, target.name + ".part")
            val connection = URL(HEAVY_URL).openConnection() as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 30_000
            try {
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: HEAVY_BYTES
                connection.inputStream.use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                        }
                    }
                }
                check(partial.length() >= HEAVY_BYTES / 2) { "Модель скачалась не полностью" }
                partial.renameTo(target)
            } finally {
                connection.disconnect()
                partial.delete()
            }
        }
    }
}
