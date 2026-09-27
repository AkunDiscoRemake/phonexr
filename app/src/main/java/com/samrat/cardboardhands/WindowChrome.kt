package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils

/**
 * The frame of VR windows, as on Quest: a dark pill floating under every window (the title — drag it
 * to move the window — and keyboard, expand, minimize, close); the browser also has its bar on top:
 * title, tabs, and back / reload / address / bookmark.
 */
object WindowChrome {
    /** The bar's colours, light or dark like the rest of PhoneXR. */
    private class Look(dark: Boolean) {
        val top = if (dark) Color.rgb(43, 47, 54) else Color.WHITE
        val bottom = if (dark) Color.rgb(31, 34, 40) else Color.rgb(242, 242, 242)
        val ink = if (dark) Color.rgb(242, 242, 242) else Color.rgb(39, 39, 39)
        val soft = if (dark) Color.argb(170, 242, 242, 242) else Color.argb(170, 39, 39, 39)
        val faint = if (dark) Color.argb(34, 242, 242, 242) else Color.argb(24, 39, 39, 39)
        val chosen = if (dark) Color.rgb(70, 70, 74) else Color.WHITE
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textAlign = Paint.Align.CENTER
    }

    // ---------------------------------------------------------------- the window's pill

    /** Pixels across the pill's texture; its height follows its width. */
    const val PILL_PX = 1200

    /** How wide the pill under a window of [windowM] is, metres. */
    fun pillWidth(windowM: Float) = (windowM * .72f).coerceIn(.48f, .7f)

    /**
     * Which part of the pill is at [u] (0..1 across): "back" (when the window has one) on the left,
     * "keyboard", "curve", "expand", "minimize" and "close" on the right, "move" everywhere else.
     */
    fun pillAction(u: Float, pillM: Float, back: Boolean): String {
        val x = u * pillM
        return when {
            x > pillM - PILL_BUTTON_M -> "close"
            x > pillM - PILL_BUTTON_M * 2 -> "minimize"
            x > pillM - PILL_BUTTON_M * 3 -> "expand"
            x > pillM - PILL_BUTTON_M * 4 -> "curve"
            x > pillM - PILL_BUTTON_M * 5 -> "keyboard"
            back && x < PILL_BUTTON_M -> "back"
            else -> "move"
        }
    }

