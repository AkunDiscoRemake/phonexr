package com.samrat.orangehanding

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF

/** Square cut-outs of a frame, and the way back from the cut-out to the frame. */
internal object Crops {
    class Crop(val bitmap: Bitmap, val left: Float, val top: Float, val side: Float) {
        /** A 0..1 point of the crop as 0..1 of the whole frame. */
        fun toFrame(x: Float, y: Float, width: Int, height: Int) =
            (left + x * side) / width to (top + y * side) / height
    }

    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    /** [box] (frame pixels) made square, cut out and scaled to [size]; the part outside the frame is black. */
    fun square(frame: Bitmap, box: RectF, size: Int): Crop? {
        val side = maxOf(box.width(), box.height())
        if (side < 8f) return null
        val left = box.centerX() - side / 2
        val top = box.centerY() - side / 2
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.BLACK)
        val scale = size / side
        val source = Rect(left.toInt().coerceAtLeast(0), top.toInt().coerceAtLeast(0),
            (left + side).toInt().coerceAtMost(frame.width), (top + side).toInt().coerceAtMost(frame.height))
        if (source.width() <= 0 || source.height() <= 0) { out.recycle(); return null }
        val target = RectF((source.left - left) * scale, (source.top - top) * scale, (source.right - left) * scale, (source.bottom - top) * scale)
        canvas.drawBitmap(frame, source, target, paint)
        return Crop(out, left, top, side)
    }
}
