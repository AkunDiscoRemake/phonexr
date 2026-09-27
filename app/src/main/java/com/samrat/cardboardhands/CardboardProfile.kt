package com.samrat.cardboardhands

import android.net.Uri
import android.util.Base64
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.URLDecoder
import kotlin.math.roundToInt

/** Reads the inter-lens distance from Google's Cardboard viewer-profile QR payload. */
object CardboardProfile {
    fun interLensMm(value: String): Int? {
        val trimmed = value.trim()
        val encoded = runCatching { Uri.parse(trimmed).getQueryParameter("p") }.getOrNull()
            ?: Regex("(?:[?&]|^)p=([^&]+)").find(trimmed)?.groupValues?.getOrNull(1)
            ?: trimmed.takeIf { !it.contains("://") }
            // Google's short Cardboard QR may redirect to the actual profile only when opened.
            // It is still a valid Cardboard code; use the standard value instead of rejecting it.
            ?: return Settings.DEFAULT_IPD_MM.takeIf { trimmed.contains("cardboard", ignoreCase = true) }
        val decoded = runCatching { URLDecoder.decode(encoded, "UTF-8") }.getOrDefault(encoded)
        val padded = decoded.replace(' ', '+').let { it + "=".repeat((4 - it.length % 4) % 4) }
        val bytes = runCatching { Base64.decode(padded, Base64.URL_SAFE or Base64.NO_WRAP) }
            .recoverCatching { Base64.decode(padded, Base64.DEFAULT) }
            .getOrNull() ?: return null
        var at = 0
        var viewerFields = 0
        while (at < bytes.size) {
            val key = readVarint(bytes, at) ?: return null
            at = key.second
            val field = key.first ushr 3
            val wire = key.first and 7
            if (field in 1..3 || field in 5..12) viewerFields++
            if (field == 4 && wire == 5 && at + 4 <= bytes.size) {
                val metres = ByteBuffer.wrap(bytes, at, 4).order(ByteOrder.LITTLE_ENDIAN).float
                val mm = (metres * 1000f).roundToInt()
                return if (mm in Settings.MIN_IPD_MM..Settings.MAX_IPD_MM) mm else Settings.DEFAULT_IPD_MM
            }
            at = skip(bytes, at, wire) ?: return null
        }
        // Inter-lens distance is optional in some older/custom ViewerParams encoders.
        return Settings.DEFAULT_IPD_MM.takeIf { viewerFields >= 2 }
    }

    /**
     * Network call: most viewers print a short link (goo.gl/…) that only leads to the real profile
     * (google.com/cardboard/cfg?p=…) when followed. Follows the redirects and returns the address
     * where they end, or null.
     */
    fun resolve(link: String): String? = runCatching {
        var address = link.trim().let { if (it.startsWith("http")) it else "https://$it" }
        repeat(6) {
            if (address.contains("p=")) return address
            val connection = java.net.URL(address).openConnection() as java.net.HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 6000
            connection.readTimeout = 6000
            connection.requestMethod = "GET"
            val code = connection.responseCode
            val next = connection.getHeaderField("Location")
            connection.disconnect()
            if (code !in 300..399 || next == null) return address.takeIf { it.contains("p=") }
            address = java.net.URL(java.net.URL(address), next).toString()
        }
        address.takeIf { it.contains("p=") }
    }.getOrNull()

    /** A viewer's short link that could not be followed (goo.gl is closed down): still a Cardboard code. */
    fun isShortLink(value: String) = value.contains("goo.gl", ignoreCase = true) || value.contains("g.co/", ignoreCase = true)

    private fun readVarint(data: ByteArray, start: Int): Pair<Int, Int>? {
        var value = 0; var shift = 0; var at = start
        while (at < data.size && shift < 32) {
            val byte = data[at++].toInt() and 0xff
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0) return value to at
            shift += 7
        }
        return null
    }

    private fun skip(data: ByteArray, at: Int, wire: Int): Int? = when (wire) {
        0 -> readVarint(data, at)?.second
        1 -> (at + 8).takeIf { it <= data.size }
        2 -> readVarint(data, at)?.let { (size, next) -> (next + size).takeIf { it <= data.size } }
        5 -> (at + 4).takeIf { it <= data.size }
        else -> null
    }
}
