package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import zone.ien.hig.CupertinoButton
import zone.ien.hig.CupertinoButtonDefaults
import zone.ien.hig.CupertinoButtonSize
import zone.ien.hig.CupertinoText
import zone.ien.hig.theme.CupertinoTheme
import kotlin.concurrent.thread

/**
 * The "Звонки" app in compose-hig: who is online, calling, and the call itself — the other
 * person's voice, a circle that breathes while they talk, and their hands in front of them.
 */
class CallContent(private val context: Context, private val onWatchTogether: (() -> Unit)? = null) : ComposeContent(pixelWidth = 1400, pixelHeight = 1000) {
    @Volatile private var running = true
    private var voice: Voice? = null
    /** Recomposes on every change of the call and on every frame. */
    private var frame by mutableIntStateOf(0)
    private val listener: () -> Unit = { frame++ }
    /** People added in the Friends tab; shown first, online or not. */
    private var friends by mutableStateOf<List<Friends.Person>>(emptyList())
    /** The call picture: the other person's hands, drawn off the main thread. */
    private val stage = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
    private val stageCanvas = Canvas(stage)
    private val handsLayer = Bitmap.createBitmap(900, 900, Bitmap.Config.ARGB_8888)
    private val handsCanvas = Canvas(handsLayer)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    init {
        Calls.listen(listener)
        thread { Calls.start(context) }
        thread { friends = runCatching { Friends.mine(context) }.getOrDefault(emptyList()) }
        voice = VoiceHub.acquire(context)
        thread(name = "PhoneXR calls") {
            while (running) {
                if (Calls.state == Calls.State.IN_CALL && !BuildConfig.LITE) {
                    synchronized(stage) { drawStage() }
                    frame++
                }
                Thread.sleep(if (Calls.state == Calls.State.IN_CALL) 50 else 400)
            }
        }
    }

    override fun release() {
        super.release()
        running = false
        Calls.unlisten(listener)
        if (voice != null) VoiceHub.release()
        voice = null
    }

    @Composable
    override fun Content() {
        @Suppress("UNUSED_VARIABLE") val tick = frame
        Box(Modifier.fillMaxSize().background(CupertinoTheme.colorScheme.systemGroupedBackground)) {
            when (Calls.state) {
                Calls.State.OFFLINE -> Offline()
                Calls.State.IDLE -> Contacts()
                Calls.State.CALLING -> Centered("Звоним ${Calls.peer?.name ?: ""}…", null) {
                    Pill(tr("Отменить"), RED) { Calls.hangUp() }
                }
                Calls.State.RINGING -> Centered("${Calls.peer?.name ?: "Кто-то"} звонит", if (BuildConfig.LITE) null else "Звонок с персоной") {
                    Pill(tr("Отклонить"), RED) { Calls.decline() }
                    Pill(tr("Принять"), GREEN) { Calls.accept() }
                }
                Calls.State.IN_CALL -> InCall()
            }
        }
    }

    @Composable
    private fun Offline() {
        val signedIn = Account.current(context) != null
        Centered(if (signedIn) "Подключение…" else "Войдите в аккаунт PhoneXR", if (signedIn) null else "На телефоне: PhoneXR → Настройки → Аккаунт") {
            if (signedIn) Pill("Повторить", CupertinoTheme.colorScheme.accent) { thread { Calls.stop(); Calls.start(context) } }
        }
    }

