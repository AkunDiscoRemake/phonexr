package com.samrat.cardboardhands

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * The user's 3D avatar: a model made in Avaturn (GLB) or taken from VRoid Hub (VRM), kept in the
 * app's own files; PhoneXR's standard character until there is one.
 */
object AvatarModel {
    enum class Source(val title: String) { STANDARD("Стандартный"), AVATURN("Avaturn"), VROID("VRoid Hub"), FILE("Из файла") }

    private const val PREFS = "avatar_model"
    private const val STANDARD = "avatar/avatar.glb"

    private fun file(context: Context) = File(context.filesDir, "avatar/model.glb")

    fun source(context: Context): Source = runCatching {
        if (!file(context).exists()) return Source.STANDARD
        Source.valueOf(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("source", Source.FILE.name)!!)
    }.getOrDefault(Source.FILE)

    /** The model to draw: the user's own, or the standard one. */
    fun open(context: Context): InputStream =
        file(context).takeIf { it.exists() }?.inputStream() ?: context.assets.open(STANDARD)

    /**
     * Keeps [bytes] (a .glb or .vrm) as the user's avatar, if it really is a character PhoneXR can
     * move. Returns an error text, or null when it is saved.
     */
    fun save(context: Context, bytes: ByteArray, source: Source): String? {
        val model = runCatching { Gltf.load(bytes.inputStream()) }.getOrElse { return "Это не 3D‑модель .glb или .vrm" }
        if (model.joints.isEmpty()) return "У модели нет скелета — её нельзя оживить"
        val target = file(context)
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "model.tmp")
        temporary.writeBytes(bytes)
        if (!temporary.renameTo(target)) return "Не удалось сохранить аватар"
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("source", source.name).apply()
        return null
    }

    /** Back to the standard character. */
    fun reset(context: Context) {
        file(context).delete()
    }

    /** VRoid's app in Google Play (the store's search, so any VRoid app for this phone shows up). */
    fun openVroid(context: Context) {
        val market = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://search?q=VRoid"))
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(market) }.onFailure {
            context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://play.google.com/store/search?q=VRoid&c=apps")).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Where to make or find an avatar, inside PhoneXR's browser. */
    const val AVATURN_URL = "https://avaturn.me/"
    const val VROID_HUB_URL = "https://hub.vroid.com/"
}
