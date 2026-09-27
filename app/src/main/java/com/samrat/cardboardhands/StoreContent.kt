package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import zone.ien.hig.theme.CupertinoTheme
import java.io.File
import kotlin.concurrent.thread

/**
 * The store in the headset, in compose-hig and laid out like the App Store: cards with an icon, a
 * name, a line of description and a pill button — VR modes, PhoneXR apps, games from the server,
 * Minecraft mods and web apps.
 */
class StoreContent(private val context: Context, private val host: Host) : ComposeContent(barTitle = tr("Магазин")) {
    interface Host {
        fun openCinema(packageName: String, scene: String)
        fun openWebApp(app: WebApps.App)
        fun openCalls()
        fun install(file: File)
        fun homeChanged()
        fun message(text: String)
    }

    private class Card(
        val title: String,
        val subtitle: String,
        val icon: () -> Any?,
        val button: () -> String,
        val action: () -> Unit,
    )

    private class Section(val title: String, val cards: List<Card>)

    private var sections by mutableStateOf(emptyList<Section>())
    private var loading = true
    /** Bumped when a button's text or an icon changes without the list changing. */
    private var version by mutableStateOf(0)
    private val progress = object : HashMap<String, Int>() {
        override fun put(key: String, value: Int): Int? = super.put(key, value).also { version++ }
        override fun remove(key: String): Int? = super.remove(key).also { version++ }
    }
    private val icons = mutableStateMapOf<String, Bitmap>()
    private var mods = emptyList<GameStore.Item>()
    private var games = emptyList<GameStore.Item>()
    private var web = emptyList<WebApps.App>()

    init {
        thread(name = "PhoneXR VR store") {
            build(emptyList(), emptyList())
            games = runCatching { GameStore.list() }.getOrDefault(emptyList())
            web = runCatching { WebApps.fromStore() }.getOrDefault(emptyList())
            mods = runCatching { GameStore.mods() }.getOrDefault(emptyList())
            loading = false
            build(games, web)
            for (game in games) GameStore.icon(game)?.let { icons[game.path] = it }
            // Web app icons come over the network: fetch them here, the cards read the cache.
            web.forEach { WebApps.icon(it) }
            version++
        }
    }

    private fun installed(name: String) = runCatching { context.packageManager.getApplicationInfo(name, 0) }.isSuccess

    private fun appIcon(name: String): Drawable? = runCatching { context.packageManager.getApplicationIcon(name) }.getOrNull()

    /** Runs a card's action off the main thread, then refreshes the buttons. */
    private fun run(card: Card) = thread(name = "PhoneXR store action") {
        card.action()
        build(games, web)
    }

