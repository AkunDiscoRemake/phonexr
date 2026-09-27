package com.samrat.cardboardhands

import android.annotation.SuppressLint
import android.app.Presentation
import android.content.ContentUris
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import android.content.Context
import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Size
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlin.concurrent.thread
import java.io.DataInputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.Socket

/**
 * A window floating in the VR home. Its picture comes from a [Content]: an Android surface (web page,
 * an app on a virtual display) or a bitmap (photos). Windows sit on a sphere around the user and are
 * moved with the bar under them.
 */
class VrWindow(val id: String, val title: String, val iconId: String, val content: Content) {
    /** Turn around the user in degrees, height relative to the eyes, both changed by the move bar. */
    @Volatile var yaw = 0f
    @Volatile var height = 0f
    @Volatile var minimized = false
    /** Width and height are independent: the corner follows the hand instead of locking aspect. */
    @Volatile var widthScale = 1f
    @Volatile var heightScale = 1f
    /** Desktop stream only: 0 is flat, otherwise the screen wraps around the viewer. */
    @Volatile var arcDegrees = 0f
    val width get() = WIDTH_M * widthScale
    val heightM get() = WIDTH_M * content.pixelHeight / content.pixelWidth * heightScale

    interface Content {
        val pixelWidth: Int
        val pixelHeight: Int
        /** True when the picture is a surface texture (GL_TEXTURE_EXTERNAL_OES). */
        val external: Boolean
        /** Texture coordinates for an eye: left and right halves for side-by-side photos. */
        fun uv(eye: Int): FloatArray = floatArrayOf(0f, 0f, 1f, 1f)
        /** Called on the GL thread with the texture the window draws; returns the surface to render into, if any. */
        fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit)
        /** A new bitmap to upload, for bitmap content. */
        fun takeBitmap(): Bitmap? = null
        /** Touch at 0..1 window coordinates. Action is MotionEvent.ACTION_DOWN / MOVE / UP. */
        fun touch(action: Int, u: Float, v: Float)
        fun key(event: KeyEvent) = Unit
        /** Safari-style bar above the window: the address to show, or null for no bar. */
        fun toolbarTitle(): String? = null
        /** A bar button: "back", "forward", "reload", "home". */
        fun toolbarAction(action: String) = Unit
        /** Bumped when the bar should be redrawn. */
        val toolbarVersion: Int get() = 0
        /** The browser's tabs (titles) and which one is shown. */
        fun toolbarTabs(): List<String> = emptyList()
        val toolbarTab: Int get() = 0
        fun motion(event: MotionEvent) = Unit
        /** True while a text field in the content wants the VR keyboard. */
        val keyboardRequested: Boolean get() = false
        /** A key from the VR keyboard: text, or "backspace" / "enter". */
        fun type(key: String) = Unit
        fun hideKeyboard() = Unit
        /** True while the content fills the whole headset view (an immersive WebXR session). */
        val immersive: Boolean get() = false
        /** An immersive-ar page: drawn over the camera picture instead of filling the view alone. */
        val immersiveAr: Boolean get() = false
        /** PhoneXR's tracked hands, as JSON, for an immersive page that uses them. */
        fun hands(json: String) = Unit
        /** Leaves the immersive view (the palm held to the face). */
        fun exitImmersive() = Unit
        fun release()
    }

    companion object {
        const val RADIUS = 1.45f
        const val WIDTH_M = 1.5f
    }
}

/** Builds touch events with one down time per gesture. */
private class TouchEvents(private val width: Int, private val height: Int, private val source: Int) {
    private var downTime = 0L

    fun event(action: Int, u: Float, v: Float): MotionEvent {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        return MotionEvent.obtain(downTime, now, action, u * width, v * height, 0).apply { this.source = source }
    }
}

/**
 * The PhoneXR browser: a WebView on the app's own virtual display, drawn into the window. It has
 * WebXR: the WebXR polyfill runs in every page before the page's scripts, and an immersive
 * session turns the window into the whole headset view — the display grows to the phone screen,
 * the polyfill draws both eyes for Cardboard lenses and follows the head with the phone's sensors.
 */
