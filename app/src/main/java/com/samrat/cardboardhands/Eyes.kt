package com.samrat.cardboardhands

import android.content.Context
import android.util.DisplayMetrics

/**
 * Where the two eyes are and where their pictures sit on the screen.
 *
 * Two different settings decide whether a headset shows one picture or two. The distance between
 * the eyes ([ipdMetres]) is how far apart the two cameras stand in the scene — it sets how deep
 * everything looks. [lensShift] moves each eye's picture sideways so its centre is under its lens:
 * the pictures are drawn in the middle of each half of the screen, and on a wide phone those
 * middles are further apart than the lenses — then the eyes cannot merge them and the picture
 * doubles, however good the tracking is.
 */
class Eyes private constructor(val ipdMetres: Float, val lensShift: Float) {
    /** Half the distance between the eyes: how far each virtual camera stands from the middle. */
    val halfIpd get() = ipdMetres / 2

    /**
     * For views drawn without the lens pass (cinema, panoramas): the same move under the lens, by
     * shifting the frustum — which keeps the view straight, unlike moving the viewport.
     */
    fun shift(projection: FloatArray, eye: Int) {
        if (lensShift == 0f) return
        // A picture moves by -projection[8] in the eye's own -1..1 view.
        projection[8] += if (eye == 0) -2f * lensShift else 2f * lensShift
    }

    companion object {
        fun load(context: Context): Eyes {
            // The whole panel, in landscape, in millimetres through the screen's own density.
            @Suppress("DEPRECATION")
            val metrics = DisplayMetrics().also {
                context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(it)
            }
            val longSide = maxOf(metrics.widthPixels, metrics.heightPixels)
            val dpi = (if (metrics.widthPixels >= metrics.heightPixels) metrics.xdpi else metrics.ydpi).takeIf { it > 1f } ?: 400f
            val screenMm = longSide / dpi * 25.4f
            val lensesMm = Settings.ipdMm(context).toFloat()
            // Each eye's picture is centred on a quarter of the screen; the lenses stand closer
            // together than that on most phones. Move each picture in under its lens (plus the
            // user's own correction), as a share of half the screen's width.
            val inwardMm = screenMm / 4 - lensesMm / 2 + Settings.lensOffsetMm(context)
            return Eyes(
                ipdMetres = lensesMm / 1000f,
                lensShift = (inwardMm / (screenMm / 2)).coerceIn(-.3f, .3f),
            )
        }

        /** What PhoneXR used before any of this could be set. */
        val DEFAULT = Eyes(.064f, 0f)
    }
}
