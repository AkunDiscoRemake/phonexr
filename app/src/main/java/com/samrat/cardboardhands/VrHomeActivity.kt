package com.samrat.cardboardhands

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SurfaceTexture
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.hardware.SensorManager
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.GLUtils
import android.opengl.Matrix
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.MicOff
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.People
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * PhoneXR Home, visionOS style, in mixed reality: the camera image fills the view, round app icons
 * float in front, apps open as windows (browser, Minecraft, Spatial Photos) with a move bar,
 * minimize to the dock and close. The hand aims a cursor and a pinch clicks, a fist at a window's left
 * or right edge carries it, and a palm held toward the face opens the menu.
 */
class VrHomeActivity : Activity(), LifecycleOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private lateinit var surfaceView: GLSurfaceView
    private lateinit var tracker: HeadTracker
    private val panel = HomePanel()
    /** Control from the Mac (PhoneXR Share) over USB: see and work the windows with mouse and keys. */
    private val remote = RemoteControl(object : RemoteControl.Host {
        override fun windows(): List<VrWindow> = windows.toList()
        override fun apps(): List<HomePanel.Entry> = synchronized(panel) { panel.homeEntries() }
        override fun capture(window: VrWindow, maxWidth: Int): Bitmap? {
            val done = java.util.concurrent.CountDownLatch(1)
            var picture: Bitmap? = null
            glTasks += { picture = runCatching { renderer?.capture(window, maxWidth) }.getOrNull(); done.countDown() }
            done.await(800, java.util.concurrent.TimeUnit.MILLISECONDS)
            return picture
        }
        override fun open(entryId: String) = runOnUiThread {
            val entry = synchronized(panel) { panel.homeEntries() }.firstOrNull { it.id == entryId } ?: HomePanel.Entry(entryId, "", null)
            openEntry(entry)
        }
        override fun openUrl(url: String) = runOnUiThread {
            openWindow("web:$url", Uri.parse(url).host ?: url, ID_BROWSER) { BrowserContent(url, ::openWebXr) }
        }
        override fun window(window: VrWindow, action: String) = runOnUiThread {
            when (action) {
                "minimize" -> minimize(window)
                "close" -> close(window)
                else -> restore(window)
            }
        }
    })
    /** The launcher in compose-hig, drawn on its own virtual display (see [HomePanelContent]). */
    private var panelContent: HomePanelContent? = null
    /** The app launcher can be hidden from the two-button palm menu without closing app windows. */
    @Volatile private var panelVisible = true
    /** 6DoF with ARCore; null means rotation only (no ARCore, or 6DoF off in settings). */
    @Volatile private var ar: ArTracker? = null
    private var renderer: Renderer? = null
    /** Head position in the world (metres), from ARCore; stays zero in 3DoF. */
    private val headPosition = FloatArray(3)
    /** How the last hand-tracking frame lies on the eye's view (ARCore frames): left, top, width, height. */
    @Volatile private var arFrameMap: FloatArray? = null
    /** The latest ARCore camera frame, kept for "take a photo". */
    @Volatile private var arPhoto: Bitmap? = null
    // Material You: the wallpaper's tones, as the rest of the home.
    private val keyboard by lazy { KeyboardPanel().apply { MaterialYouIcons.palette(this@VrHomeActivity, true).let { tint(it.tile, it.glyph) } } }
    private val keyboardRedraw = AtomicBoolean(true)
    @Volatile private var hoveredKey: String? = null
    /** Window the keyboard was opened for by hand (apps that cannot ask for it themselves). */
    @Volatile private var manualKeyboard: VrWindow? = null
    private val redraw = AtomicBoolean(true)
    private val cameraExecutor = Executors.newSingleThreadExecutor()
    private val trackingExecutor = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)
    private var handTracker: HandTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null

    @Volatile private var frame: Bitmap? = null
    private val frameLock = Any()
    private val frameFresh = AtomicBoolean(false)

    private val windows = CopyOnWriteArrayList<VrWindow>()
    /** GL work that must run on the render thread: creating and deleting window textures. */
    private val glTasks = ConcurrentLinkedQueue<() -> Unit>()
    private val textures = ConcurrentHashMap<String, Int>()
    private val surfaceTextures = ConcurrentHashMap<String, SurfaceTexture>()
    private val framesReady = ConcurrentHashMap.newKeySet<String>()
    /**
     * How image coordinates map to view tangents on screen, set by the renderer from the eye and camera
     * shapes: a point at image x shows at tan = (x - 0.5) * 2 * viewScaleX. The cursor and hand use the
     * same mapping, so they sit exactly on the fingers seen in the passthrough.
     */
    @Volatile private var viewScaleX = 16f / 9f
    @Volatile private var viewScaleY = 1f
    /** Landmarks of visible hands (x, y pairs, 21 points each), for the white hand overlay. */
    @Volatile private var handPoints: List<FloatArray> = emptyList()
    /** The cursor on the view (image coordinates): the hand's aim point, or the Joy-Con's; null with no hand. */
    @Volatile private var pinchPoint: FloatArray? = null

    /** Pointer ray in world space (from the eyes), or null without a hand. */
    @Volatile private var ray: FloatArray? = null
    @Volatile private var hit: Hit? = null
    @Volatile private var pressing = false
    private var focused: VrWindow? = null
        set(value) {
            field = value
            // Joy-Con play an app window (Minecraft) as a gamepad; elsewhere they point and click.
            CinemaActivity.setJoyConPassthrough(this, value?.content is ShizukuAppContent)
        }
    private var drag: Drag? = null
    private var pressedHit: Hit? = null
    /** First-start setup; null once the home is set up. */
    @Volatile private var onboarding: Onboarding? = null
    private var onboardingTexture = 0
    /** When the home appeared after setup, for its entrance animation. */
    @Volatile private var appearStart = 0L
    /** Bone lengths from the setup's hand scan (null before it). */
    private val handProfile by lazy { HandProfile.bones(this) }
    /** Pinch click, with the thresholds from the setup's calibration. */
    private val pinch by lazy { HandProfile.latch(this) }
    // Quick to follow a moving finger, still calm when it rests on a button.
    private val filterX = HandGestures.OneEuro(minCutoff = .9f, beta = 3f, deadZone = .0015f)
    private val filterY = HandGestures.OneEuro(minCutoff = .9f, beta = 3f, deadZone = .0015f)
    private val steamVrLink = SteamVrLink()
    /** Which hand drives the cursor: the one that pinches keeps it until it lets go. */
    private var activeLeft: Boolean? = null
    private var lastMoveSent = 0L

    private var games = emptyMap<String, GameLibrary.Game>()
    private var storeApps = emptyList<WebApps.App>()

    private sealed class Hit {
        data class Panel(val u: Float, val v: Float) : Hit()
        data class Setup(val u: Float, val v: Float) : Hit()
        data class Content(val window: VrWindow, val u: Float, val v: Float) : Hit()
        data class Bar(val window: VrWindow) : Hit()
        data class Minimize(val window: VrWindow) : Hit()
        data class Close(val window: VrWindow) : Hit()
        data class Toolbar(val window: VrWindow, val u: Float, val v: Float) : Hit()
        data class Resize(val window: VrWindow) : Hit()
        data class KeyboardButton(val window: VrWindow) : Hit()
        data class Expand(val window: VrWindow) : Hit()
        data class Curve(val window: VrWindow) : Hit()
        data class Keyboard(val window: VrWindow, val u: Float, val v: Float) : Hit()
        data class DesktopWidth(val window: VrWindow, val wider: Boolean) : Hit()
        data class DesktopCurve(val window: VrWindow) : Hit()
    }

    /** The window the VR keyboard types into, if it is shown. */
    private fun keyboardWindow(): VrWindow? {
        val manual = manualKeyboard?.takeIf { it in windows && !it.minimized }
        if (manual != null) return manual
        return windows.firstOrNull { !it.minimized && it.content.keyboardRequested }
    }

    /** As on Quest: close in front of the user, a little below the eyes — easy to reach and to read. */
    @Suppress("UNUSED_PARAMETER")
    private fun keyboardCenterY(window: VrWindow) = -.1f * distanceScale

    /** Height of the browser's top bar for [window], metres (0 for other windows). */
    private fun barHeight(window: VrWindow) =
        if ((window.content as? BrowserContent)?.appMode == false) window.width * WindowChrome.BAR_H / WindowChrome.BAR_W else 0f

    /** Where the window's frame ends below: the bottom of its pill. */
    private fun frameBottom(window: VrWindow) = -window.heightM / 2 - WindowChrome.PILL_GAP - WindowChrome.PILL_H

    /** Whether the window's pill has a back button (windows with pages of their own, not the browser). */
    private fun pillBack(window: VrWindow) = (window.content as? BrowserContent)?.appMode == true ||
        window.content !is BrowserContent && window.content.toolbarTitle() != null

    /** Enlarged with the pill's expand button. */
    private fun expanded(window: VrWindow) = window.widthScale >= EXPANDED_SCALE - .01f

    private data class Drag(val window: VrWindow, val startYaw: Float, val startHeight: Float, val pointerYaw: Float, val pointerHeight: Float, val resize: Boolean = false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        L10n.init(this)
        // The VR home, too, is only for a signed-in user — except the first setup, which signs in
        // (or creates the account) itself.
        if (Account.current(this) == null && Settings.setupDone(this)) {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
            finish()
            return
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        tracker = HeadTracker(getSystemService(SensorManager::class.java)) { display }
        surfaceView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            setRenderer(Renderer().also { renderer = it })
            setOnClickListener { recenter() }
            // A captured mouse sends its movement here instead of moving a pointer on the phone.
            setOnCapturedPointerListener { _, event -> onMouse(event, captured = true); true }
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(surfaceView)
        // Always the compact panel: the library opens from the panel or the left hand's menu button.
        panel.compact = true
        panel.dark = MaterialYouIcons.dark(this)
        loadRoom()
        RoomScan.load(this)
        // The old face scan (Persona) is gone: its pictures of the face are not kept.
        java.io.File(filesDir, "persona").deleteRecursively()
        if (!Settings.setupDone(this)) onboarding = Onboarding(this, onboardingHost)
        // BE has no camera to show the room: a black space around the windows.
        if (BuildConfig.BE) setEnvironment("black")
        // 6DoF: ARCore follows the room through the camera (walking, the room scan, the table);
        // where ARCore is missing (Huawei, Lite) a neck model stands in: the eyes swing around the
        // neck as the head turns and tilts. Integrating the accelerometer drifted away within seconds.
        // Car mode is always 3DoF: in a moving car the room itself moves.
        val sixDof = Settings.load(this).sixDof && !Settings.travelMode(this)
        if (!BuildConfig.LITE && sixDof && ArTracker.availability(this) == ArTracker.Availability.READY) {
            ar = ArTracker.create(this)
        }
        neckModel = sixDof
        remote.start()
        if (!BuildConfig.BE) trackingExecutor.execute {
            handTracker = runCatching { HandTracker(this, useGpu = true, onResult = ::onHands) }
                .getOrElse { HandTracker(this, useGpu = false, onResult = ::onHands) }
        }
    }

    override fun onResume() {
        super.onResume()
        DisplayRate.apply(this)
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
        stopService(Intent(this, HandTrackingService::class.java))
        // ARCore owns the camera in 6DoF; if it cannot start, CameraX gives 3DoF passthrough.
        if (ar?.resume() == false) { ar?.close(); ar = null; toast("6DoF недоступен: работает 3DoF") }
        surfaceView.onResume()
        carMode = Settings.travelMode(this)
        tracker.travelMode = carMode
        synchronized(panel) { panel.car = carMode }
        tracker.start()
        sensorSixDof?.let { it.reset(); it.start() }
        if (BuildConfig.BE) Unit
        else if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) toast("Разрешите PhoneXR доступ к камере")
        else if (ar == null) {
            bindCamera()
            if (Settings.load(this).sixDof && !Settings.travelMode(this)) startArLater()
        }
        CinemaActivity.setJoyConPassthrough(this, false)
        loadApps()
        Calls.localHands = { handPoints }
        Calls.localHandImage = if (BuildConfig.LITE) ({ null }) else ({ handFrameForCall() })
        Calls.unlisten(callListener)
        Calls.listen(callListener)
        if (!BuildConfig.BE) thread(name = "PhoneXR calls start") { Calls.start(this); Calls.setStatus(tr("В VR")) }
        Calls.onShared = { url -> watchTogether(url, fromPeer = true) }
        Calls.onRemoteInput = { input ->
            runOnUiThread { (windows.firstOrNull { it.id == SHARED_WINDOW }?.content as? SharedContent)?.apply(input) }
        }
    }

    override fun onPause() {
        super.onPause()
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        surfaceView.onPause()
        ar?.pause()
        tracker.stop()
        sensorSixDof?.stop()
        cameraProvider?.unbindAll()
    }

    override fun onDestroy() {
        remote.stop()
        Calls.unlisten(callListener)
        Calls.onShared = null
        Calls.onRemoteInput = null
        Calls.stop()
        Calls.localHandImage = { null }
        steamVrLink.close()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        windows.forEach { it.content.release() }
        panelContent?.release()
        ar?.close()
        cameraExecutor.shutdownNow()
        trackingExecutor.execute { handTracker?.close() }
        trackingExecutor.shutdown()
        super.onDestroy()
    }

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /** PhoneXR's own 6DoF (the neck and the accelerometer): while ARCore is not running. */
    private var sensorSixDof: SensorSixDof? = null
    /** Without ARCore: the eyes' offset from the neck, turned with the head — steady, never drifting. */
    @Volatile private var neckModel = false
    private val neckHead = FloatArray(16)
    private val neckEyes = FloatArray(4)

    private fun neck() {
        tracker.copyHead(neckHead)
        // Eyes 8 cm ahead of and 7.5 cm above the neck's pivot (Cardboard's neck model).
        Matrix.multiplyMV(neckEyes, 0, neckHead, 0, floatArrayOf(0f, NECK_UP, -NECK_FORWARD, 0f), 0)
        synchronized(headPosition) {
            headPosition[0] = neckEyes[0]
            headPosition[1] = neckEyes[1] - NECK_UP
            headPosition[2] = neckEyes[2] + NECK_FORWARD
        }
    }

    /**
     * ARCore may still be checking when the home opens: ask again for a few seconds, then move the
     * camera from CameraX (3DoF) to ARCore (6DoF) on the fly.
     */
    private fun startArLater(attempt: Int = 0) {
        if (ar != null || isFinishing || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (BuildConfig.LITE) return
        when (ArTracker.availability(this)) {
            ArTracker.Availability.CHECKING -> if (attempt < 40) handler.postDelayed({ startArLater(attempt + 1) }, 250)
            ArTracker.Availability.MISSING -> Unit
            ArTracker.Availability.READY -> {
                cameraProvider?.unbindAll()
                val created = ArTracker.create(this)
                if (created == null || !created.resume()) {
                    created?.close()
                    bindCamera()
                    return
                }
                glTasks += { renderer?.attachAr(created) }
                ar = created
                toast("6DoF включён: можно ходить по комнате")
            }
        }
    }

    private val onboardingHost = object : Onboarding.Host {
        override val sixDof get() = ar != null
        override fun tableFound() = RoomScan.table != null
        override fun finish() {
            onboarding = null
            appearStart = SystemClock.elapsedRealtime()
            redraw.set(true)
        }
    }

    /** People: friends, calls, and a room to be in together (the calls window). */
    private fun openCalls() = runOnUiThread { openWindow("calls", tr("Люди"), ID_PEOPLE) { CallContent(this) { watchTogether() } } }

    /** The easter egg: space all around in 360°, with its own exit button in front. */
    private fun startEgg() = runOnUiThread {
        val back = environmentId
        thread(name = "PhoneXR egg") {
            val bitmap = runCatching { Environments.eggPanorama(this) }.getOrNull() ?: return@thread
            glTasks += { renderer?.setEnvironment(bitmap) }
            redraw.set(true)
        }
        openWindow(EGG_WINDOW, tr("Выйти"), ID_SETTINGS) {
            EggExitContent {
                runOnUiThread {
                    windows.firstOrNull { it.id == EGG_WINDOW }?.let { close(it) }
                    setEnvironment(back)
                }
            }
        }
        windows.firstOrNull { it.id == EGG_WINDOW }?.let { it.widthScale = .3f; it.heightScale = .3f }
    }

    /** One room in a call: a browser window both people see and both work. */
    private fun watchTogether(url: String = BrowserContent.HOME, fromPeer: Boolean = false) = runOnUiThread {
        windows.firstOrNull { it.id == SHARED_WINDOW }?.let { close(it) }
        openWindow(SHARED_WINDOW, tr("Вместе"), ID_BROWSER) { SharedContent(BrowserContent(url, ::openWebXr)) }
        Calls.setStatus(tr("Смотрит вместе"))
        if (!fromPeer) Calls.share(url)
    }

    /** An incoming call brings the Calls window up wherever the user is. */
    private val callListener: () -> Unit = {
        synchronized(panel) { panel.alerts = if (panel.dnd) 0 else Calls.online.size }
        redraw.set(true)
        // Do not disturb: a call still rings on the phone, but does not open in VR.
        if (!panel.dnd && Calls.state == Calls.State.RINGING && windows.none { it.id == "calls" && !it.minimized }) openCalls()
    }

    private val storeHost = object : StoreContent.Host {
        override fun openCinema(packageName: String, scene: String) = runOnUiThread {
            if (VirtualScreen.access() != VirtualScreen.Access.READY) return@runOnUiThread toast("Запустите Shizuku и разрешите доступ PhoneXR")
            startActivity(Intent(this@VrHomeActivity, CinemaActivity::class.java)
                .putExtra(CinemaActivity.EXTRA_PACKAGE, packageName).putExtra(CinemaActivity.EXTRA_SCENE, scene))
        }

        override fun openWebApp(app: WebApps.App) = runOnUiThread {
            openWindow("web:${app.url}", app.name, "web:${app.url}") { BrowserContent(app.url, ::openWebXr) }
        }

        override fun openCalls() = this@VrHomeActivity.openCalls()

        override fun install(file: java.io.File) = runOnUiThread {
            if (VirtualScreen.access() != VirtualScreen.Access.READY)
                return@runOnUiThread toast("Для внутренней установки запустите Shizuku и разрешите PhoneXR")
            thread(name = "PhoneXR store prepare") {
                val apk = runCatching {
                    if (file.extension.equals("pxr", true)) PxrPackage.androidApk(this@VrHomeActivity, android.net.Uri.fromFile(file))
                    else ApkPatcher.patch(this@VrHomeActivity, android.net.Uri.fromFile(file)).apk
                }.getOrElse { return@thread toast(it.localizedMessage ?: "Не удалось подготовить игру") }
                runOnUiThread {
                    InternalInstaller.install(this@VrHomeActivity, apk) { problem ->
                        if (problem != null) toast(problem)
                        else {
                            toast("Игра добавлена в PhoneXR")
                            loadApps()
                        }
                    }
                }
            }
        }

        override fun homeChanged() = loadApps()

        override fun message(text: String) = toast(text)
    }

    /** "Straight ahead" and "here" become the current head direction and spot. */
    private fun recenter() {
        tracker.recenter()
        ar?.recenter()
        sensorSixDof?.reset()
    }

    private val settingsHost = object : SettingsContent.Host {
        override fun easterEgg() = startEgg()
        override fun trackingText(): String {
            val tracker = ar
            return when {
                tracker == null && !Settings.load(this@VrHomeActivity).sixDof -> "3DoF · 6DoF выключен в настройках"
                tracker == null -> "3DoF · нет ARCore (Google Play Services for AR)"
                tracker.tracking -> "6DoF · ARCore, комната отслеживается"
                else -> "6DoF · ARCore ищет комнату…"
            }
        }

        override fun avatarWeb(vroid: Boolean) = openAvatarWeb(vroid)


        override fun startRoomScan() = runOnUiThread {
            val tracker = ar ?: return@runOnUiThread toast("Сканирование комнаты работает только в 6DoF")
            tracker.recenter()
            windows.firstOrNull { it.id == "settings" }?.let { minimize(it) }
            toast("Медленно осмотрите пол, стены и столы — найденные поверхности появятся автоматически")
        }

        override fun roomText(): String {
            val tracker = ar ?: return "Комната недоступна"
            return if (!tracker.tracking) "Камера ищет окружение…"
            else "Найдено: пол/столы — ${tracker.horizontalPlanes}, стены — ${tracker.verticalPlanes}"
        }

        override fun openSystemSettings() = runOnUiThread {
            if (VirtualScreen.access() != VirtualScreen.Access.READY) return@runOnUiThread toast("Сначала запустите Shizuku")
            openWindow("android-settings", "Wi‑Fi и Bluetooth", ID_SETTINGS) {
                ShizukuAppContent(this@VrHomeActivity, "com.android.settings") { toast(it) }
            }
        }

        override fun requestShizuku() = runOnUiThread {
            when (VirtualScreen.access()) {
                VirtualScreen.Access.READY -> toast("Shizuku уже подключён")
                VirtualScreen.Access.NEEDS_PERMISSION -> VirtualScreen.requestPermission()
                VirtualScreen.Access.NOT_RUNNING -> toast("Запустите Shizuku на телефоне, затем вернитесь в VR")
            }
        }

        override fun shizukuText(): String = when (VirtualScreen.access()) {
            VirtualScreen.Access.READY -> "Подключён"
            VirtualScreen.Access.NEEDS_PERMISSION -> "Нужно разрешение"
            VirtualScreen.Access.NOT_RUNNING -> "Не запущен"
        }

        override fun setHomeStyle(style: Settings.HomeStyle) = runOnUiThread {
            panel.compact = style == Settings.HomeStyle.COMPACT
            redraw.set(true)
        }
    }

    /**
     * The avatar browser: Avaturn to make one from a selfie, or VRoid Hub to take one. A model
     * downloaded there becomes the user's avatar at once.
     */
    private fun openAvatarWeb(vroid: Boolean) = runOnUiThread {
        val source = if (vroid) AvatarModel.Source.VROID else AvatarModel.Source.AVATURN
        openWindow("avatar-web", source.title, ID_SETTINGS) {
            BrowserContent(if (vroid) AvatarModel.VROID_HUB_URL else AvatarModel.AVATURN_URL, ::openWebXr) { bytes, _ ->
                val error = AvatarModel.save(this, bytes, source)
                toast(error ?: tr("Аватар сохранён"))
            }
        }
    }

    // ------------------------------------------------------------------ Apps and windows

    private fun loadApps() {
        thread(name = "PhoneXR home apps") {
            val found = runCatching { GameLibrary.scan(this) }.getOrDefault(emptyList())
                // Games still to be patched are left out: patching happens in the PhoneXR app, not in VR.
                .filterNot {
                    it.kind == GameLibrary.Kind.VRAPI_UNSUPPORTED ||
                        it.kind == GameLibrary.Kind.VRAPI_ORIGINAL && !VrApiDriver.ready(this)
                }
            games = found.associateBy { it.packageName }
            // Minecraft stays in the PhoneXR app (PXR Bedrock), not on the MR home screen.
            // Material You: PhoneXR's own apps are Material icons on themed tiles; other apps show their
            // themed (monochrome) icon where they have one.
            fun own(id: String, label: String, glyph: androidx.compose.ui.graphics.vector.ImageVector) = HomePanel.Entry(id, label, null, glyph = glyph)
            val own = listOf(
                own(ID_STORE, tr("Магазин"), Icons.Rounded.Storefront),
                own(ID_BROWSER, tr("Браузер"), Icons.Rounded.Explore),
            ) + (if (BuildConfig.BE) emptyList() else listOf(own(ID_PEOPLE, tr("Люди"), Icons.Rounded.People))) + listOf(
                own(ID_INSTAGRAM, "Instagram", Icons.Rounded.PhotoCamera),
                own(ID_DISCORD, "Discord", Icons.Rounded.Forum),
                own(ID_PHOTOS, tr("Фото"), Icons.Rounded.PhotoLibrary),
                own(ID_SETTINGS, tr("Настройки"), Icons.Rounded.Settings),
                own(ID_DESKTOP, tr("Компьютер"), Icons.Rounded.Computer),
            ) +
                if (BuildConfig.LITE || AndroidAppsContent.enabled(this)) listOf(own(ID_ANDROID, "Android", Icons.Rounded.Android)) else emptyList()
            val dark = MaterialYouIcons.dark(this)
            val vr = found.map {
                HomePanel.Entry("app:${it.packageName}", it.label,
                    MaterialYouIcons.themed(this, it.packageName, dark) ?: runCatching { packageManager.getApplicationIcon(it.packageName) }.getOrNull())
            }
            val web = WebApps.installed(this).map { app ->
                HomePanel.Entry("web:${app.url}", app.name, WebApps.icon(app)?.let { BitmapDrawable(resources, it) } ?: letterIcon(app.name))
            }
            synchronized(panel) { panel.setHome(own + vr + web) }
            updateDock()
            storeApps = WebApps.fromStore()
            refreshStore()
        }
    }

    private fun refreshStore() {
        val installed = WebApps.installed(this).map { it.url }.toSet()
        val entries = storeApps.map { app ->
            HomePanel.Entry(
                "store:${app.url}", app.name,
                WebApps.icon(app)?.let { BitmapDrawable(resources, it) } ?: letterIcon(app.name),
                badge = if (app.url in installed) "✓" else "+"
            )
        }
        synchronized(panel) { panel.setStore(entries) }
        redraw.set(true)
    }

    private fun openEntry(entry: HomePanel.Entry) {
        val id = entry.id
        when {
            id == ID_BROWSER -> openWindow("browser", tr("Браузер"), ID_BROWSER) { BrowserContent(BrowserContent.HOME, ::openWebXr) }
            id == ID_PHOTOS -> openWindow("photos", tr("Фото"), ID_PHOTOS) {
                PhotosContent(
                    this,
                    onVideo = { uri, name, width, height ->
                        // A video from the gallery gets its own window, in 3D when it holds two eyes.
                        runOnUiThread {
                            openWindow("video:$uri", name.ifEmpty { tr("Видео") }, ID_PHOTOS) {
                                VideoContent(this, uri, name, width, height) { message -> toast(message) }
                            }
                        }
                    },
                    onPanorama = { uri, name, width, height, video ->
                        runOnUiThread {
                            startActivity(
                                Intent(this, PanoramaActivity::class.java)
                                    .putExtra(PanoramaActivity.EXTRA_URI, uri.toString())
                                    .putExtra(PanoramaActivity.EXTRA_NAME, name)
                                    .putExtra(PanoramaActivity.EXTRA_VIDEO, video)
                                    .putExtra(PanoramaActivity.EXTRA_WIDTH, width)
                                    .putExtra(PanoramaActivity.EXTRA_HEIGHT, height)
                            )
                        }
                    },
                )
            }
            id == ID_SETTINGS -> openWindow("settings", tr("Настройки"), ID_SETTINGS) { SettingsContent(this, settingsHost) }
            id == ID_DESKTOP -> openWindow("desktop", tr("Компьютер"), ID_DESKTOP) { DesktopStreamContent { toast(it) } }
            id == ID_ANDROID -> openWindow("android", tr("Android‑приложения"), ID_ANDROID) {
                AndroidAppsContent(this) { name, label ->
                    runOnUiThread {
                        if (VirtualScreen.access() != VirtualScreen.Access.READY) toast("Запустите Shizuku и разрешите доступ PhoneXR")
                        else openWindow("app:$name", label, ID_ANDROID) { ShizukuAppContent(this, name) { toast(it) } }
                    }
                }
            }
            id == ID_MINECRAFT -> {
                if (runCatching { packageManager.getApplicationInfo(MINECRAFT, 0) }.isFailure) {
                    toast("Установите Minecraft из Google Play")
                } else {
                    openWindow("minecraft", "Minecraft", ID_MINECRAFT) { ShizukuAppContent(this, MINECRAFT) { toast(it) } }
                }
            }
            id == ID_STORE -> openWindow("store", tr("Магазин"), ID_STORE) { StoreContent(this, storeHost) }
            id.startsWith("env:") -> setEnvironment(id.removePrefix("env:"))
            id.startsWith("person:") -> openCalls()
            id == "own:calls" -> openCalls()
            id == ID_INSTAGRAM -> openWindow("web:https://www.instagram.com/", "Instagram", ID_INSTAGRAM) { BrowserContent("https://www.instagram.com/", ::openWebXr).apply { appMode = true } }
            id == ID_DISCORD -> openWindow("web:https://discord.com/app", "Discord", ID_DISCORD) { BrowserContent("https://discord.com/app", ::openWebXr).apply { appMode = true } }
            id == ID_PEOPLE -> openCalls()
            id.startsWith("app:") -> launchGame(id.removePrefix("app:"))
            id.startsWith("web:") -> id.removePrefix("web:").let { url -> openWindow("web:$url", entry.label, id) { BrowserContent(url, ::openWebXr) } }
            id.startsWith("dock:") -> windows.firstOrNull { it.id == id.removePrefix("dock:") }?.let { restore(it) }
            id.startsWith("store:") -> {
                val app = storeApps.firstOrNull { it.url == id.removePrefix("store:") } ?: return
                if (WebApps.installed(this).any { it.url == app.url }) {
                    openWindow("web:${app.url}", app.name, "web:${app.url}") { BrowserContent(app.url, ::openWebXr) }
                } else {
                    WebApps.add(this, app)
                    toast("«${app.name}» добавлено на главный экран")
                    thread { refreshStore(); loadApps() }
                }
            }
            id == MENU_PHOTO -> takePhoto()
            id == MENU_MUTE -> { Calls.muted = !Calls.muted; toast(if (Calls.muted) tr("Микрофон выкл.") else tr("Микрофон")) }
            id == MENU_PASSTHROUGH -> setEnvironment(if (environmentId == Environments.REAL_WORLD) lastWorld else Environments.REAL_WORLD)
            id == MENU_TOGGLE_APPS -> toggleLibrary()
            id == MENU_RECORD -> toggleRecording()
            id == MENU_RECENTER -> { recenter(); switchMode(HomePanel.Mode.HOME) }
            id == MENU_HOME -> switchMode(HomePanel.Mode.HOME)
            id == MENU_EXIT -> {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP))
                finish()
            }
        }
    }

    /** Opens a window, or brings back the one already open with that id. */
    private fun openWindow(id: String, title: String, iconId: String, content: () -> VrWindow.Content) {
        windows.firstOrNull { it.id == id }?.let { return restore(it) }
        val window = VrWindow(id, title, iconId, content())
        val open = windows.count { !it.minimized }
        window.yaw = if (open == 0) 0f else (if (open % 2 == 1) -32f else 32f) * ((open + 1) / 2)
        window.height = .05f
        windows += window
        focused = window
        switchMode(HomePanel.Mode.HOME)
        glTasks += {
            val external = window.content.external
            val ids = IntArray(1)
            GLES20.glGenTextures(1, ids, 0)
            val target = if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D
            GLES20.glBindTexture(target, ids[0])
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            textures[window.id] = ids[0]
            val surface = if (external) SurfaceTexture(ids[0]).also { texture ->
                texture.setOnFrameAvailableListener { framesReady += window.id }
                surfaceTextures[window.id] = texture
            } else null
            window.content.attach(this, surface) {}
        }
    }

    private fun restore(window: VrWindow) {
        window.minimized = false
        focused = window
        updateDock()
    }

    private fun minimize(window: VrWindow) {
        window.minimized = true
        if (focused == window) focused = null
        updateDock()
    }

    private fun close(window: VrWindow) {
        windows.remove(window)
        if (focused == window) focused = null
        window.content.release()
        glTasks += {
            textures.remove(window.id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it), 0) }
            surfaceTextures.remove(window.id)?.release()
        }
        updateDock()
    }

    /** Open windows go first in the recent part of the dock, with the icon of the app they came from. */
    private fun updateDock() {
        val home = synchronized(panel) { panel.homeEntries() }.associateBy { it.id }
        val dock = windows.map { window ->
            val from = home[window.iconId]
            HomePanel.Entry("dock:${window.id}", window.title, from?.icon ?: if (from?.glyph == null) letterIcon(window.title) else null, glyph = from?.glyph, tint = window.iconId)
        }
        synchronized(panel) { panel.setDock(dock) }
        redraw.set(true)
    }

    /** A page asked for an immersive WebXR session: the WebView has none, so the PhoneXR browser (OpenXR) takes over. */
    private fun openWebXr(url: String) = runOnUiThread {
        if (WebApps.browserPackage(this) == null) return@runOnUiThread toast("Для WebXR установите «Браузер PhoneXR» с сайта или из магазина")
        cameraProvider?.unbindAll()
        ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
        if (!WebApps.open(this, url)) toast("Не удалось открыть WebXR")
    }

    /** Real photo: the current passthrough frame goes to the gallery (Pictures/PhoneXR). */
    private fun takePhoto() {
        val bitmap = synchronized(frameLock) {
            (arPhoto?.takeIf { !it.isRecycled } ?: frame?.takeIf { !it.isRecycled })?.let { runCatching { it.copy(Bitmap.Config.ARGB_8888, false) }.getOrNull() }
        }
            ?: return toast("Камера ещё не готова")
        switchMode(HomePanel.Mode.HOME)
        thread(name = "PhoneXR photo") {
            val saved = runCatching { Daydream.savePhoto(this, bitmap) }.isSuccess
            bitmap.recycle()
            toast(if (saved) "Фото сохранено в «Фото»" else "Не удалось сохранить фото")
            windows.forEach { (it.content as? PhotosContent)?.reload() }
        }
    }

    /** Small camera snapshot used to texture real-hand cut-outs in full-version calls. */
    private fun handFrameForCall(): Bitmap? = synchronized(frameLock) {
        val source = arPhoto?.takeIf { !it.isRecycled } ?: frame?.takeIf { !it.isRecycled } ?: return null
        runCatching {
            val width = 320
            Bitmap.createBitmap(width, width * source.height / source.width, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(source, null, android.graphics.Rect(0, 0, it.width, it.height), Paint(Paint.FILTER_BITMAP_FLAG))
            }
        }.getOrNull()
    }

    private fun launchGame(packageName: String) {
        val game = games[packageName] ?: return
        val intent = GameLibrary.launchIntent(this, game) ?: return toast("У «${game.label}» нет экрана запуска")
        if (game.kind == GameLibrary.Kind.DAYDREAM && !Daydream.servicesInstalled(this)) {
            toast("Для Daydream нужны VR Services — ставлю Opendream Services")
            runOnUiThread { Daydream.installServices(this) }
            return
        }
        recent.remove(packageName)
        recent.add(0, packageName)
        cameraProvider?.unbindAll()
        // A game that tracks hands itself gets the camera; PhoneXR does not start its own tracking.
        if (GameLibrary.ownsHandTracking(this, packageName)) stopService(Intent(this, HandTrackingService::class.java))
        else ContextCompat.startForegroundService(this, Intent(this, HandTrackingService::class.java))
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** A palm turned to the face: its button, head space (tangent units). Right: PhoneXR, left: the menu. */
    private class PalmButton(val left: Boolean, val x: Float, val y: Float, val radius: Float)
    @Volatile private var palmButtons: List<PalmButton> = emptyList()
    private val palmPinched = HashMap<Boolean, Boolean>()

    /**
     * As on Quest: look at your right palm and pinch — the quick menu; the left palm's pinch shows or
     * hides the main menu. Returns the hands busy with their palm button.
     */
    private fun palmButtons(seen: List<SeenHand>): Set<Boolean> {
        val buttons = ArrayList<PalmButton>()
        val busy = HashSet<Boolean>()
        for (hand in seen) {
            val shape = hand.shape
            if (!palmFacesEyes(hand) || shape.fist || hand.left == grabLeft) {
                palmPinched[hand.left] = false
                continue
            }
            val size = shape.palmWidth * 2f * viewScaleX
            val x = (shape.pinchX - .5f) * 2f * viewScaleX
            val y = (.5f - shape.pinchY) * 2f * viewScaleY + size * .18f
            buttons += PalmButton(hand.left, x, y, size * .2f)
            busy += hand.left
            val pinched = shape.pinchGap < .3f
            val before = palmPinched[hand.left] == true
            palmPinched[hand.left] = pinched
            if (pinched && !before) runOnUiThread {
                if (hand.left) toggleLibrary() else if (handMenu == null) openHandMenu(false) else closeHandMenu()
            }
        }
        seen.map { it.left }.toSet().let { present -> palmPinched.keys.retainAll(present) }
        palmButtons = buttons
        return busy
    }

    /**
     * The palm really turned to the eyes, fingers up (as when looking at the palm on Quest) — from
     * the hand's 3D points, so a hand pointing forward never counts and keeps clicking.
     */
    private fun palmFacesEyes(hand: SeenHand): Boolean {
        val p = hand.head ?: return false
        fun v(i: Int) = floatArrayOf(p[i * 3], p[i * 3 + 1], p[i * 3 + 2])
        val wrist = v(0); val index = v(5); val pinky = v(17); val middle = v(9)
        val a = floatArrayOf(index[0] - wrist[0], index[1] - wrist[1], index[2] - wrist[2])
        val b = floatArrayOf(pinky[0] - wrist[0], pinky[1] - wrist[1], pinky[2] - wrist[2])
        val sign = if (hand.left) -1f else 1f
        val n = floatArrayOf(sign * (a[1] * b[2] - a[2] * b[1]), sign * (a[2] * b[0] - a[0] * b[2]), sign * (a[0] * b[1] - a[1] * b[0]))
        val nl = kotlin.math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]).coerceAtLeast(1e-6f)
        // Towards the eyes: from the palm to the head's origin.
        val c = floatArrayOf((wrist[0] + middle[0]) / 2, (wrist[1] + middle[1]) / 2, (wrist[2] + middle[2]) / 2)
        val cl = kotlin.math.sqrt(c[0] * c[0] + c[1] * c[1] + c[2] * c[2]).coerceAtLeast(1e-6f)
        val facing = -(n[0] * c[0] + n[1] * c[1] + n[2] * c[2]) / (nl * cl)
        val up = floatArrayOf(middle[0] - wrist[0], middle[1] - wrist[1], middle[2] - wrist[2])
        val ul = kotlin.math.sqrt(up[0] * up[0] + up[1] * up[1] + up[2] * up[2]).coerceAtLeast(1e-6f)
        return facing > .6f && up[1] / ul > .55f
    }

    /** BE, without a camera: the middle of the view aims — a small dot on what it meets. */
    private fun gaze() {
        val head = FloatArray(16).also { tracker.copyHead(it) }
        val direction = FloatArray(4).also { Matrix.multiplyMV(it, 0, head, 0, floatArrayOf(0f, 0f, -1f, 0f), 0) }
        ray = direction
        pinchPoint = null
        val target = hitTest(direction)
        updateHit(target)
        val far = when (target) { null -> 1.2f; is Hit.Panel, is Hit.Setup -> panelRadius; else -> windowRadius }
        beam = floatArrayOf(0f, 0f, 0f, 0f, 0f, -far, 1f)
        otherBeam = null
        if (pressing) dragOrMove(direction)
    }

    /** The library of apps: opens (with the dock) or closes again. */
    private fun toggleLibrary() = runOnUiThread {
        panelVisible = true
        switchMode(if (synchronized(panel) { panel.mode } == HomePanel.Mode.LIBRARY) HomePanel.Mode.HOME else HomePanel.Mode.LIBRARY)
        redraw.set(true)
    }

    /** Records what the user sees in VR (one eye, flat) to the gallery, or stops it. */
    private fun toggleRecording() {
        val view = renderer ?: return
        if (view.recording) {
            view.stopRecording = { saved -> toast(if (saved) tr("Видео сохранено в «Фото»") else tr("Не удалось сохранить видео")) }
            return
        }
        val recorder = runCatching { VideoRecorder(this, 1280, 1152) }.getOrElse { return toast(tr("Не удалось начать запись")) }
        view.pendingRecorder = recorder
        toast(tr("Запись началась"))
    }

    /** Compact palm menu: only the two actions that must remain available everywhere. */
    @Volatile private var handMenu: HandMenu? = null

    /** Palm held toward the face: the compact menu above that hand. */
    private fun openHandMenu(holderLeft: Boolean) {
        // Material You: the wallpaper's tonal colours, as on the rest of the home.
        val palette = MaterialYouIcons.palette(this, true)
        val ink = palette.glyph
        handMenu = HandMenu(holderLeft, palette.tile, palette.glyph, listOf(
            HandMenu.Item(MENU_MUTE, if (Calls.muted) tr("Микрофон выкл.") else tr("Микрофон"), vectorIcon(if (Calls.muted) Icons.Rounded.MicOff else Icons.Rounded.Mic, ink)),
            HandMenu.Item(MENU_RECENTER, tr("Выровнять"), vectorIcon(Icons.Rounded.CenterFocusStrong, ink)),
            HandMenu.Item(MENU_RECORD, if (renderer?.recording == true) tr("Остановить запись") else tr("Снять видео"),
                vectorIcon(if (renderer?.recording == true) Icons.Rounded.Stop else Icons.Rounded.Videocam, ink)),
            HandMenu.Item(MENU_PASSTHROUGH, tr("Видеть комнату"), vectorIcon(Icons.Rounded.Visibility, ink)),
            HandMenu.Item(MENU_TOGGLE_APPS, tr("Меню"), vectorIcon(Icons.Rounded.Apps, ink)),
        ))
        pinch.reset()
        filterX.reset(); filterY.reset()
    }

    private fun closeHandMenu() {
        handMenu = null
    }

    private fun openMenu() {
        val home = synchronized(panel) { panel.homeEntries() }.associateBy { it.id }
        val entries = windows.map { window ->
            val from = home[window.iconId]
            HomePanel.Entry("dock:${window.id}", window.title, from?.icon ?: if (from?.glyph == null) letterIcon(window.title) else null, glyph = from?.glyph, tint = window.iconId)
        } + recent.take(3).mapNotNull { name -> home["app:$name"] } + listOf(
            HomePanel.Entry(MENU_HOME, tr("Главная"), null, glyph = Icons.Rounded.Home),
            HomePanel.Entry(MENU_PHOTO, tr("Снять фото"), null, glyph = Icons.Rounded.PhotoCamera),
            HomePanel.Entry(MENU_RECENTER, tr("Выровнять"), null, glyph = Icons.Rounded.CenterFocusStrong),
            HomePanel.Entry(MENU_EXIT, tr("Выйти из VR"), null, glyph = Icons.Rounded.Close),
        )
        synchronized(panel) { panel.setMenu(entries) }
        switchMode(HomePanel.Mode.MENU)
    }

    private fun switchMode(mode: HomePanel.Mode) {
        synchronized(panel) {
            panel.mode = mode
            panel.closeSearch()
            panel.showPage(0)
        }
        redraw.set(true)
    }

    // ------------------------------------------------------------------ Camera and hands

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            cameraProvider = provider
            val analysis = ImageAnalysis.Builder()
                .setTargetResolution(android.util.Size(if (BuildConfig.LITE) 960 else 1280, if (BuildConfig.LITE) 540 else 720))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(cameraExecutor) { image ->
                try {
                    val upright = image.toBitmap().rotate(image.imageInfo.rotationDegrees)
                    synchronized(frameLock) {
                        val old = frame
                        frame = upright
                        if (frameFresh.getAndSet(true) && old != null && old !== upright) old.recycle()
                    }
                    val timestamp = image.imageInfo.timestamp / 1_000_000L
                    if (busy.compareAndSet(false, true)) {
                        val small = Bitmap.createScaledBitmap(upright, 640, 640 * upright.height / upright.width, true)
                        trackingExecutor.execute {
                            try { handTracker?.detect(small, timestamp) } finally { small.recycle(); busy.set(false) }
                        }
                    }
                } catch (error: Throwable) {
                    Log.w(TAG, "Camera frame failed", error)
                } finally {
                    image.close()
                }
            }
            provider.unbindAll()
            runCatching { provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis) }
                .onFailure { toast("Камера занята другим приложением") }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onHands(result: HandLandmarkerResult) {
        val now = SystemClock.elapsedRealtimeNanos()
        // The camera frame this result belongs to: the head is looked up for that moment.
        val frameNs = result.timestampMs() * 1_000_000L
        // ARCore frames: landmarks are on the upright camera image; place them on the eye's view.
        val map = arFrameMap
        val raw = result.landmarks().map { points ->
            if (map == null) points
            else points.map { NormalizedLandmark.create(map[0] + it.x() * map[2], map[1] + it.y() * map[3], it.z()) }
        }
        // Every point of each hand through a One Euro filter: steady while the hand holds still,
        // no lag when it moves — the silhouette, the ray and the pinch all stop trembling.
        val landmarks = raw.mapIndexed { index, points ->
            if (points.size < 21) return@mapIndexed points
            val label = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName() ?: "hand$index"
            handSmoothers.getOrPut(label) { LandmarkSmoother() }.smooth(points, frameNs)
        }
        // A hand that is gone starts afresh next time instead of sliding in from where it was.
        val present = raw.indices.mapNotNull { result.handednesses().getOrNull(it)?.firstOrNull()?.categoryName() }.toSet()
        handSmoothers.keys.retainAll(present)
        handPoints = landmarks.filter { it.size >= 21 }.map { points ->
            FloatArray(63) { i -> when (i % 3) { 0 -> points[i / 3].x(); 1 -> points[i / 3].y(); else -> points[i / 3].z() } }
        }
        val world = result.worldLandmarks()
        val seen = landmarks.mapIndexedNotNull { index, points ->
            if (points.size < 21) return@mapIndexedNotNull null
            val physicalLeft = result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().equals("Right", true)
            val depth = world.getOrNull(index)?.let { HandGestures.tipDepth(points, it, viewScaleX, viewScaleY) } ?: 0f
            val head = world.getOrNull(index)?.let { HandGestures.headPoints(points, it, viewScaleX, viewScaleY) }
            SeenHand(physicalLeft, HandGestures.shape(points, physicalLeft), depth, head)
        }
        val hands = seen.map { it.left to it.shape }
        val steamHead = FloatArray(16).also { tracker.copyHead(it) }
        val steamPosition = synchronized(headPosition) { headPosition.copyOf() }
        steamVrLink.send(steamHead, steamPosition, hands.map { SteamVrLink.Hand(it.first, it.second) })
        onboarding?.onHands(hands, handPoints)


        // An immersive WebXR page fills the view: a push of the finger is its "select", and the
        // palm held toward the face leaves it.
        windows.firstOrNull { it.content.immersive }?.let { xrWindow ->
            immersiveInput(xrWindow, seen, now)
            return
        }

        // The hand menu rides on the hand that called it; the other hand touches it.
        val menu = handMenu
        if (menu != null) {
            val holder = landmarks.indices.firstOrNull { index ->
                landmarks[index].size >= 21 &&
                    result.handednesses().getOrNull(index)?.firstOrNull()?.categoryName().equals("Right", true) == menu.holderLeft
            }?.let { landmarks[it] }
            if (holder != null) {
                fun tx(i: Int) = (holder[i].x() - .5f) * 2f * viewScaleX
                fun ty(i: Int) = (.5f - holder[i].y()) * 2f * viewScaleY
                val palm = intArrayOf(0, 5, 9, 17)
                // Placed once where the hand opened it (as on Quest).
                if (menu.x.isNaN()) menu.follow(palm.map { tx(it) }.average().toFloat(), palm.map { ty(it) }.average().toFloat(),
                    kotlin.math.hypot(tx(9) - tx(0), ty(9) - ty(0)))
            }
            // Then it stays in the room: fixed as a world direction, redrawn where it now is in view.
            if (!menu.x.isNaN()) {
                val headNow = FloatArray(16).also { tracker.copyHeadAt(frameNs, it) }
                val world = menu.world ?: FloatArray(4).also {
                    Matrix.multiplyMV(it, 0, headNow, 0, floatArrayOf(menu.x, menu.y, -1f, 0f), 0)
                    menu.world = it
                }
                val toHead = FloatArray(16).also { Matrix.transposeM(it, 0, headNow, 0) }
                val local = FloatArray(4).also { Matrix.multiplyMV(it, 0, toHead, 0, world, 0) }
                if (local[2] < -.05f) { menu.x = local[0] / -local[2]; menu.y = local[1] / -local[2] }
            }
            // It stays open where it is until an item is picked or the palm button is pinched again.
        }

        // A fist at the left or right edge of a window takes it; opening the hand lets go.
        grabWindows(seen, frameNs)

        // NEAR: a fingertip close to a window touches it directly, and the pointer is off.
        // FAR (below): the pointer, exactly as before.
        if (menu == null && !mouseActive() && directTouch(seen, frameNs)) return

        // The pointing hand: the one already pinching keeps the ray, else a pinching one, else the nearest.
        val pointing = seen.filter { it.left != grabLeft }
        val chosen = pointing.firstOrNull { it.left == activeLeft && pinch.pinching }
            ?: pointing.firstOrNull { it.shape.pinchGap < .3f }
            ?: pointing.maxByOrNull { it.shape.palmWidth }
        if (chosen != null && chosen.left != activeLeft) {
            if (activeLeft != null) { filterX.reset(); filterY.reset() }
            if (pinch.pinching) { pinch.reset(); release() }
            activeLeft = chosen.left
        }
        val hand = chosen?.shape
        // A mouse in use owns the pointer; the hands are still drawn.
        if (mouseActive()) { beam = null; otherBeam = null; return }
        if (hand == null) {
            activeLeft = null
            filterX.reset(); filterY.reset(); pinch.reset()
            ray = null
            pinchPoint = null
            beam = null; otherBeam = null
            // A Joy-Con button held down keeps its press even when the hand leaves the camera.
            if (menu == null && !mouseDown) release()
            updateHit(null)
            return
        }
        // The ray, as on Quest: it leaves the hand (between the thumb and index knuckles, which
        // stay put while the fingers pinch) and goes on away from the shoulder, so the ring sits a
        // little ahead of the hand. Worked out on the view itself: the hand's depth from the camera
        // is too noisy to steer by.
        val baseX = (hand.aimX - .5f) * 2f * viewScaleX
        val baseY = (.5f - hand.aimY) * 2f * viewScaleY
        val rightSide = chosen.head?.let { it[0] > 0f } ?: (baseX > 0f)
        val shoulderX = if (rightSide) SHOULDER_X else -SHOULDER_X
        val rawX = baseX + (baseX - shoulderX) * RAY_LEAD
        val rawY = baseY + (baseY - SHOULDER_Y) * RAY_LEAD
        val tx = filterX.filter(rawX, now)
        val ty = filterY.filter(rawY, now)
        val direction = tangentRay(tx, ty, frameNs)
        ray = direction
        pinchPoint = null
        val wasPinching = pinch.pinching
        val pinching = pinch.update(hand)
        if (menu != null) {
            menu.hover(menu.itemAt(tx, ty))
            updateHit(null)
            beam = null; otherBeam = null
            if (pinching && !wasPinching) {
                val item = menu.item(menu.itemAt(tx, ty))
                closeHandMenu()
                if (item != null) runOnUiThread { openEntry(HomePanel.Entry(item.id, item.label, item.icon)) }
            }
            return
        }
        val target = hitTest(direction)
        updateHit(target)
        // The beam ends where it meets what it points at (about the distance of the home or a window).
        beam = beamFor(baseX, baseY, tx, ty, target)
        // Two hands, as on Quest: the other hand has its own cursor too; a pinch makes it the one that clicks.
        otherBeam = pointing.firstOrNull { it.left != chosen.left }?.let { other ->
            if (other.left != otherLeft) { otherFilterX.reset(); otherFilterY.reset(); otherLeft = other.left }
            val ox = (other.shape.aimX - .5f) * 2f * viewScaleX
            val oy = (.5f - other.shape.aimY) * 2f * viewScaleY
            val oShoulder = if (other.head?.let { it[0] > 0f } ?: (ox > 0f)) SHOULDER_X else -SHOULDER_X
            val otx = otherFilterX.filter(ox + (ox - oShoulder) * RAY_LEAD, now)
            val oty = otherFilterY.filter(oy + (oy - SHOULDER_Y) * RAY_LEAD, now)
            beamFor(ox, oy, otx, oty, hitTest(tangentRay(otx, oty, frameNs)))
        }
        when {
            pinching && !wasPinching -> press(target, direction)
            pinching -> dragOrMove(direction)
            wasPinching -> release()
        }
    }

    // ------------------------------------------------------------------ direct touch (NEAR)

    /** The window the index fingertip is near, and whose hand it is (null: FAR, the pointer). */
    private var nearWindow: VrWindow? = null
    private var nearLeft = false
    /** The finger is on the window's surface: a touch is down. */
    private var touching = false
    /** Smoothed distance of the fingertip in front of the window's plane (metres), and where on it. */
    private var nearD = 0f
    private var nearU = 0f
    private var nearV = 0f

    /**
     * Direct touch, like a tablet: the index fingertip (in 3D, from the hand's metric landmarks)
     * against the plane of each flat window. Within [NEAR_DISTANCE] of a window, inside its edges,
     * the pointer goes away; the finger reaching the surface is ACTION_DOWN, moving on it
     * ACTION_MOVE, pulling back ACTION_UP — one contact, one press. The distance is smoothed, and
     * the way in and the way out have different thresholds, so tracking jitter does not click.
     * True while NEAR (the pointer must stay off).
     */
    private fun directTouch(seen: List<SeenHand>, frameNs: Long): Boolean {
        val head = FloatArray(16).also { tracker.copyHeadAt(frameNs, it) }
        val position = synchronized(headPosition) { headPosition.copyOf() }
        class Near(val left: Boolean, val window: VrWindow, val d: Float, val u: Float, val v: Float, val tip: FloatArray)
        var best: Near? = null
        val rotate = FloatArray(16)
        val local = FloatArray(4)
        for (hand in seen) {
            if (hand.left == grabLeft) continue
            val p = hand.head ?: continue
            // Only a finger within real arm's reach touches: a far hand's distance is a guess that
            // tops out near the windows' own distance and would take the pointer away.
            if (kotlin.math.sqrt(p[24] * p[24] + p[25] * p[25] + p[26] * p[26]) > ARM_REACH) continue
            // Landmark 8: the tip of the index finger, head space → world.
            val w = FloatArray(4)
            Matrix.multiplyMV(w, 0, head, 0, floatArrayOf(p[24], p[25], p[26], 0f), 0)
            val tip = floatArrayOf(w[0] + position[0], w[1] + position[1], w[2] + position[2], 1f)
            for (window in windows) {
                if (window.minimized || window.arcDegrees > 0f || window.content.immersive) continue
                val current = window == nearWindow && hand.left == nearLeft
                Matrix.setRotateM(rotate, 0, -window.yaw, 0f, 1f, 0f)
                Matrix.multiplyMV(local, 0, rotate, 0, tip, 0)
                // The window's plane is z = -radius in its own frame, facing the room's middle.
                val d = local[2] + windowRadius
                val x = local[0]
                val y = local[1] - window.height
                val margin = if (current) NEAR_MARGIN_OUT else NEAR_MARGIN_IN
                if (d > (if (current) NEAR_EXIT else NEAR_DISTANCE) || d < -NEAR_BEHIND) continue
                if (abs(x) > window.width / 2 + margin || abs(y) > window.heightM / 2 + margin) continue
                val u = (x + window.width / 2) / window.width
                val v = (window.heightM / 2 - y) / window.heightM
                val candidate = Near(hand.left, window, d, u, v, tip)
                val held = best?.let { it.window == nearWindow && it.left == nearLeft } == true
                if (best == null || current || !held && abs(d) < abs(best.d)) best = candidate
            }
        }
        val near = best
        if (near == null) {
            endTouch()
            return false
        }
        if (near.window != nearWindow || near.left != nearLeft) {
            // Entering NEAR: the pointer's press (if any) ends, then the finger takes over.
            endTouch()
            if (pinch.pinching) pinch.reset()
            release()
            nearWindow = near.window
            nearLeft = near.left
            nearD = near.d; nearU = near.u; nearV = near.v
        } else {
            nearD += (near.d - nearD) * TOUCH_SMOOTHING
            nearU += (near.u - nearU) * TOUCH_SMOOTHING
            nearV += (near.v - nearV) * TOUCH_SMOOTHING
        }
        val window = near.window
        val inside = nearU in 0f..1f && nearV in 0f..1f
        val u = nearU.coerceIn(0f, 1f); val v = nearV.coerceIn(0f, 1f)
        if (!touching && inside && nearD <= TOUCH_IN && previousNearD > TOUCH_IN) {
            touching = true
            focused = window
            window.content.touch(MotionEvent.ACTION_DOWN, u, v)
        } else if (touching && (nearD >= TOUCH_OUT || !inside)) {
            window.content.touch(MotionEvent.ACTION_UP, u, v)
            touching = false
        } else if (touching) {
            window.content.touch(MotionEvent.ACTION_MOVE, u, v)
        }
        previousNearD = nearD
        // No pointer: only a ring on the window where the finger is over it, smaller while touching.
        ray = null
        pinchPoint = null
        updateHit(null)
        Matrix.setRotateM(rotate, 0, window.yaw, 0f, 1f, 0f)
        val normal = FloatArray(4)
        Matrix.multiplyMV(normal, 0, rotate, 0, floatArrayOf(0f, 0f, 1f, 0f), 0)
        val onPlane = floatArrayOf(
            near.tip[0] - normal[0] * near.d - position[0],
            near.tip[1] - normal[1] * near.d - position[1],
            near.tip[2] - normal[2] * near.d - position[2], 0f,
        )
        val toHead = FloatArray(16)
        Matrix.transposeM(toHead, 0, head, 0)
        val h = FloatArray(4)
        Matrix.multiplyMV(h, 0, toHead, 0, onPlane, 0)
        // Start at 0: drawBeam draws no drop and no ray then, only the ring.
        beam = if (h[2] < -.05f) floatArrayOf(0f, 0f, 0f, h[0], h[1], h[2], 1f) else null
        otherBeam = null
        return true
    }

    private var previousNearD = 1f

    /** Leaves NEAR: a touch still down is lifted (ACTION_UP), and the pointer may come back. */
    private fun endTouch() {
        val window = nearWindow
        if (touching && window != null) window.content.touch(MotionEvent.ACTION_UP, nearU.coerceIn(0f, 1f), nearV.coerceIn(0f, 1f))
        touching = false
        nearWindow = null
        previousNearD = 1f
    }

    /**
     * The hand's ray for drawing, in head space: where it leaves the hand (x, y, z), where it ends
     * (x, y, z), and 1 when it ends on something. Null without a pointing hand.
     */
    @Volatile private var beam: FloatArray? = null
    /** The other hand's cursor (it points, but only the clicking hand presses). */
    @Volatile private var otherBeam: FloatArray? = null
    private var otherLeft: Boolean? = null
    private val otherFilterX = HandGestures.OneEuro(minCutoff = .9f, beta = 3f, deadZone = .0015f)
    private val otherFilterY = HandGestures.OneEuro(minCutoff = .9f, beta = 3f, deadZone = .0015f)

    /** A beam from the hand at ([hx], [hy]) along the tangent ([tx], [ty]) to what it meets. */
    private fun beamFor(hx: Float, hy: Float, tx: Float, ty: Float, target: Hit?): FloatArray {
        val far = when (target) { null -> 1.2f; is Hit.Panel, is Hit.Setup -> panelRadius; else -> windowRadius }
        val length = kotlin.math.sqrt(tx * tx + ty * ty + 1f)
        return floatArrayOf(hx, hy, -1f, tx / length * far, ty / length * far, -1f / length * far, if (target != null) 1f else 0f)
    }

    /**
     * How far the home, the windows and the keyboard stand. In 6DoF the eye's view is the camera's,
     * narrower than the 90° of 3DoF, so the same distance looks much nearer there: 6DoF puts things
     * a little further away and 3DoF a little nearer, and both look the same, in between.
     */
    private val distanceScale get() = if (ar != null) SIX_DOF_DISTANCE else THREE_DOF_DISTANCE
    private val windowRadius get() = VrWindow.RADIUS * distanceScale
    private val panelRadius get() = PANEL_RADIUS * distanceScale
    private val keyboardRadius get() = KEYBOARD_RADIUS * distanceScale

    /** A direction in head space as tangents (x, y at one metre ahead), turned into the room. */
    private fun tangentRay(x: Float, y: Float, frameNs: Long): FloatArray {
        val head = FloatArray(16)
        tracker.copyHeadAt(frameNs, head)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, head, 0, floatArrayOf(x, y, -1f, 0f), 0)
        return world
    }

    private val handSmoothers = HashMap<String, LandmarkSmoother>()

    /** One Euro filters for the 21 points of one hand (x and y on the view, z relative depth). */
    private class LandmarkSmoother {
        private val filters = Array(21 * 3) { i ->
            if (i % 3 == 2) HandGestures.OneEuro(minCutoff = .8f, beta = 1.2f)
            else HandGestures.OneEuro(minCutoff = 1.4f, beta = 12f, deadZone = .0008f)
        }

        fun smooth(points: List<NormalizedLandmark>, timeNs: Long): List<NormalizedLandmark> = points.mapIndexed { i, p ->
            if (i >= 21) p
            else NormalizedLandmark.create(
                filters[i * 3].filter(p.x(), timeNs),
                filters[i * 3 + 1].filter(p.y(), timeNs),
                filters[i * 3 + 2].filter(p.z(), timeNs),
            )
        }
    }

    private class SeenHand(val left: Boolean, val shape: HandGestures.Shape, val depth: Float, val head: FloatArray? = null)

    private fun immersiveInput(window: VrWindow, seen: List<SeenHand>, now: Long) {
        updateHit(null)
        ray = null
        // The hands themselves, in 3D, for pages that play with them.
        val json = org.json.JSONArray()
        seen.forEach { hand ->
            val points = hand.head ?: return@forEach
            val p = org.json.JSONArray()
            points.forEach { p.put(Math.round(it * 10000) / 10000.0) }
            json.put(org.json.JSONObject().put("left", hand.left).put("p", p)
                .put("pinch", hand.shape.pinchGap < .3f).put("grab", hand.shape.fist))
        }
        window.content.hands(org.json.JSONObject().put("hands", json).toString())
        // A pinch is WebXR "select".
        val hand = seen.firstOrNull { it.shape.pinchGap < .3f }?.shape ?: seen.maxByOrNull { it.shape.palmWidth }?.shape
        val was = pinch.pinching
        val pinching = hand != null && pinch.update(hand)
        if (hand == null) pinch.reset()
        // Palm toward the face and a pinch leaves the immersive page.
        if (pinching && !was && hand?.palmToFace == true) { window.content.exitImmersive(); return }
        if (pinching && !was) window.content.touch(MotionEvent.ACTION_DOWN, .5f, .5f)
        if (!pinching && was) window.content.touch(MotionEvent.ACTION_UP, .5f, .5f)
    }

    /** Window held by a fist, and which hand holds it. */
    private var grab: Drag? = null
    private var grabLeft: Boolean? = null
    private val grabX = HandGestures.OneEuro(minCutoff = .6f, beta = 1f)
    private val grabY = HandGestures.OneEuro(minCutoff = .6f, beta = 1f)

    private fun grabWindows(seen: List<SeenHand>, frameNs: Long) {
        val now = SystemClock.elapsedRealtimeNanos()
        val holding = grab
        if (holding != null) {
            val hand = seen.firstOrNull { it.left == grabLeft }
            if (hand == null || !hand.shape.fist) {
                grab = null; grabLeft = null
                redraw.set(true)
                return
            }
            val direction = pointerRay(grabX.filter(hand.shape.palmX, now), grabY.filter(hand.shape.palmY, now), frameNs)
            holding.window.yaw = holding.startYaw + (yawOf(direction) - holding.pointerYaw)
            holding.window.height = holding.startHeight + (heightAt(direction, windowRadius) - holding.pointerHeight)
            return
        }
        // An open hand near a window's edge lights its frame: a fist there takes it.
        grabHover = seen.firstOrNull { !it.shape.fist }?.let { hand ->
            val direction = pointerRay(hand.shape.palmX, hand.shape.palmY, frameNs)
            sideHit(direction)?.takeIf { window ->
                val local = toLocal(direction, window.yaw, windowRadius) ?: return@takeIf false
                val x = abs(local[0]); val y = local[1] - window.height
                x > window.width / 2 - SIDE_INSIDE || y > window.heightM / 2 - SIDE_INSIDE || y < -window.heightM / 2 + SIDE_INSIDE
            }
        }
        // A fist that just closed on a window takes that window.
        for (hand in seen) {
            val closed = hand.shape.fist
            val before = fistBefore[hand.left] == true
            fistBefore[hand.left] = closed
            if (!closed || before) continue
            grabX.reset(); grabY.reset()
            val direction = pointerRay(grabX.filter(hand.shape.palmX, now), grabY.filter(hand.shape.palmY, now), frameNs)
            val side = sideHit(direction)
            if (side == null) continue
            if (pinch.pinching && activeLeft == hand.left) { pinch.reset(); release() }
            focused = side
            grab = Drag(side, side.yaw, side.height, yawOf(direction), heightAt(direction, windowRadius))
            grabLeft = hand.left
            redraw.set(true)
            return
        }
    }

    private val fistBefore = HashMap<Boolean, Boolean>()

    /** The window under an open hand's palm near its edge: its white frame shows it can be taken. */
    @Volatile private var grabHover: VrWindow? = null

    /** The window under the palm (with a margin around it), for a fist to take. */
    private fun sideHit(direction: FloatArray): VrWindow? {
        val ordered = windows.filter { !it.minimized && !it.content.immersive }.sortedByDescending { it == focused }
        for (window in ordered) {
            val local = toLocal(direction, window.yaw, windowRadius) ?: continue
            val x = abs(local[0])
            val y = local[1] - window.height
            val w = window.width / 2
            // The whole window and a margin around it: a fist anywhere on it takes it.
            if (x < w + SIDE_OUTSIDE && y < window.heightM / 2 + barHeight(window) + .08f && y > frameBottom(window) - .06f) return window
        }
        return null
    }

    /** A point of the camera picture (the fingertip) seen from the eyes, as a world direction. */
    private fun pointerRay(x: Float, y: Float, frameNs: Long): FloatArray {
        val head = FloatArray(16)
        tracker.copyHeadAt(frameNs, head)
        val local = floatArrayOf((x - .5f) * 2f * viewScaleX, (.5f - y) * 2f * viewScaleY, -1f, 0f)
        val world = FloatArray(4)
        Matrix.multiplyMV(world, 0, head, 0, local, 0)
        return world
    }

    private fun yawOf(direction: FloatArray) = Math.toDegrees(atan2(-direction[0], -direction[2]).toDouble()).toFloat()
    private fun heightAt(direction: FloatArray, radius: Float) = radius * direction[1] / hypot(direction[0], direction[2])

    private fun hitTest(direction: FloatArray?): Hit? {
        direction ?: return null
        if (onboarding != null) {
            val local = toLocal(direction, panelYaw, panelRadius) ?: return null
            val height = PANEL_WIDTH * Onboarding.HEIGHT / Onboarding.WIDTH
            val u = (local[0] + PANEL_WIDTH / 2) / PANEL_WIDTH
            val v = (height / 2 - local[1]) / height
            return if (u in 0f..1f && v in 0f..1f) Hit.Setup(u, v) else null
        }
        // On the table: where the ray meets the table top, in the keyboard's own frame.
        keyboardWindow()?.let { window ->
            val place = tableKeyboard(window) ?: return@let
            val head = synchronized(headPosition) { headPosition.copyOf() }
            if (direction[1] >= -1e-3f) return@let
            val t = (place[1] - head[1]) / direction[1]
            if (t <= 0f) return@let
            val dx = head[0] + direction[0] * t - place[0]
            val dz = head[2] + direction[2] * t - place[2]
            val yaw = Math.toRadians(place[3].toDouble()).toFloat()
            val c = kotlin.math.cos(yaw); val sn = kotlin.math.sin(yaw)
            val lx = dx * c - dz * sn
            val ly = -(dx * sn + dz * c)
            val u = (lx + TABLE_KEYBOARD_W / 2) / TABLE_KEYBOARD_W
            val v = (TABLE_KEYBOARD_H / 2 - ly) / TABLE_KEYBOARD_H
            if (u in 0f..1f && v in 0f..1f) return Hit.Keyboard(window, u, v)
            return@let
        }
        // The keyboard floats closest to the user.
        keyboardWindow()?.takeIf { tableKeyboard(it) == null }?.let { window ->
            val local = toLocal(direction, window.yaw, keyboardRadius)
            if (local != null) {
                val u = (local[0] + KEYBOARD_W / 2) / KEYBOARD_W
                val v = (keyboardCenterY(window) + KEYBOARD_H / 2 - local[1]) / KEYBOARD_H
                if (u in 0f..1f && v in 0f..1f) return Hit.Keyboard(window, u, v)
            }
        }
        // Windows are in front of the icons; the focused one first.
        val ordered = windows.filter { !it.minimized }.sortedByDescending { it == focused }
        for (window in ordered) {
            if (window.arcDegrees > 0f) curvedContentHit(direction, window)?.let { return it }
            val local = toLocal(direction, window.yaw, windowRadius) ?: continue
            val x = local[0]
            val y = local[1] - window.height
            val w = window.width / 2
            val h = window.heightM / 2
            if (abs(x) <= w && abs(y) <= h) return Hit.Content(window, (x + w) / window.width, (h - y) / window.heightM)
            // The browser's bar on top.
            val bar = barHeight(window)
            if (bar > 0f && y > h && y <= h + bar && abs(x) <= w) return Hit.Toolbar(window, (x + w) / window.width, (h + bar - y) / bar)
            // The pill under the window: back, the title (drag to move), keyboard, expand, minimize, close.
            val pill = WindowChrome.pillWidth(window.width)
            val pillTop = -h - WindowChrome.PILL_GAP
            if (y <= pillTop + .01f && y >= pillTop - WindowChrome.PILL_H - .01f && abs(x) <= pill / 2) {
                return when (WindowChrome.pillAction((x + pill / 2) / pill, pill, pillBack(window))) {
                    "close" -> Hit.Close(window)
                    "back" -> Hit.Toolbar(window, -1f, -1f)
                    "minimize" -> Hit.Minimize(window)
                    "expand" -> Hit.Expand(window)
                    "curve" -> Hit.Curve(window)
                    "keyboard" -> Hit.KeyboardButton(window)
                    else -> Hit.Bar(window)
                }
            }
            val handleY = frameBottom(window) - WindowChrome.HANDLE_GAP
            val desktopY = handleY - .12f
            if (window.id == "desktop" && abs(y - desktopY) < .075f && abs(x) < .57f) {
                return when {
                    x < -.18f -> Hit.DesktopWidth(window, wider = false)
                    x < .18f -> Hit.DesktopWidth(window, wider = true)
                    else -> Hit.DesktopCurve(window)
                }
            }
        }
        if (!panelVisible) return null
        val local = toLocal(direction, panelYaw, panelRadius) ?: return null
        val u = (local[0] + PANEL_WIDTH / 2) / PANEL_WIDTH
        val v = (PANEL_HEIGHT / 2 - local[1]) / PANEL_HEIGHT
        return if (u in 0f..1f && v in 0f..1f) Hit.Panel(u, v) else null
    }

    /** Content hit on a cylindrical desktop screen wrapped around the viewer. */
    private fun curvedContentHit(direction: FloatArray, window: VrWindow): Hit.Content? {
        val rotate = FloatArray(16); Matrix.setRotateM(rotate, 0, -window.yaw, 0f, 1f, 0f)
        val d = FloatArray(4); Matrix.multiplyMV(d, 0, rotate, 0, direction, 0)
        val origin = synchronized(headPosition) { floatArrayOf(headPosition[0], headPosition[1], headPosition[2], 1f) }
        val o = FloatArray(4); Matrix.multiplyMV(o, 0, rotate, 0, origin, 0)
        val a = d[0] * d[0] + d[2] * d[2]
        val b = 2f * (o[0] * d[0] + o[2] * d[2])
        val c = o[0] * o[0] + o[2] * o[2] - windowRadius * windowRadius
        val disc = b * b - 4f * a * c
        if (a < 1e-5f || disc < 0f) return null
        val roots = floatArrayOf((-b - kotlin.math.sqrt(disc)) / (2f * a), (-b + kotlin.math.sqrt(disc)) / (2f * a))
        val t = roots.filter { it > 0f }.minOrNull() ?: return null
        val x = o[0] + d[0] * t; val z = o[2] + d[2] * t; val y = o[1] + d[1] * t - window.height
        val span = Math.toRadians((window.arcDegrees * window.widthScale).coerceAtMost(330f).toDouble()).toFloat()
        val angle = kotlin.math.atan2(x, -z)
        if (kotlin.math.abs(angle) > span / 2 || kotlin.math.abs(y) > window.heightM / 2) return null
        return Hit.Content(window, angle / span + .5f, (.5f - y / window.heightM).coerceIn(0f, 1f))
    }

    /**
     * The keyboard on the table: x, y, z of its centre and its yaw in degrees — in front of the
     * user towards [window], kept on the table top. Null without 6DoF or a table within reach.
     */
    private fun tableKeyboard(window: VrWindow): FloatArray? {
        if (ar == null) return null
        val table = RoomScan.table ?: return null
        val head = synchronized(headPosition) { headPosition.copyOf() }
        val yaw = Math.toRadians(window.yaw.toDouble()).toFloat()
        var x = head[0] - kotlin.math.sin(yaw) * .42f
        var z = head[2] - kotlin.math.cos(yaw) * .42f
        // Onto the table: into its frame, clamp, back.
        val ax = kotlin.math.cos(table.yaw); val az = -kotlin.math.sin(table.yaw)
        val bx = kotlin.math.sin(table.yaw); val bz = kotlin.math.cos(table.yaw)
        val vx = x - table.x; val vz = z - table.z
        val along = (vx * ax + vz * az).coerceIn(-maxOf(0f, table.halfX - .12f), maxOf(0f, table.halfX - .12f))
        val across = (vx * bx + vz * bz).coerceIn(-maxOf(0f, table.halfZ - .08f), maxOf(0f, table.halfZ - .08f))
        x = table.x + along * ax + across * bx
        z = table.z + along * az + across * bz
        // Only a table the user sits or stands at.
        if (kotlin.math.hypot(x - head[0], z - head[2]) > 1.1f) return null
        return floatArrayOf(x, table.y + .006f, z, window.yaw)
    }

    /** Where a ray from the eyes crosses the plane of a window at [yaw] and [radius], in that window's frame. */
    private fun toLocal(direction: FloatArray, yaw: Float, radius: Float): FloatArray? {
        val rotate = FloatArray(16)
        Matrix.setRotateM(rotate, 0, -yaw, 0f, 1f, 0f)
        val d = FloatArray(4)
        Matrix.multiplyMV(d, 0, rotate, 0, direction, 0)
        // In 6DoF the ray starts where the head is now, not at the centre of the room.
        val origin = synchronized(headPosition) { floatArrayOf(headPosition[0], headPosition[1], headPosition[2], 1f) }
        val o = FloatArray(4)
        Matrix.multiplyMV(o, 0, rotate, 0, origin, 0)
        if (d[2] >= -1e-3f) return null
        val t = (-radius - o[2]) / d[2]
        if (t <= 0f) return null
        return floatArrayOf(o[0] + d[0] * t, o[1] + d[1] * t)
    }

    private fun updateHit(target: Hit?) {
        // Watching together: the other side sees where we point on the shared page.
        if (target is Hit.Content && target.window.id == SHARED_WINDOW) {
            (target.window.content as? SharedContent)?.pointer(target.u, target.v)
        }
        if (target is Hit.Setup) onboarding?.hover(target.u, target.v)
        val key = (target as? Hit.Keyboard)?.let { keyboard.hovered(it.u, it.v) }
        if (key != hoveredKey) {
            hoveredKey = key
            keyboardRedraw.set(true)
        }
        val panelTarget = (target as? Hit.Panel)?.let { synchronized(panel) { panel.hit(it.u, it.v) } }
        val previous = hit
        hit = target
        if (panelTarget != hoveredPanel || (previous is Hit.Panel) != (target is Hit.Panel)) {
            hoveredPanel = panelTarget
            redraw.set(true)
        }
    }

    @Volatile private var hoveredPanel: HomePanel.Target? = null
    private var panelPressMoved = false

    private fun press(target: Hit?, direction: FloatArray?) {
        pressing = true
        pressedHit = target
        when (target) {
            is Hit.Setup -> onboarding?.press(target.u, target.v)
            is Hit.Panel -> {
                val item = synchronized(panel) { panel.hit(target.u, target.v) }
                // A room slider follows the pointer while pinched.
                if (item is HomePanel.Target.Slider) {
                    sliderDrag = item.slider
                    moveSlider(item.slider, target.u, target.v)
                    return
                }
                // Home icons open when the finger lets go (a swipe turns the page instead).
                if (panel.mode == HomePanel.Mode.HOME || panel.mode == HomePanel.Mode.LIBRARY) {
                    panelPress = item
                    panelPressAt = SystemClock.elapsedRealtime()
                    panelPressMoved = false
                    return
                }
                item ?: return
                runOnUiThread { panelAction(item); redraw.set(true) }
            }
            is Hit.Content -> {
                focused = target.window
                target.window.content.touch(MotionEvent.ACTION_DOWN, target.u, target.v)
            }
            is Hit.Bar -> if (direction != null) {
                focused = target.window
                drag = Drag(target.window, target.window.yaw, target.window.height, yawOf(direction), heightAt(direction, windowRadius))
            }
            is Hit.Toolbar -> {
                focused = target.window
                // u < 0: the back button of a bottom bar; otherwise a place on the browser's bar.
                val action = if (target.u < 0f) "back" else WindowChrome.barAction(target.u, target.v, target.window.content.toolbarTabs().size)
                when (action) {
                    null -> Unit
                    "minimize" -> runOnUiThread { minimize(target.window) }
                    "close" -> runOnUiThread { close(target.window) }
                    // The empty parts of the bar move the window, like its handle.
                    "move" -> if (direction != null) {
                        drag = Drag(target.window, target.window.yaw, target.window.height, yawOf(direction), heightAt(direction, windowRadius))
                    }
                    else -> target.window.content.toolbarAction(action)
                }
            }
            is Hit.Resize -> if (direction != null) {
                focused = target.window
                drag = Drag(target.window, target.window.widthScale, target.window.heightScale, 0f, 0f, resize = true)
            }
            is Hit.DesktopWidth -> {
                target.window.widthScale = (target.window.widthScale + if (target.wider) .2f else -.2f).coerceIn(MIN_SCALE, MAX_SCALE)
            }
            is Hit.DesktopCurve -> {
                target.window.arcDegrees = when (target.window.arcDegrees.toInt()) { 0 -> 90f; 90 -> 180f; 180 -> 300f; else -> 0f }
                toast(if (target.window.arcDegrees == 0f) "Экран плоский" else "Изгиб экрана: ${target.window.arcDegrees.toInt()}°")
            }
            is Hit.Keyboard -> {
                val key = keyboard.press(target.u, target.v)
                keyboardRedraw.set(true)
                when (key) {
                    null -> Unit
                    KeyboardPanel.HIDE -> {
                        manualKeyboard = null
                        target.window.content.hideKeyboard()
                    }
                    else -> target.window.content.type(key)
                }
            }
            is Hit.KeyboardButton -> {
                focused = target.window
                if (keyboardWindow() == target.window) {
                    manualKeyboard = null
                    target.window.content.hideKeyboard()
                } else {
                    manualKeyboard = target.window
                }
                keyboardRedraw.set(true)
            }
            is Hit.Minimize -> runOnUiThread { minimize(target.window) }
            is Hit.Close -> runOnUiThread { close(target.window) }
            is Hit.Curve -> {
                // Bent around the user at the windows' own distance, keeping its width; or flat.
                target.window.arcDegrees = if (target.window.arcDegrees > 0f) 0f
                    else Math.toDegrees((VrWindow.WIDTH_M / windowRadius).toDouble()).toFloat()
            }
            is Hit.Expand -> {
                focused = target.window
                val scale = if (expanded(target.window)) 1f else EXPANDED_SCALE
                target.window.widthScale = scale
                target.window.heightScale = scale
            }
            null -> Unit
        }
    }

    private fun dragOrMove(direction: FloatArray) {
        if (!pressing) return
        sliderDrag?.let { slider ->
            (hit as? Hit.Panel)?.let { moveSlider(slider, it.u, it.v) }
            return
        }
        val panelStart = pressedHit as? Hit.Panel
        val panelNow = hit as? Hit.Panel
        if (panelStart != null) {
            if (panelNow != null && kotlin.math.abs(panelNow.u - panelStart.u) > PANEL_SWIPE_SLOP) {
                panelPressMoved = true
                panelPressAt = 0L
                panelPress = null
            }
            return
        }
        drag?.let { d ->
            if (d.resize) {
                // The window keeps its centre; the corner follows the pointer.
                val local = toLocal(direction, d.window.yaw, windowRadius) ?: return
                val byWidth = 2 * kotlin.math.abs(local[0]) / VrWindow.WIDTH_M
                val aspect = d.window.content.pixelHeight.toFloat() / d.window.content.pixelWidth
                val byHeight = 2 * kotlin.math.abs(d.window.height - local[1]) / (VrWindow.WIDTH_M * aspect)
                d.window.widthScale = byWidth.coerceIn(MIN_SCALE, MAX_SCALE)
                d.window.heightScale = byHeight.coerceIn(MIN_SCALE, MAX_SCALE)
                return
            }
            d.window.yaw = d.startYaw + (yawOf(direction) - d.pointerYaw)
            d.window.height = d.startHeight + (heightAt(direction, windowRadius) - d.pointerHeight)
            return
        }
        val pressed = pressedHit as? Hit.Content ?: return
        val now = SystemClock.uptimeMillis()
        if (now - lastMoveSent < 16) return
        lastMoveSent = now
        val local = toLocal(direction, pressed.window.yaw, windowRadius) ?: return
        val w = pressed.window.width / 2
        val h = pressed.window.heightM / 2
        val u = ((local[0] + w) / pressed.window.width).coerceIn(0f, 1f)
        val v = ((h - (local[1] - pressed.window.height)) / pressed.window.heightM).coerceIn(0f, 1f)
        pressed.window.content.touch(MotionEvent.ACTION_MOVE, u, v)
    }

    private fun panelAction(item: HomePanel.Target) {
        when (item) {
            is HomePanel.Target.App -> {
                synchronized(panel) {
                    if (item.entry.id.startsWith("app:") || item.entry.id.startsWith("web:") || item.entry.id.startsWith("own:") || item.entry.id == ID_DESKTOP) {
                        panel.opened(item.entry.id)
                    }
                    panel.closeSearch()
                }
                openEntry(item.entry)
            }
            is HomePanel.Target.Page -> synchronized(panel) { panel.showPage(item.index) }
            // The library button: opens the Library, or closes it back to the dock.
            HomePanel.Target.Library -> synchronized(panel) {
                val open = panel.library() && !panel.searching && (panel.mode == HomePanel.Mode.HOME || panel.mode == HomePanel.Mode.LIBRARY)
                if (open && panel.compact) switchMode(HomePanel.Mode.HOME)
                else { switchMode(HomePanel.Mode.LIBRARY); panel.setTab(HomePanel.Tab.ALL) }
            }
            HomePanel.Target.Store -> switchMode(HomePanel.Mode.STORE)
            HomePanel.Target.Sort -> synchronized(panel) { panel.toggleSort() }
            // Sliders move while pinched (see press and dragOrMove), nothing to do on release.
            is HomePanel.Target.Slider -> Unit
            HomePanel.Target.Card -> Unit
            is HomePanel.Target.Quick -> quickAction(item.quick)
            is HomePanel.Target.Key -> {
                val open = synchronized(panel) { panel.type(item.key) }
                if (open != null) panelAction(HomePanel.Target.App(open))
            }
            is HomePanel.Target.Control -> when (item.control) {
                HomePanel.Control.PROFILE -> openOwn(ID_SETTINGS)
                // The time opens quick settings, as on Quest (and closes them again).
                HomePanel.Control.STATUS -> switchMode(if (panel.mode == HomePanel.Mode.QUICK) HomePanel.Mode.HOME else HomePanel.Mode.QUICK)
                HomePanel.Control.NOTIFICATIONS -> openCalls()
                HomePanel.Control.SEARCH -> synchronized(panel) { if (panel.searching) panel.closeSearch() else panel.openSearch() }
                HomePanel.Control.PASSTHROUGH -> {
                    // Like the Horizon button: the room through the camera, or back to the chosen world.
                    val world = if (environmentId == Environments.REAL_WORLD) lastWorld else Environments.REAL_WORLD
                    setEnvironment(world)
                }
            }
            HomePanel.Target.Close -> switchMode(HomePanel.Mode.HOME)
            is HomePanel.Target.Rail -> {
                synchronized(panel) {
                    if (panel.mode != HomePanel.Mode.LIBRARY && panel.mode != HomePanel.Mode.HOME) panel.mode = HomePanel.Mode.LIBRARY
                    if (panel.mode == HomePanel.Mode.HOME && panel.compact) panel.mode = HomePanel.Mode.LIBRARY
                    panel.closeSearch()
                    panel.setTab(item.tab)
                }
                redraw.set(true)
                when (item.tab) {
                    HomePanel.Tab.PEOPLE -> loadPeople()
                    HomePanel.Tab.ENVIRONMENTS -> loadEnvironments()
                    else -> Unit
                }
            }
        }
    }

    private fun quickAction(item: HomePanel.Quick) {
        when (item) {
            HomePanel.Quick.SETTINGS, HomePanel.Quick.WIFI -> { switchMode(HomePanel.Mode.HOME); openOwn(ID_SETTINGS) }
            HomePanel.Quick.CAR -> setCarMode(!carMode)
            HomePanel.Quick.DESKTOP -> { switchMode(HomePanel.Mode.HOME); openOwn(ID_DESKTOP) }
            HomePanel.Quick.PASSTHROUGH -> setEnvironment(if (environmentId == Environments.REAL_WORLD) lastWorld else Environments.REAL_WORLD)
            HomePanel.Quick.RECENTER -> recenter()
            HomePanel.Quick.BLUETOOTH -> { switchMode(HomePanel.Mode.HOME); openOwn(ID_SETTINGS) }
            HomePanel.Quick.DND -> synchronized(panel) {
                panel.dnd = !panel.dnd
                panel.alerts = if (panel.dnd) 0 else Calls.online.size
            }
            HomePanel.Quick.PHOTO -> takePhoto()
        }
        redraw.set(true)
    }

    /** Opens one of PhoneXR's own apps from the home by its id. */
    private fun openOwn(id: String) {
        val entry = synchronized(panel) { panel.homeEntries() }.firstOrNull { it.id == id } ?: HomePanel.Entry(id, "", null)
        openEntry(entry)
    }

    /** Friends on the "people" tab: tapping one opens the calls window. */
    private fun loadPeople() {
        thread(name = "PhoneXR people") {
            val friends = runCatching { Friends.mine(this) }.getOrDefault(emptyList())
            val entries = friends.map { person ->
                HomePanel.Entry(
                    "person:${person.username}",
                    person.name.ifEmpty { "@" + person.username },
                    letterIcon(person.name.ifEmpty { person.username }),
                    badge = if (Calls.online.any { it.id == person.id }) "•" else null,
                )
            }
            synchronized(panel) { panel.setPeople(entries) }
            redraw.set(true)
        }
    }

    /** Places to be in: only curated PhoneXR backgrounds; personal gallery photos stay private. */
    private fun loadEnvironments() {
        thread(name = "PhoneXR environments") {
            val places = Environments.BUILT_IN
            val entries = places.map { place ->
                val thumb = Environments.thumbnail(this, place.id)
                HomePanel.Entry(
                    "env:${place.id}",
                    place.title,
                    thumb?.let { BitmapDrawable(resources, it) },
                    badge = if (place.id == environmentId) "✓" else null,
                )
            }
            synchronized(panel) { panel.setEnvironments(entries) }
            redraw.set(true)
        }
    }

    /** Which place is around the user; [Environments.REAL_WORLD] is the room through the camera. */
    @Volatile private var environmentId = Environments.REAL_WORLD

    // ------------------------------------------------------------------ Room: volume, brightness, rain, fog

    @Volatile private var sliderDrag: HomePanel.Slider? = null
    /** How bright the room (passthrough or world) is, 0..1. */
    @Volatile private var roomBrightness = 1f
    @Volatile private var rainLevel = 0f
    @Volatile private var fogLevel = 0f

    private fun audio() = getSystemService(android.media.AudioManager::class.java)

    /** The saved room and the phone's media volume, onto the sliders. */
    private fun loadRoom() {
        val prefs = getSharedPreferences(ROOM_PREFS, MODE_PRIVATE)
        val brightness = prefs.getFloat("brightness", 1f)
        roomBrightness = .15f + .85f * brightness
        rainLevel = prefs.getFloat("rain", 0f).takeIf { it >= OFF_BELOW } ?: 0f
        fogLevel = prefs.getFloat("fog", 0f).takeIf { it >= OFF_BELOW } ?: 0f
        val volume = runCatching {
            val audio = audio()
            audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC).toFloat() /
                audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        }.getOrDefault(.5f)
        synchronized(panel) {
            panel.sliders[HomePanel.Slider.VOLUME.ordinal] = volume
            panel.sliders[HomePanel.Slider.BRIGHTNESS.ordinal] = brightness
            panel.sliders[HomePanel.Slider.RAIN.ordinal] = rainLevel
            panel.sliders[HomePanel.Slider.FOG.ordinal] = fogLevel
        }
    }

    private fun saveRoom() {
        getSharedPreferences(ROOM_PREFS, MODE_PRIVATE).edit()
            .putFloat("brightness", synchronized(panel) { panel.sliders[HomePanel.Slider.BRIGHTNESS.ordinal] }).putFloat("rain", rainLevel).putFloat("fog", fogLevel).apply()
    }

    private fun moveSlider(slider: HomePanel.Slider, u: Float, v: Float) {
        val value = synchronized(panel) { panel.sliderValue(slider, u, v).also { panel.sliders[slider.ordinal] = it } }
        when (slider) {
            HomePanel.Slider.VOLUME -> runCatching {
                val audio = audio()
                val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
                val index = kotlin.math.round(value * max).toInt()
                if (index != audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)) {
                    audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, index, 0)
                }
            }
            // Never quite black: the room stays findable.
            HomePanel.Slider.BRIGHTNESS -> roomBrightness = .15f + .85f * value
            // The bottom of the slider is really off: a slider left a hair above zero rained anyway.
            HomePanel.Slider.RAIN -> rainLevel = if (value < OFF_BELOW) 0f else value
            HomePanel.Slider.FOG -> fogLevel = if (value < OFF_BELOW) 0f else value
        }
        redraw.set(true)
    }

    /** The world the passthrough button goes back to. */
    private var lastWorld = Environments.BUILT_IN.first { it.id != Environments.REAL_WORLD }.id

    private fun setEnvironment(id: String) {
        environmentId = id
        if (id != Environments.REAL_WORLD) lastWorld = id
        synchronized(panel) { panel.passthrough = id == Environments.REAL_WORLD }
        redraw.set(true)
        thread(name = "PhoneXR environment") {
            val bitmap = runCatching { Environments.panorama(this, id) }.getOrNull()
            // The picture goes to the GPU on the drawing thread, where textures may be touched.
            glTasks += { renderer?.setEnvironment(bitmap) }
            loadEnvironments()
        }
    }

    @Volatile private var panelPress: HomePanel.Target? = null
    @Volatile private var panelPressAt = 0L

    private fun release() {
        if (!pressing) return
        pressing = false
        drag = null
        if (sliderDrag != null) {
            sliderDrag = null
            saveRoom()
        }
        val panelStart = pressedHit as? Hit.Panel
        val panelEnd = hit as? Hit.Panel
        val swipe = if (panelPressMoved && panelStart != null && panelEnd != null) panelEnd.u - panelStart.u else 0f
        if (kotlin.math.abs(swipe) > PANEL_SWIPE_THRESHOLD) {
            synchronized(panel) { panel.turnPage(if (swipe < 0f) 1 else -1) }
            redraw.set(true)
        } else if (panelPressAt != 0L) {
            val item = panelPress
            panelPressAt = 0L
            panelPress = null
            if (item != null) runOnUiThread { panelAction(item); redraw.set(true) }
        }
        panelPressMoved = false
        val pressed = pressedHit as? Hit.Content
        if (pressed != null) {
            val current = hit as? Hit.Content
            val u = if (current?.window == pressed.window) current.u else pressed.u
            val v = if (current?.window == pressed.window) current.v else pressed.v
            pressed.window.content.touch(MotionEvent.ACTION_UP, u, v)
        }
        pressedHit = null
    }

    // ------------------------------------------------------------------ Gamepad goes to the focused window

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (physicalKeyboard(event)) return typeKey(event)
        if (!event.isFromSource(InputDevice.SOURCE_GAMEPAD) && !event.isFromSource(InputDevice.SOURCE_DPAD)) {
            return super.dispatchKeyEvent(event)
        }
        val window = focused?.takeIf { !it.minimized }
        if (window != null) {
            window.content.key(event)
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_R2 -> hit?.let { press(it, ray); release() }
                KeyEvent.KEYCODE_BUTTON_START -> openMenu()
                KeyEvent.KEYCODE_BUTTON_B -> switchMode(HomePanel.Mode.HOME)
                KeyEvent.KEYCODE_BUTTON_R1 -> { synchronized(panel) { panel.turnPage(1) }; redraw.set(true) }
                KeyEvent.KEYCODE_BUTTON_L1 -> { synchronized(panel) { panel.turnPage(-1) }; redraw.set(true) }
            }
        }
        return true
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            // The first touch of a mouse takes it over: relative movement, no system pointer.
            if (!surfaceView.hasPointerCapture()) surfaceView.requestPointerCapture()
            onMouse(event, captured = false)
            return true
        }
        val window = focused?.takeIf { !it.minimized }
        if (window != null && event.isFromSource(InputDevice.SOURCE_JOYSTICK)) {
            window.content.motion(event)
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    // ------------------------------------------------------------------ Mouse and keyboard

    /** Mouse clicks before the pointer is captured: the mouse's, not a touch on the phone. */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // BE: a tap anywhere on the screen clicks where the dot in the middle points.
        if (BuildConfig.BE && event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> press(hit, ray)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> release()
            }
            return true
        }
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) {
            if (!surfaceView.hasPointerCapture()) surfaceView.requestPointerCapture()
            onMouse(event, captured = false)
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    /** A real keyboard (Bluetooth or USB), not the gamepad or the phone's own buttons. */
    private fun physicalKeyboard(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (device.isVirtual || event.isFromSource(InputDevice.SOURCE_GAMEPAD)) return false
        return device.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC
    }

    /**
     * Typing on a real keyboard: into the window that has a text field, else to the focused window
     * as keys, else into the home's search (typing on the home starts a search, as on Horizon).
     */
    private fun typeKey(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return true
        val text = when (event.keyCode) {
            KeyEvent.KEYCODE_DEL -> "backspace"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "enter"
            KeyEvent.KEYCODE_ESCAPE -> null
            else -> event.unicodeChar.takeIf { it > 0 }?.toChar()?.toString()
        }
        onboarding?.let { setup -> text?.let { setup.type(it) }; return true }
        if (event.keyCode == KeyEvent.KEYCODE_ESCAPE) {
            runOnUiThread { @Suppress("DEPRECATION") onBackPressed() }
            return true
        }
        val typing = keyboardWindow()
        if (typing != null && text != null) {
            typing.content.type(text)
            keyboardRedraw.set(true)
            return true
        }
        val window = focused?.takeIf { !it.minimized }
        if (window != null) {
            window.content.key(event)
            return true
        }
        text ?: return true
        val open = synchronized(panel) {
            if (!panel.searching) {
                if (text == "backspace" || text == "enter" || text == " ") return true
                panel.openSearch()
            }
            panel.type(when (text) { "backspace" -> HomePanel.KEY_BACKSPACE; "enter" -> HomePanel.KEY_ENTER; " " -> HomePanel.KEY_SPACE; else -> text })
        }
        if (open != null) runOnUiThread { panelAction(HomePanel.Target.App(open)) }
        redraw.set(true)
        return true
    }

    /** The mouse pointer: a direction in the room, like a laser from the eyes, moved by the mouse. */
    @Volatile private var mouseYaw = Float.NaN
    @Volatile private var mousePitch = 0f
    @Volatile private var mouseAt = 0L
    @Volatile private var mouseDown = false
    private var hoverX = Float.NaN
    private var hoverY = Float.NaN

    private fun mouseActive() = mouseAt != 0L && SystemClock.elapsedRealtime() - mouseAt < MOUSE_IDLE_MS

    /** The mouse's direction in the room (unit vector), or null before it has moved. */
    private fun mouseDirection(): FloatArray? {
        if (mouseYaw.isNaN()) return null
        val c = kotlin.math.cos(mousePitch)
        return floatArrayOf(-kotlin.math.sin(mouseYaw) * c, kotlin.math.sin(mousePitch), -kotlin.math.cos(mouseYaw) * c, 0f)
    }

    private fun onMouse(event: MotionEvent, captured: Boolean) {
        if (mouseYaw.isNaN()) {
            // Start where the user looks.
            val head = FloatArray(16).also { tracker.copyHead(it) }
            mouseYaw = atan2(head[8], head[10])
            mousePitch = 0f
        }
        mouseAt = SystemClock.elapsedRealtime()
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_HOVER_MOVE -> {
                val (dx, dy) = if (captured) event.x to event.y else {
                    val d = if (hoverX.isNaN()) 0f to 0f else (event.x - hoverX) to (event.y - hoverY)
                    hoverX = event.x; hoverY = event.y
                    d
                }
                mouseYaw -= dx * MOUSE_SPEED
                mousePitch = (mousePitch - dy * MOUSE_SPEED).coerceIn(-1.3f, 1.3f)
            }
            MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_DOWN -> {
                if (event.isButtonPressed(MotionEvent.BUTTON_SECONDARY)) {
                    runOnUiThread { @Suppress("DEPRECATION") onBackPressed() }
                    return
                }
                if (!mouseDown) {
                    mouseDown = true
                    press(hit, mouseDirection())
                }
            }
            MotionEvent.ACTION_BUTTON_RELEASE, MotionEvent.ACTION_UP -> if (mouseDown && !event.isButtonPressed(MotionEvent.BUTTON_PRIMARY)) {
                mouseDown = false
                release()
            }
            MotionEvent.ACTION_SCROLL -> scroll(event.getAxisValue(MotionEvent.AXIS_VSCROLL))
        }
        val direction = mouseDirection() ?: return
        ray = direction
        pinchPoint = null
        val target = hitTest(direction)
        updateHit(target)
        if (mouseDown) dragOrMove(direction)
    }

    /** The wheel: pages on the home, a drag on a window (as a finger would scroll it). */
    private fun scroll(amount: Float) {
        if (amount == 0f) return
        when (val target = hit) {
            is Hit.Panel -> { synchronized(panel) { panel.turnPage(if (amount < 0) 1 else -1) }; redraw.set(true) }
            is Hit.Content -> {
                val content = target.window.content
                val to = (target.v + amount * .12f).coerceIn(0f, 1f)
                content.touch(MotionEvent.ACTION_DOWN, target.u, target.v)
                content.touch(MotionEvent.ACTION_MOVE, target.u, (target.v + to) / 2)
                content.touch(MotionEvent.ACTION_MOVE, target.u, to)
                content.touch(MotionEvent.ACTION_UP, target.u, to)
            }
            else -> Unit
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (panel.mode != HomePanel.Mode.HOME) switchMode(HomePanel.Mode.HOME) else super.onBackPressed()
    }

    // ------------------------------------------------------------------ Icons of the built-in apps

    private fun symbolIcon(symbol: String, color: Int, ink: Int = Color.WHITE): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        canvas.drawRect(0f, 0f, size, size, paint)
        paint.color = ink
        paint.textSize = size * .42f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(symbol, size / 2, size / 2 + size * .15f, paint)
    }

    /** The first letter on a Material You tile in a colour of the name's own. */
    private fun letterIcon(name: String): Drawable =
        MaterialYouIcons.palette(name, MaterialYouIcons.dark(this)).let { symbolIcon(name.take(1).uppercase(), it.tile, it.glyph) }

    /** A Material icon in [ink] on a clear square: the vector's own paths, drawn at 256 px. */
    private fun vectorIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, ink: Int): Drawable = iconCanvas { canvas, size ->
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink }
        val scale = size / icon.viewportWidth
        canvas.save()
        canvas.scale(scale, scale)
        fun draw(group: androidx.compose.ui.graphics.vector.VectorGroup) {
            for (child in group) when (child) {
                is androidx.compose.ui.graphics.vector.VectorPath ->
                    canvas.drawPath(androidx.compose.ui.graphics.vector.PathParser().addPathNodes(child.pathData).toPath().asAndroidPath(), paint)
                is androidx.compose.ui.graphics.vector.VectorGroup -> draw(child)
            }
        }
        draw(icon.root)
        canvas.restore()
    }

    private fun iconCanvas(paint: (Canvas, Float) -> Unit): Drawable {
        val bitmap = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        paint(Canvas(bitmap), 256f)
        return BitmapDrawable(resources, bitmap)
    }

    private fun toast(text: String) = runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }

    // ------------------------------------------------------------------ Rendering

    /**
     * Where the home panel hangs, in degrees around the user. It stays put while the head turns a
     * little and follows lazily once the user really looks away, so it is always within reach but
     * never glued to the nose.
     */
    @Volatile private var panelYaw = 0f
    private var panelFollows = false

    /**
     * The home follows the eyes lazily; in car mode the windows come too — the whole view drifts
     * after the head, but only once it has looked away far enough and then smoothly, so it stays
     * put for reading and never feels glued to the head.
     */
    private fun followWithPanel() {
        val car = carMode
        if (!panelFollows && !car) return
        val head = FloatArray(16)
        tracker.copyHead(head)
        // Straight ahead in world coordinates is the head's own -z.
        val forward = FloatArray(4)
        Matrix.multiplyMV(forward, 0, head, 0, floatArrayOf(0f, 0f, -1f, 0f), 0)
        val headYaw = yawOf(forward)
        var delta = headYaw - panelYaw
        while (delta > 180f) delta -= 360f
        while (delta < -180f) delta += 360f
        val beyond = abs(delta) - (if (car) CAR_DEAD_ZONE else PANEL_DEAD_ZONE)
        if (beyond <= 0f) return
        val step = (if (delta > 0) beyond else -beyond) * (if (car) CAR_CATCH_UP else PANEL_CATCH_UP)
        panelYaw += step
        if (car) for (window in windows) window.yaw += step
    }

    /** Car mode: 3DoF, the vehicle's turns taken out, and the windows following the view. */
    @Volatile private var carMode = false

    /** Car mode on or off, at once: in the car the camera's 6DoF is stopped (the room itself moves). */
    private fun setCarMode(on: Boolean) = runOnUiThread {
        Settings.setTravelMode(this, on)
        carMode = on
        tracker.travelMode = on
        synchronized(panel) { panel.car = on }
        if (on) {
            ar?.let { tracker6 -> ar = null; tracker6.pause(); tracker6.close(); bindCamera() }
            sensorSixDof?.stop()
            synchronized(headPosition) { headPosition.fill(0f) }
            toast(tr("Режим машины: 3DoF, окна следуют за взглядом"))
        } else {
            sensorSixDof?.let { it.reset(); it.start() }
            if (Settings.load(this).sixDof) startArLater()
        }
        redraw.set(true)
    }

    private inner class Renderer : GLSurfaceView.Renderer {
        /** The user's own eyes and lenses, read when the headset starts. */
        private var eyes = Eyes.DEFAULT
        /** The place around the user, when it is not the real room. */
        private var environmentTexture = 0
        private var hasEnvironment = false
        private var environmentMesh: FloatArray? = null

        /** Puts a place around the user; null brings the real room back. Call on the GL thread. */
        fun setEnvironment(bitmap: Bitmap?) {
            if (bitmap == null) {
                hasEnvironment = false
                return
            }
            if (environmentTexture == 0) {
                environmentTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, environmentTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_REPEAT)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, environmentTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            if (environmentMesh == null) environmentMesh = sphereMesh()
            hasEnvironment = true
        }

        /** The inside of a sphere as triangles: x, y, z, u, v. */
        private fun sphereMesh(): FloatArray {
            val stacks = 32
            val slices = 64
            val data = ArrayList<Float>(stacks * slices * 30)
            fun put(u: Float, v: Float) {
                val lon = (u - .5f) * 2f * Math.PI.toFloat()
                val lat = (.5f - v) * Math.PI.toFloat()
                data += kotlin.math.sin(lon) * kotlin.math.cos(lat) * 20f
                data += kotlin.math.sin(lat) * 20f
                data += -kotlin.math.cos(lon) * kotlin.math.cos(lat) * 20f
                data += u
                data += v
            }
            for (stack in 0 until stacks) {
                val v0 = stack.toFloat() / stacks
                val v1 = (stack + 1f) / stacks
                for (slice in 0 until slices) {
                    val u0 = slice.toFloat() / slices
                    val u1 = (slice + 1f) / slices
                    put(u0, v0); put(u0, v1); put(u1, v0)
                    put(u1, v0); put(u0, v1); put(u1, v1)
                }
            }
            return data.toFloatArray()
        }
        private var panelTexture = 0
        private var panelSurface: SurfaceTexture? = null
        private val panelFrame = AtomicBoolean(false)
        private var cameraTexture = 0
        private var desktopControlsTexture = 0
        private var keyboardTexture = 0
        /** ARCore draws the camera into this external texture (6DoF passthrough). */
        private var arTexture = 0
        private var tracingTexture = 0
        private var textureProgram = 0
        private var externalProgram = 0
        private var colorProgram = 0
        private var roundedProgram = 0
        private var roundedExternalProgram = 0
        /** Final Cardboard pass: both eyes are rendered off-screen, then warped for the lenses. */
        private var cardboardProgram = 0
        private var cardboardTexture = 0
        private var cardboardFramebuffer = 0
        private var hasCamera = false
        private var cameraAspect = 16f / 9f
        private var width = 1
        private var height = 1
        private val head = FloatArray(16)
        private val worldToHead = FloatArray(16)
        private val projection = FloatArray(16)
        private val eye = FloatArray(16)
        private val view = FloatArray(16)
        private val model = FloatArray(16)
        private val modelView = FloatArray(16)
        private val mvp = FloatArray(16)
        private val identity = FloatArray(16).also { Matrix.setIdentityM(it, 0) }

        override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
            eyes = Eyes.load(this@VrHomeActivity)
            // The home stays where it was put; only car mode brings the view along.
            panelFollows = false
            textureProgram = CinemaRenderer.program(TEXTURE_VERTEX, TEXTURE_FRAGMENT)
            externalProgram = CinemaRenderer.program(TEXTURE_VERTEX, EXTERNAL_FRAGMENT)
            colorProgram = CinemaRenderer.program(COLOR_VERTEX, COLOR_FRAGMENT)
            roundedProgram = CinemaRenderer.program(ROUNDED_VERTEX, ROUNDED_FRAGMENT)
            roundedExternalProgram = CinemaRenderer.program(ROUNDED_VERTEX, ROUNDED_EXTERNAL_FRAGMENT)
            cardboardProgram = CinemaRenderer.program(TEXTURE_VERTEX, CARDBOARD_FRAGMENT)
            val ids = IntArray(4)
            GLES20.glGenTextures(4, ids, 0)
            cameraTexture = ids[1]
            // The home panel is compose-hig on a virtual display: an external texture fed by its surface.
            panelTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, panelTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            panelSurface?.release()
            panelContent?.release()
            val surface = SurfaceTexture(panelTexture).also { it.setOnFrameAvailableListener { panelFrame.set(true) } }
            panelSurface = surface
            panelContent = HomePanelContent(panel).also { it.attach(this@VrHomeActivity, surface) {} }
            desktopControlsTexture = ids[3]
            for (id in ids.drop(1)) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, desktopControlsTexture)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, drawDesktopControls(), 0)
            keyboardTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, keyboardTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            keyboardRedraw.set(true)
            arTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, arTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            ar?.attachTexture(arTexture)
            onboardingTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, onboardingTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            tracingTexture = bannerTexture("Закончить сканирование · сожмите кулак", Color.rgb(10, 132, 255))
            redraw.set(true)
        }

        private fun bannerTexture(text: String, color: Int): Int {
            val bitmap = Bitmap.createBitmap(1400, 180, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.argb(225, 28, 28, 32)
            canvas.drawRoundRect(RectF(0f, 0f, 1400f, 180f), 90f, 90f, paint)
            paint.color = color
            canvas.drawCircle(95f, 90f, 34f, paint)
            paint.color = Color.WHITE
            paint.textSize = 54f
            canvas.drawText(text, 160f, 108f, paint)
            val id = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            return id
        }

        /** ARCore started after the GL surface: give it the camera texture and the eye size. */
        fun attachAr(tracker6: ArTracker) {
            tracker6.attachTexture(arTexture)
            @Suppress("DEPRECATION")
            tracker6.setDisplay(windowManager.defaultDisplay.rotation, width / 2, height)
        }

        override fun onSurfaceChanged(unused: GL10?, w: Int, h: Int) {
            width = w
            height = h
            createCardboardTarget(w, h)
            @Suppress("DEPRECATION")
            ar?.setDisplay(windowManager.defaultDisplay.rotation, w / 2, h)
        }

        /** Google Cardboard's rendering order: draw eyes to a texture, then lens-distort it. */
        private var scanFrames = 0

        /**
         * The room scan as grids on the floor, the walls and the table (the table in the accent
         * colour) while the room is being set up.
         */
        private fun roomGrid(world: FloatArray) {
            val tracker6 = ar ?: return
            val setup = onboarding?.step == Onboarding.Step.ROOM
            if (!setup) return
            val surfaces = tracker6.surfaces
            if (surfaces.isEmpty()) return
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, world, 0)
            val color = GLES20.glGetUniformLocation(colorProgram, "uColor")
            val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            GLES20.glLineWidth(2f)
            for (surface in surfaces) {
                when (surface.kind) {
                    RoomScan.Kind.TABLE -> GLES20.glUniform4f(color, .45f, .8f, 1f, .85f)
                    RoomScan.Kind.FLOOR -> GLES20.glUniform4f(color, 1f, 1f, 1f, .45f)
                    else -> GLES20.glUniform4f(color, 1f, 1f, 1f, .3f)
                }
                drawArray(surface.lines, GLES20.GL_LINES, position)
            }
        }

        private fun createCardboardTarget(w: Int, h: Int) {
            if (cardboardTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(cardboardTexture), 0)
            if (cardboardFramebuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(cardboardFramebuffer), 0)
            cardboardTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cardboardTexture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
            cardboardFramebuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cardboardFramebuffer)
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, cardboardTexture, 0
            )
            check(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "Cardboard framebuffer is incomplete"
            }
            attachStencil(w, h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        }

        private var stencilBuffer = 0
        /** The see-through hands are drawn once per pixel with the stencil; without one they are solid. */
        private var hasStencil = false
        /** A depth buffer for the 3D controller models (nothing else tests depth). */
        private var hasDepth = false

        /**
         * A stencil for the eye framebuffer (nothing else uses it): an 8-bit one, or the packed
         * depth-stencil some GPUs need. If neither works the framebuffer stays as it was.
         */
        private fun attachStencil(w: Int, h: Int) {
            if (stencilBuffer != 0) GLES20.glDeleteRenderbuffers(1, intArrayOf(stencilBuffer), 0)
            stencilBuffer = IntArray(1).also { GLES20.glGenRenderbuffers(1, it, 0) }[0]
            GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, stencilBuffer)
            hasStencil = false
            hasDepth = false
            // Depth and stencil together where the GPU has it: the controller models need depth.
            if (GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_OES_packed_depth_stencil") == true) {
                GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES11Ext.GL_DEPTH24_STENCIL8_OES, w, h)
                GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, stencilBuffer)
                GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_STENCIL_ATTACHMENT, GLES20.GL_RENDERBUFFER, stencilBuffer)
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { hasStencil = true; hasDepth = true; return }
                GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, 0)
                GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_STENCIL_ATTACHMENT, GLES20.GL_RENDERBUFFER, 0)
            }
            GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES20.GL_STENCIL_INDEX8, w, h)
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_STENCIL_ATTACHMENT, GLES20.GL_RENDERBUFFER, stencilBuffer)
            if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { hasStencil = true; return }
            GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_STENCIL_ATTACHMENT, GLES20.GL_RENDERBUFFER, 0)
            if (GLES20.glGetString(GLES20.GL_EXTENSIONS)?.contains("GL_OES_packed_depth_stencil") == true) {
                GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES11Ext.GL_DEPTH24_STENCIL8_OES, w, h)
                GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_STENCIL_ATTACHMENT, GLES20.GL_RENDERBUFFER, stencilBuffer)
                if (GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) { hasStencil = true; return }
                GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_STENCIL_ATTACHMENT, GLES20.GL_RENDERBUFFER, 0)
            }
        }

        /** 6DoF: one ARCore step — head position, and a camera frame for the hands when they are free. */
        private fun updateAr(tracker6: ArTracker) {
            tracker.copyHead(head)
            val job = tracker6.update(head, wantImage = !busy.get())
            // The table, once a second: where it stands is remembered for the keyboard.
            if (++scanFrames % 60 == 0) RoomScan.remember(this@VrHomeActivity, tracker6.surfaces)
            if (job != null && busy.compareAndSet(false, true)) {
                trackingExecutor.execute {
                    try {
                        val frame = job()
                        if (frame != null) {
                            arFrameMap = floatArrayOf(frame.viewLeft, frame.viewTop, frame.viewWidth, frame.viewHeight)
                            handTracker?.detect(frame.bitmap, frame.timestampNs / 1_000_000L)
                            // Swapped under the lock that photo and call snapshots read it with.
                            synchronized(frameLock) {
                                val old = arPhoto
                                arPhoto = frame.bitmap
                                old?.recycle()
                            }
                        }
                    } catch (error: Throwable) {
                        Log.w(TAG, "ARCore frame failed", error)
                    } finally {
                        busy.set(false)
                    }
                }
            }
            synchronized(headPosition) { synchronized(tracker6.position) { System.arraycopy(tracker6.position, 0, headPosition, 0, 3) } }
        }

        override fun onDrawFrame(unused: GL10?) {
            while (true) glTasks.poll()?.invoke() ?: break
            followWithPanel()
            if (BuildConfig.BE) gaze()
            val tracker6 = ar
            if (tracker6 != null) updateAr(tracker6)
            else if (neckModel && !carMode) neck()
            if (redraw.getAndSet(false)) {
                // The layout (where each target is) and the compose-hig panel's state, which redraws itself.
                synchronized(panel) { panel.draw(hoveredPanel, pressing) }
            }
            if (panelFrame.getAndSet(false)) panelSurface?.updateTexImage()
            val setup = onboarding
            if (setup != null) {
                // The setup animates every frame (hello, progress, the cursor in the name field).
                synchronized(setup) {
                    if (setup.draw()) {
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, onboardingTexture)
                        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, setup.bitmap, 0)
                    }
                }
            }
            val typing = keyboardWindow()
            if (typing != null && keyboardRedraw.getAndSet(false)) {
                keyboard.draw(hoveredKey)
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, keyboardTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, keyboard.bitmap, 0)
            }
            synchronized(frameLock) {
                val bitmap = frame
                if (bitmap != null && frameFresh.getAndSet(false) && !bitmap.isRecycled) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, cameraTexture)
                    GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
                    cameraAspect = bitmap.width.toFloat() / bitmap.height
                    hasCamera = true
                }
            }
            for (window in windows) {
                if (framesReady.remove(window.id)) surfaceTextures[window.id]?.updateTexImage()
                window.content.takeBitmap()?.let { bitmap ->
                    textures[window.id]?.let { id ->
                        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
                        synchronized(window.content) { GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0) }
                    }
                }
            }

            // WebXR: the page draws both eyes plainly, with PhoneXR's own field of view; the same
            // lens pass as everything else (and the headset's lens shift) then goes over it.
            windows.firstOrNull { it.content.immersive }?.let { xrWindow ->
                val texture = textures[xrWindow.id] ?: return@let
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cardboardFramebuffer)
                GLES20.glViewport(0, 0, width, height)
                GLES20.glClearColor(0f, 0f, 0f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                // WebXR AR: the camera in each eye first, the page (transparent where it draws nothing) over it.
                if (xrWindow.content.immersiveAr && hasCamera) {
                    val eyeAspect = (width / 2f) / height
                    val (u0, u1, v0, v1) = if (eyeAspect < cameraAspect) {
                        val span = eyeAspect / cameraAspect
                        listOf(.5f - span / 2, .5f + span / 2, 0f, 1f)
                    } else {
                        val span = cameraAspect / eyeAspect
                        listOf(0f, 1f, .5f - span / 2, .5f + span / 2)
                    }
                    for (x0 in floatArrayOf(-1f, 0f)) {
                        val x1 = x0 + 1f
                        quad(textureProgram, cameraTexture, identity, floatArrayOf(x0, -1f, 0f, u0, v1, x1, -1f, 0f, u1, v1, x0, 1f, 0f, u0, v0, x1, 1f, 0f, u1, v0))
                    }
                    GLES20.glEnable(GLES20.GL_BLEND)
                    GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                }
                quad(externalProgram, texture, identity, floatArrayOf(-1f, -1f, 0f, 0f, 1f, 1f, -1f, 0f, 1f, 1f, -1f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 0f), external = true)
                GLES20.glDisable(GLES20.GL_BLEND)
                lensPass()
                return
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, cardboardFramebuffer)
            GLES20.glClearColor(.08f, .08f, .1f, 1f)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearStencil(0)
            GLES20.glClearDepthf(1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or (if (hasStencil) GLES20.GL_STENCIL_BUFFER_BIT else 0) or (if (hasDepth) GLES20.GL_DEPTH_BUFFER_BIT else 0))
            tracker.copyHead(head)
            Matrix.transposeM(worldToHead, 0, head, 0)
            // 6DoF: the world moves opposite to the head.
            val position = synchronized(headPosition) { headPosition.copyOf() }
            Matrix.translateM(worldToHead, 0, -position[0], -position[1], -position[2])
            val eyeWidth = width / 2
            val lenses = eyes
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            val eyeAspect = eyeWidth.toFloat() / height
            // Keep the pointer mapping in step with how the camera image is laid out below.
            if (tracker6 != null) {
                // ARCore landmarks are already placed on the eye's view; its projection sets the angles.
                viewScaleX = 1f / projection[0]
                viewScaleY = 1f / projection[5]
            } else if (eyeAspect < cameraAspect) {
                viewScaleX = cameraAspect
                viewScaleY = 1f
            } else {
                viewScaleX = eyeAspect
                viewScaleY = eyeAspect / cameraAspect
            }
            for (index in 0..1) {
                if (tracker6 != null) synchronized(tracker6.projection) { System.arraycopy(tracker6.projection, 0, projection, 0, 16) }
                else Matrix.perspectiveM(projection, 0, 90f, eyeWidth.toFloat() / height, .05f, 100f)
                GLES20.glViewport(index * eyeWidth, 0, eyeWidth, height)
                // Passthrough fills each eye; the camera image is cropped to the eye's shape.
                // A chosen place takes the room's stead and is drawn below, once the view is known.
                if (hasEnvironment) Unit
                else if (tracker6 != null && tracker6.hasUv) {
                    val uv = FloatArray(8)
                    synchronized(tracker6.passthroughUv) { tracker6.passthroughUv.position(0); tracker6.passthroughUv.get(uv); tracker6.passthroughUv.position(0) }
                    quad(externalProgram, arTexture, identity, floatArrayOf(
                        -1f, -1f, 0f, uv[0], uv[1], 1f, -1f, 0f, uv[2], uv[3], -1f, 1f, 0f, uv[4], uv[5], 1f, 1f, 0f, uv[6], uv[7]
                    ), external = true)
                } else if (hasCamera) {
                    val (u0, u1, v0, v1) = if (eyeAspect < cameraAspect) {
                        val span = eyeAspect / cameraAspect
                        listOf(.5f - span / 2, .5f + span / 2, 0f, 1f)
                    } else {
                        val span = cameraAspect / eyeAspect
                        listOf(0f, 1f, .5f - span / 2, .5f + span / 2)
                    }
                    quad(textureProgram, cameraTexture, identity, floatArrayOf(-1f, -1f, 0f, u0, v1, 1f, -1f, 0f, u1, v1, -1f, 1f, 0f, u0, v0, 1f, 1f, 0f, u1, v0))
                }
                Matrix.setIdentityM(eye, 0)
                Matrix.translateM(eye, 0, if (index == 0) lenses.halfIpd else -lenses.halfIpd, 0f, 0f)
                Matrix.multiplyMM(view, 0, eye, 0, worldToHead, 0)

                // The place around the user, behind everything else.
                Matrix.multiplyMM(mvp, 0, projection, 0, view, 0)
                if (hasEnvironment) environmentMesh?.let { mesh ->
                    GLES20.glUseProgram(textureProgram)
                    GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, environmentTexture)
                    GLES20.glUniform1i(GLES20.glGetUniformLocation(textureProgram, "uTexture"), 0)
                    GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(textureProgram, "uMvp"), 1, false, mvp, 0)
                    triangles(textureProgram, mesh)
                }
                // The room sliders: dim the room, then fog and rain in it, all under the home and windows.
                atmosphere(mvp, position)
                roomGrid(mvp)

                // Home icons, turned to where the panel hangs.
                val panelPlace = FloatArray(16)
                Matrix.setRotateM(panelPlace, 0, panelYaw, 0f, 1f, 0f)
                val panelMvp = FloatArray(16)
                Matrix.multiplyMM(panelMvp, 0, mvp, 0, panelPlace, 0)
                if (onboarding != null) {
                    val sw = PANEL_WIDTH / 2
                    val sh = sw * Onboarding.HEIGHT / Onboarding.WIDTH
                    val sz = -panelRadius
                    quad(textureProgram, onboardingTexture, panelMvp, floatArrayOf(-sw, -sh, sz, 0f, 1f, sw, -sh, sz, 1f, 1f, -sw, sh, sz, 0f, 0f, sw, sh, sz, 1f, 0f))
                } else if (panelVisible) {
                    // After setup the home flies in from a little further away and grows into place.
                    val appear = if (appearStart == 0L) 1f else ((SystemClock.elapsedRealtime() - appearStart) / 900f).coerceIn(0f, 1f)
                    val ease = 1f - (1f - appear) * (1f - appear) * (1f - appear)
                    val grow = .6f + .4f * ease
                    val pw = PANEL_WIDTH / 2 * grow
                    val ph = PANEL_HEIGHT / 2 * grow
                    val pz = -panelRadius - (1f - ease) * .9f
                    quad(externalProgram, panelTexture, panelMvp, floatArrayOf(-pw, -ph, pz, 0f, 1f, pw, -ph, pz, 1f, 1f, -pw, ph, pz, 0f, 0f, pw, ph, pz, 1f, 0f), external = true)
                }

                // Windows, focused last so it is on top.
                val ordered = windows.filter { !it.minimized }.sortedBy { it == focused }
                for (window in ordered) {
                    val texture = textures[window.id] ?: continue
                    Matrix.setRotateM(model, 0, window.yaw, 0f, 1f, 0f)
                    Matrix.translateM(model, 0, 0f, window.height, 0f)
                    Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
                    Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
                    val w = window.width / 2
                    val h = window.heightM / 2
                    val z = -windowRadius
                    val uv = window.content.uv(index)
                    val top = uv[1]
                    val bottom = uv[3]
                    if (window.arcDegrees > 0f) {
                        val program = if (window.content.external) externalProgram else textureProgram
                        triangles(program, texture, mvp, curvedWindow(window, h, uv), window.content.external)
                    } else {
                        val program = if (window.content.external) roundedExternalProgram else roundedProgram
                        GLES20.glUseProgram(program)
                        GLES20.glUniform2f(GLES20.glGetUniformLocation(program, "uHalf"), window.width / 2, window.heightM / 2)
                        GLES20.glUniform1f(GLES20.glGetUniformLocation(program, "uRadius"), CORNER_RADIUS)
                        quad(program, texture, mvp, floatArrayOf(-w, -h, z, uv[0], bottom, w, -h, z, uv[2], bottom, -w, h, z, uv[0], top, w, h, z, uv[2], top), window.content.external)
                    }
                    // The frame, as on Quest: the browser's bar on top, or a bar along the bottom
                    // (close, minimize, title, keyboard); and a handle under it to move the window.
                    val hovered = hit
                    val dragging = drag
                    val held = grab?.window == window || hovered is Hit.Bar && hovered.window == window || dragging?.window == window && !dragging.resize
                    val title = window.content.toolbarTitle()
                    if (window.content is BrowserContent && title != null) browserBar(window, title, mvp, w, h, z)
                    val lit = when {
                        hovered is Hit.Close && hovered.window == window -> "close"
                        hovered is Hit.Minimize && hovered.window == window -> "minimize"
                        hovered is Hit.Expand && hovered.window == window -> "expand"
                        hovered is Hit.Curve && hovered.window == window -> "curve"
                        hovered is Hit.KeyboardButton && hovered.window == window -> "keyboard"
                        hovered is Hit.Toolbar && hovered.window == window && hovered.u < 0f -> "back"
                        else -> null
                    }
                    val name = if (window.content is BrowserContent) window.title else title ?: window.title
                    pill(window, name, mvp, h, z, typing == window, lit, held)
                    // Held by the hand (or with the hand at its edge): a glowing white frame, as on Quest.
                    val grabbed = grab?.window == window
                    if (grabbed || grabHover == window) grabFrame(window, mvp, w, h, z, if (grabbed) 1f else .45f)
                    val handleY = frameBottom(window) - WindowChrome.HANDLE_GAP
                    if (window.id == "desktop") desktopControls(mvp, handleY - .12f, z)
                }
                val onTable = typing?.let { tableKeyboard(it) }
                if (onTable != null) {
                    // The keyboard lies on the remembered table, facing the user.
                    Matrix.setIdentityM(model, 0)
                    Matrix.translateM(model, 0, onTable[0], onTable[1], onTable[2])
                    Matrix.rotateM(model, 0, onTable[3], 0f, 1f, 0f)
                    Matrix.rotateM(model, 0, -90f, 1f, 0f, 0f)
                    Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
                    Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
                    val kw = TABLE_KEYBOARD_W / 2
                    val kh = TABLE_KEYBOARD_H / 2
                    quad(textureProgram, keyboardTexture, mvp, floatArrayOf(-kw, -kh, 0f, 0f, 1f, kw, -kh, 0f, 1f, 1f, -kw, kh, 0f, 0f, 0f, kw, kh, 0f, 1f, 0f))
                } else if (typing != null) {
                    Matrix.setRotateM(model, 0, typing.yaw, 0f, 1f, 0f)
                    Matrix.multiplyMM(modelView, 0, view, 0, model, 0)
                    Matrix.multiplyMM(mvp, 0, projection, 0, modelView, 0)
                    val kw = KEYBOARD_W / 2
                    val kh = KEYBOARD_H / 2
                    val ky = keyboardCenterY(typing)
                    val kz = -keyboardRadius
                    quad(textureProgram, keyboardTexture, mvp, floatArrayOf(-kw, ky - kh, kz, 0f, 1f, kw, ky - kh, kz, 1f, 1f, -kw, ky + kh, kz, 0f, 0f, kw, ky + kh, kz, 1f, 0f))
                }
                hand()
            }
            GLES20.glDisable(GLES20.GL_BLEND)
            lensPass()
        }

        /** Compensate for Cardboard lenses only after the complete stereo scene exists. */
        private fun lensPass() {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, width, height)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            GLES20.glUseProgram(cardboardProgram)
            // Each eye's picture moved in under its lens (see Eyes.lensShift).
            GLES20.glUniform1f(GLES20.glGetUniformLocation(cardboardProgram, "uLensShift"), eyes.lensShift)
            quad(cardboardProgram, cardboardTexture, identity, floatArrayOf(
                -1f, -1f, 0f, 0f, 0f,
                 1f, -1f, 0f, 1f, 0f,
                -1f,  1f, 0f, 0f, 1f,
                 1f,  1f, 0f, 1f, 1f,
            ))
            record()
        }

        // ---------------------------------------------------------------- recording (as on Quest)

        @Volatile var pendingRecorder: VideoRecorder? = null
        @Volatile var stopRecording: ((Boolean) -> Unit)? = null
        private var recorder: VideoRecorder? = null
        private var recordSurface: android.opengl.EGLSurface = android.opengl.EGL14.EGL_NO_SURFACE
        val recording get() = recorder != null || pendingRecorder != null

        /** The left eye, without the lens warp, into the video: what the user sees, flat. */
        private fun record() {
            val display = android.opengl.EGL14.eglGetCurrentDisplay()
            val context = android.opengl.EGL14.eglGetCurrentContext()
            pendingRecorder?.let { next ->
                pendingRecorder = null
                val id = IntArray(1)
                android.opengl.EGL14.eglQueryContext(display, context, android.opengl.EGL14.EGL_CONFIG_ID, id, 0)
                val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
                android.opengl.EGL14.eglChooseConfig(display, intArrayOf(android.opengl.EGL14.EGL_CONFIG_ID, id[0], android.opengl.EGL14.EGL_NONE), 0, configs, 0, 1, IntArray(1), 0)
                recordSurface = configs[0]?.let {
                    android.opengl.EGL14.eglCreateWindowSurface(display, it, next.surface, intArrayOf(android.opengl.EGL14.EGL_NONE), 0)
                } ?: android.opengl.EGL14.EGL_NO_SURFACE
                recorder = next
            }
            val active = recorder ?: return
            val draw = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_DRAW)
            val read = android.opengl.EGL14.eglGetCurrentSurface(android.opengl.EGL14.EGL_READ)
            if (recordSurface != android.opengl.EGL14.EGL_NO_SURFACE &&
                android.opengl.EGL14.eglMakeCurrent(display, recordSurface, recordSurface, context)) {
                GLES20.glViewport(0, 0, active.width, active.height)
                quad(textureProgram, cardboardTexture, identity, floatArrayOf(
                    -1f, -1f, 0f, 0f, 0f,
                     1f, -1f, 0f, .5f, 0f,
                    -1f,  1f, 0f, 0f, 1f,
                     1f,  1f, 0f, .5f, 1f,
                ))
                android.opengl.EGLExt.eglPresentationTimeANDROID(display, recordSurface, System.nanoTime())
                android.opengl.EGL14.eglSwapBuffers(display, recordSurface)
                android.opengl.EGL14.eglMakeCurrent(display, draw, read, context)
            }
            stopRecording?.let { done ->
                stopRecording = null
                if (recordSurface != android.opengl.EGL14.EGL_NO_SURFACE) android.opengl.EGL14.eglDestroySurface(display, recordSurface)
                recordSurface = android.opengl.EGL14.EGL_NO_SURFACE
                recorder = null
                Thread { done(active.stop()) }.start()
            }
        }

        /** visionOS-style corner arc at the bottom right: drag it to resize the window. */
        private fun resizeHandle(mvp: FloatArray, w: Float, h: Float, z: Float, active: Boolean) {
            val alpha = if (active) 1f else .7f
            val thick = if (active) .014f else .01f
            val points = ArrayList<Float>()
            val cx = w - CORNER_RADIUS
            val cy = -h + CORNER_RADIUS
            val r = CORNER_RADIUS + .035f
            for (i in 0..12) {
                val angle = Math.toRadians(-90.0 + i * 90.0 / 12).toFloat()
                val cos = kotlin.math.cos(angle)
                val sin = kotlin.math.sin(angle)
                for (radius in floatArrayOf(r - thick, r + thick)) {
                    points += listOf(cx + cos * radius, cy + sin * radius, z, 0f, 0f)
                }
            }
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 1f, 1f, 1f, alpha)
            val data = points.toFloatArray()
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, data.size / 5)
        }

        private fun desktopControls(mvp: FloatArray, y: Float, z: Float) {
            val w = .57f; val h = .075f
            quad(textureProgram, desktopControlsTexture, mvp, floatArrayOf(
                -w, y - h, z, 0f, 1f, w, y - h, z, 1f, 1f,
                -w, y + h, z, 0f, 0f, w, y + h, z, 1f, 0f,
            ))
        }

        /** Textured cylinder segment for the computer display, from flat through almost 360°. */
        private fun curvedWindow(window: VrWindow, halfHeight: Float, uv: FloatArray): FloatArray {
            val segments = 48
            val span = Math.toRadians((window.arcDegrees * window.widthScale).coerceAtMost(330f).toDouble()).toFloat()
            val out = ArrayList<Float>(segments * 30)
            fun point(step: Int, top: Boolean) {
                val s = step.toFloat() / segments
                val angle = (s - .5f) * span
                out += kotlin.math.sin(angle) * windowRadius
                out += if (top) halfHeight else -halfHeight
                out += -kotlin.math.cos(angle) * windowRadius
                out += uv[0] + (uv[2] - uv[0]) * s
                out += if (top) uv[1] else uv[3]
            }
            for (i in 0 until segments) {
                point(i, false); point(i + 1, false); point(i, true)
                point(i, true); point(i + 1, false); point(i + 1, true)
            }
            return out.toFloatArray()
        }

        /** Safari-style bar above a window: back, forward, address, reload. */
        /** Cached textures of the frames: the texture and what it was drawn for. */
        private val frameTextures = HashMap<String, Pair<Int, String>>()

        private fun frameTexture(key: String, stamp: String, draw: () -> Bitmap): Int {
            val cached = frameTextures[key]
            if (cached != null && cached.second == stamp) return cached.first
            val id = cached?.first ?: IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, id)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val bitmap = draw()
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
            bitmap.recycle()
            frameTextures[key] = id to stamp
            return id
        }

        /**
         * The white frame around a window being carried: a rounded outline a little outside it
         * (around its bar and pill too) with a soft glow.
         */
        private fun grabFrame(window: VrWindow, mvp: FloatArray, w: Float, h: Float, z: Float, alpha: Float) {
            val top = h + barHeight(window) + .04f
            val bottom = frameBottom(window) - .03f
            val right = w + .04f
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
            val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            val color = GLES20.glGetUniformLocation(colorProgram, "uColor")
            // Glow first (wide and faint), then the line.
            for ((thickness, strength) in listOf(.035f to .22f, .012f to .95f)) {
                val radius = .09f
                val outline = ArrayList<FloatArray>()
                // Corners counter-clockwise from the top right, each an arc.
                val corners = listOf(
                    floatArrayOf(right - radius, top - radius, 0f), floatArrayOf(-right + radius, top - radius, 90f),
                    floatArrayOf(-right + radius, bottom + radius, 180f), floatArrayOf(right - radius, bottom + radius, 270f),
                )
                for (corner in corners) for (k in 0..8) {
                    val a = Math.toRadians((corner[2] + k * 90f / 8).toDouble())
                    outline += floatArrayOf(corner[0], corner[1], kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
                }
                val data = ArrayList<Float>(outline.size * 18)
                for (i in outline.indices) {
                    val a = outline[i]; val b = outline[(i + 1) % outline.size]
                    fun o(p: FloatArray, r: Float) = floatArrayOf(p[0] + p[2] * r, p[1] + p[3] * r)
                    val ao = o(a, radius + thickness / 2); val ai = o(a, radius - thickness / 2)
                    val bo = o(b, radius + thickness / 2); val bi = o(b, radius - thickness / 2)
                    data.addAll(listOf(ai[0], ai[1], z, ao[0], ao[1], z, bo[0], bo[1], z, ai[0], ai[1], z, bo[0], bo[1], z, bi[0], bi[1], z))
                }
                GLES20.glUniform4f(color, 1f, 1f, 1f, strength * alpha)
                drawArray(data.toFloatArray(), GLES20.GL_TRIANGLES, position)
            }
        }

        /** The pill under a window, as on Quest: its title, and keyboard, expand, minimize, close. */
        private fun pill(window: VrWindow, title: String, mvp: FloatArray, h: Float, z: Float, keyboardOn: Boolean, lit: String?, held: Boolean) {
            val pill = WindowChrome.pillWidth(window.width) * (if (held) 1.04f else 1f)
            val back = pillBack(window)
            val big = expanded(window)
            val curved = window.arcDegrees > 0f
            val texture = frameTexture("pill:${window.id}", "$title|$back|$keyboardOn|$big|$curved|$lit|${(pill * 100).toInt()}") {
                WindowChrome.drawPill(title, WindowChrome.pillWidth(window.width), back, keyboardOn, big, curved, lit)
            }
            val top = -h - WindowChrome.PILL_GAP; val bottom = top - WindowChrome.PILL_H
            val pw = pill / 2
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            quad(textureProgram, texture, mvp, floatArrayOf(-pw, bottom, z, 0f, 1f, pw, bottom, z, 1f, 1f, -pw, top, z, 0f, 0f, pw, top, z, 1f, 0f))
        }

        /** The browser's bar on top: title, tabs, navigation and the address. */
        private fun browserBar(window: VrWindow, title: String, mvp: FloatArray, w: Float, h: Float, z: Float) {
            val dark = Ui.dark
            val tabs = window.content.toolbarTabs()
            val active = window.content.toolbarTab
            val texture = frameTexture("bar:${window.id}", "${window.content.toolbarVersion}|$title|$tabs|$active|$dark") {
                WindowChrome.drawBar(title, tabs, active, dark)
            }
            val top = h + barHeight(window)
            quad(textureProgram, texture, mvp, floatArrayOf(-w, h, z, 0f, 1f, w, h, z, 1f, 1f, -w, top, z, 0f, 0f, w, top, z, 1f, 0f))
        }

        /**
         * The user's hands as see-through white shapes over the passthrough, and the cursor on the
         * aim point. Drawn in head space with the passthrough's own mapping, so they sit on the real hands.
         */
        /** A message fixed in front of the eyes at height [y] (head space, tangent units). */
        private fun banner(texture: Int, y: Float) {
            val head = FloatArray(16)
            Matrix.multiplyMM(head, 0, projection, 0, eye, 0)
            val w = .62f; val h = w * 180f / 1400f
            quad(textureProgram, texture, head, floatArrayOf(-w, y - h, -1f, 0f, 1f, w, y - h, -1f, 1f, 1f, -w, y + h, -1f, 0f, 0f, w, y + h, -1f, 1f, 0f))
        }

        private fun drawArray(data: FloatArray, mode: Int, location: Int) {
            if (data.isEmpty()) return
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            GLES20.glVertexAttribPointer(location, 3, GLES20.GL_FLOAT, false, 12, buffer)
            GLES20.glEnableVertexAttribArray(location)
            GLES20.glDrawArrays(mode, 0, data.size / 3)
        }

        private var handMenuTexture = 0

        /**
         * The hand menu, the user's real hands cut out of the camera picture on top of everything
         * (so they are never hidden behind a window), and the cursor on the aim point.
         */
        private fun hand() {
            Matrix.multiplyMM(mvp, 0, projection, 0, eye, 0)
            handMenu?.let { menu -> if (!menu.x.isNaN()) drawHandMenu(menu) }
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDepthMask(true)
            Matrix.multiplyMM(mvp, 0, projection, 0, eye, 0)
            GLES20.glUseProgram(colorProgram)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(colorProgram, "uMvp"), 1, false, mvp, 0)
            val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            drawHandSkeletons(position)
            // The cursor: the hand's aim point.
            mouseCursor(position)
            drawBeam(position)
            val point = pinchPoint ?: return
            val x = (point[0] - .5f) * 2f * viewScaleX
            val y = (.5f - point[1]) * 2f * viewScaleY
            val r = if (pressing) .010f else .016f
            // A ring cursor: dark edge, white centre, readable over any background.
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 0f, 0f, 0f, .45f)
            disc(x, y, r * 1.45f, position)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 1f, 1f, 1f, 1f)
            disc(x, y, r, position)
        }

        /**
         * The pointer as on Quest: no line — a small white drop by the hand, pointing where the ray
         * goes (shorter and rounder while pinching), and a thin hollow ring where the ray meets the
         * home or a window. The ring inverts what is under it, so it shows on white pages too.
         * The drop sits at the hands' depth and the ring at the target's, so neither doubles.
         */
        private fun drawBeam(position: Int) {
            if (mouseActive()) return
            otherBeam?.let { drawBeam(it, false, position) }
            beam?.let { drawBeam(it, pressing, position) }
        }

        /**
         * A hand's pointer, as on Quest: by the hand a big white drop (about a little finger's size)
         * pointing where the ray goes; while clicking it stretches into a stick, and only then a
         * line runs from it towards the target. A small ring shows where the ray meets the home or
         * a window. The drop sits on the hands' plane (1 m) and the ring at the target's depth, so
         * neither doubles.
         */
        private fun drawBeam(b: FloatArray, down: Boolean, position: Int) {
            val color = GLES20.glGetUniformLocation(colorProgram, "uColor")
            if (b[2] < -.05f) {
                val hx = b[0] / -b[2]; val hy = b[1] / -b[2]
                val tx = b[3] / -b[5]; val ty = b[4] / -b[5]
                var dx = tx - hx; var dy = ty - hy
                val l = kotlin.math.sqrt(dx * dx + dy * dy)
                if (l > 1e-4f) { dx /= l; dy /= l } else { dx = 0f; dy = 1f }
                val shape = ArrayList<Float>(96 * 3)
                val sx: Float; val sy: Float
                if (!down) {
                    // The drop: a round end at the hand and a point towards the target.
                    val r = CURSOR_R
                    val tip = CURSOR_R * .9f
                    handDisc(shape, hx, hy, r)
                    shape.addAll(listOf(hx - dy * r, hy + dx * r, -1f, hx + dy * r, hy - dx * r, -1f, hx + dx * (r + tip), hy + dy * (r + tip), -1f))
                    GLES20.glUniform4f(color, .95f, .95f, .97f, .9f)
                    drawArray(shape.toFloatArray(), GLES20.GL_TRIANGLES, position)
                    sx = hx; sy = hy
                } else {
                    // Clicking: a stick along the ray, rounded at both ends.
                    val w = CURSOR_R * .45f
                    val len = CURSOR_R * 2f
                    val ex = hx + dx * len; val ey = hy + dy * len
                    handBone(shape, hx, hy, ex, ey, w, w)
                    handDisc(shape, hx, hy, w)
                    handDisc(shape, ex, ey, w)
                    GLES20.glUniform4f(color, 1f, 1f, 1f, .95f)
                    drawArray(shape.toFloatArray(), GLES20.GL_TRIANGLES, position)
                    sx = ex; sy = ey
                }
                if (down) {
                    // The line, only while clicking: from the stick's end, fading out before the target.
                    val sz = -1f
                    val ex = b[3]; val ey = b[4]; val ez = b[5]
                    fun across(px: Float, py: Float, pz: Float, width: Float): FloatArray {
                        val lx = ex - sx; val ly = ey - sy; val lz = ez - sz
                        var cx = ly * pz - lz * py; var cy = lz * px - lx * pz; var cz = lx * py - ly * px
                        val cl = kotlin.math.sqrt(cx * cx + cy * cy + cz * cz).coerceAtLeast(1e-6f)
                        cx = cx / cl * width; cy = cy / cl * width; cz = cz / cl * width
                        return floatArrayOf(cx, cy, cz)
                    }
                    val segments = 10
                    for (k in 0 until segments) {
                        val t0 = k / segments.toFloat() * RAY_SHOWN; val t1 = (k + 1) / segments.toFloat() * RAY_SHOWN
                        val ax = sx + (ex - sx) * t0; val ay = sy + (ey - sy) * t0; val az = sz + (ez - sz) * t0
                        val bx = sx + (ex - sx) * t1; val by = sy + (ey - sy) * t1; val bz = sz + (ez - sz) * t1
                        val wa = across(ax, ay, az, .006f)
                        val wb = across(bx, by, bz, .006f)
                        GLES20.glUniform4f(color, 1f, 1f, 1f, .85f * (1f - t0 / RAY_SHOWN * .9f))
                        drawArray(floatArrayOf(
                            ax - wa[0], ay - wa[1], az - wa[2], ax + wa[0], ay + wa[1], az + wa[2], bx + wb[0], by + wb[1], bz + wb[2],
                            ax - wa[0], ay - wa[1], az - wa[2], bx + wb[0], by + wb[1], bz + wb[2], bx - wb[0], by - wb[1], bz - wb[2],
                        ), GLES20.GL_TRIANGLES, position)
                    }
                }
            }
            if (b[6] < .5f) return
            // The ring: small, white inverted over what is behind it, empty inside.
            val depth = -b[5]
            // A small point for precise aiming, a little smaller still while pressing.
            val outer = (if (down || touching) .009f else .012f) * depth
            val inner = outer - .005f * depth
            GLES20.glBlendFunc(GLES20.GL_ONE_MINUS_DST_COLOR, GLES20.GL_ZERO)
            GLES20.glUniform4f(color, 1f, 1f, 1f, 1f)
            ring3d(b[3], b[4], b[5], outer, inner, position)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }

        /** A thin ring facing the eye at a point in head space, from [inner] to [outer] radius. */
        private fun ring3d(x: Float, y: Float, z: Float, outer: Float, inner: Float, position: Int) {
            val steps = 40
            val data = FloatArray(steps * 18)
            for (k in 0 until steps) {
                val a0 = (k * 2 * Math.PI / steps).toFloat(); val a1 = ((k + 1) * 2 * Math.PI / steps).toFloat()
                val c0 = kotlin.math.cos(a0); val s0 = kotlin.math.sin(a0); val c1 = kotlin.math.cos(a1); val s1 = kotlin.math.sin(a1)
                val o = k * 18
                floatArrayOf(
                    x + c0 * inner, y + s0 * inner, z, x + c0 * outer, y + s0 * outer, z, x + c1 * outer, y + s1 * outer, z,
                    x + c0 * inner, y + s0 * inner, z, x + c1 * outer, y + s1 * outer, z, x + c1 * inner, y + s1 * inner, z,
                ).copyInto(data, o)
            }
            drawArray(data, GLES20.GL_TRIANGLES, position)
        }

        /**
         * The mouse pointer, an arrow in the air: along the mouse's direction, as far away as the
         * windows, so it sits on what it points at.
         */
        private fun mouseCursor(position: Int) {
            if (!mouseActive()) return
            val direction = mouseDirection() ?: return
            val far = floatArrayOf(direction[0] * windowRadius, direction[1] * windowRadius, direction[2] * windowRadius, 0f)
            val local = FloatArray(4)
            // Rotation only: the pointer starts at the eyes, wherever the head is.
            Matrix.multiplyMV(local, 0, worldToHead, 0, far, 0)
            if (local[2] >= -.05f) return
            val x = local[0] / -local[2]
            val y = local[1] / -local[2]
            val size = if (mouseDown) .026f else .03f
            fun arrow(grow: Float) = floatArrayOf(
                x - grow * .6f, y + grow, -1f,
                x - grow * .6f, y - size - grow * 1.6f, -1f,
                x + size * .72f + grow * 1.6f, y - size * .62f - grow, -1f,
            )
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 0f, 0f, 0f, .7f)
            drawArray(arrow(.004f), GLES20.GL_TRIANGLES, position)
            GLES20.glUniform4f(GLES20.glGetUniformLocation(colorProgram, "uColor"), 1f, 1f, 1f, 1f)
            drawArray(arrow(0f), GLES20.GL_TRIANGLES, position)
        }

        /**
         * The tracked hands as Horizon-style hands over the real ones: a see-through dark silhouette
         * with a thin light outline, cut at the wrist. The outline turns white and bolder while a pinch
         * clicks. With a stencil every pixel of a hand is drawn once, so the see-through fill is even.
         */
        private fun drawHandSkeletons(position: Int) {
            val hands = handPoints
            if (hands.isEmpty()) return
            val color = GLES20.glGetUniformLocation(colorProgram, "uColor")
            val pinching = pinch.pinching
            if (hasStencil) {
                // A pixel passes only while its stencil is 0, and is then marked 1: drawn once.
                GLES20.glEnable(GLES20.GL_STENCIL_TEST)
                GLES20.glStencilMask(0xFF)
                GLES20.glStencilFunc(GLES20.GL_GREATER, 1, 0xFF)
                GLES20.glStencilOp(GLES20.GL_KEEP, GLES20.GL_KEEP, GLES20.GL_REPLACE)
            }
            for (hand in hands) {
                fun x(i: Int) = (hand[i * 3] - .5f) * 2f * viewScaleX
                fun y(i: Int) = (.5f - hand[i * 3 + 1]) * 2f * viewScaleY
                val palm = hypot(x(9) - x(0), y(9) - y(0))
                if (palm < 1e-4f) continue
                val r = palm * .12f
                val edge = if (pinching) maxOf(palm * .035f, .003f) else maxOf(palm * .018f, .0016f)
                // With a stencil: fill first (it marks its pixels), then the outline only where the
                // fill is not. Without one: the outline first and the solid fill over it.
                for (pass in 0..1) {
                    val outline = if (hasStencil) pass == 1 else pass == 0
                    val grow = if (outline) edge else 0f
                    val shape = ArrayList<Float>(4096)
                    fun disc(i: Int, radius: Float) = handDisc(shape, x(i), y(i), radius + grow)
                    fun bone(i: Int, j: Int, ri: Float, rj: Float) {
                        handBone(shape, x(i), y(i), x(j), y(j), ri + grow, rj + grow)
                        disc(i, ri); disc(j, rj)
                    }
                    // The palm: a fan over its outline, its sides rounded by bones.
                    val ring = intArrayOf(0, 1, 2, 5, 9, 13, 17)
                    var cx = 0f; var cy = 0f
                    for (i in ring) { cx += x(i); cy += y(i) }
                    cx /= ring.size; cy /= ring.size
                    for (k in ring.indices) {
                        val i = ring[k]; val j = ring[(k + 1) % ring.size]
                        shape.addAll(listOf(cx, cy, -1f, x(i), y(i), -1f, x(j), y(j), -1f))
                    }
                    bone(0, 1, r * 1.1f, r * 1.15f)
                    bone(0, 17, r * 1.1f, r * .9f)
                    bone(2, 5, r * .7f, r * 1f)
                    bone(5, 9, r, r); bone(9, 13, r, r); bone(13, 17, r * .95f, r * .9f)
                    // Thumb and fingers, thinner towards the tips.
                    val scale = floatArrayOf(1f, 1f, 1.03f, .97f, .85f)
                    for (finger in 0 until 5) {
                        val base = 1 + finger * 4
                        val f = scale[finger]
                        if (finger == 0) {
                            bone(1, 2, r * 1.15f, r * 1.02f); bone(2, 3, r * 1.02f, r * .95f); bone(3, 4, r * .95f, r * .82f)
                        } else {
                            bone(base, base + 1, r * f, r * .92f * f)
                            bone(base + 1, base + 2, r * .92f * f, r * .85f * f)
                            bone(base + 2, base + 3, r * .85f * f, r * .76f * f)
                        }
                    }
                    when {
                        outline && pinching -> GLES20.glUniform4f(color, 1f, 1f, 1f, 1f)
                        outline -> GLES20.glUniform4f(color, .78f, .79f, .84f, .9f)
                        // Without a stencil overlaps would darken, so the fill is then solid.
                        else -> GLES20.glUniform4f(color, .09f, .09f, .16f, if (hasStencil) .62f else 1f)
                    }
                    drawArray(shape.toFloatArray(), GLES20.GL_TRIANGLES, position)
                }
            }
            if (hasStencil) GLES20.glDisable(GLES20.GL_STENCIL_TEST)
        }

        /** Rain drops around the user: x, z, where in the fall, speed. */
        private val drops = FloatArray(RAIN_DROPS * 4).also { d ->
            val random = java.util.Random(7)
            for (i in 0 until RAIN_DROPS) {
                val angle = random.nextFloat() * 2f * Math.PI.toFloat()
                val distance = .6f + random.nextFloat() * 4.4f
                d[i * 4] = kotlin.math.cos(angle) * distance
                d[i * 4 + 1] = kotlin.math.sin(angle) * distance
                d[i * 4 + 2] = random.nextFloat()
                d[i * 4 + 3] = 5f + random.nextFloat() * 3f
            }
        }

        private val fullView = floatArrayOf(-1f, -1f, 0f, 1f, -1f, 0f, -1f, 1f, 0f, 1f, -1f, 0f, 1f, 1f, 0f, -1f, 1f, 0f)

        /**
         * Room brightness, fog and rain from the sliders under the worlds: a dark veil, a pale haze
         * over the whole view, and streaks of rain falling around the user ([head] is where the
         * head is in the room, so the rain stays around them in 6DoF).
         */
        private fun atmosphere(world: FloatArray, head: FloatArray) {
            val dim = 1f - roomBrightness
            val fog = fogLevel
            val rain = rainLevel
            if (dim < .01f && fog < .01f && rain < .01f) return
            val depth = GLES20.glIsEnabled(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            GLES20.glUseProgram(colorProgram)
            val color = GLES20.glGetUniformLocation(colorProgram, "uColor")
            val matrix = GLES20.glGetUniformLocation(colorProgram, "uMvp")
            val position = GLES20.glGetAttribLocation(colorProgram, "aPosition")
            if (dim > .01f) {
                GLES20.glUniformMatrix4fv(matrix, 1, false, identity, 0)
                GLES20.glUniform4f(color, 0f, 0f, 0f, dim)
                drawArray(fullView, GLES20.GL_TRIANGLES, position)
            }
            if (rain > .01f) {
                val count = (RAIN_DROPS * rain).toInt().coerceAtLeast(1)
                val seconds = (SystemClock.elapsedRealtime() % 100_000L) / 1000f
                val lines = FloatArray(count * 6)
                for (i in 0 until count) {
                    val x = head[0] + drops[i * 4]
                    val z = head[2] + drops[i * 4 + 1]
                    val fall = ((drops[i * 4 + 2] + seconds * drops[i * 4 + 3] / RAIN_HEIGHT) % 1f)
                    val y = head[1] + 2.2f - fall * RAIN_HEIGHT
                    lines[i * 6] = x; lines[i * 6 + 1] = y; lines[i * 6 + 2] = z
                    lines[i * 6 + 3] = x; lines[i * 6 + 4] = y - .22f; lines[i * 6 + 5] = z
                }
                GLES20.glUniformMatrix4fv(matrix, 1, false, world, 0)
                GLES20.glUniform4f(color, .82f, .87f, .95f, .35f + .3f * rain)
                GLES20.glLineWidth(3f)
                drawArray(lines, GLES20.GL_LINES, position)
                // Rain also greys the air a little.
                GLES20.glUniformMatrix4fv(matrix, 1, false, identity, 0)
                GLES20.glUniform4f(color, .3f, .33f, .38f, .18f * rain)
                drawArray(fullView, GLES20.GL_TRIANGLES, position)
            }
            if (fog > .01f) {
                GLES20.glUniformMatrix4fv(matrix, 1, false, identity, 0)
                GLES20.glUniform4f(color, .8f, .82f, .85f, .72f * fog)
                drawArray(fullView, GLES20.GL_TRIANGLES, position)
            }
            if (depth) GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        }

        /** A filled circle as triangles, added to [out]. */
        private fun handDisc(out: ArrayList<Float>, x: Float, y: Float, r: Float) {
            val segments = 14
            for (k in 0 until segments) {
                val a0 = (k * 2 * Math.PI / segments).toFloat()
                val a1 = ((k + 1) * 2 * Math.PI / segments).toFloat()
                out.addAll(listOf(
                    x, y, -1f,
                    x + kotlin.math.cos(a0) * r, y + kotlin.math.sin(a0) * r, -1f,
                    x + kotlin.math.cos(a1) * r, y + kotlin.math.sin(a1) * r, -1f,
                ))
            }
        }

        /** A tapered band from (x1, y1) to (x2, y2), [r1] and [r2] wide on either side, added to [out]. */
        private fun handBone(out: ArrayList<Float>, x1: Float, y1: Float, x2: Float, y2: Float, r1: Float, r2: Float) {
            val length = hypot(x2 - x1, y2 - y1)
            if (length < 1e-6f) return
            val nx = -(y2 - y1) / length
            val ny = (x2 - x1) / length
            val ax = x1 + nx * r1; val ay = y1 + ny * r1
            val bx = x1 - nx * r1; val by = y1 - ny * r1
            val cx = x2 + nx * r2; val cy = y2 + ny * r2
            val dx = x2 - nx * r2; val dy = y2 - ny * r2
            out.addAll(listOf(ax, ay, -1f, bx, by, -1f, cx, cy, -1f, bx, by, -1f, dx, dy, -1f, cx, cy, -1f))
        }

        /** Where a point of the eye's view (0..1, y down) is in ARCore's camera texture. */
        private fun arUv(c: FloatArray, u: Float, v: Float): FloatArray {
            val s = u; val t = 1f - v
            val bottomU = c[0] + (c[2] - c[0]) * s; val bottomV = c[1] + (c[3] - c[1]) * s
            val topU = c[4] + (c[6] - c[4]) * s; val topV = c[5] + (c[7] - c[5]) * s
            return floatArrayOf(bottomU + (topU - bottomU) * t, bottomV + (topV - bottomV) * t)
        }

        private fun drawHandMenu(menu: HandMenu) {
            if (handMenuTexture == 0) {
                handMenuTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handMenuTexture)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
                menu.dirty = true
            }
            if (menu.dirty || menuShown !== menu) {
                menu.draw()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, handMenuTexture)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, menu.bitmap, 0)
                menuShown = menu
            }
            val w = HandMenu.HALF_W; val h = HandMenu.HALF_H
            quad(textureProgram, handMenuTexture, mvp, floatArrayOf(
                menu.x - w, menu.y - h, -1f, 0f, 1f, menu.x + w, menu.y - h, -1f, 1f, 1f,
                menu.x - w, menu.y + h, -1f, 0f, 0f, menu.x + w, menu.y + h, -1f, 1f, 0f
            ))
        }

        private var menuShown: HandMenu? = null

        private fun triangles(program: Int, texture: Int, matrix: FloatArray, data: FloatArray, external: Boolean = false) {
            if (data.isEmpty()) return
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, matrix, 0)
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            val uv = GLES20.glGetAttribLocation(program, "aUv")
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, data.size / 5)
        }

        /** The buttons on palms turned to the face: a white disc, ∞ on the right hand, ☰ on the left. */
        private fun drawPalmButtons(position: Int) {
            val color = GLES20.glGetUniformLocation(colorProgram, "uColor")
            for (button in palmButtons) {
                val r = button.radius
                GLES20.glUniform4f(color, 0f, 0f, 0f, .35f)
                disc(button.x, button.y - r * .08f, r * 1.12f, position)
                GLES20.glUniform4f(color, 1f, 1f, 1f, .96f)
                disc(button.x, button.y, r, position)
                GLES20.glUniform4f(color, .12f, .12f, .14f, 1f)
                if (button.left) {
                    for (k in -1..1) {
                        val y = button.y + k * r * .32f
                        drawArray(floatArrayOf(
                            button.x - r * .45f, y - r * .07f, -1f, button.x + r * .45f, y - r * .07f, -1f, button.x + r * .45f, y + r * .07f, -1f,
                            button.x - r * .45f, y - r * .07f, -1f, button.x + r * .45f, y + r * .07f, -1f, button.x - r * .45f, y + r * .07f, -1f,
                        ), GLES20.GL_TRIANGLES, position)
                    }
                } else {
                    // ∞: two rings side by side.
                    for (side in floatArrayOf(-1f, 1f)) {
                        GLES20.glUniform4f(color, .12f, .12f, .14f, 1f)
                        disc(button.x + side * r * .3f, button.y, r * .32f, position)
                        GLES20.glUniform4f(color, 1f, 1f, 1f, 1f)
                        disc(button.x + side * r * .3f, button.y, r * .17f, position)
                    }
                }
            }
        }

        private fun disc(x: Float, y: Float, r: Float, position: Int) {
            val data = FloatArray(3 * 18) { i ->
                val k = i / 3
                when (i % 3) {
                    0 -> if (k == 0) x else x + kotlin.math.cos(((k - 1) * 2 * Math.PI / 16).toFloat()) * r
                    1 -> if (k == 0) y else y + kotlin.math.sin(((k - 1) * 2 * Math.PI / 16).toFloat()) * r
                    else -> -1f
                }
            }
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 12, buffer)
            GLES20.glEnableVertexAttribArray(position)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN, 0, 18)
        }

        private fun quad(program: Int, texture: Int, matrix: FloatArray, data: FloatArray, external: Boolean = false) {
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(if (external) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, texture)
            GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
            GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(program, "uMvp"), 1, false, matrix, 0)
            draw(program, data, true)
        }

        private var captureBuffer = 0
        private var captureTexture = 0
        private var captureSize = 0 to 0

        /** A window's picture read back from its texture (for the Mac remote), at most [maxWidth] wide. */
        fun capture(window: VrWindow, maxWidth: Int): Bitmap? {
            val texture = textures[window.id] ?: return null
            val w = minOf(maxWidth, window.content.pixelWidth)
            val h = (w.toLong() * window.content.pixelHeight / window.content.pixelWidth).toInt().coerceAtLeast(1)
            if (captureSize != w to h) {
                if (captureTexture != 0) GLES20.glDeleteTextures(1, intArrayOf(captureTexture), 0)
                if (captureBuffer != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(captureBuffer), 0)
                captureTexture = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, captureTexture)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
                captureBuffer = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureBuffer)
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, captureTexture, 0)
                captureSize = w to h
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, captureBuffer)
            GLES20.glViewport(0, 0, w, h)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
            val external = window.content.external
            quad(if (external) externalProgram else textureProgram, texture, identity,
                floatArrayOf(-1f, -1f, 0f, 0f, 1f, 1f, -1f, 0f, 1f, 1f, -1f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 0f), external)
            val pixels = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            val raw = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            pixels.rewind()
            raw.copyPixelsFromBuffer(pixels)
            // GL reads from the bottom row up.
            val upright = Bitmap.createBitmap(raw, 0, 0, w, h, android.graphics.Matrix().apply { preScale(1f, -1f) }, false)
            if (upright !== raw) raw.recycle()
            return upright
        }

        /** Draws a mesh of textured triangles, which a quad's two-triangle strip cannot hold. */
        private fun triangles(program: Int, data: FloatArray) {
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            val uv = GLES20.glGetAttribLocation(program, "aUv")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            buffer.position(3)
            GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(uv)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, data.size / 5)
        }

        private fun draw(program: Int, data: FloatArray, textured: Boolean) {
            val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(data)
            val position = GLES20.glGetAttribLocation(program, "aPosition")
            buffer.position(0)
            GLES20.glVertexAttribPointer(position, 3, GLES20.GL_FLOAT, false, 20, buffer)
            GLES20.glEnableVertexAttribArray(position)
            if (textured) {
                val uv = GLES20.glGetAttribLocation(program, "aUv")
                buffer.position(3)
                GLES20.glVertexAttribPointer(uv, 2, GLES20.GL_FLOAT, false, 20, buffer)
                GLES20.glEnableVertexAttribArray(uv)
            }
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }

        /** Atlas: minimize button, move bar, close button, keyboard button (visionOS window controls). */
        private fun drawDesktopControls(): Bitmap {
            val bitmap = Bitmap.createBitmap(900, 120, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            val look = Glass(Ui.dark)
            look.fill(paint, 0f, 120f)
            canvas.drawRoundRect(RectF(0f, 0f, 900f, 120f), 60f, 60f, paint)
            paint.shader = null
            paint.color = look.faint
            canvas.drawRect(299f, 18f, 301f, 102f, paint); canvas.drawRect(599f, 18f, 601f, 102f, paint)
            paint.color = look.ink; paint.textAlign = Paint.Align.CENTER; paint.textSize = 54f
            canvas.drawText("− ширина", 150f, 78f, paint)
            canvas.drawText("+ ширина", 450f, 78f, paint)
            canvas.drawText("изгиб 360°", 750f, 78f, paint)
            return bitmap
        }

    }

    /** The Horizon glass for the canvas-drawn bits of windows: white or dark, with ink. */
    private class Glass(dark: Boolean) {
        val top = if (dark) Color.argb(240, 43, 47, 54) else Color.argb(247, 255, 255, 255)
        val bottom = if (dark) Color.argb(240, 29, 32, 38) else Color.argb(247, 242, 242, 242)
        val ink = if (dark) Color.rgb(242, 242, 242) else Color.rgb(39, 39, 39)
        val faint = if (dark) Color.argb(30, 242, 242, 242) else Color.argb(26, 39, 39, 39)
        val edge = if (dark) Color.argb(50, 255, 255, 255) else Color.argb(20, 39, 39, 39)
        fun fill(paint: Paint, from: Float, to: Float) {
            // A shader still takes the paint's alpha: full, or the glass turns see-through.
            paint.color = Color.WHITE
            paint.shader = android.graphics.LinearGradient(0f, from, 0f, to, top, bottom, android.graphics.Shader.TileMode.CLAMP)
        }
    }

    companion object {
        private const val TAG = "PhoneXR-Home"
        const val MINECRAFT = "com.mojang.minecraftpe"
        private const val ID_BROWSER = "own:browser"
        private const val ID_STORE = "own:store"
        private const val SHARED_WINDOW = "shared"
        private const val EGG_WINDOW = "egg"
        private const val ID_INSTAGRAM = "own:instagram"
        private const val ID_DISCORD = "own:discord"
        private const val ID_PEOPLE = "own:people"
        private const val ID_PHOTOS = "own:photos"
        private const val ID_MINECRAFT = "own:minecraft"
        private const val MENU_RECENTER = "menu:recenter"
        private const val MENU_HOME = "menu:home"
        private const val MENU_EXIT = "menu:exit"
        private const val MENU_PHOTO = "menu:photo"
        private const val MENU_MUTE = "menu:mute"
        private const val MENU_RECORD = "menu:record"
        private const val MENU_PASSTHROUGH = "menu:passthrough"
        private const val MENU_TOGGLE_APPS = "menu:toggle-apps"
        private const val ID_DESKTOP = "desktop"
        /** MediaPipe hand bones as pairs of landmark indices. */
        /** Grab zone of a window: this far inside its left/right edge and this far outside. */
        private const val SIDE_INSIDE = .06f
        private const val SIDE_OUTSIDE = .16f
        private const val ID_SETTINGS = "own:settings"
        private const val ID_ANDROID = "own:android"
        /** Where the hand rays come from, as seen on the view (tangents): the shoulders, low down. */
        private const val SHOULDER_X = .45f
        private const val SHOULDER_Y = -1f
        /** How far past the hand the ray leads, as a share of the shoulder–hand line. */
        private const val RAY_LEAD = .25f
        /** How much of the way to its target the drawn ray goes before it has faded out. */
        private const val RAY_SHOWN = .55f
        private const val NECK_UP = .075f
        private const val NECK_FORWARD = .08f
        /** Rain and fog below this share of their sliders are off. */
        private const val OFF_BELOW = .05f
        /** Radius of the cursor by the hand, on the hands' plane (about a little finger's width). */
        private const val CURSOR_R = .016f
        /** Direct touch: NEAR within this of a window's plane (metres), FAR again past [NEAR_EXIT]. */
        private const val NEAR_DISTANCE = .15f
        /** Farther than this from the eyes a fingertip never touches a window directly, metres. */
        private const val ARM_REACH = .7f
        private const val NEAR_EXIT = .20f
        /** How far a finger may go through a window and still be touching it. */
        private const val NEAR_BEHIND = .12f
        /** How far outside a window's edges NEAR starts, and how far it lasts. */
        private const val NEAR_MARGIN_IN = .03f
        private const val NEAR_MARGIN_OUT = .07f
        /** The finger is down within this of the surface, and up again only past [TOUCH_OUT]. */
        private const val TOUCH_IN = .012f
        private const val TOUCH_OUT = .035f
        /** How much of each new fingertip reading is taken (the rest is the smoothed value). */
        private const val TOUCH_SMOOTHING = .45f
        /** Radians of pointer turn per mouse count. */
        private const val MOUSE_SPEED = .0022f
        /** After this long without the mouse, the hands point again. */
        private const val MOUSE_IDLE_MS = 4000L
        private const val ROOM_PREFS = "vr_room"
        private const val RAIN_DROPS = 700
        /** Rain falls through this many metres around the head. */
        private const val RAIN_HEIGHT = 4.4f
        private const val KEYBOARD_W = .78f
        private val KEYBOARD_H = KEYBOARD_W * KeyboardPanel.HEIGHT / KeyboardPanel.WIDTH
        private const val KEYBOARD_RADIUS = .75f
        /** The keyboard on a table: about the size of a real one. */
        private const val TABLE_KEYBOARD_W = .5f
        private val TABLE_KEYBOARD_H = TABLE_KEYBOARD_W * KeyboardPanel.HEIGHT / KeyboardPanel.WIDTH
        private const val MIN_SCALE = .45f
        /** A window made bigger with the pill's expand button. */
        private const val EXPANDED_SCALE = 1.5f
        private const val MAX_SCALE = 2.2f
        private val recent = ArrayList<String>()

        /** How far the head may turn before the panel starts to follow, and how fast it catches up. */
        private const val PANEL_DEAD_ZONE = 16f
        private const val PANEL_CATCH_UP = .06f
        /** Car mode: how far the head may look away before the view follows, and how gently. */
        private const val CAR_DEAD_ZONE = 22f
        private const val CAR_CATCH_UP = .035f
        private const val PANEL_SWIPE_SLOP = .04f
        private const val PANEL_SWIPE_THRESHOLD = .12f
        private const val PANEL_RADIUS = 1.7f
        /** See [distanceScale]. */
        private const val SIX_DOF_DISTANCE = 1.28f
        private const val THREE_DOF_DISTANCE = .8f
        private const val PANEL_WIDTH = 2.2f
        private val PANEL_HEIGHT = PANEL_WIDTH * HomePanel.HEIGHT / HomePanel.WIDTH
        private const val CORNER_RADIUS = .06f

        private const val TEXTURE_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() { vUv = aUv; gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val TEXTURE_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
        /**
         * Pre-warps each half of the stereo texture so a simple Cardboard lens straightens it.
         * Separate RGB radii also remove most of the coloured fringe near inexpensive lens edges.
         */
        /**
         * Cardboard lenses: the same gentle barrel warp around each eye's centre that PhoneXR always
         * used (its halves line up in the viewer), but each eye keeps its rectangle — no round mask.
         */
        private const val CARDBOARD_FRAGMENT = """
            precision highp float;
            uniform sampler2D uTexture;
            // How far each eye's picture moves towards the middle of the screen, in half-screen widths.
            uniform float uLensShift;
            varying vec2 vUv;

            // The lens warp around the lens centre [centre] (in the eye's half of the screen).
            vec2 sourceUv(vec2 eye, vec2 centre, float amount) {
                vec2 p = (eye - centre) * 2.0;
                float r2 = dot(p, p);
                p *= 1.0 + amount * r2 + 0.06 * r2 * r2;
                return p * 0.5 + vec2(0.5);
            }

            void main() {
                float rightEye = step(0.5, vUv.x);
                vec2 eye = vec2(vUv.x * 2.0 - rightEye, vUv.y);
                vec2 centre = vec2(0.5 + mix(uLensShift, -uLensShift, rightEye), 0.5);
                vec2 red = sourceUv(eye, centre, 0.205);
                vec2 green = sourceUv(eye, centre, 0.215);
                vec2 blue = sourceUv(eye, centre, 0.225);
                if (min(min(green.x, green.y), min(1.0 - green.x, 1.0 - green.y)) < 0.0) {
                    gl_FragColor = vec4(0.0, 0.0, 0.0, 1.0);
                    return;
                }
                float base = rightEye * 0.5;
                float r = texture2D(uTexture, vec2(base + clamp(red.x, 0.0, 1.0) * 0.5, red.y)).r;
                float g = texture2D(uTexture, vec2(base + green.x * 0.5, green.y)).g;
                float b = texture2D(uTexture, vec2(base + clamp(blue.x, 0.0, 1.0) * 0.5, blue.y)).b;
                gl_FragColor = vec4(r, g, b, 1.0);
            }"""
        private const val EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            varying vec2 vUv;
            void main() { gl_FragColor = texture2D(uTexture, vUv); }"""
        /** Windows with rounded corners, like visionOS; uHalf is half the size in metres. */
        private const val ROUNDED_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            attribute vec2 aUv;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() { vUv = aUv; vLocal = aPosition.xy; gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val ROUNDED_FRAGMENT = """
            precision mediump float;
            uniform sampler2D uTexture;
            uniform vec2 uHalf;
            uniform float uRadius;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() {
                vec2 d = abs(vLocal) - (uHalf - vec2(uRadius));
                float edge = length(max(d, 0.0)) - uRadius;
                float alpha = 1.0 - smoothstep(-0.003, 0.0, edge);
                vec4 color = texture2D(uTexture, vUv);
                gl_FragColor = vec4(color.rgb, color.a * alpha);
            }"""
        private const val ROUNDED_EXTERNAL_FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTexture;
            uniform vec2 uHalf;
            uniform float uRadius;
            varying vec2 vUv;
            varying vec2 vLocal;
            void main() {
                vec2 d = abs(vLocal) - (uHalf - vec2(uRadius));
                float edge = length(max(d, 0.0)) - uRadius;
                float alpha = 1.0 - smoothstep(-0.003, 0.0, edge);
                gl_FragColor = vec4(texture2D(uTexture, vUv).rgb, alpha);
            }"""
        private const val COLOR_VERTEX = """
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            void main() { gl_Position = uMvp * vec4(aPosition, 1.0); }"""
        private const val COLOR_FRAGMENT = """
            precision mediump float;
            uniform vec4 uColor;
            void main() { gl_FragColor = uColor; }"""
    }
}
