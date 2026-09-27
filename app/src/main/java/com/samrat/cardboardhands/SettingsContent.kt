package com.samrat.cardboardhands

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PanTool
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Search
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import zone.ien.hig.CupertinoText
import zone.ien.hig.theme.CupertinoTheme
import kotlin.concurrent.thread

/**
 * The VR Settings app in compose-hig, laid out like Settings on an iPad: pages on the left, the
 * chosen page on the right. About the headset, Wi‑Fi and Bluetooth, the home, hands and touch,
 * software update, the avatar and the room scan.
 */
class SettingsContent(
    private val context: Context,
    private val host: Host,
) : ComposeContent(barTitle = tr("Настройки")) {
    interface Host {
        /** The space easter egg all around the user in VR (five taps on the version). */
        fun easterEgg() = Unit
        /** "6DoF · ARCore" or "3DoF" and whether the room is tracked right now. */
        fun trackingText(): String
        /** Opens Avaturn (or VRoid Hub) in a window; a model downloaded there becomes the avatar. */
        fun avatarWeb(vroid: Boolean)
        fun startRoomScan()
        fun roomText(): String
        fun openSystemSettings()
        fun requestShizuku()
        fun shizukuText(): String
        fun setHomeStyle(style: Settings.HomeStyle)
    }

    /** The sections, shown as tiles like Quest's settings: a title and what is inside. */
    private enum class Page(val title: String, val detail: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
        ABOUT(tr("О гарнитуре"), tr("Устройство, версия, аккаунт"), Icons.Rounded.Info),
        CONNECTIVITY("Wi‑Fi и Bluetooth", tr("Сеть, контроллеры, клавиатура"), Icons.Rounded.Wifi),
        HANDS(tr("Руки и касания"), tr("Трекинг рук, щипок"), Icons.Rounded.PanTool),
        UPDATE(tr("Обновление ПО"), tr("Версия PhoneXR"), Icons.Rounded.SystemUpdate),
        AVATAR(tr("Аватар"), tr("Avaturn или VRoid Hub"), Icons.Rounded.Face),
        ROOM(tr("Сканирование комнаты"), tr("Сетка, стол, 6DoF"), Icons.Rounded.ViewInAr),
    }

    /** Null: the tiles of all sections; otherwise the section that is open. */
    private var page by mutableStateOf<Page?>(null)
    /** What the search field filters the tiles by, and whether the VR keyboard is typing into it. */
    private var filter by mutableStateOf("")
    private var typing by mutableStateOf(false)

    override val keyboardRequested get() = typing

    override fun type(key: String) {
        when (key) {
            "backspace" -> filter = filter.dropLast(1)
            "enter" -> { typing = false; Page.entries.firstOrNull { matches(it) }?.let { open(it) } }
            else -> if (filter.length < 40) filter += key
        }
    }

    override fun hideKeyboard() { typing = false }

    private fun matches(item: Page) = filter.isBlank() ||
        item.title.contains(filter.trim(), ignoreCase = true) || item.detail.contains(filter.trim(), ignoreCase = true)
    private var status by mutableStateOf<String?>(null)
    private var release by mutableStateOf<Updates.Release?>(null)
    private var checked by mutableStateOf(false)
    private var checking by mutableStateOf(false)
    private var downloadProgress by mutableStateOf<Float?>(null)

    private var egg by mutableStateOf(false)
    private val taps = VersionTaps()

    @Composable
    override fun Content() = if (egg) SpaceEasterEgg { egg = false } else Screen()

    private fun open(target: Page?) {
        page = target
        typing = false
        status = null
        if (target == Page.UPDATE && !checked) checkUpdate()
    }

    private fun checkUpdate() {
        checking = true
        thread(name = "PhoneXR VR update check") {
            release = runCatching { Updates.check(context) }.getOrNull()
            checked = true
            checking = false
        }
    }

    @Composable
    private fun Screen() {
        // Values from the host (tracking, room) change without telling; look again every second.
        var tick by remember { mutableIntStateOf(0) }
        LaunchedEffect(Unit) { while (true) { delay(1000); tick++ } }
        val current = page
        if (current == null) {
            Tiles()
            return
        }
        androidx.compose.runtime.key(tick) {
            HigPage(title = current.title, onBack = { open(null) }, bottomInset = 24.dp) {
                when (current) {
                    Page.ABOUT -> About()
                    Page.CONNECTIVITY -> Connectivity()
                    Page.HANDS -> Hands()
                    Page.UPDATE -> Update()
                    Page.AVATAR -> Avatar()
                    Page.ROOM -> Room()
                }
                status?.let { HigSection { HigRow(it) } }
            }
        }
    }

    /** The first screen, as in Quest: a search field over tiles of all sections. */
    @Composable
    private fun Tiles() {
        val colors = androidx.compose.material3.MaterialTheme.colorScheme
        Column(
            Modifier.fillMaxSize()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(colors.surfaceContainerLowest, colors.surface)))
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Search: tap it and type with the VR keyboard (or a real one).
            Row(
                Modifier.align(androidx.compose.ui.Alignment.CenterHorizontally).width(420.dp).clip(RoundedCornerShape(50))
                    .background(colors.surfaceContainerHigh).clickable { typing = true }
                    .padding(horizontal = 18.dp, vertical = 11.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                androidx.compose.material3.Icon(Icons.Rounded.Search, null, tint = colors.onSurfaceVariant, modifier = Modifier.size(22.dp))
                androidx.compose.material3.Text(
                    if (filter.isEmpty() && !typing) tr("Поиск в настройках") else filter + if (typing) "▏" else "",
                    color = if (filter.isEmpty()) colors.onSurfaceVariant else colors.onSurface
                )
            }
            val shown = Page.entries.filter { matches(it) }
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                shown.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { item -> Tile(item, Modifier.weight(1f)) }
                        repeat(4 - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
                if (shown.isEmpty()) androidx.compose.material3.Text(tr("Ничего не найдено"), color = colors.onSurfaceVariant)
            }
        }
    }

    @Composable
    private fun Tile(item: Page, modifier: Modifier) {
        val colors = androidx.compose.material3.MaterialTheme.colorScheme
        Column(
            modifier.clip(RoundedCornerShape(20.dp)).background(colors.surfaceContainerHigh).clickable { open(item) }
                .height(118.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            androidx.compose.material3.Icon(item.icon, null, tint = colors.onSurface, modifier = Modifier.size(26.dp))
            Box(Modifier.weight(1f))
            androidx.compose.material3.Text(item.title, color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, maxLines = 1)
            androidx.compose.material3.Text(item.detail, color = colors.onSurfaceVariant, fontSize = 13.sp, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }

    @Composable
    private fun About() {
        val metrics = context.resources.displayMetrics
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / it.getIntExtra(BatteryManager.EXTRA_SCALE, 100) }
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val runtime = when (PhoneXrRuntime.state(context)) {
            PhoneXrRuntime.State.READY -> "PhoneXR Runtime"
            PhoneXrRuntime.State.OUTDATED -> "PhoneXR Runtime (есть обновление)"
            PhoneXrRuntime.State.MISSING -> "не установлен"
        }
        HigSection {
            HigRow("Устройство", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            HigRow("Android", Build.VERSION.RELEASE)
            // Five taps in a row on the version: the easter egg.
            HigLink("PhoneXR", value = version ?: "—") {
                if (taps.tap()) { host.easterEgg(); status = null }
                else if (taps.left in 1..3) status = "Ещё ${taps.left}…"
            }
            HigRow("Аккаунт", Account.current(context)?.let { "${it.name} · ${it.email}" } ?: "—")
        }
        HigSection(title = "Шлем") {
            HigRow("Отслеживание", host.trackingText())
            HigRow("Экран", "${metrics.widthPixels}×${metrics.heightPixels}, по ${metrics.widthPixels / 2}×${metrics.heightPixels} на глаз")
            HigRow("Поле зрения", "90° по вертикали")
            HigRow("Межзрачковое", "${Settings.ipdMm(context)} мм")
            HigRow("Руки", "OrangeHanding: MediaPipe + YOLO11, 21 точка на руку")
            HigRow("OpenXR", runtime)
            HigRow("Батарея", battery?.let { "$it %" } ?: "—")
        }
    }

    @Composable
    private fun Connectivity() {
        HigSection(footer = "Системные сети открываются прямо в отдельном VR‑окне. Для управления Android‑окнами нужен Shizuku.") {
            HigRow("Shizuku", host.shizukuText())
            HigLink("Открыть настройки Android") { host.openSystemSettings() }
            HigLink("Разрешить Shizuku") { host.requestShizuku() }
        }
    }

    @Composable
    private fun Hands() {
        HigSection(
            title = "Нажатие",
            footer = "Рука ведёт курсор, щипок большим и указательным пальцами — нажатие. Удерживайте щипок и ведите руку, чтобы листать."
        ) {
            HigRow("Курсор и щипок", "Как на Quest")
        }
        HigSection(
            title = "Окна",
            footer = "Чтобы переместить окно, поднесите руку к его левому или правому краю, сожмите кулак и несите. Разожмите руку — окно останется там."
        ) {
        }
    }

    @Composable
    private fun Update() {
        HigSection {
            HigSwitchRow(tr("Автообновление"), Updates.autoUpdate(context)) { Updates.setAutoUpdate(context, it) }
            HigSwitchRow(tr("Бета‑обновления"), Updates.beta(context)) { Updates.setBeta(context, it); checkUpdate() }
        }
        val found = release
        HigSection {
            when {
                checking -> HigRow("Проверка обновлений…", trailing = { HigSpinner() })
                found == null -> {
                    HigRow("PhoneXR ${Updates.currentVersion(context)}", if (checked) "Установлена последняя версия ПО" else null)
                    HigLink("Проверить снова") { checkUpdate() }
                }
                else -> {
                    HigRow("PhoneXR ${found.version}", Updates.formatSize(found.size))
                    val progress = downloadProgress
                    HigLink(
                        when {
                            progress == null -> tr("Обновить сейчас")
                            progress < 0f -> tr("Загрузка…")
                            else -> "Загрузка ${(progress * 100).toInt()}%"
                        },
                        enabled = progress == null
                    ) {
                        downloadProgress = 0f
                        thread(name = "PhoneXR VR update") {
                            val file = runCatching { Updates.download(context, found) { downloadProgress = it } }.getOrNull()
                            downloadProgress = null
                            val activity = context as? android.app.Activity
                            if (file != null && activity != null) activity.runOnUiThread { Updates.install(activity, file) }
                            else status = "Обновление не скачалось"
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun Avatar() {
        val source = remember(page) { AvatarModel.source(context) }
        HigSection(
            footer = "Сделайте аватар по селфи в Avaturn или выберите персонажа на VRoid Hub — окно откроется здесь же. " +
                "Нажмите там «Скачать» или «Экспорт»: PhoneXR сам заберёт модель, и аватар оживёт по вашему трекингу. " +
                "Аватары Avaturn — avaturn.me, VRoid — hub.vroid.com (условия каждой модели задаёт её автор)."
        ) {
            HigRow(tr("Сейчас"), if (source == AvatarModel.Source.STANDARD) tr("Стандартный") else source.title)
            HigLink(tr("Создать в Avaturn")) { host.avatarWeb(vroid = false) }
            HigLink(tr("Сделать в VRoid"), value = "Google Play") { AvatarModel.openVroid(context) }
            if (source != AvatarModel.Source.STANDARD) HigLink(tr("Вернуть стандартный")) { AvatarModel.reset(context); status = tr("Стандартный аватар") }
        }
    }

    @Composable
    private fun Room() {
        if (host.trackingText().startsWith("3DoF")) {
            HigSection {
                HigRow("Нужен 6DoF", "Сканирование пола, стен и столов работает только в 6DoF. Включите 6DoF в настройках PhoneXR и установите Google Play Services for AR.",
                    detailColor = Color(0xFFFF9F0A))
            }
            return
        }
        HigSection(
            footer = "Медленно осмотрите пол, стены и поверхности со всех сторон. PhoneXR показывает найденные горизонтальные и вертикальные плоскости."
        ) {
            HigRow(host.roomText())
            HigLink(tr("Начать новое сканирование")) { host.startRoomScan() }
        }
    }
}