    /**
     * The pill floating under every window, as on Quest: dark and fully rounded, the title on the
     * left and "···" (keyboard), expand, minimize and close on the right; [hovered] is lit.
     */
    @Synchronized
    fun drawPill(title: String, pillM: Float, back: Boolean, keyboardOn: Boolean, expanded: Boolean, curved: Boolean, hovered: String?): Bitmap {
        val h = (PILL_PX * PILL_H / pillM).toInt().coerceIn(40, 400)
        val bitmap = Bitmap.createBitmap(PILL_PX, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = h / 2f
        paint.shader = null
        paint.style = Paint.Style.FILL
        paint.color = Color.argb(236, 30, 31, 35)
        canvas.drawRoundRect(RectF(0f, 0f, PILL_PX.toFloat(), h.toFloat()), r, r, paint)
        val button = PILL_PX * PILL_BUTTON_M / pillM
        val cy = h / 2f
        fun lit(cx: Float, name: String) {
            if (hovered != name) return
            paint.color = Color.argb(60, 255, 255, 255)
            canvas.drawCircle(cx, cy, h * .38f, paint)
        }
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = h * .055f
        val s = h * .13f
        // × close
        val cClose = PILL_PX - button / 2 - h * .15f
        lit(cClose, "close")
        paint.color = Color.WHITE
        canvas.drawLine(cClose - s, cy - s, cClose + s, cy + s, paint)
        canvas.drawLine(cClose + s, cy - s, cClose - s, cy + s, paint)
        // − minimize
        val cMin = cClose - button
        lit(cMin, "minimize")
        paint.color = Color.WHITE
        canvas.drawLine(cMin - s, cy, cMin + s, cy, paint)
        // Expand: four corners, pointing out (or in while expanded).
        val cExp = cMin - button
        lit(cExp, "expand")
        paint.color = Color.WHITE
        val e = s * 1.05f; val k = s * .55f
        for (dx in floatArrayOf(-1f, 1f)) for (dy in floatArrayOf(-1f, 1f)) {
            if (!expanded) {
                // ⛶: each corner's point outside, its arms towards the middle.
                val x = cExp + dx * e; val y = cy + dy * e
                canvas.drawLine(x, y, x - dx * k, y, paint)
                canvas.drawLine(x, y, x, y - dy * k, paint)
            } else {
                // Back to normal: each corner's point inside, its arms outwards.
                val x = cExp + dx * (e - k); val y = cy + dy * (e - k)
                canvas.drawLine(x, y, x + dx * k, y, paint)
                canvas.drawLine(x, y, x, y + dy * k, paint)
            }
        }
        // Curve: the window bent around the user (an arc), or flat again (a straight line).
        val cCurve = cExp - button
        lit(cCurve, "curve")
        paint.color = if (curved) Color.rgb(120, 170, 255) else Color.WHITE
        paint.style = Paint.Style.STROKE
        canvas.drawArc(RectF(cCurve - s * 1.3f, cy - s * .2f, cCurve + s * 1.3f, cy + s * 1.9f), 200f, 140f, false, paint)
        paint.style = Paint.Style.FILL
        canvas.drawLine(cCurve - s * 1.1f, cy + s * .9f, cCurve + s * 1.1f, cy + s * .9f, paint.apply { alpha = 110 })
        paint.alpha = 255
        // ··· the keyboard (filled while it is up).
        val cKey = cCurve - button
        lit(cKey, "keyboard")
        paint.color = if (keyboardOn) Color.rgb(120, 170, 255) else Color.WHITE
        for (i in -1..1) canvas.drawCircle(cKey + i * s * .9f, cy, h * .045f, paint)
        // ‹ back, then the title.
        var left = h * .55f
        if (back) {
            val cBack = button / 2 + h * .1f
            lit(cBack, "back")
            paint.color = Color.WHITE
            canvas.drawLine(cBack + s * .45f, cy - s, cBack - s * .45f, cy, paint)
            canvas.drawLine(cBack - s * .45f, cy, cBack + s * .45f, cy + s, paint)
            left = button + h * .2f
        }
        text.color = Color.WHITE
        text.textSize = h * .34f
        text.textAlign = Paint.Align.LEFT
        val room = cKey - button / 2 - left - h * .2f
        val shown = TextUtils.ellipsize(title, text, room.coerceAtLeast(10f), TextUtils.TruncateAt.END).toString()
        canvas.drawText(shown, left, cy + text.textSize * .36f, text)
        text.textAlign = Paint.Align.CENTER
        return bitmap
    }

    // ---------------------------------------------------------------- the browser's top bar

    const val BAR_W = 1600
    const val BAR_H = 240
    private const val ROW1 = 64f
    private const val ROW2 = 144f

    /** What is at (u, v) of the browser bar, or null. */
    fun barAction(u: Float, v: Float, tabs: Int): String? {
        val x = u * BAR_W; val y = v * BAR_H
        return when {
            y < ROW1 -> if (x < 110f) "home" else "move"
            y < ROW2 -> {
                val width = tabWidth(tabs)
                val index = ((x - 20f) / width).toInt()
                when {
                    x >= 20f && index in 0 until tabs -> if (x > 20f + (index + 1) * width - 52f) "closetab:$index" else "tab:$index"
                    x >= 20f + tabs * width && x < 20f + tabs * width + 70f -> "newtab"
                    else -> "move"
                }
            }
            else -> when {
                x < 100f -> "back"
                x < 170f -> "forward"
                x < 245f -> "reload"
                x > BAR_W - 90f -> "home"
                x > BAR_W - 170f -> "bookmark"
                else -> "address"
            }
        }
    }

    private fun tabWidth(tabs: Int) = minOf(400f, (BAR_W - 40f - 80f) / tabs.coerceAtLeast(1))

    /** The browser bar: "···", the name, − ×; the tabs and +; back, forward, reload, the address, ☆, home. */
    @Synchronized
    fun drawBar(address: String, tabs: List<String>, active: Int, dark: Boolean): Bitmap {
        val look = Look(dark)
        val bitmap = Bitmap.createBitmap(BAR_W, BAR_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val r = 56f
        val shape = Path().apply {
            addRoundRect(RectF(0f, 0f, BAR_W.toFloat(), BAR_H.toFloat()), floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f), Path.Direction.CW)
        }
        paint.color = Color.WHITE
        paint.shader = LinearGradient(0f, 0f, 0f, BAR_H.toFloat(), look.bottom, look.top, Shader.TileMode.CLAMP)
        canvas.drawPath(shape, paint)
        paint.shader = null
        // Row 1: menu and title (minimize and close are on the pill under the window).
        text.color = look.ink
        text.textSize = 30f
        canvas.drawText("···", 60f, 44f, text)
        text.color = look.soft
        canvas.drawText(tr("Браузер PhoneXR"), BAR_W / 2f, 44f, text)
        paint.strokeCap = Paint.Cap.ROUND
        // Row 2: tabs.
        val width = tabWidth(tabs.size)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 28f
        tabs.forEachIndexed { i, title ->
            val left = 20f + i * width
            val rect = RectF(left, ROW1 + 6f, left + width - 8f, ROW2 - 6f)
            if (i == active) {
                paint.color = look.chosen
                canvas.drawRoundRect(rect, 22f, 22f, paint)
            }
            text.color = if (i == active) look.ink else look.soft
            val shown = TextUtils.ellipsize(title, text, width - 90f, TextUtils.TruncateAt.END).toString()
            canvas.drawText(shown, left + 22f, rect.centerY() + 10f, text)
            // × on each tab.
            paint.color = look.soft
            paint.strokeWidth = 3f
            val cx = rect.right - 28f; val cy = rect.centerY()
            canvas.drawLine(cx - 9f, cy - 9f, cx + 9f, cy + 9f, paint)
            canvas.drawLine(cx + 9f, cy - 9f, cx - 9f, cy + 9f, paint)
        }
        // + for a new tab.
        paint.color = look.ink
        paint.strokeWidth = 4f
        val px = 20f + tabs.size * width + 34f; val py = (ROW1 + ROW2) / 2
        canvas.drawLine(px - 14f, py, px + 14f, py, paint)
        canvas.drawLine(px, py - 14f, px, py + 14f, paint)
        // Row 3: navigation and the address.
        text.textAlign = Paint.Align.CENTER
        text.color = look.ink
        text.textSize = 50f
        val row = (ROW2 + BAR_H) / 2 + 16f
        canvas.drawText("‹", 60f, row + 2f, text)
        canvas.drawText("›", 135f, row + 2f, text)
        text.textSize = 42f
        canvas.drawText("↻", 207f, row - 2f, text)
        paint.color = look.faint
        val field = RectF(260f, ROW2 + 12f, BAR_W - 185f, BAR_H - 12f)
        canvas.drawRoundRect(field, field.height() / 2, field.height() / 2, paint)
        val starred = address.startsWith("★ ")
        text.textSize = 40f
        text.color = if (starred) Color.rgb(240, 180, 0) else look.ink
        canvas.drawText(if (starred) "★" else "☆", BAR_W - 130f, row - 2f, text)
        text.color = look.ink
        canvas.drawText("⌂", BAR_W - 48f, row - 2f, text)
        text.textAlign = Paint.Align.LEFT
        text.textSize = 30f
        text.color = look.soft
        val shown = TextUtils.ellipsize(address.removePrefix("★ "), text, field.width() - 70f, TextUtils.TruncateAt.START).toString()
        canvas.drawText(shown, field.left + 34f, field.centerY() + 10f, text)
        text.textAlign = Paint.Align.CENTER
        return bitmap
    }

    /** The pill under each window: its height, the width of one button, and its gap under the window, metres. */
    const val PILL_H = .075f
    const val PILL_BUTTON_M = .07f
    const val PILL_GAP = .025f

    /** Space left under the pill before what hangs below a window (the desktop's controls). */
    const val HANDLE_GAP = .04f
}
