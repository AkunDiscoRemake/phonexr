package com.samrat.orangehanding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.RectF
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.Category
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.ImageProcessingOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import kotlin.math.hypot
import kotlin.math.max

/**
 * Hands by MediaPipe, rescued by YOLO11. MediaPipe's palm detector misses small, blurred or
 * partly hidden hands; YOLO11-pose still sees the arm. When YOLO finds a wrist that MediaPipe has
 * no hand for, a square around that wrist (pushed along the forearm) is cut out, enlarged and given
 * to a second MediaPipe landmarker, and the hand found there is put back into the full frame.
 *
 * The result is an ordinary [HandLandmarkerResult], so everything written for MediaPipe keeps working.
 */
class FusionHands(
    context: Context,
    useGpu: Boolean = false,
    handModel: String = "hand_landmarker.task",
    /** How often YOLO looks for lost hands, at most (it only runs while a hand is missing). */
    private val yoloEveryMs: Long = 250,
) : AutoCloseable {
    private val video: HandLandmarker = landmarker(context, handModel, useGpu, RunningMode.VIDEO, .45f)
    private val rescue: HandLandmarker = landmarker(context, handModel, false, RunningMode.IMAGE, .3f)
    private val yolo: Yolo11Pose? = runCatching { Yolo11Pose(context, threads = 2) }.getOrNull()
    /** YOLO runs here, never on the tracking thread: hands come out as fast as MediaPipe alone. */
    private val yoloThread = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "PhoneXR YOLO11").apply { priority = Thread.MIN_PRIORITY } }
    private val yoloBusy = java.util.concurrent.atomic.AtomicBoolean(false)
    private var lastTimestamp = -1L
    private var frames = 0
    private var lastYoloAt = 0L
    /** Wrists YOLO saw most recently (x, y, elbow x, elbow y, physical left, person width) and when. */
    @Volatile private var wrists: List<FloatArray> = emptyList()
    @Volatile private var wristsAt = 0L

    /** True when the YOLO11 model loaded; without it this is plain MediaPipe. */
    val fused get() = yolo != null

    fun detect(bitmap: Bitmap, timestampMs: Long, rotationDegrees: Int = 0): HandLandmarkerResult {
        val stamp = maxOf(timestampMs, lastTimestamp + 1)
        lastTimestamp = stamp
        val image = BitmapImageBuilder(bitmap).build()
        val options = ImageProcessingOptions.builder().setRotationDegrees(rotationDegrees).build()
        val base = video.detectForVideo(image, options, stamp)
        image.close()
        if (yolo == null || rotationDegrees != 0 || base.landmarks().size >= 2) return base
        frames++
        val now = android.os.SystemClock.elapsedRealtime()
        // Ask YOLO (in the background, on a small copy) where the wrists are, a few times a second.
        if (now - lastYoloAt >= yoloEveryMs && yoloBusy.compareAndSet(false, true)) {
            lastYoloAt = now
            val copy = runCatching {
                val w = 320
                Bitmap.createBitmap(w, w * bitmap.height / bitmap.width, Bitmap.Config.ARGB_8888).also {
                    android.graphics.Canvas(it).drawBitmap(bitmap, null, android.graphics.Rect(0, 0, it.width, it.height), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                }
            }.getOrNull()
            if (copy == null) yoloBusy.set(false)
            else yoloThread.execute {
                try {
                    wrists = findWrists(copy)
                    wristsAt = android.os.SystemClock.elapsedRealtime()
                } finally {
                    copy.recycle()
                    yoloBusy.set(false)
                }
            }
        }
        // Rescue only with fresh wrists, and every other frame: MediaPipe usually takes the hand back itself.
        val seen = wrists
        if (seen.isEmpty() || now - wristsAt > 500 || frames % 2 != 0) return base

        val landmarks = ArrayList(base.landmarks())
        val world = ArrayList(base.worldLandmarks())
        val sides = ArrayList(base.handedness())
        for (wrist in seen) {
            if (landmarks.size >= 2) break
            // A wrist near a hand MediaPipe already has is that hand.
            val covered = landmarks.any { hand -> hypot(hand[0].x() - wrist[0], hand[0].y() - wrist[1]) < .1f }
            if (covered) continue
            val found = rescueAt(bitmap, wrist) ?: continue
            landmarks += found.first
            world += found.second
            sides += found.third
        }
        if (landmarks.size == base.landmarks().size) return base
        return Result(stamp, landmarks, world, sides)
    }

    private fun findWrists(bitmap: Bitmap): List<FloatArray> {
        val people = yolo?.detect(bitmap) ?: return emptyList()
        val out = ArrayList<FloatArray>()
        for (person in people.take(1)) {
            for ((wrist, elbow, left) in listOf(
                Triple(Yolo11Pose.LEFT_WRIST, Yolo11Pose.LEFT_ELBOW, true),
                Triple(Yolo11Pose.RIGHT_WRIST, Yolo11Pose.RIGHT_ELBOW, false),
            )) {
                if (person.confidence(wrist) < .45f) continue
                out += floatArrayOf(person.x(wrist), person.y(wrist), person.x(elbow), person.y(elbow), if (left) 1f else 0f,
                    person.width.coerceAtLeast(.05f))
            }
        }
        return out
    }

    private fun rescueAt(bitmap: Bitmap, wrist: FloatArray): Triple<List<NormalizedLandmark>, List<Landmark>, List<Category>>? {
        // The hand lies past the wrist, along the forearm.
        val fx = wrist[0] - wrist[2]
        val fy = wrist[1] - wrist[3]
        val cx = wrist[0] + fx * .45f
        val cy = wrist[1] + fy * .45f
        val forearm = hypot(fx * bitmap.width, fy * bitmap.height)
        val side = max(forearm * 1.6f, wrist[5] * bitmap.width * .45f).coerceIn(48f, max(bitmap.width, bitmap.height).toFloat())
        val box = RectF(cx * bitmap.width - side / 2, cy * bitmap.height - side / 2, cx * bitmap.width + side / 2, cy * bitmap.height + side / 2)
        val crop = Crops.square(bitmap, box, 224) ?: return null
        val image = BitmapImageBuilder(crop.bitmap).build()
        val result = runCatching { rescue.detect(image) }.getOrNull()
        image.close()
        crop.bitmap.recycle()
        val hand = result?.landmarks()?.firstOrNull() ?: return null
        val mapped = hand.map { p ->
            val (x, y) = crop.toFrame(p.x(), p.y(), bitmap.width, bitmap.height)
            NormalizedLandmark.create(x, y, p.z() * crop.side / bitmap.width)
        }
        return Triple(mapped, result.worldLandmarks().first(), result.handedness().first())
    }

    override fun close() {
        yoloThread.shutdown()
        runCatching { yoloThread.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        video.close()
        rescue.close()
        yolo?.close()
    }

    /** A MediaPipe result put together from both landmarkers. */
    private class Result(
        private val stamp: Long,
        private val hands: List<List<NormalizedLandmark>>,
        private val world: List<List<Landmark>>,
        private val sides: List<List<Category>>,
    ) : HandLandmarkerResult() {
        override fun timestampMs() = stamp
        override fun landmarks() = hands
        override fun worldLandmarks() = world
        override fun handedness() = sides
        override fun handednesses() = sides
    }

    companion object {
        private fun landmarker(context: Context, model: String, gpu: Boolean, mode: RunningMode, confidence: Float): HandLandmarker {
            val base = BaseOptions.builder().setModelAssetPath(model).setDelegate(if (gpu) Delegate.GPU else Delegate.CPU).build()
            val options = HandLandmarker.HandLandmarkerOptions.builder()
                .setBaseOptions(base)
                .setRunningMode(mode)
                .setNumHands(2)
                .setMinHandDetectionConfidence(confidence)
                .setMinHandPresenceConfidence(confidence)
                .setMinTrackingConfidence(.35f)
                .build()
            return HandLandmarker.createFromOptions(context, options)
        }
    }
}