    @Composable
    override fun Content() {
        @Suppress("UNUSED_VARIABLE") val watch = version + icons.size
        Column(
            Modifier.fillMaxSize().background(CupertinoTheme.colorScheme.systemGroupedBackground)
                .verticalScroll(rememberScrollState()).padding(bottom = 24.dp)
        ) {
            VrTitle(tr("Магазин"), "Игры, режимы и приложения для PhoneXR")
            for (section in sections) {
                VrHeading(section.title)
                section.cards.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { card ->
                            VrStoreCard(card.title, card.subtitle, card.icon(), card.button(), Modifier.weight(1f)) { run(card) }
                        }
                        if (pair.size == 1) Box(Modifier.weight(1f))
                    }
                }
            }
            if (loading) Row(Modifier.padding(24.dp)) { HigSpinner() }
        }
    }

    private fun build(games: List<GameStore.Item>, web: List<WebApps.App>) {
        val modes = listOf(
            Triple("Minecraft VR", "com.mojang.minecraftpe", CinemaActivity.SCENE_ROOM) to "Bedrock на большом экране",
            Triple("Roblox VR", "com.roblox.client", CinemaActivity.SCENE_ROBLOX) to "Roblox в доме из Brookhaven",
            Triple("Brawl Stars VR", "com.supercell.brawlstars", CinemaActivity.SCENE_BRAWL) to "Посреди арены, 360°",
        ).map { (mode, subtitle) ->
            val (title, name, scene) = mode
            Card(title, subtitle, { appIcon(name) }, { if (installed(name)) tr("Играть") else tr("Скачать") }) {
                if (installed(name)) host.openCinema(name, scene)
                else context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("market://details?id=$name"))
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
        val apps = listOf(
            Card(tr("Звонки"), "Общение персонами: голос, лицо и руки", { null }, { tr("Открыть") }) { host.openCalls() },
            Card(tr("Android‑приложения"), "Любые приложения телефона окнами в VR", { null },
                { if (AndroidAppsContent.enabled(context)) tr("Удалить") else tr("Получить") }) {
                AndroidAppsContent.setEnabled(context, !AndroidAppsContent.enabled(context))
                host.homeChanged()
            },
        )
        val gameCards = games.map { item ->
            Card(item.title, item.extension.uppercase() + if (item.size > 0) " · " + Updates.formatSize(item.size) else "",
                { icons[item.path] }, { progress[item.path]?.let { "$it%" } ?: tr("Загрузить") }) {
                if (progress.containsKey(item.path)) return@Card
                progress[item.path] = 0
                val file = runCatching {
                    GameStore.download(item, File(context.cacheDir, "patched/store")) { value ->
                        val percent = (value * 100).toInt().coerceAtLeast(0)
                        if (percent != progress[item.path]) { progress[item.path] = percent; }
                    }
                }.getOrNull()
                progress.remove(item.path)
                if (file != null) host.install(file) else host.message("«${item.title}» не скачалась")
            }
        }
        val modCards = mods.map { item ->
            Card(item.title, item.path.substringAfterLast('.').uppercase() + if (item.size > 0) " · " + Updates.formatSize(item.size) else "",
                { appIcon(MinecraftMods.MINECRAFT) }, { progress[item.path]?.let { "$it%" } ?: tr("Установить") }) {
                if (progress.containsKey(item.path)) return@Card
                progress[item.path] = 0
                val activity = context as? android.app.Activity
                val file = runCatching {
                    GameStore.download(item, File(context.cacheDir, "patched/mods")) { value ->
                        val percent = (value * 100).toInt().coerceAtLeast(0)
                        if (percent != progress[item.path]) { progress[item.path] = percent; }
                    }
                }.getOrNull()
                progress.remove(item.path)
                val problem = if (file != null && activity != null) MinecraftMods.install(activity, file) else "«${item.title}» не скачался"
                if (problem != null) host.message(problem) else host.message(tr("Мод открыт в Minecraft: подтвердите импорт"))
            }
        }
        val webCards = web.map { app ->
            Card(app.name, app.url.removePrefix("https://").substringBefore('/'), { WebApps.icon(app) },
                { if (WebApps.installed(context).any { it.url == app.url }) tr("Открыть") else tr("Добавить") }) {
                if (WebApps.installed(context).any { it.url == app.url }) host.openWebApp(app)
                else { WebApps.add(context, app); host.homeChanged(); host.message("«${app.name}» на главном экране") }
            }
        }
        // WebXR games open in the built-in browser, which enters VR through the WebXR polyfill.
        val xrCards = WebXrGames.ALL.map { (name, detail, url) ->
            val app = WebApps.App(name, url, null)
            Card(name, detail, { WebApps.icon(app) }, { tr("Играть") }) { host.openWebApp(app) }
        }
        sections = listOfNotNull(
            Section(tr("VR‑режимы"), modes),
            Section("WebXR‑игры", xrCards),
            Section(tr("Приложения PhoneXR"), apps),
            Section(if (loading) "Игры · загрузка…" else tr("Игры"), gameCards).takeIf { loading || gameCards.isNotEmpty() },
            Section(tr("Моды Minecraft"), modCards).takeIf { modCards.isNotEmpty() },
            Section(tr("Веб‑приложения"), webCards).takeIf { webCards.isNotEmpty() },
        )
    }
}