class BrowserContent(
    private val startUrl: String,
    private val onWebXr: (String) -> Unit = {},
    /**
     * Set for the avatar browser (Avaturn, VRoid Hub): a downloaded .glb / .vrm is handed here as
     * the user's avatar instead of going to the phone's Downloads.
     */
    private val onModel: ((ByteArray, String) -> Unit)? = null,
) : VrWindow.Content {
    /** A site shown as an app (Instagram, Discord): no address bar or tabs, only the window's pill. */
    var appMode: Boolean = false
    override val pixelWidth = 1600
    override val pixelHeight = 1000
    override val external = true
    private val main = Handler(Looper.getMainLooper())

    /** Set by a window watched together: run on every page; [onVideo] hears the page's video play/pause/seek. */
    var togetherScript: String? = null
    var onVideo: ((String) -> Unit)? = null

    /** Runs [script] in the page shown now. */
    fun runScript(script: String) {
        main.post { webView?.evaluateJavascript(script, null) }
    }
    private var display: VirtualDisplay? = null
    private var presentation: Presentation? = null
    /** A tab: its own WebView, where it is and what it is called. Changed on the main thread only. */
    private class Tab(var view: WebView, @Volatile var url: String, @Volatile var title: String = "")

    private val tabs = java.util.concurrent.CopyOnWriteArrayList<Tab>()
    @Volatile private var active = 0
    /** The page on screen: the chosen tab's WebView. */
    private val webView: WebView? get() = tabs.getOrNull(active)?.view
    private var texture: SurfaceTexture? = null
    private var screen = Size(2400, 1080)
    private val touches = TouchEvents(pixelWidth, pixelHeight, InputDevice.SOURCE_TOUCHSCREEN)
    private var immersiveTouches = TouchEvents(screen.width, screen.height, InputDevice.SOURCE_TOUCHSCREEN)
    @Volatile private var xr = false
    @Volatile private var ar = false
    private var viewContext: Context? = null

    override val immersive get() = xr
    override val immersiveAr get() = xr && ar

    override fun exitImmersive() {
        main.post {
            webView?.evaluateJavascript("window.__pxrSession && window.__pxrSession.end()", null)
        }
    }

    /**
     * The session starts or ended: the virtual display takes the standard WebXR size ([xrSize]), or
     * goes back to the window. The page asks for this before its session exists ([Bridge.prepareXr]),
     * so the game lays itself out once, at the right size.
     */
    private fun setImmersive(on: Boolean, augmented: Boolean = false) = main.post {
        ar = on && augmented
        // An AR page lets the camera show through where it draws nothing.
        webView?.setBackgroundColor(if (ar) android.graphics.Color.TRANSPARENT else android.graphics.Color.WHITE)
        if (on == xr) return@post
        val size = if (on) xrSize() else Size(pixelWidth, pixelHeight)
        texture?.setDefaultBufferSize(size.width, size.height)
        display?.resize(size.width, size.height, if (on) XR_DPI else 280)
        xr = on
    }

    /**
     * The standard WebXR view: always [XR_HEIGHT] pixels high and the phone screen's shape wide, at
     * the same density every time — so a game gets the same canvas on every start instead of
     * whatever the window happened to be, and nothing of it falls off the edges.
     */
    private fun xrSize(): Size {
        val aspect = (screen.width.toFloat() / screen.height).coerceIn(1.6f, 2.4f)
        return Size((XR_HEIGHT * aspect).toInt() / 2 * 2, XR_HEIGHT)
    }
    private val currentUrl: String get() = tabs.getOrNull(active)?.url ?: startUrl
    private val currentTitle: String get() = tabs.getOrNull(active)?.title.orEmpty()

    private fun tabOf(view: WebView) = tabs.firstOrNull { it.view === view }

    /** A new tab with [url], shown at once. */
    private fun addTab(url: String) {
        val context = viewContext ?: return
        val view = buildWebView(context, url)
        tabs += Tab(view, url)
        showTab(tabs.size - 1)
    }

    private fun showTab(index: Int) {
        if (index !in tabs.indices) return
        setImmersive(false)
        active = index
        presentation?.setContentView(tabs[index].view)
        keyboard = false
        version++
    }

    /** Closes a tab; the last one is not closed but goes to the start page. */
    private fun closeTab(index: Int) {
        val tab = tabs.getOrNull(index) ?: return
        if (tabs.size == 1) { tab.view.loadUrl(HOME); return }
        tabs.removeAt(index)
        tab.view.destroy()
        showTab(if (active >= tabs.size) tabs.size - 1 else if (index < active) active - 1 else active)
    }

    override fun toolbarTabs(): List<String> = tabs.map { tab ->
        tab.title.ifEmpty { if (tab.url.startsWith("file:")) "Новая вкладка" else Uri.parse(tab.url).host?.removePrefix("www.") ?: tab.url }
    }

    override val toolbarTab: Int get() = active
    @Volatile private var version = 0
    @Volatile private var keyboard = false
    /** The address bar while it is being typed into (the VR keyboard is up); null otherwise. */
    @Volatile private var editing: String? = null

    override val keyboardRequested get() = keyboard || editing != null

    /** Only PhoneXR's own start page may use the browser's private calls (history, bookmarks). */
    private fun ownPage() = currentUrl.startsWith("file:///android_asset/")

    /** Called from the page script: text fields and WebXR sessions. */
    private inner class Bridge {
        @JavascriptInterface fun keyboard(show: Boolean) { keyboard = show }
        @JavascriptInterface fun video(state: String) { onVideo?.invoke(state) }
        @JavascriptInterface fun enterXr(url: String) { onWebXr(url) }
        @JavascriptInterface fun immersive(on: Boolean, augmented: Boolean) { setImmersive(on, augmented) }
        /** Before an immersive session: the page goes to the standard size and gets it as "WxH". */
        @JavascriptInterface fun prepareXr(augmented: Boolean): String {
            setImmersive(true, augmented)
            return xrSize().let { "${it.width}x${it.height}" }
        }
        /** A model the page made in memory (a blob: download), as a data: URL. */
        @JavascriptInterface fun model(data: String, name: String) {
            val sink = onModel ?: return
            val bytes = runCatching { android.util.Base64.decode(data.substringAfter(","), android.util.Base64.DEFAULT) }.getOrNull() ?: return
            sink(bytes, name)
        }
        @JavascriptInterface fun newTab(url: String) { if (ownPage()) main.post { addTab(BrowserData.addressFor(url)) } }

        // The start page's own calls.
        @JavascriptInterface fun homeData(): String = viewContext?.takeIf { ownPage() }?.let { BrowserData.homeJson(it) } ?: "{}"
        @JavascriptInterface fun setDesktop(on: Boolean) { viewContext?.takeIf { ownPage() }?.let { BrowserData.setDesktop(it, on); main.post { applySettings() } } }
        @JavascriptInterface fun setAdblock(on: Boolean) { viewContext?.takeIf { ownPage() }?.let { BrowserData.setAdblock(it, on) } }
        @JavascriptInterface fun clearHistory() { viewContext?.takeIf { ownPage() }?.let { BrowserData.clearHistory(it) } }
        @JavascriptInterface fun removeBookmark(url: String) { viewContext?.takeIf { ownPage() }?.let { BrowserData.removeBookmark(it, url) } }
        @JavascriptInterface fun editAddress() { if (ownPage()) startEditing() }
    }

    /** Desktop sites: a computer's browser name and a wide page; otherwise the phone's own. */
    private fun applySettings() {
        val view = webView ?: return
        val context = viewContext ?: return
        val desktop = BrowserData.desktop(context)
        view.settings.userAgentString = if (desktop) BrowserData.DESKTOP_AGENT else null
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = desktop
    }

    private fun startEditing() {
        editing = ""
        version++
    }

    override fun type(key: String) {
        val typed = editing
        if (typed != null) {
            when (key) {
                "backspace" -> editing = typed.dropLast(1)
                "enter" -> {
                    editing = null
                    val url = BrowserData.addressFor(typed)
                    main.post { webView?.loadUrl(url) }
                }
                else -> if (typed.length < 400) editing = typed + key
            }
            version++
            return
        }
        val script = when (key) {
            "backspace" -> "window.__pxrType && __pxrType('', 1)"
            "enter" -> "window.__pxrType && __pxrType('\\n', 0)"
            else -> "window.__pxrType && __pxrType(${org.json.JSONObject.quote(key)}, 0)"
        }
        main.post { webView?.evaluateJavascript(script, null) }
    }

    override fun hideKeyboard() {
        if (editing != null) { editing = null; version++; return }
        keyboard = false
        main.post { webView?.evaluateJavascript("document.activeElement && document.activeElement.blur()", null) }
    }

    override val toolbarVersion get() = version

    override fun toolbarTitle(): String? {
        if (appMode) return null
        editing?.let { return it + "|" }
        val star = if (viewContext?.let { BrowserData.isBookmarked(it, currentUrl) } == true) "★ " else ""
        return star + when {
            currentUrl.startsWith("file:") -> "Поиск или адрес"
            else -> Uri.parse(currentUrl).host?.removePrefix("www.") ?: currentUrl
        }
    }

    override fun toolbarAction(action: String) {
        main.post {
            val view = webView ?: return@post
            when (action) {
                "back" -> if (view.canGoBack()) view.goBack()
                "forward" -> if (view.canGoForward()) view.goForward()
                "reload" -> view.reload()
                "home" -> view.loadUrl(HOME)
                "newtab" -> addTab(HOME)
                else -> when {
                    action.startsWith("tab:") -> action.removePrefix("tab:").toIntOrNull()?.let { showTab(it) }
                    action.startsWith("closetab:") -> action.removePrefix("closetab:").toIntOrNull()?.let { closeTab(it) }
                }
            }
            when (action) {
                // The address field: type a site or a search with the VR keyboard.
                "address" -> startEditing()
                "bookmark" -> viewContext?.let { context ->
                    if (currentUrl.startsWith("http")) {
                        BrowserData.toggleBookmark(context, currentUrl, currentTitle)
                        version++
                    }
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        texture?.setDefaultBufferSize(pixelWidth, pixelHeight)
        this.texture = texture
        // The whole panel, not the part left by system bars, so the shape is the same every time.
        @Suppress("DEPRECATION")
        val metrics = android.util.DisplayMetrics().also { context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(it) }
        screen = Size(maxOf(metrics.widthPixels, metrics.heightPixels), minOf(metrics.widthPixels, metrics.heightPixels))
        immersiveTouches = xrSize().let { TouchEvents(it.width, it.height, InputDevice.SOURCE_TOUCHSCREEN) }
        viewContext = context
        val surface = Surface(texture)
        main.post {
            val manager = context.getSystemService(DisplayManager::class.java)
            // A private display owned by PhoneXR: no special permission needed for our own content.
            // 280 dpi reads like a tablet at arm's length in VR.
            val created = manager.createVirtualDisplay("PhoneXR Browser", pixelWidth, pixelHeight, 280, surface, 0)
            display = created
            val view = buildWebView(context, startUrl)
            tabs += Tab(view, startUrl)
            active = 0
            presentation = Presentation(context, created.display).apply {
                // Translucent, so an immersive-ar page can show the camera where it draws nothing.
                window?.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
                setContentView(view)
                show()
            }
            onReady()
        }
    }

    /** A WebView with PhoneXR's page scripts and WebXR; built again if its renderer ever dies. */
    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(context: Context, url: String): WebView {
        // Debug builds: pages can be inspected from chrome://inspect on the computer.
        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
        return WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                webViewClient = object : WebViewClient() {
                    override fun doUpdateVisitedHistory(view: WebView, address: String, isReload: Boolean) {
                        tabOf(view)?.url = address
                        version++
                    }

                    // The ad blocker: known ad and tracker hosts get an empty answer.
                    override fun shouldInterceptRequest(view: WebView, request: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        // Pages bundled in the APK are served over https, so ES modules load.
                        if (request.url.host == ASSET_HOST) return assets.shouldInterceptRequest(request.url)
                        val context = viewContext ?: return null
                        if (!BrowserData.adblock(context) || !BrowserData.blocked(request.url.host)) return null
                        return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    }

                    override fun onPageStarted(view: WebView, address: String?, favicon: Bitmap?) {
                        keyboard = false
                        setImmersive(false)
                        // Old WebView without document-start scripts: as early as it can be.
                        if (!webXrAtStart) view.evaluateJavascript(webXrScript(view.context), null)
                        view.evaluateJavascript(PAGE_SCRIPT, null)
                    }

                    override fun onPageCommitVisible(view: WebView, address: String?) = view.evaluateJavascript(PAGE_SCRIPT, null)

                    override fun onPageFinished(view: WebView, address: String?) {
                        view.evaluateJavascript(PAGE_SCRIPT, null)
                        togetherScript?.let { view.evaluateJavascript(it, null) }
                        val title = view.title.orEmpty()
                        tabOf(view)?.title = title
                        version++
                        if (address != null) viewContext?.let { BrowserData.visit(it, address, title) }
                    }

                    // A heavy WebXR game can take the page's renderer down. Without this Android
                    // would close all of PhoneXR; instead the page comes back in a new WebView.
                    override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                        android.util.Log.w("PhoneXR-WebXR", "page renderer gone, crash=${detail.didCrash()}")
                        setImmersive(false)
                        main.post {
                            val tab = tabOf(view)
                            val fresh = buildWebView(view.context, tab?.url ?: currentUrl)
                            tab?.view = fresh
                            if (tab == tabs.getOrNull(active)) presentation?.setContentView(fresh)
                            view.destroy()
                        }
                        return true
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                        if (message.message().startsWith("PhoneXR")) android.util.Log.i("PhoneXR-WebXR", message.message())
                        return false
                    }
                }
                addJavascriptInterface(Bridge(), "PhoneXR")
                // Downloads go to Downloads/PhoneXR with the system's download manager.
                setDownloadListener { url, agent, disposition, mime, _ ->
                    val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
                    if (onModel != null && isModel(url, name, mime)) {
                        takeModel(this, url, name, agent)
                        return@setDownloadListener
                    }
                    runCatching {
                        val name = android.webkit.URLUtil.guessFileName(url, disposition, mime)
                        val request = android.app.DownloadManager.Request(Uri.parse(url))
                            .setMimeType(mime)
                            .addRequestHeader("User-Agent", agent)
                            .addRequestHeader("Cookie", android.webkit.CookieManager.getInstance().getCookie(url) ?: "")
                            .setNotificationVisibility(android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(android.os.Environment.DIRECTORY_DOWNLOADS, "PhoneXR/$name")
                        context.getSystemService(android.app.DownloadManager::class.java).enqueue(request)
                        android.widget.Toast.makeText(context, "Загрузка: $name", android.widget.Toast.LENGTH_SHORT).show()
                    }
                }
                settings.userAgentString = if (BrowserData.desktop(context)) BrowserData.DESKTOP_AGENT else null
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = BrowserData.desktop(context)
                settings.setSupportMultipleWindows(false)
                if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, webXrScript(context), setOf("*"))
                    webXrAtStart = true
                }
                loadUrl(url)
            }
    }

    private fun isModel(url: String, name: String, mime: String?) =
        url.startsWith("blob:") || name.endsWith(".glb", true) || name.endsWith(".vrm", true) ||
            mime == "model/gltf-binary" || mime == "application/octet-stream" && url.contains(".glb", true)

    /** Fetches a model download (with the page's cookies) and hands it to [onModel]. */
    private fun takeModel(view: WebView, url: String, name: String, agent: String) {
        val sink = onModel ?: return
        if (url.startsWith("blob:")) {
            // Only the page can read its own blob: turn it into a data: URL and pass it over.
            view.evaluateJavascript(
                "(async()=>{const b=await (await fetch(${org.json.JSONObject.quote(url)})).blob();" +
                    "const r=new FileReader();r.onload=()=>PhoneXR.model(r.result, ${org.json.JSONObject.quote(name)});r.readAsDataURL(b);})()",
                null,
            )
            return
        }
        thread(name = "PhoneXR avatar download") {
            runCatching {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", agent)
                android.webkit.CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                connection.inputStream.use { it.readBytes() }
            }.getOrNull()?.let { sink(it, name) }
        }
    }

    private var webXrAtStart = false
    private val assets by lazy {
        androidx.webkit.WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", androidx.webkit.WebViewAssetLoader.AssetsPathHandler(viewContext!!))
            .build()
    }

    /** PhoneXR's own hands for the page (21 points each in head space, metres), while immersive. */
    override fun hands(json: String) {
        if (!xr) return
        main.post { webView?.evaluateJavascript("window.__pxrHandsIn && __pxrHandsIn($json)", null) }
    }

    override fun touch(action: Int, u: Float, v: Float) {
        main.post {
            // In the immersive view a touch is WebXR "select", given where the polyfill listens: the screen.
            val event = if (xr) immersiveTouches.event(action, u, v) else touches.event(action, u, v)
            presentation?.window?.decorView?.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    override fun key(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        main.post {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BACK -> webView?.takeIf { it.canGoBack() }?.goBack()
                KeyEvent.KEYCODE_BUTTON_L1 -> webView?.pageUp(false)
                KeyEvent.KEYCODE_BUTTON_R1 -> webView?.pageDown(false)
            }
        }
    }

    override fun release() {
        main.post {
            presentation?.dismiss()
            tabs.forEach { it.view.destroy() }
            display?.release()
        }
    }

    companion object {
        /** Start page with shortcuts, so browsing works without a keyboard. */
        const val HOME = "file:///android_asset/browser/home.html"
        const val ASSET_HOST = "appassets.androidplatform.net"

        private var polyfill: String? = null
        /** The standard immersive WebXR view: this many pixels high, at this density. */
        private const val XR_HEIGHT = 900
        private const val XR_DPI = 300

        /**
         * WebXR for the WebView: Google's WebXR polyfill in Cardboard mode (its own buttons and
         * "rotate the phone" screen off, since the phone already sits in the headset), and a
         * watch on immersive sessions so PhoneXR can open the full view and close it again.
         */
        /** The phone's whole screen in metres, as the polyfill's device: the page fills it. */
        private fun device(context: Context): String {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also {
                context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(it)
            }
            val landscape = metrics.widthPixels >= metrics.heightPixels
            val long = maxOf(metrics.widthPixels, metrics.heightPixels)
            val short = minOf(metrics.widthPixels, metrics.heightPixels)
            val longDpi = (if (landscape) metrics.xdpi else metrics.ydpi).takeIf { it > 1f } ?: 400f
            val shortDpi = (if (landscape) metrics.ydpi else metrics.xdpi).takeIf { it > 1f } ?: 400f
            return "{ widthMeters: ${long / longDpi * .0254f}, heightMeters: ${short / shortDpi * .0254f}, bevelMeters: 0.003 }"
        }

        /**
         * The field of view PhoneXR itself draws each eye with: 90° high, as wide as half the screen
         * allows at that height (VR home's projection).
         */
        private fun fov(context: Context): String {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics().also {
                context.getSystemService(android.view.WindowManager::class.java).defaultDisplay.getRealMetrics(it)
            }
            val aspect = maxOf(metrics.widthPixels, metrics.heightPixels) / 2f / minOf(metrics.widthPixels, metrics.heightPixels)
            val side = Math.toDegrees(kotlin.math.atan(aspect.toDouble())).toFloat()
            return "{ upDegrees: 45, downDegrees: 45, leftDegrees: $side, rightDegrees: $side }"
        }

        /** The headset chosen in PhoneXR, with the user's lens correction, as a Cardboard viewer. */
        private fun viewer(context: Context): String {
            val headset = Headsets.current(context)
            val lenses = Settings.ipdMm(context) / 1000f
            return "{ id: 'PhoneXR', label: 'PhoneXR', fov: ${headset.fovDeg}, interLensDistance: $lenses, " +
                "baselineLensDistance: ${headset.trayToLensMm / 1000f}, screenLensDistance: ${headset.screenToLensMm / 1000f}, " +
                "distortionCoefficients: [0.34, 0.55], inverseCoefficients: [-0.33836704, -0.18162185, 0.862655, -1.2462051, 1.0560602, " +
                "-0.58208317, 0.21609078, -0.05444823, 0.009177956, -0.0009904169, 6.183535e-5, -1.6981803e-6] }"
        }

        fun webXrScript(context: Context): String {
            // The polyfill guesses the phone's screen from a list of phones; PhoneXR knows it.
            val source = polyfill ?: context.assets.open("browser/webxr-polyfill.js").bufferedReader().use { it.readText() }
                .replace("determineDevice_=function(e){if(!e)", "determineDevice_=function(e){if(window.__pxrDevice)return new H(window.__pxrDevice);if(!e)")
                // PhoneXR's field of view for every eye, and no lens warp of the polyfill's own: the
                // plain side-by-side picture goes through PhoneXR's lens pass like everything else.
                .replace("_getFieldOfView=function(e){var t;", "_getFieldOfView=function(e){if(window.__pxrFov)return window.__pxrFov;var t;")
                .replace("d=2*(o.x+d*o.width-.5),u=2*(o.y+u*o.height-.5)",
                    "d=window.__pxrFov?2*(A*.5+p*.5-.5):2*(o.x+d*o.width-.5),u=window.__pxrFov?2*(f-.5):2*(o.y+u*o.height-.5)")
                .also { polyfill = it }
            return """
                (function() {
                  if (window.__pxrXr) return;
                  window.__pxrXr = true;
                  // The headset and screen set in PhoneXR, not the polyfill's guesses.
                  window.__pxrDevice = ${device(context)};
                  window.__pxrFov = ${fov(context)};
                  try { localStorage.setItem('WEBVR_CARDBOARD_VIEWER', 'PhoneXR'); } catch (e) {}
                  // Hands from PhoneXR's tracking, for pages that want them: an event and a global.
                  window.__pxrHandsIn = function(data) {
                    data.at = performance.now();
                    window.PhoneXRHands = data;
                    window.dispatchEvent(new CustomEvent('phonexrhands', { detail: data }));
                  };
                  // Android WebView may carry a navigator.xr that can do nothing: take it away first,
                  // or the polyfill would think WebXR is already there and stay out.
                  try { delete Navigator.prototype.xr; } catch (e) {}
                  try { if ('xr' in navigator) Object.defineProperty(navigator, 'xr', { value: undefined, configurable: true, writable: true }); } catch (e) {}
                  var savedDefine = window.define; window.define = undefined;
                  // No browser fullscreen for the VR canvas (it would detach the page in a WebView):
                  // the polyfill then fills the page itself, and PhoneXR makes the page the whole view.
                  ['requestFullscreen', 'webkitRequestFullscreen', 'webkitRequestFullScreen', 'mozRequestFullScreen', 'msRequestFullscreen']
                    .forEach(function(name) { try { Element.prototype[name] = undefined; } catch (e) {} });
                  if (!navigator.xr) {
                    $source
                    try {
                      new WebXRPolyfill({ cardboard: true, allowCardboardOnDesktop: true,
                        cardboardConfig: { CARDBOARD_UI_DISABLED: true, ROTATE_INSTRUCTIONS_DISABLED: true, BUFFER_SCALE: 0.75,
                          DPDB_URL: false, ADDITIONAL_VIEWERS: [${viewer(context)}], DEFAULT_VIEWER: 'PhoneXR' } });
                    } catch (e) { console.log('PhoneXR WebXR: ' + e); }
                  }
                  window.define = savedDefine;
                  if (!navigator.xr || !navigator.xr.requestSession) return;
                  // AR: PhoneXR has the camera, so immersive-ar is offered too. It runs as a VR
                  // session whose empty pixels show the camera ("alpha-blend"), without hit tests.
                  var supported = navigator.xr.isSessionSupported.bind(navigator.xr);
                  navigator.xr.isSessionSupported = function(mode) {
                    if (mode === 'immersive-ar') return supported('immersive-vr');
                    return supported(mode);
                  };
                  var request = navigator.xr.requestSession.bind(navigator.xr);
                  // Standard size first: PhoneXR resizes the page, and the session starts once the
                  // page really is that size, so the game lays out its canvas once and fully.
                  function prepare(augmented) {
                    var size = String(PhoneXR.prepareXr(augmented)).split('x');
                    var want = Number(size[0]);
                    var started = performance.now();
                    return new Promise(function(resolve) {
                      (function wait() {
                        var now = Math.round(window.innerWidth * (window.devicePixelRatio || 1));
                        if (Math.abs(now - want) <= 8 || performance.now() - started > 1500) resolve();
                        else setTimeout(wait, 30);
                      })();
                    });
                  }
                  navigator.xr.requestSession = function(mode, options) {
                    var augmented = mode === 'immersive-ar';
                    var immersive = String(mode).indexOf('immersive') === 0 && window.PhoneXR;
                    var asked = options;
                    if (augmented) {
                      var keep = function(list) { return (list || []).filter(function(f) { return f === 'local' || f === 'local-floor' || f === 'viewer'; }); };
                      asked = Object.assign({}, options || {}, { requiredFeatures: keep(options && options.requiredFeatures), optionalFeatures: keep(options && options.optionalFeatures) });
                    }
                    var ready = immersive ? prepare(augmented) : Promise.resolve();
                    return ready.then(function() { return request(augmented ? 'immersive-vr' : mode, asked); }).then(function(session) {
                      if (augmented) {
                        try { Object.defineProperty(session, 'environmentBlendMode', { value: 'alpha-blend' }); } catch (e) {}
                        document.documentElement.style.background = 'transparent';
                        if (document.body) document.body.style.background = 'transparent';
                      }
                      if (immersive) {
                        window.__pxrSession = session;
                        PhoneXR.immersive(true, augmented);
                        session.addEventListener('end', function() { window.__pxrSession = null; PhoneXR.immersive(false, false); });
                        // Anything sized before the session (the polyfill's canvas) catches up now.
                        window.dispatchEvent(new Event('resize'));
                      }
                      return session;
                    }, function(error) {
                      if (immersive) PhoneXR.immersive(false, false);
                      throw error;
                    });
                  };
                })();
            """.trimIndent()
        }

        /**
         * Runs in every page: reports focused text fields so the VR keyboard shows, and types into them.
         */
        private val PAGE_SCRIPT = """
            (function() {
              if (window.__pxrType) return;
              var field = null;
              function editable(e) {
                if (!e || !e.tagName) return false;
                var tag = e.tagName.toLowerCase();
                if (tag == 'textarea') return !e.readOnly;
                if (tag == 'input') return ['text','search','email','url','tel','password','number',''].indexOf((e.type || '').toLowerCase()) >= 0 && !e.readOnly;
                return e.isContentEditable;
              }
              function pick(ev) { var t = ev.target; if (editable(t)) { field = t; PhoneXR.keyboard(true); } }
              document.addEventListener('focusin', pick, true);
              document.addEventListener('click', pick, true);
              document.addEventListener('focusout', function(ev) {
                if (ev.target === field) setTimeout(function() { if (document.activeElement !== field) PhoneXR.keyboard(false); }, 150);
              }, true);
              window.__pxrType = function(text, back) {
                var e = field || document.activeElement;
                if (!editable(e)) return;
                if (e.isContentEditable) {
                  e.focus();
                  if (back) document.execCommand('delete'); else if (text == '\n') document.execCommand('insertParagraph'); else document.execCommand('insertText', false, text);
                  return;
                }
                if (text == '\n' && e.tagName.toLowerCase() != 'textarea') {
                  var opts = {key: 'Enter', code: 'Enter', keyCode: 13, which: 13, bubbles: true};
                  var go = e.dispatchEvent(new KeyboardEvent('keydown', opts));
                  e.dispatchEvent(new KeyboardEvent('keyup', opts));
                  if (go && e.form) { if (e.form.requestSubmit) e.form.requestSubmit(); else e.form.submit(); }
                  PhoneXR.keyboard(false);
                  return;
                }
                var start = e.selectionStart, end = e.selectionEnd;
                if (start == null) { start = end = e.value.length; }
                if (back) { if (start == end && start > 0) start--; text = ''; }
                var proto = e.tagName.toLowerCase() == 'textarea' ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                var setter = Object.getOwnPropertyDescriptor(proto, 'value').set;
                setter.call(e, e.value.slice(0, start) + text + e.value.slice(end));
                try { e.setSelectionRange(start + text.length, start + text.length); } catch (x) {}
                e.dispatchEvent(new InputEvent('input', {bubbles: true, data: text, inputType: back ? 'deleteContentBackward' : 'insertText'}));
              };
            })();
        """.trimIndent()
    }
}

/** Another app (Minecraft first of all) on a Shizuku virtual display, played with a gamepad or by pinching. */
class ShizukuAppContent(context: Context, private val packageName: String, private val onError: (String) -> Unit) : VrWindow.Content {
    private val portrait = requestedPortrait(context, packageName)
    override val pixelWidth = if (portrait) 1080 else 1920
    override val pixelHeight = if (portrait) 1920 else 1080
    override val external = true
    private var connection: ServiceConnection? = null
    private var service: IDisplayService? = null
    private var displayId = -1
    private var context: Context? = null
    private val touches = TouchEvents(pixelWidth, pixelHeight, InputDevice.SOURCE_TOUCHSCREEN)

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        this.context = context
        texture?.setDefaultBufferSize(pixelWidth, pixelHeight)
        val surface = Surface(texture)
        Handler(Looper.getMainLooper()).post {
            if (VirtualScreen.access() != VirtualScreen.Access.READY) {
                onError("Запустите Shizuku и разрешите доступ PhoneXR")
                return@post
            }
            connection = VirtualScreen.bind(context) { bound ->
                service = bound ?: return@bind
                thread {
                    val id = runCatching { bound.createDisplay(surface, pixelWidth, pixelHeight, 320) }.getOrDefault(-1)
                    if (id < 0) return@thread onError("Не удалось создать экран")
                    displayId = id
                    val component = VirtualScreen.launcherComponent(context, packageName)
                        ?: return@thread onError("Приложение не установлено")
                    runCatching { bound.launch(component, id) }.getOrNull()?.let(onError)
                    onReady()
                }
            }
        }
    }

    override fun touch(action: Int, u: Float, v: Float) {
        val shell = service ?: return
        if (displayId < 0) return
        val event = touches.event(action, u, v)
        runCatching { shell.injectMotion(event, displayId) }
        event.recycle()
    }

    override fun key(event: KeyEvent) {
        if (displayId >= 0) runCatching { service?.injectKey(event, displayId) }
    }

    override fun motion(event: MotionEvent) {
        if (displayId >= 0) runCatching { service?.injectMotion(event, displayId) }
    }

    override fun type(key: String) {
        if (displayId < 0) return
        val events = when (key) {
            "backspace" -> keyPress(KeyEvent.KEYCODE_DEL)
            "enter" -> keyPress(KeyEvent.KEYCODE_ENTER)
            else -> KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(key.toCharArray())?.toList()
                // Letters the key map lacks (Cyrillic) go as a character event.
                ?: listOf(KeyEvent(SystemClock.uptimeMillis(), key, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
        events.forEach { runCatching { service?.injectKey(it, displayId) } }
    }

    private fun keyPress(code: Int): List<KeyEvent> {
        val now = SystemClock.uptimeMillis()
        return listOf(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0), KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0))
    }

    override fun release() {
        runCatching { service?.releaseDisplay() }
        val ctx = context
        connection?.let { if (ctx != null) VirtualScreen.unbind(ctx, it) }
    }

    companion object {
        /** Honour the launched activity's Android orientation; ordinary phone apps default portrait. */
        private fun requestedPortrait(context: Context, packageName: String): Boolean {
            val component = VirtualScreen.launcherComponent(context, packageName)?.let(ComponentName::unflattenFromString)
            val orientation = component?.let {
                runCatching { context.packageManager.getActivityInfo(it, 0).screenOrientation }.getOrNull()
            }
            return when (orientation) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> false
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT -> true
                else -> runCatching {
                    context.packageManager.getApplicationInfo(packageName, 0).category != ApplicationInfo.CATEGORY_GAME
                }.getOrDefault(true)
            }
        }
    }
}

