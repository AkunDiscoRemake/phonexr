package com.samrat.cardboardhands

import android.content.Context

/**
 * Phone VR headsets PhoneXR knows: how far apart their lenses are, how far the lenses stand from
 * the screen and how wide they see. Choosing one sets the lens distance for PhoneXR's own views
 * and for WebXR pages.
 */
object Headsets {
    /**
     * [lensesMm] between the lens centres, [screenToLensMm] from the screen to the lenses,
     * [trayToLensMm] from the bottom of the phone to the lens centres, [fovDeg] seen through a lens.
     */
    data class Headset(
        val id: String, val name: String, val lensesMm: Int,
        val screenToLensMm: Float, val trayToLensMm: Float, val fovDeg: Float,
    )

    val ALL = listOf(
        Headset("cardboard2", "Google Cardboard (2015)", 64, 39.3f, 35f, 60f),
        Headset("cardboard1", "Google Cardboard (2014)", 60, 42f, 35f, 40f),
        Headset("vrbox", "VR Box", 63, 42f, 35f, 60f),
        Headset("shinecon", "VR Shinecon", 62, 40f, 35f, 60f),
        Headset("bobovr", "BOBOVR Z4 / Z5", 62, 39f, 35f, 65f),
        Headset("homido", "Homido", 64, 41f, 35f, 60f),
        Headset("merge", "Merge VR", 63, 40f, 35f, 60f),
        Headset("daydream", "Google Daydream View", 64, 39.3f, 35f, 60f),
        Headset("gearvr", "Samsung Gear VR", 62, 39f, 35f, 60f),
        Headset("mivr", "Xiaomi Mi VR Play", 62, 40f, 35f, 60f),
        Headset("other", "Другой шлем", 64, 39.3f, 35f, 60f),
    )

    private const val PREFS = "headset"

    /** The headset chosen, or the standard Cardboard before any choice. */
    fun current(context: Context): Headset {
        val id = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("id", null)
        return ALL.firstOrNull { it.id == id } ?: ALL.first()
    }

    fun chosen(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains("id")

    /** Chooses [headset]: its lens distance becomes PhoneXR's, the user's fine correction is reset. */
    fun choose(context: Context, headset: Headset) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("id", headset.id).apply()
        Settings.setIpdMm(context, headset.lensesMm)
        Settings.setLensOffsetMm(context, 0)
    }
}