    @Composable
    private fun Contacts() {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            VrTitle(tr("Звонки"), "Вы: ${Account.current(context)?.name ?: ""}")
            Calls.message?.let { CupertinoText(it, color = Color(0xFFFFB45A), modifier = Modifier.padding(horizontal = 24.dp)) }
            // Friends first (online ones can be called), then anyone else who is online.
            val online = Calls.online
            val friendIds = friends.map { it.id }.toSet()
            val rows = friends.map { friend ->
                Triple(Calls.Contact(friend.id, friend.name.ifBlank { friend.username }), online.any { it.id == friend.id }, "@${friend.username}")
            }.sortedByDescending { it.second } + online.filter { it.id !in friendIds }.map { Triple(it, true, null) }
            if (rows.isEmpty()) {
                HigSection(footer = "Добавьте друзей во вкладке «Друзья» на телефоне") { HigRow(tr("Сейчас никого нет в сети")) }
                return
            }
            HigSection(title = "Друзья и в сети") {
                rows.forEach { (contact, isOnline, username) ->
                    HigRow(
                        contact.name,
                        listOfNotNull(username, if (isOnline) online.firstOrNull { it.id == contact.id }?.status?.ifBlank { null } ?: "в сети"
                            else tr("Не в сети")).joinToString(" · "),
                        detailColor = if (isOnline) GREEN else Color.Unspecified,
                    ) {
                        // Watching something together: joining is calling in, the page opens by itself.
                        val watching = online.firstOrNull { it.id == contact.id }?.status == tr("Смотрит вместе")
                        if (isOnline) Pill(if (watching) tr("Присоединиться") else tr("Позвонить"), GREEN, small = true) { thread { Calls.call(contact) } }
                    }
                }
            }
        }
    }

    @Composable
    private fun InCall() {
        Column(Modifier.fillMaxSize().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    CupertinoText(Calls.peer?.name ?: tr("Звонок"), fontSize = 30.sp, fontWeight = FontWeight.Bold)
                    if (Calls.remoteTalking) CupertinoText("говорит", color = GREEN)
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                // Who it is, the circle breathing with their voice, and their hands over it.
                val name = Calls.peer?.name ?: "?"
                val grow = 1f + Calls.remoteMouth.coerceIn(0f, 1f) * .12f
                Box(
                    Modifier.size(260.dp * grow).clip(CircleShape).background(Color(0xFF1877F2)),
                    contentAlignment = Alignment.Center
                ) { CupertinoText(name.take(1).uppercase(), color = Color.White, fontSize = 110.sp, fontWeight = FontWeight.Bold) }
                Image(synchronized(stage) { stage.asImageBitmap() }, null, modifier = Modifier.size(460.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Pill(if (Calls.muted) "Микрофон выкл." else tr("Микрофон"), if (Calls.muted) RED else Color(0x55FFFFFF)) {
                    Calls.muted = !Calls.muted; frame++
                }
                onWatchTogether?.let { Pill(tr("Смотреть вместе"), Color(0xFF0A84FF)) { it() } }
                Pill(tr("Завершить"), RED) { Calls.hangUp() }
            }
        }
    }

    @Composable
    private fun Centered(title: String, detail: String?, buttons: @Composable () -> Unit) {
        Column(Modifier.fillMaxSize().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CupertinoText(title, fontSize = 34.sp, fontWeight = FontWeight.Bold)
            if (detail != null) CupertinoText(detail, color = CupertinoTheme.colorScheme.secondaryLabel)
            Spacer(Modifier.height(40.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) { buttons() }
        }
    }

    @Composable
    private fun Pill(label: String, color: Color, small: Boolean = false, onClick: () -> Unit) {
        CupertinoButton(
            onClick = onClick,
            size = if (small) CupertinoButtonSize.Small else CupertinoButtonSize.Large,
            colors = CupertinoButtonDefaults.filledButtonColors(containerColor = color),
        ) { CupertinoText(label, color = Color.White, fontWeight = FontWeight.SemiBold) }
    }

    /** The other person's hands, where they hold them in front of their camera. */
    private fun drawStage() {
        val pixelWidth = stage.width
        val pixelHeight = stage.height
        stage.eraseColor(AndroidColor.TRANSPARENT)
        val canvas = stageCanvas
        // Their hands, see-through, where they hold them in front of their camera.
        val hands = Calls.remoteHands
        if (hands.isNotEmpty()) {
            handsLayer.eraseColor(AndroidColor.TRANSPARENT)
            val handImage = Calls.remoteHandImage
            val silhouette = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AndroidColor.rgb(214, 164, 132) }
            for (points in hands) {
                val xs = FloatArray(21) { points[it * 2] * pixelWidth }
                val ys = FloatArray(21) { points[it * 2 + 1] * pixelHeight }
                val triangles = GhostHand.triangles(xs, ys, 0f)
                val path = Path()
                var i = 0
                while (i + 8 < triangles.size) {
                    path.moveTo(triangles[i], triangles[i + 1])
                    path.lineTo(triangles[i + 3], triangles[i + 4])
                    path.lineTo(triangles[i + 6], triangles[i + 7])
                    path.close()
                    i += 9
                }
                if (handImage != null) {
                    handsCanvas.save()
                    handsCanvas.clipPath(path)
                    handsCanvas.drawBitmap(handImage, null, RectF(0f, 0f, pixelWidth.toFloat(), pixelHeight.toFloat()), paint)
                    handsCanvas.restore()
                } else {
                    handsCanvas.drawPath(path, silhouette)
                }
            }
            paint.alpha = 255
            canvas.drawBitmap(handsLayer, 0f, 0f, paint)
            paint.alpha = 255
        }
    }

    private companion object {
        val GREEN = Color(0xFF30D158)
        val RED = Color(0xFFFF453A)
    }
}
