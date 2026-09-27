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

    private var symbols = false
    // Material You colours: the panel, the keys and the accent (enter, hover), from the wallpaper.
    private var panelColor = Color.rgb(30, 42, 64)
    private var keyColor = Color.rgb(62, 75, 92)
    private var specialColor = Color.rgb(50, 62, 78)
    private var accentColor = BLUE

    /** Tints the keyboard with a Material You pair: [tile] (a deep tone) and [glyph] (a light accent). */
    fun tint(tile: Int, glyph: Int) {
        fun mix(a: Int, b: Int, t: Float) = Color.rgb(
            (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
            (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
            (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
        panelColor = mix(tile, Color.BLACK, .35f)
        keyColor = mix(tile, glyph, .18f)
        specialColor = mix(tile, Color.BLACK, .1f)
        accentColor = glyph
    }

    /** What a touch at 0..1 panel coordinates types: a character, or "backspace", "enter", "hide". */
    fun press(u: Float, v: Float): String? {
        val key = keys.firstOrNull { it.first.contains(u * WIDTH, v * HEIGHT) }?.second ?: return null
        return when (key) {
            SHIFT -> { shift = !shift; null }
            LANGUAGE -> { russian = !russian; symbols = false; null }
            SYMBOLS -> { symbols = !symbols; null }
            SPACE -> " "
            BACKSPACE, ENTER, HIDE -> key
            else -> (if (shift) key.uppercase() else key).also { shift = false }
        }
    }

    /** Latin letters (e-mail, passwords) or Russian. */
    fun setRussian(value: Boolean) { russian = value }

    fun hovered(u: Float, v: Float): String? = keys.firstOrNull { it.first.contains(u * WIDTH, v * HEIGHT) }?.second

    /** Quest's keyboard: a deep blue-grey panel, four rows of large keys, numbers as small hints. */
    fun draw(hover: String?) {
        keys.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        paint.shader = android.graphics.LinearGradient(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(),
            (panelColor and 0xFFFFFF) or (245 shl 24), (specialColor and 0xFFFFFF) or (245 shl 24), android.graphics.Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat()), 48f, 48f, paint)
        paint.shader = null
        val rows = when {
            symbols -> SYMBOL_ROWS
            russian -> RUSSIAN
            else -> ENGLISH
        }
        val pad = 28f
        val gap = 12f
        val rowH = (HEIGHT - 2 * pad - 3 * gap) / 4
        val unit = (WIDTH - 2 * pad - 11 * gap) / 12
        fun top(row: Int) = pad + row * (rowH + gap)
        // Row 1: letters, then ⌫.
        var x = pad
        rows[0].forEachIndexed { i, c ->
            key(RectF(x, top(0), x + unit, top(0) + rowH), c.toString(), label(c), hover, hint = if (!symbols && i < 10) "1234567890"[i].toString() else null)
            x += unit + gap
        }
        key(RectF(x, top(0), WIDTH - pad, top(0) + rowH), BACKSPACE, "⌫", hover)
        // Row 2: letters, then the blue →.
        x = pad + unit * .35f
        rows[1].forEach { c ->
            key(RectF(x, top(1), x + unit, top(1) + rowH), c.toString(), label(c), hover)
            x += unit + gap
        }
        key(RectF(x, top(1), WIDTH - pad, top(1) + rowH), ENTER, "→", hover)
        // Row 3: ⇧, letters, ⇧.
        x = pad
        key(RectF(x, top(2), x + unit * 1.4f, top(2) + rowH), SHIFT, "⇧", hover, lit = shift)
        x += unit * 1.4f + gap
        rows[2].forEach { c ->
            key(RectF(x, top(2), x + unit, top(2) + rowH), c.toString(), label(c), hover)
            x += unit + gap
        }
        key(RectF(x, top(2), WIDTH - pad, top(2) + rowH), SHIFT, "⇧", hover, lit = shift)
        // Row 4: !123, 🌐, space, ",", ".", hide.
        x = pad
        val y = top(3)
        key(RectF(x, y, x + unit * 1.6f, y + rowH), SYMBOLS, if (symbols) "ABC" else "!123", hover); x += unit * 1.6f + gap
        key(RectF(x, y, x + unit, y + rowH), LANGUAGE, "🌐", hover); x += unit + gap
        val spaceEnd = WIDTH - pad - 3 * (unit + gap)
        key(RectF(x, y, spaceEnd, y + rowH), SPACE, if (russian) "пробел" else "space", hover); x = spaceEnd + gap
        key(RectF(x, y, x + unit, y + rowH), ",", ",", hover); x += unit + gap
        key(RectF(x, y, x + unit, y + rowH), ".", ".", hover); x += unit + gap
        key(RectF(x, y, WIDTH - pad, y + rowH), HIDE, "⌨", hover)
    }

    private fun label(c: Char) = if (shift) c.uppercase() else c.toString()

    private fun key(rect: RectF, id: String, text: String, hover: String?, hint: String? = null, lit: Boolean = false) {
        val enter = id == ENTER
        val special = id.startsWith("#") || id == BACKSPACE || id == HIDE
        val hovered = id == hover
        paint.color = when {
            enter -> accentColor
            hovered || lit -> (accentColor and 0xFFFFFF) or (150 shl 24)
            special -> specialColor
            else -> keyColor
        }
        canvas.drawRoundRect(rect, 22f, 22f, paint)
        paint.typeface = android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL)
        paint.textAlign = Paint.Align.CENTER
        if (hint != null) {
            paint.color = Color.argb(150, 255, 255, 255)
            paint.textSize = 26f
            canvas.drawText(hint, rect.right - 22f, rect.top + 32f, paint)
        }
        paint.color = if (enter) panelColor else Color.WHITE
        paint.textSize = if (text.length > 2) 40f else 52f
        canvas.drawText(text, rect.centerX(), rect.centerY() + paint.textSize * .35f, paint)
        keys += rect to id
    }

    companion object {
        private val BLUE = Color.rgb(24, 119, 242)
        const val WIDTH = 1560
        const val HEIGHT = 560
        const val SHIFT = "#shift"
        const val LANGUAGE = "#lang"
        const val SPACE = "#space"
        const val SYMBOLS = "#symbols"
        const val BACKSPACE = "backspace"
        const val ENTER = "enter"
        const val HIDE = "hide"
        private val RUSSIAN = listOf("йцукенгшщзх", "фывапролджэ", "ячсмитьбю")
        private val ENGLISH = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm@")
        private val SYMBOL_ROWS = listOf("1234567890-", "@#_&+()/*\"", "!?:;'%=")
    }
}
