package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri

/**
 * PhoneXR Runtime: the OpenXR runtime (Monado with PhoneXR's hands and Joy-Con) that OpenXR games
 * talk to. It ships inside PhoneXR (assets/runtime) and shows up in the OpenXR Runtime Broker as
 * "PhoneXR Runtime", replacing a separately installed Monado.
 */
object PhoneXrRuntime {
    const val PACKAGE = "org.freedesktop.monado.openxr_runtime.out_of_process"
    private const val ASSET = "runtime/phonexr-runtime.apk"
    private const val BROKER = "org.khronos.openxr.runtime_broker"
    /** versionCode of the runtime bundled in this PhoneXR (openxr-runtime/build_runtime_apk.py). */
    private const val BUNDLED_VERSION = 7L

    enum class State { MISSING, OUTDATED, READY }

    fun state(context: Context): State {
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull() ?: return State.MISSING
        return if (info.longVersionCode < BUNDLED_VERSION && bundled(context)) State.OUTDATED else State.READY
    }

    fun bundled(context: Context) = runCatching { context.assets.open(ASSET).close() }.isSuccess

    fun install(activity: Activity) = Daydream.installAsset(activity, ASSET, "phonexr-runtime.apk")

    /**
     * PhoneXR keeps its own runtime in place without asking: when the bundled one is missing or newer,
     * it installs it quietly — through root (`pm install` as su) or through Shizuku. Games then reach
     * PhoneXR's OpenXR (and its built-in broker) with no patch and nothing for the user to set up.
     * Returns null when done, or why it could not (then the ordinary installer is the way).
     * Slow: call off the main thread.
     */
    fun installQuietly(activity: Activity): String? {
        if (state(activity) == State.READY || !bundled(activity)) return null
        return installAssetQuietly(activity, ASSET, "phonexr-runtime.apk")
    }

    /** Installs the APK asset [asset] through root or Shizuku; null when done, else why not. Slow. */
    fun installAssetQuietly(activity: Activity, asset: String, name: String): String? {
        val file = java.io.File(java.io.File(activity.cacheDir, "patched").apply { mkdirs() }, name)
        activity.assets.open(asset).use { input -> file.outputStream().use { input.copyTo(it) } }
        val rooted = runCatching {
            val process = ProcessBuilder("su", "-c",
                "cp '${file.path}' /data/local/tmp/$name && pm install -r -d /data/local/tmp/$name; rm -f /data/local/tmp/$name")
                .redirectErrorStream(true).start()
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor() == 0 && out.contains("Success")
        }.getOrDefault(false)
        if (rooted) return null
        if (VirtualScreen.access() != VirtualScreen.Access.READY) return "Нет root и Shizuku"
        val done = java.util.concurrent.CountDownLatch(1)
        var problem: String? = null
        activity.runOnUiThread { InternalInstaller.install(activity, file) { problem = it; done.countDown() } }
        done.await(120, java.util.concurrent.TimeUnit.SECONDS)
        return problem
    }

    fun brokerInstalled(context: Context) =
        runCatching { context.packageManager.getApplicationInfo(BROKER, 0) }.isSuccess

    /** The broker app, where the user picks "PhoneXR Runtime"; its Play page when it is missing. */
    fun openBroker(activity: Activity) {
        val launch = activity.packageManager.getLaunchIntentForPackage(BROKER)
        activity.startActivity(launch ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$BROKER")))
    }
}
