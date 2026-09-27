package com.samrat.cardboardhands

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Remote control of the VR home from a computer (PhoneXR Share on the Mac): a tiny HTTP server on
 * the phone's own loopback, reached over USB with `adb forward tcp:8766 tcp:8766` — nothing on the
 * network can connect to it. The computer sees each window's picture and works it with mouse and
 * keyboard as if touching it in the headset.
 *
 *   GET  /state                      windows and home apps, as JSON
 *   GET  /frame?id=…                 the window's picture, JPEG
 *   POST /touch?id=…&action=down|move|up&u=…&v=…
 *   POST /type?id=…&text=…           text, or key=backspace|enter
 *   POST /bar?id=…&action=back|forward|reload|home
 *   POST /open?entry=…               a home app;  /url?url=… opens a page in the browser
 *   POST /window?id=…&action=focus|minimize|close
 */
class RemoteControl(private val host: Host) {
    interface Host {
        fun windows(): List<VrWindow>
        fun apps(): List<HomePanel.Entry>
        /** The window's picture now (from the GL thread), or null. */
        fun capture(window: VrWindow, maxWidth: Int): Bitmap?
        fun open(entryId: String)
        fun openUrl(url: String)
        fun window(window: VrWindow, action: String)
    }

    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        thread(name = "PhoneXR remote") {
            val socket = runCatching { ServerSocket(PORT, 8, InetAddress.getLoopbackAddress()) }
                .onFailure { Log.w(TAG, "Remote control not started", it) }.getOrNull() ?: return@thread
            server = socket
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                thread(name = "PhoneXR remote client") { runCatching { serve(client) }; runCatching { client.close() } }
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun serve(client: Socket) {
        client.soTimeout = 15_000
        val reader = BufferedReader(InputStreamReader(client.getInputStream()))
        val out = client.getOutputStream()
        // Keep-alive: several requests may come over one connection.
        while (true) {
            val request = reader.readLine() ?: return
            if (request.isBlank()) continue
            var length = 0
            while (true) {
                val header = reader.readLine() ?: return
                if (header.isEmpty()) break
                if (header.startsWith("Content-Length:", true)) length = header.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            if (length > 0) { val body = CharArray(length); reader.read(body) }
            val (method, target) = request.split(' ').let { it.getOrElse(0) { "" } to it.getOrElse(1) { "/" } }
            val path = target.substringBefore('?')
            val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }
                .associate { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            handle(method, path, query, out)
        }
    }

    private fun handle(method: String, path: String, query: Map<String, String>, out: OutputStream) {
        fun window() = host.windows().firstOrNull { it.id == query["id"] }
        when (path) {
            "/state" -> {
                val windows = JSONArray()
                host.windows().forEach { w ->
                    windows.put(JSONObject().put("id", w.id).put("title", w.title).put("minimized", w.minimized)
                        .put("width", w.content.pixelWidth).put("height", w.content.pixelHeight)
                        .put("bar", w.content.toolbarTitle() != null).put("keyboard", w.content.keyboardRequested))
                }
                val apps = JSONArray()
                host.apps().forEach { apps.put(JSONObject().put("id", it.id).put("label", it.label)) }
                json(out, JSONObject().put("windows", windows).put("apps", apps))
            }
            "/frame" -> {
                val w = window() ?: return status(out, 404)
                val picture = host.capture(w, (query["width"]?.toIntOrNull() ?: 1280).coerceIn(160, 1920)) ?: return status(out, 503)
                val bytes = java.io.ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.JPEG, 72, it) }.toByteArray()
                picture.recycle()
                send(out, 200, "image/jpeg", bytes)
            }
            "/touch" -> {
                val w = window() ?: return status(out, 404)
                val action = when (query["action"]) { "down" -> MotionEvent.ACTION_DOWN; "up" -> MotionEvent.ACTION_UP; else -> MotionEvent.ACTION_MOVE }
                w.content.touch(action, (query["u"]?.toFloatOrNull() ?: .5f).coerceIn(0f, 1f), (query["v"]?.toFloatOrNull() ?: .5f).coerceIn(0f, 1f))
                status(out, 200)
            }
            "/type" -> {
                val w = window() ?: return status(out, 404)
                query["key"]?.let { w.content.type(it) }
                query["text"]?.forEach { w.content.type(it.toString()) }
                status(out, 200)
            }
            "/bar" -> {
                val w = window() ?: return status(out, 404)
                w.content.toolbarAction(query["action"] ?: "reload")
                status(out, 200)
            }
            "/open" -> { query["entry"]?.let(host::open); status(out, 200) }
            "/url" -> {
                val url = query["url"]?.trim().orEmpty()
                if (url.isEmpty()) return status(out, 400)
                host.openUrl(if (url.contains("://")) url else if (url.contains('.') && !url.contains(' ')) "https://$url"
                    else "https://www.google.com/search?q=" + Uri.encode(url))
                status(out, 200)
            }
            "/window" -> {
                val w = window() ?: return status(out, 404)
                host.window(w, query["action"] ?: "focus")
                status(out, 200)
            }
            else -> status(out, if (method == "GET" || method == "POST") 404 else 405)
        }
    }

    private fun json(out: OutputStream, value: JSONObject) = send(out, 200, "application/json; charset=utf-8", value.toString().toByteArray())

    private fun status(out: OutputStream, code: Int) = send(out, code, "text/plain", ByteArray(0))

    private fun send(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val head = "HTTP/1.1 $code X\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: keep-alive\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
    }

    companion object {
        const val PORT = 8766
        private const val TAG = "PhoneXR-Remote"
    }
}
