package com.samrat.cardboardhands

import android.annotation.SuppressLint
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import kotlin.concurrent.thread

/**
 * Avaturn or VRoid Hub inside PhoneXR on the phone: make an avatar from a selfie, or pick one, and
 * the model downloaded there becomes the user's avatar right away (it does not go to Downloads).
 */
class AvatarWebActivity : ComponentActivity() {
    private lateinit var web: WebView
    private var pendingRequest: android.webkit.PermissionRequest? = null
    private val askCamera = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
        pendingRequest?.let { if (granted) it.grant(it.resources) else it.deny() }
        pendingRequest = null
    }
    private val source get() = if (intent.getBooleanExtra(EXTRA_VROID, false)) AvatarModel.Source.VROID else AvatarModel.Source.AVATURN

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            webViewClient = WebViewClient()
            // The selfie in Avaturn asks for the camera: given to Avaturn and VRoid only.
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    val host = request.origin.host.orEmpty()
                    if (!host.endsWith("avaturn.me") && !host.endsWith("vroid.com")) { request.deny(); return }
                    // The page gets the camera only once Android gave it to PhoneXR: ask first if needed.
                    if (checkSelfPermission(android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                        request.grant(request.resources)
                    } else {
                        pendingRequest = request
                        askCamera.launch(android.Manifest.permission.CAMERA)
                    }
                }
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(Bridge(), "PhoneXR")
            setDownloadListener { url, agent, disposition, mime, _ -> take(url, URLUtil.guessFileName(url, disposition, mime), agent) }
            loadUrl(if (source == AvatarModel.Source.VROID) AvatarModel.VROID_HUB_URL else AvatarModel.AVATURN_URL)
        }
        setContentView(web)
        // Android draws edge to edge: keep the page clear of the status and navigation bars.
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(web) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { if (web.canGoBack()) web.goBack() else finish() }
        })
    }

    private inner class Bridge {
        /** A model the page made in memory (a blob: download), as a data: URL. */
        @JavascriptInterface fun model(data: String, name: String) {
            val bytes = runCatching { android.util.Base64.decode(data.substringAfter(","), android.util.Base64.DEFAULT) }.getOrNull() ?: return
            keep(bytes)
        }
    }

    private fun take(url: String, name: String, agent: String) {
        if (url.startsWith("blob:")) {
            web.evaluateJavascript(
                "(async()=>{const b=await (await fetch(${org.json.JSONObject.quote(url)})).blob();" +
                    "const r=new FileReader();r.onload=()=>PhoneXR.model(r.result, ${org.json.JSONObject.quote(name)});r.readAsDataURL(b);})()",
                null,
            )
            return
        }
        thread(name = "PhoneXR avatar download") {
            val bytes = runCatching {
                val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                connection.setRequestProperty("User-Agent", agent)
                CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
                connection.inputStream.use { it.readBytes() }
            }.getOrNull()
            if (bytes == null) runOnUiThread { Toast.makeText(this, tr("Не удалось скачать модель"), Toast.LENGTH_LONG).show() }
            else keep(bytes)
        }
    }

    private fun keep(bytes: ByteArray) {
        val error = AvatarModel.save(this, bytes, source)
        runOnUiThread {
            Toast.makeText(this, error ?: tr("Аватар сохранён"), Toast.LENGTH_LONG).show()
            if (error == null) finish()
        }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_VROID = "vroid"
    }
}
