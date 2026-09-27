package com.samrat.cardboardhands

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * What the PhoneXR browser remembers: bookmarks, history, and its switches (desktop sites, the ad
 * blocker). Kept on the phone only; the start page shows them.
 */
object BrowserData {
    data class Page(val url: String, val title: String)

    private const val PREFS = "browser"
    private const val HISTORY_LIMIT = 300

    /** Ad and tracker hosts the blocker drops (and every subdomain of them). */
    val BLOCKED = setOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com", "adnxs.com", "adsrvr.org",
        "taboola.com", "outbrain.com", "criteo.com", "criteo.net", "pubmatic.com", "rubiconproject.com",
        "openx.net", "moatads.com", "scorecardresearch.com", "amazon-adsystem.com", "adform.net",
        "yandexadexchange.net", "an.yandex.ru", "mc.yandex.ru", "ads.vk.com", "top-fwz1.mail.ru",
        "facebook.net", "connect.facebook.net", "hotjar.com", "mgid.com", "propellerads.com", "popads.net",
    )

    fun blocked(host: String?): Boolean {
        host ?: return false
        return BLOCKED.any { host == it || host.endsWith(".$it") }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun read(context: Context, key: String): List<Page> {
        val array = runCatching { JSONArray(prefs(context).getString(key, "[]")) }.getOrDefault(JSONArray())
        return (0 until array.length()).mapNotNull { i ->
            array.optJSONObject(i)?.let { Page(it.optString("url"), it.optString("title")) }?.takeIf { it.url.isNotBlank() }
        }
    }

    private fun write(context: Context, key: String, pages: List<Page>) {
        val array = JSONArray()
        pages.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title)) }
        prefs(context).edit().putString(key, array.toString()).apply()
    }

    @Synchronized fun bookmarks(context: Context) = read(context, "bookmarks")

    @Synchronized fun isBookmarked(context: Context, url: String) = bookmarks(context).any { it.url == url }

    /** Adds the page, or removes it when it is already there. Returns true when it is now bookmarked. */
    @Synchronized fun toggleBookmark(context: Context, url: String, title: String): Boolean {
        val list = bookmarks(context)
        if (list.any { it.url == url }) {
            write(context, "bookmarks", list.filterNot { it.url == url })
            return false
        }
        write(context, "bookmarks", listOf(Page(url, title.ifBlank { url })) + list)
        return true
    }

    @Synchronized fun removeBookmark(context: Context, url: String) = write(context, "bookmarks", bookmarks(context).filterNot { it.url == url })

    @Synchronized fun history(context: Context) = read(context, "history")

    @Synchronized fun visit(context: Context, url: String, title: String) {
        if (!url.startsWith("http")) return
        val rest = history(context).filterNot { it.url == url }
        write(context, "history", (listOf(Page(url, title.ifBlank { url })) + rest).take(HISTORY_LIMIT))
    }

    @Synchronized fun clearHistory(context: Context) = write(context, "history", emptyList())

    fun desktop(context: Context) = prefs(context).getBoolean("desktop", false)
    fun setDesktop(context: Context, on: Boolean) = prefs(context).edit().putBoolean("desktop", on).apply()

    fun adblock(context: Context) = prefs(context).getBoolean("adblock", true)
    fun setAdblock(context: Context, on: Boolean) = prefs(context).edit().putBoolean("adblock", on).apply()

    /** Everything the start page shows, as JSON. */
    fun homeJson(context: Context): String {
        fun list(pages: List<Page>) = JSONArray().also { array -> pages.forEach { array.put(JSONObject().put("url", it.url).put("title", it.title)) } }
        return JSONObject()
            .put("bookmarks", list(bookmarks(context)))
            .put("history", list(history(context).take(40)))
            .put("desktop", desktop(context))
            .put("adblock", adblock(context))
            .toString()
    }

    /** What the user typed in the address bar: a web address, or words to search for. */
    fun addressFor(text: String): String {
        val typed = text.trim()
        return when {
            typed.isEmpty() -> VrWindowsHome.HOME
            typed.contains("://") -> typed
            typed.contains('.') && !typed.contains(' ') -> "https://$typed"
            else -> "https://www.google.com/search?q=" + android.net.Uri.encode(typed)
        }
    }

    const val DESKTOP_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36"
}

/** The start page's address, where [BrowserData] can reach it without the browser class. */
object VrWindowsHome {
    const val HOME = "file:///android_asset/browser/home.html"
}
