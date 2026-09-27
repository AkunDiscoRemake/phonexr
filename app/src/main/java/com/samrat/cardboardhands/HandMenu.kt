package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint

/**
 * The system menu that opens on the hand: palm held toward the face. A small glass bar with
 * round buttons that floats just above the hand which called it and moves with it; the other hand
 * touches an item with a fingertip to choose.
 */
class HandMenu(val holderLeft: Boolean, private val tileColor: Int, private val accent: Int, private val items: List<Item>) {
    class Item(val id: String, val label: String, val icon: Drawable?)

    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 0, 0, 0); maskFilter = BlurMaskFilter(22f, BlurMaskFilter.Blur.NORMAL) }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 26f; textAlign = Paint.Align.CENTER; typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    /** Where the menu sits, head space (tangent units): centre x, y. Follows the holder's hand. */
    @Volatile var x = Float.NaN
    @Volatile var y = Float.NaN
    @Volatile var hovered = -1
        private set
    @Volatile var dirty = true
    /** Where it was opened, as a world direction: it stays there while the head turns. */
    var world: FloatArray? = null
    var lastSeen = System.currentTimeMillis()

    /** Moves toward the holder's palm, smoothly so the menu does not shake with the hand. */
    fun follow(palmX: Float, palmY: Float, palmSize: Float) {
        val targetX = palmX
        val targetY = palmY + palmSize * 1.9f + HALF_H
        if (x.isNaN()) { x = targetX; y = targetY } else { x += (targetX - x) * .35f; y += (targetY - y) * .35f }
        lastSeen = System.currentTimeMillis()
    }

    /** The item under a head-space point, or -1. */
    fun itemAt(px: Float, py: Float): Int {
        if (x.isNaN()) return -1
        val u = (px - (x - HALF_W)) / (HALF_W * 2)
        val v = ((y + HALF_H) - py) / (HALF_H * 2)
        if (u !in 0f..1f || v !in 0f..1f) return -1
        val column = ((u * WIDTH - PAD) / tileW).toInt()
        val row = ((v * HEIGHT - PAD) / tileH).toInt()
        if (column !in 0 until COLUMNS || row < 0) return -1
        return (row * COLUMNS + column).takeIf { it in items.indices } ?: -1
    }

    fun contains(px: Float, py: Float) = !x.isNaN() && kotlin.math.abs(px - x) <= HALF_W && kotlin.math.abs(py - y) <= HALF_H

    fun hover(index: Int) {
        if (index != hovered) { hovered = index; dirty = true }
    }

    fun item(index: Int) = items.getOrNull(index)

    fun draw() {
        bitmap.eraseColor(Color.TRANSPARENT)
        val card = RectF(12f, 12f, WIDTH - 12f, HEIGHT - 12f)
        canvas.drawRoundRect(card, 90f, 90f, shadow)
        // The Horizon glass, light or dark like the rest of PhoneXR.
        val dark = Ui.dark
        val ink = if (dark) Color.rgb(242, 242, 242) else Color.rgb(39, 39, 39)
        paint.color = Color.WHITE
        paint.shader = android.graphics.LinearGradient(0f, card.top, 0f, card.bottom,
            if (dark) Color.argb(240, 43, 47, 54) else Color.argb(247, 255, 255, 255),
            if (dark) Color.argb(240, 29, 32, 38) else Color.argb(247, 242, 242, 242), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(card, 90f, 90f, paint)
        paint.shader = null
        label.color = ink
        paint.color = if (dark) Color.argb(50, 255, 255, 255) else Color.argb(20, 39, 39, 39)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        canvas.drawRoundRect(card, 90f, 90f, paint)
        paint.style = Paint.Style.FILL
        // Quest's quick menu: tiles in rows of three, an icon over a label; the one under the pointer lights up.
        items.forEachIndexed { i, item ->
            val left = PAD + (i % COLUMNS) * tileW
            val top = PAD + (i / COLUMNS) * tileH
            val tile = RectF(left + 8f, top + 8f, left + tileW - 8f, top + tileH - 8f)
            paint.color = if (i == hovered) accent else tileColor
            canvas.drawRoundRect(tile, 34f, 34f, paint)
            if (i == hovered) {
                paint.style = Paint.Style.STROKE; paint.strokeWidth = 6f; paint.color = Color.WHITE
                canvas.drawRoundRect(RectF(tile.left - 5f, tile.top - 5f, tile.right + 5f, tile.bottom + 5f), 38f, 38f, paint)
                paint.style = Paint.Style.FILL
            }
            val r = 34f
            val circle = RectF(tile.centerX() - r, tile.top + 22f, tile.centerX() + r, tile.top + 22f + 2 * r)
            item.icon?.let { it.setBounds(circle.left.toInt(), circle.top.toInt(), circle.right.toInt(), circle.bottom.toInt()); it.draw(canvas) }
            label.color = if (i == hovered) tileColor else accent
            val shown = android.text.TextUtils.ellipsize(item.label, label, tile.width() - 20f, android.text.TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, tile.centerX(), tile.bottom - 26f, label)
        }
        dirty = false
    }

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 470
        private const val COLUMNS = 3
        private const val PAD = 30f
        /** Size in head space (tangent units, about metres at arm's length). */
        const val HALF_W = .24f
        const val HALF_H = HALF_W * HEIGHT / WIDTH
    }

    private val tileW get() = (WIDTH - 2 * PAD) / COLUMNS
    private val tileH get() = (HEIGHT - 2 * PAD) / ((items.size + COLUMNS - 1) / COLUMNS).coerceAtLeast(1)
}
