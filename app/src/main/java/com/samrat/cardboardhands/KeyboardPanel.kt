package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF

/**
 * A floating VR keyboard in the Horizon look (dark glass, see-through keys, a blue enter key):
 * Russian and English letters, digits, shift, backspace, space, enter.
 */
class KeyboardPanel {
    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val keys = ArrayList<Pair<RectF, String>>()
    private var russian = true
    private var shift = false

    /** What a touch at 0..1 panel coordinates types: a character, or "backspace", "enter", "hide". */
    fun press(u: Float, v: Float): String? {
        val key = keys.firstOrNull { it.first.contains(u * WIDTH, v * HEIGHT) }?.second ?: return null
        return when (key) {
            SHIFT -> { shift = !shift; null }
            LANGUAGE -> { russian = !russian; null }
            SPACE -> " "
            BACKSPACE, ENTER, HIDE -> key
            else -> (if (shift) key.uppercase() else key).also { shift = false }
        }
    }

    /** Latin letters (e-mail, passwords) or Russian. */
    fun setRussian(value: Boolean) { russian = value }

    fun hovered(u: Float, v: Float): String? = keys.firstOrNull { it.first.contains(u * WIDTH, v * HEIGHT) }?.second

    fun draw(hover: String?) {
        keys.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.color = Color.WHITE
        // Quest 3 style: a near-black glass panel, solid rounded keys standing on it.
        paint.shader = android.graphics.LinearGradient(0f, 0f, 0f, HEIGHT.toFloat(), Color.argb(235, 28, 29, 33), Color.argb(235, 16, 17, 20), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), 56f, 56f, paint)
        paint.shader = null
        val rows = if (russian) RUSSIAN else ENGLISH
        val allRows = listOf(DIGITS) + rows
        val keyHeight = 78f
        allRows.forEachIndexed { rowIndex, row ->
            val keyWidth = (WIDTH - 40f) / 12f
            val start = (WIDTH - row.length * keyWidth) / 2
            row.forEachIndexed { i, char ->
                val rect = RectF(start + i * keyWidth + 4, 20f + rowIndex * (keyHeight + 8), start + (i + 1) * keyWidth - 4, 20f + rowIndex * (keyHeight + 8) + keyHeight)
                key(rect, char.toString(), if (shift) char.uppercase() else char.toString(), hover)
            }
        }
        val y = 20f + allRows.size * (keyHeight + 8)
        key(RectF(24f, y, 184f, y + keyHeight), SHIFT, if (shift) "⇧ ✓" else "⇧", hover)
        key(RectF(194f, y, 344f, y + keyHeight), LANGUAGE, if (russian) "EN" else "РУ", hover)
        key(RectF(354f, y, 1054f, y + keyHeight), SPACE, if (russian) "пробел" else "space", hover)
        key(RectF(1064f, y, 1224f, y + keyHeight), BACKSPACE, "⌫", hover)
        key(RectF(1234f, y, 1384f, y + keyHeight), ENTER, "↵", hover)
        key(RectF(1394f, y, WIDTH - 24f, y + keyHeight), HIDE, "⌄", hover)
    }

    private fun key(rect: RectF, id: String, label: String, hover: String?) {
        val enter = id == ENTER
        val special = id.startsWith("#") || id == BACKSPACE || id == HIDE
        val hovered = id == hover
        // The key under the pointer lifts a little and lights up.
        val shape = if (hovered) RectF(rect.left - 3, rect.top - 3, rect.right + 3, rect.bottom + 3) else rect
        paint.color = Color.argb(90, 0, 0, 0)
        canvas.drawRoundRect(RectF(shape.left, shape.top + 5, shape.right, shape.bottom + 5), 24f, 24f, paint)
        paint.color = when {
            enter -> if (hovered) Color.rgb(66, 145, 250) else BLUE
            hovered -> Color.rgb(96, 98, 106)
            special -> Color.rgb(46, 47, 52)
            else -> Color.rgb(62, 63, 69)
        }
        canvas.drawRoundRect(shape, 24f, 24f, paint)
        paint.color = if (special && !hovered && !enter) Color.argb(210, 255, 255, 255) else Color.WHITE
        paint.textSize = if (label.length > 2) 40f else 54f
        paint.typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(label, shape.centerX(), shape.centerY() + paint.textSize * .35f, paint)
        keys += rect to id
    }

    companion object {
        private val BLUE = Color.rgb(24, 119, 242)
        const val WIDTH = 1560
        const val HEIGHT = 470
        const val SHIFT = "#shift"
        const val LANGUAGE = "#lang"
        const val SPACE = "#space"
        const val BACKSPACE = "backspace"
        const val ENTER = "enter"
        const val HIDE = "hide"
        private const val DIGITS = "1234567890-."
        private val RUSSIAN = listOf("йцукенгшщзхъ", "фывапролджэ", "ячсмитьбю")
        private val ENGLISH = listOf("qwertyuiop", "asdfghjkl@", "zxcvbnm/:?")
    }
}