/** Monitor streamed by the native Windows/Linux/macOS PhoneXR companion. */
class DesktopStreamContent(private val onStatus: (String) -> Unit) : VrWindow.Content {
    override val pixelWidth = 1600
    override val pixelHeight = 900
    override val external = false
    @Volatile private var next: Bitmap? = null
    @Volatile private var running = true
    private var discovery: DatagramSocket? = null
    private var socket: Socket? = null

    override fun attach(context: Context, texture: SurfaceTexture?, onReady: () -> Unit) {
        thread(name = "PhoneXR desktop stream") {
            while (running) {
                runCatching {
                    onStatus("Ищу PhoneXR Desktop в локальной сети…")
                    val udp = DatagramSocket(null).also { discovery = it; it.reuseAddress = true; it.bind(InetSocketAddress(24819)) }
                    val bytes = ByteArray(512); val packet = DatagramPacket(bytes, bytes.size)
                    udp.receive(packet)
                    val parts = String(packet.data, 0, packet.length).split(' ', limit = 3)
                    require(parts.firstOrNull() == "PHONEXR_DESKTOP_V1")
                    val port = parts.getOrNull(1)?.toIntOrNull() ?: 24820
                    udp.close(); discovery = null
                    val tcp = Socket().also { socket = it; it.connect(InetSocketAddress(packet.address, port), 4000); it.tcpNoDelay = true }
                    onStatus("Подключено: ${parts.getOrNull(2) ?: packet.address.hostAddress}")
                    val input = DataInputStream(tcp.getInputStream())
                    while (running) {
                        val magic = ByteArray(4); input.readFully(magic)
                        require(String(magic) == "PXS1")
                        input.readUnsignedShort(); input.readUnsignedShort()
                        val size = input.readInt(); require(size in 1..20_000_000)
                        val jpeg = ByteArray(size); input.readFully(jpeg)
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.let { frame ->
                            val old = next; next = frame; old?.takeIf { it !== frame }?.recycle(); onReady()
                        }
                    }
                }.onFailure { if (running) { onStatus("Связь с ПК потеряна, переподключаюсь…"); Thread.sleep(700) } }
                runCatching { socket?.close() }; socket = null
            }
        }
    }

