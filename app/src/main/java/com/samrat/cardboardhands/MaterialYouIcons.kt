package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build

/**
 * Material You themed icons for the VR home, the way Android 13 draws them on the home screen:
 * the app's monochrome layer in a wallpaper tone on a soft tonal tile. Apps without a monochrome
 * layer keep their own icon. Light or dark is the choice made with a long touch on the home.
 */
object MaterialYouIcons {
    /** [tile] behind the glyph, [glyph] the glyph itself; both ARGB. */
    data class Palette(val tile: Int, val glyph: Int)

    fun palette(context: Context, dark: Boolean): Palette {
        if (Build.VERSION.SDK_INT >= 31) {
            val r = context.resources
            @Suppress("DEPRECATION")
            return if (dark) Palette(r.getColor(android.R.color.system_neutral1_800), r.getColor(android.R.color.system_accent1_200))
            else Palette(r.getColor(android.R.color.system_accent1_100), r.getColor(android.R.color.system_accent1_700))
        }
        // Before Android 12 there is no wallpaper palette: PhoneXR orange tones.
        return if (dark) Palette(Color.rgb(49, 40, 34), Color.rgb(255, 183, 134))
        else Palette(Color.rgb(255, 220, 196), Color.rgb(112, 55, 12))
    }

    /** Hues of PhoneXR's own apps, so each tile has its own colour. */
    private val HUES = mapOf(
        "own:browser" to 212f, "own:photos" to 338f, "own:settings" to 262f, "own:store" to 28f,
        "own:calls" to 138f, "desktop" to 190f, "own:games" to 2f,
        "own:avatar" to 292f, "own:elix" to 168f, "own:android" to 96f,
    )

    /**
     * A Material You tonal pair in a hue of its own for [key] (an app id or package name): a light
     * container with a deep glyph, or a deep container with a light glyph in the dark look.
     */
    fun palette(key: String, dark: Boolean): Palette {
        val hue = HUES[key] ?: ((key.hashCode() and 0x7fffffff) % 360).toFloat()
        return if (dark) Palette(Color.HSVToColor(floatArrayOf(hue, .55f, .30f)), Color.HSVToColor(floatArrayOf(hue, .32f, 1f)))
        else Palette(Color.HSVToColor(floatArrayOf(hue, .20f, 1f)), Color.HSVToColor(floatArrayOf(hue, .85f, .48f)))
    }

    /**
     * Horizon-style tiles for PhoneXR's own apps: a diagonal gradient (top-left to bottom-right)
     * with a white glyph, like Horizon Feed, the Store, People and the Browser on Quest.
     */
    val GRADIENTS = mapOf(
        "own:instagram" to (Color.rgb(252, 175, 69) to Color.rgb(193, 53, 132)),
        "own:discord" to (Color.rgb(114, 137, 255) to Color.rgb(71, 82, 196)),
        "own:store" to (Color.rgb(255, 167, 38) to Color.rgb(230, 81, 0)),
        "own:people" to (Color.rgb(236, 110, 173) to Color.rgb(116, 72, 212)),
        "own:browser" to (Color.rgb(196, 160, 255) to Color.rgb(84, 117, 230)),
        "own:photos" to (Color.rgb(255, 112, 150) to Color.rgb(211, 47, 95)),
        "own:settings" to (Color.rgb(144, 164, 174) to Color.rgb(69, 90, 100)),
        "own:calls" to (Color.rgb(102, 220, 140) to Color.rgb(27, 140, 80)),
        "desktop" to (Color.rgb(77, 208, 225) to Color.rgb(0, 121, 150)),
        "own:games" to (Color.rgb(255, 99, 99) to Color.rgb(183, 28, 60)),
        "own:mirror" to (Color.rgb(186, 104, 200) to Color.rgb(94, 53, 177)),
        "own:elix" to (Color.rgb(100, 230, 210) to Color.rgb(0, 137, 123)),
        "own:android" to (Color.rgb(174, 213, 129) to Color.rgb(56, 142, 60)),
    )

    /** PhoneXR is dark only. */
    @Suppress("UNUSED_PARAMETER")
    fun dark(context: Context) = true

    /** The themed icon of [packageName], a full square (the home rounds its corners), or null. */
    fun themed(context: Context, packageName: String, dark: Boolean = dark(context)): Drawable? {
        if (Build.VERSION.SDK_INT < 33) return null
        val icon = runCatching { context.packageManager.getApplicationIcon(packageName) }.getOrNull() as? AdaptiveIconDrawable ?: return null
        val mono = icon.monochrome ?: return null
        val colors = palette(packageName, dark)
        val size = 192
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(colors.tile)
        // Adaptive layers reach a quarter past the icon on every side.
        val extra = size / 4
        mono.mutate().colorFilter = PorterDuffColorFilter(colors.glyph, PorterDuff.Mode.SRC_IN)
        mono.setBounds(-extra, -extra, size + extra, size + extra)
        mono.draw(canvas)
        return BitmapDrawable(context.resources, bitmap)
    }
}
