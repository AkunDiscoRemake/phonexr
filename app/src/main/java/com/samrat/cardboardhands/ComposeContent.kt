package com.samrat.cardboardhands

import android.app.Presentation
import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner

/**
 * A VR window whose inside is Compose, in the same compose-hig look as the PhoneXR app: the
 * composable lives on a private virtual display (like the browser's WebView), its picture is the
 * window's texture, and touches from the fingers arrive as a touchscreen.
 */
abstract class ComposeContent(
    private val barTitle: String? = null,
    override val pixelWidth: Int = 1600,
    override val pixelHeight: Int = 1000,
) : VrWindow.Content {
    /** A window without a panel (Elix): only what the composable draws is seen, the room shows through. */
    open val transparent = false

    /** What the window shows. */
    @Composable
    protected abstract fun Content()

    override val external = true
    private val main = Handler(Looper.getMainLooper())
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    private val owner = Owner()
    private var downTime = 0L

    /** App windows have no Safari-style bar: that belongs to the browser alone. */
    override fun toolbarTitle(): String? = null

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        texture?.setDefaultBufferSize(pixelWidth, pixelHeight)
        val surface = Surface(texture)
        main.post {
            val manager = context.getSystemService(DisplayManager::class.java)
            // 320 dpi: text and controls read like an iPad at arm's length.
            val created = manager.createVirtualDisplay("PhoneXR ${barTitle ?: "window"}", pixelWidth, pixelHeight, 320, surface, 0)
            display = created
            val shown = Presentation(context, created.display)
            val view = ComposeView(shown.context).apply {
                setContent { PhoneXRTheme(scale = 1f) { this@ComposeContent.Content() } }
            }
            if (transparent) shown.window?.apply {
                setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
                setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            }
            shown.setContentView(view)
            shown.window?.decorView?.let { decor ->
                decor.setViewTreeLifecycleOwner(owner)
                decor.setViewTreeSavedStateRegistryOwner(owner)
                decor.setViewTreeViewModelStoreOwner(owner)
            }
            owner.start()
            shown.show()
            presentation = shown
            onReady()
        }
    }

    override fun touch(action: Int, u: Float, v: Float) {
        main.post {
            val now = SystemClock.uptimeMillis()
            if (action == MotionEvent.ACTION_DOWN) downTime = now
            val event = MotionEvent.obtain(downTime, now, action, u * pixelWidth, v * pixelHeight, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            presentation?.window?.decorView?.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    override fun release() {
        main.post {
            owner.stop()
            presentation?.dismiss()
            display?.release()
        }
    }

    /** Lifecycle, saved state and view models for a composable that has no activity of its own. */
    private class Owner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        private val saved = SavedStateRegistryController.create(this)
        override val lifecycle: Lifecycle get() = registry
        override val savedStateRegistry: SavedStateRegistry get() = saved.savedStateRegistry
        override val viewModelStore = ViewModelStore()

        fun start() {
            saved.performRestore(null)
            registry.currentState = Lifecycle.State.RESUMED
        }

        fun stop() {
            registry.currentState = Lifecycle.State.DESTROYED
            viewModelStore.clear()
        }
    }
}
