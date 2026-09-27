package com.samrat.orangehanding

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

/**
 * YOLO11-pose (Ultralytics, nano) on LiteRT: every person in the frame with a box and the 17 COCO
 * keypoints. It is quick and sees people who are small or far away, where MediaPipe alone loses them.
 * The input and output layouts are read from the model, so a re-exported model of another size works too.
 */
class Yolo11Pose(context: Context, threads: Int = 2) : AutoCloseable {
    /** One person: box and keypoints in 0..1 image coordinates, confidences 0..1. */
    class Person(
        val left: Float, val top: Float, val right: Float, val bottom: Float,
        val score: Float,
        /** 17 × (x, y, confidence), COCO order: nose, eyes, ears, shoulders, elbows, wrists, hips, knees, ankles. */
        val keypoints: FloatArray,
    ) {
        fun x(i: Int) = keypoints[i * 3]
        fun y(i: Int) = keypoints[i * 3 + 1]
        fun confidence(i: Int) = keypoints[i * 3 + 2]
        val width get() = right - left
        val height get() = bottom - top
    }

    private val interpreter: Interpreter
    private val size: Int
    private val channelsFirst: Boolean
    private val input: ByteBuffer
    private val output: Array<Array<FloatArray>>
    /** True when the output is [1, 56, anchors]; false for [1, anchors, 56]. */
    private val featuresFirst: Boolean
    private val anchors: Int
    private val pixels: IntArray
    private val square: Bitmap
    private val canvas: Canvas
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        interpreter = Interpreter(load(context, MODEL), Interpreter.Options().setNumThreads(threads))
        val shape = interpreter.getInputTensor(0).shape()
        channelsFirst = shape[1] == 3
        size = if (channelsFirst) shape[2] else shape[1]
        input = ByteBuffer.allocateDirect(size * size * 3 * 4).order(ByteOrder.nativeOrder())
        val out = interpreter.getOutputTensor(0).shape()
        featuresFirst = out[1] == FEATURES
        anchors = if (featuresFirst) out[2] else out[1]
        output = if (featuresFirst) Array(1) { Array(FEATURES) { FloatArray(anchors) } } else Array(1) { Array(anchors) { FloatArray(FEATURES) } }
        pixels = IntArray(size * size)
        square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        canvas = Canvas(square)
    }

    /** People in [bitmap], best first, after non-maximum suppression. */
    @Synchronized
    fun detect(bitmap: Bitmap, minScore: Float = .35f): List<Person> {
        // Letterbox: the whole frame fits the square input, grey bars fill the rest.
        val scale = min(size.toFloat() / bitmap.width, size.toFloat() / bitmap.height)
        val dx = (size - bitmap.width * scale) / 2
        val dy = (size - bitmap.height * scale) / 2
        canvas.drawColor(Color.rgb(114, 114, 114))
        canvas.drawBitmap(bitmap, Matrix().apply { setScale(scale, scale); postTranslate(dx, dy) }, paint)
        square.getPixels(pixels, 0, size, 0, 0, size, size)
        input.rewind()
        if (channelsFirst) {
            for (c in 0 until 3) for (p in pixels) input.putFloat(((p shr (16 - c * 8)) and 0xff) / 255f)
        } else {
            for (p in pixels) {
                input.putFloat(((p shr 16) and 0xff) / 255f)
                input.putFloat(((p shr 8) and 0xff) / 255f)
                input.putFloat((p and 0xff) / 255f)
            }
        }
        input.rewind()
        interpreter.run(input, output)

        fun value(feature: Int, anchor: Int) = if (featuresFirst) output[0][feature][anchor] else output[0][anchor][feature]
        // Some exports give pixels of the input, others 0..1 of it.
        var largest = 0f
        for (a in 0 until anchors) largest = max(largest, value(0, a))
        val unit = if (largest > 2f) size.toFloat() else 1f
        fun toImageX(v: Float) = ((v / unit * size - dx) / scale / bitmap.width).coerceIn(0f, 1f)
        fun toImageY(v: Float) = ((v / unit * size - dy) / scale / bitmap.height).coerceIn(0f, 1f)

        val found = ArrayList<Person>()
        for (a in 0 until anchors) {
            val score = value(4, a)
            if (score < minScore) continue
            val cx = value(0, a); val cy = value(1, a); val w = value(2, a); val h = value(3, a)
            val keypoints = FloatArray(KEYPOINTS * 3)
            for (k in 0 until KEYPOINTS) {
                keypoints[k * 3] = toImageX(value(5 + k * 3, a))
                keypoints[k * 3 + 1] = toImageY(value(6 + k * 3, a))
                keypoints[k * 3 + 2] = value(7 + k * 3, a).let { if (it > 1f || it < 0f) sigmoid(it) else it }
            }
            found += Person(toImageX(cx - w / 2), toImageY(cy - h / 2), toImageX(cx + w / 2), toImageY(cy + h / 2), score, keypoints)
        }
        return suppress(found)
    }

    private fun suppress(people: List<Person>): List<Person> {
        val kept = ArrayList<Person>()
        for (person in people.sortedByDescending { it.score }) {
            if (kept.none { iou(it, person) > .5f }) kept += person
            if (kept.size >= 6) break
        }
        return kept
    }

    private fun iou(a: Person, b: Person): Float {
        val w = min(a.right, b.right) - max(a.left, b.left)
        val h = min(a.bottom, b.bottom) - max(a.top, b.top)
        if (w <= 0f || h <= 0f) return 0f
        val inter = w * h
        return inter / (a.width * a.height + b.width * b.height - inter)
    }

    private fun sigmoid(x: Float) = 1f / (1f + kotlin.math.exp(-x))

    override fun close() = interpreter.close()

    companion object {
        const val MODEL = "yolo11n-pose.tflite"
        const val KEYPOINTS = 17
        private const val FEATURES = 5 + KEYPOINTS * 3

        const val NOSE = 0; const val LEFT_SHOULDER = 5; const val RIGHT_SHOULDER = 6
        const val LEFT_ELBOW = 7; const val RIGHT_ELBOW = 8; const val LEFT_WRIST = 9; const val RIGHT_WRIST = 10
        const val LEFT_HIP = 11; const val RIGHT_HIP = 12

        /** COCO keypoint → MediaPipe Pose landmark with the same meaning. */
        val TO_MEDIAPIPE = intArrayOf(0, 2, 5, 7, 8, 11, 12, 13, 14, 15, 16, 23, 24, 25, 26, 27, 28)

        private fun load(context: Context, name: String): MappedByteBuffer {
            val descriptor = context.assets.openFd(name)
            FileInputStream(descriptor.fileDescriptor).use { stream ->
                return stream.channel.map(FileChannel.MapMode.READ_ONLY, descriptor.startOffset, descriptor.declaredLength)
            }
        }
    }
}