    override fun takeBitmap(): Bitmap? = next.also { next = null }
    override fun touch(action: Int, u: Float, v: Float) = Unit
    override fun release() { running = false; runCatching { discovery?.close() }; runCatching { socket?.close() }; next?.recycle(); next = null }
}

/**
 * 3D memories: the gallery as a grid of photos and videos; one opens large. What holds two eyes
 * (side by side or one over the other) is shown in 3D, each eye taking its own half — see [Spatial].
 * A video opens in its own window, through [onVideo].
 */
class PhotosContent(
    private val context: Context,
    private val onVideo: (Uri, String, Int, Int) -> Unit = { _, _, _, _ -> },
    /** A 360° or 180° memory does not fit a window: it goes around the viewer instead. */
    private val onPanorama: (Uri, String, Int, Int, Boolean) -> Unit = { _, _, _, _, _ -> },
) : ComposeContent() {
    private data class Photo(
        val uri: Uri,
        val name: String,
        val width: Int,
        val height: Int,
        val video: Boolean = false,
    ) {
        val layout get() = Spatial.layout(name, width, height)
        val shape get() = Spatial.shape(name, width, height, layout)
        val stereo get() = layout != Spatial.Layout.MONO
    }

    private var photos by androidx.compose.runtime.mutableStateOf(emptyList<Photo>())
    private var open by androidx.compose.runtime.mutableStateOf<Photo?>(null)
    /** What the window says while a flat photo is being turned into a 3D one. */
    private var status by androidx.compose.runtime.mutableStateOf<String?>(null)
    private var busy by androidx.compose.runtime.mutableStateOf(false)

    init {
        thread(name = "PhoneXR photos") { photos = query() }
    }


    override fun toolbarAction(action: String) {
        thread {
            when (action) {
                "back" -> open = null
                "forward" -> open?.let { current -> photos.getOrNull(photos.indexOf(current) + 1)?.let { open = it } }
                "reload", "home" -> { open = null; photos = query() }
            }
        }
    }

    /** Re-reads the gallery, e.g. after a photo was taken in VR. */
    fun reload() = toolbarAction("reload")

    override fun uv(eye: Int): FloatArray {
        val photo = open?.takeIf { it.stereo } ?: return floatArrayOf(0f, 0f, 1f, 1f)
        // A 3D photo fills the window, so each eye's half of the window is that eye's half of it.
        return Spatial.uv(photo.layout, eye)
    }

    private fun pick(item: Photo) {
        when {
            item.shape != Spatial.Shape.FLAT -> onPanorama(item.uri, item.name, item.width, item.height, item.video)
            // A video plays in a window of its own, with a timeline under it.
            item.video -> onVideo(item.uri, item.name, item.width, item.height)
            else -> { status = null; open = item }
        }
    }

    @androidx.compose.runtime.Composable
    override fun Content() {
        val photo = open
        if (photo != null) Viewer(photo) else Grid()
    }

    @androidx.compose.runtime.Composable
    private fun Grid() {
        androidx.compose.foundation.layout.Column(
            androidx.compose.ui.Modifier.fillMaxSize().background(zone.ien.hig.theme.CupertinoTheme.colorScheme.systemBackground)
        ) {
            VrTitle(tr("Фото"), if (photos.isEmpty()) "Нет фото или нет доступа к галерее" else "${photos.size} · новые сверху")
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(5),
                modifier = androidx.compose.ui.Modifier.fillMaxSize().padding(horizontal = 12.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp),
            ) {
                items(photos.size, key = { photos[it].uri.toString() }) { index ->
                    val item = photos[index]
                    androidx.compose.foundation.layout.Box(
                        androidx.compose.ui.Modifier.aspectRatio(1f).clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                            .background(androidx.compose.ui.graphics.Color(0xFF2C2C2E)).clickable { pick(item) }
                    ) {
                        val thumb = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, item.uri) {
                            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                runCatching { context.contentResolver.loadThumbnail(item.uri, Size(320, 320), null).asImageBitmap() }.getOrNull()
                            }
                        }.value
                        if (thumb != null) androidx.compose.foundation.Image(thumb, null, contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            modifier = androidx.compose.ui.Modifier.fillMaxSize())
                        val badge = when {
                            item.shape != Spatial.Shape.FLAT && item.stereo -> "3D 360"
                            item.shape != Spatial.Shape.FLAT -> "360"
                            item.stereo -> "3D"
                            item.video -> "▶"
                            else -> null
                        }
                        if (badge != null) zone.ien.hig.CupertinoText(
                            badge, color = androidx.compose.ui.graphics.Color.White,
                            modifier = androidx.compose.ui.Modifier.padding(6.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                                .background(androidx.compose.ui.graphics.Color(0xAA000000)).padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Viewer(photo: Photo) {
        val full = androidx.compose.runtime.produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, photo.uri) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                (runCatching { context.contentResolver.loadThumbnail(photo.uri, Size(2400, 1400), null) }.getOrNull()
                    ?: runCatching { context.contentResolver.openInputStream(photo.uri)?.use { BitmapFactory.decodeStream(it) } }.getOrNull())
                    ?.asImageBitmap()
            }
        }.value
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
            // Side-by-side photos fill the whole window with nothing over them: each eye samples its own half.
            if (full != null) androidx.compose.foundation.Image(
                full, null,
                contentScale = if (photo.stereo) androidx.compose.ui.layout.ContentScale.FillBounds else androidx.compose.ui.layout.ContentScale.Fit,
                modifier = androidx.compose.ui.Modifier.fillMaxSize().clickable { open = null }
            )
            if (!photo.stereo) androidx.compose.foundation.layout.Row(
                androidx.compose.ui.Modifier.align(androidx.compose.ui.Alignment.BottomStart).padding(20.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                zone.ien.hig.CupertinoButton(onClick = { open = null }, colors = zone.ien.hig.CupertinoButtonDefaults.tintedButtonColors()) {
                    zone.ien.hig.CupertinoText("‹ " + tr("Назад"))
                }
                if (!photo.video) zone.ien.hig.CupertinoButton(onClick = { makeStereo(photo) }, enabled = !busy) {
                    zone.ien.hig.CupertinoText(if (busy) "Делаю 3D…" else "Сделать 3D")
                }
                status?.let { zone.ien.hig.CupertinoText(it, color = androidx.compose.ui.graphics.Color.White) }
            }
        }
    }

    /** Photos and videos of the gallery, newest first. */
    /**
     * Turns a flat photo into a 3D one: the depth network says how far everything is, and the photo
     * is drawn again for each eye. The copy goes to the gallery and opens right away.
     */
    private fun makeStereo(photo: Photo) {
        if (busy) return
        busy = true
        thread {
            try {
                if (!DepthModel.installed(context)) {
                    status = "Нейросеть глубины не скачана: включите её в настройках PhoneXR (${DepthModel.MEGABYTES} МБ)"
                    return@thread
                }
                val made = SpatialPhoto.create(context, photo.uri, photo.name) { stage -> status = stage }
                photos = query()
                open = photos.firstOrNull { it.uri == made } ?: open
                status = "Готово: 3D‑копия лежит в галерее"
            } catch (failure: Throwable) {
                status = failure.message ?: "Не получилось сделать 3D"
            } finally {
                busy = false
            }
        }
    }

    private fun query(): List<Photo> = images() + videos()

    private fun images(): List<Photo> = runCatching {
        val list = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DISPLAY_NAME, MediaStore.Images.Media.WIDTH, MediaStore.Images.Media.HEIGHT),
            null, null, "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && list.size < 400) {
                val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                list += Photo(uri, cursor.getString(1) ?: "", cursor.getInt(2), cursor.getInt(3))
            }
        }
        list
    }.getOrDefault(emptyList())

    private fun videos(): List<Photo> = runCatching {
        val list = ArrayList<Photo>()
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.WIDTH, MediaStore.Video.Media.HEIGHT),
            null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            while (cursor.moveToNext() && list.size < 200) {
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0))
                list += Photo(uri, cursor.getString(1) ?: "", cursor.getInt(2), cursor.getInt(3), video = true)
            }
        }
        list
    }.getOrDefault(emptyList())
}
