package com.samrat.cardboardhands

import android.app.Activity
import android.content.Context

/**
 * PhoneXR VrApi Driver: the package com.oculus.systemdriver that the VrApi loader inside every Gear VR
 * and Quest (VrApi) game opens by itself. It carries PhoneXR's own VrApi (gearvr-shim), so such games
 * run as they came from the store, without a patch (vrapi-driver/build_driver_apk.py).
 */
object VrApiDriver {
    const val PACKAGE = "com.oculus.systemdriver"
    private const val ASSET = "runtime/phonexr-vrapi-driver.apk"
    /** versionCode of the driver bundled in this PhoneXR. */
    private const val BUNDLED_VERSION = 1L

    fun bundled(context: Context) = runCatching { context.assets.open(ASSET).close() }.isSuccess

    /** Installed, and it is PhoneXR's (another package by that name would not have the launcher). */
    fun ready(context: Context): Boolean {
        val info = runCatching { context.packageManager.getPackageInfo(PACKAGE, 0) }.getOrNull() ?: return false
        return info.longVersionCode >= BUNDLED_VERSION && runCatching {
            context.packageManager.getActivityInfo(android.content.ComponentName(PACKAGE, "dev.phonexr.vrapidriver.GameLauncher"), 0)
        }.isSuccess
    }

    fun install(activity: Activity) = Daydream.installAsset(activity, ASSET, "phonexr-vrapi-driver.apk")

    /** Installs or updates it through root or Shizuku; null when done. Slow. */
    fun installQuietly(activity: Activity): String? {
        if (ready(activity) || !bundled(activity)) return null
        return PhoneXrRuntime.installAssetQuietly(activity, ASSET, "phonexr-vrapi-driver.apk")
    }
}
