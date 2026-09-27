package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Calls between PhoneXR accounts. Everyone signed in is "online" in a lobby (Supabase
 * Realtime presence). A call is a private channel where both sides send, 20 times a second, their
 * voice (IMA ADPCM), how loud they speak and where their hands are. No video of the face leaves
 * the headset.
 */
object Calls {
    enum class State { OFFLINE, IDLE, CALLING, RINGING, IN_CALL }

    data class Contact(val id: String, val name: String, val status: String = "")

    /** What we are doing, shown to friends ("В VR", "Смотрит вместе"…). */
    @Volatile var status: String = ""
        private set

    fun setStatus(text: String) {
        if (text == status) return
        status = text
        val user = me ?: return
        realtime?.track(LOBBY, JSONObject().put("id", user.id).put("name", user.name).put("st", text))
    }

    @Volatile var state = State.OFFLINE
        private set
    @Volatile var online: List<Contact> = emptyList()
        private set
    @Volatile var peer: Contact? = null
        private set
    @Volatile var message: String? = null
    @Volatile var muted = false

    // What the other side sends.
    @Volatile var remoteMouth = 0f
        private set
    @Volatile var remoteRound = .5f
        private set
    @Volatile var remoteTalking = false
        private set
    /** Remote hands: 21 (x, y) points each, camera image coordinates 0..1. */
    @Volatile var remoteHands: List<FloatArray> = emptyList()
        private set
    /** Low-rate camera texture for the hand silhouettes; full edition only. */
    @Volatile var remoteHandImage: Bitmap? = null
        private set

    /** Our own hands (x, y, z per landmark), set by the VR home. */
    @Volatile var localHands: () -> List<FloatArray> = { emptyList() }
    @Volatile var localHandImage: () -> Bitmap? = { null }
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    private var context: Context? = null
    private var me: Account.User? = null
    private var realtime: Realtime? = null
    private var room: String? = null
    private var voice: Voice? = null
    private var sender: ScheduledExecutorService? = null
    private var track: AudioTrack? = null
    private val encoder = Adpcm()
    private var decoder = Adpcm()
    private val pending = ByteArrayOutputStream()
    private var greeted = false
    private var lastHandImageAt = 0L

    /** The page watched together in this call, if any ([share]). */
    @Volatile var sharedUrl: String? = null
        private set
    /** Called when the other person starts watching something together with us. */
    @Volatile var onShared: ((String) -> Unit)? = null
    /** What the other person did to the shared window. */
    @Volatile var onRemoteInput: ((JSONObject) -> Unit)? = null

    /** Opens [url] together: the other side opens the same page in its own shared window. */
    fun share(url: String) {
        sharedUrl = url
        if (state == State.IN_CALL) room?.let { realtime?.broadcast(it, "app", JSONObject().put("url", url)) }
    }

    /** A touch, key or button on the shared window, for the other side to repeat. */
    fun sendInput(input: JSONObject) {
        if (state == State.IN_CALL) room?.let { realtime?.broadcast(it, "in", input) }
    }

    fun listen(listener: () -> Unit) = listeners.add(listener)
    fun unlisten(listener: () -> Unit) = listeners.remove(listener)
    private fun changed() = listeners.forEach { it() }

    /** Goes online if signed in. */
    @Synchronized
    fun start(context: Context) {
        if (realtime != null) return
        this.context = context.applicationContext
        val token = Account.token(context) ?: run { state = State.OFFLINE; changed(); return }
        val user = Account.current(context) ?: return
        me = user
        realtime = Realtime(token, object : Realtime.Listener {
            override fun onConnected() {
                realtime?.join(LOBBY, user.id)
                realtime?.track(LOBBY, JSONObject().put("id", user.id).put("name", user.name).put("st", status))
                state = State.IDLE
                changed()
            }

            override fun onBroadcast(topic: String, event: String, payload: JSONObject) = received(topic, event, payload)

            override fun onPresence(topic: String, members: Map<String, JSONObject>) {
                if (topic != LOBBY) return
                online = members.values.map { Contact(it.optString("id"), it.optString("name"), it.optString("st")) }
                    .filter { it.id.isNotEmpty() && it.id != user.id }.distinctBy { it.id }.sortedBy { it.name.lowercase() }
                changed()
            }

            override fun onClosed() {
                endCall()
                state = State.OFFLINE
                realtime = null
                changed()
            }
        }).also { it.connect() }
    }

    @Synchronized
    fun stop() {
        hangUp()
        realtime?.close()
        realtime = null
        state = State.OFFLINE
        online = emptyList()
        changed()
    }

    fun call(contact: Contact) {
        val user = me ?: return
        if (state != State.IDLE) return
        val id = "call-" + UUID.randomUUID().toString().take(12)
        room = id
        peer = contact
        state = State.CALLING
        message = null
        realtime?.broadcast(LOBBY, "invite", JSONObject().put("to", contact.id).put("from", user.id).put("name", user.name).put("room", id))
        changed()
    }

    fun accept() {
        val user = me ?: return
        val caller = peer ?: return
        if (state != State.RINGING) return
        realtime?.broadcast(LOBBY, "accept", JSONObject().put("to", caller.id).put("from", user.id).put("room", room))
        enterCall()
    }

    fun decline() {
        val user = me ?: return
        val caller = peer ?: return
        realtime?.broadcast(LOBBY, "decline", JSONObject().put("to", caller.id).put("from", user.id).put("room", room))
        reset("Звонок отклонён")
    }

    fun hangUp() {
        when (state) {
            State.IN_CALL -> room?.let { realtime?.broadcast(it, "bye", JSONObject()) }
            State.CALLING -> peer?.let { realtime?.broadcast(LOBBY, "cancel", JSONObject().put("to", it.id).put("room", room)) }
            State.RINGING -> { decline(); return }
            else -> return
        }
        reset("Звонок завершён")
    }

    private fun reset(text: String?) {
        endCall()
        room = null
        peer = null
        sharedUrl = null
        message = text
        if (realtime != null) state = State.IDLE
        changed()
    }

    private fun received(topic: String, event: String, payload: JSONObject) {
        val user = me ?: return
        if (topic == LOBBY) {
            if (payload.optString("to") != user.id) return
            when (event) {
                "invite" -> if (state == State.IDLE) {
                    peer = Contact(payload.optString("from"), payload.optString("name"))
                    room = payload.optString("room")
                    state = State.RINGING
                    changed()
                } else {
                    realtime?.broadcast(LOBBY, "busy", JSONObject().put("to", payload.optString("from")).put("room", payload.optString("room")))
                }
                "accept" -> if (state == State.CALLING && payload.optString("room") == room) enterCall()
                "decline" -> if (payload.optString("room") == room) reset("${peer?.name ?: "Собеседник"} отклонил звонок")
                "busy" -> if (payload.optString("room") == room) reset("${peer?.name ?: "Собеседник"} занят")
                "cancel" -> if (payload.optString("room") == room && state == State.RINGING) reset("Пропущенный звонок")
            }
            return
        }
        if (topic != room) return
        when (event) {
            "hi" -> {
                if (!greeted) { greeted = true; realtime?.broadcast(topic, "hi", JSONObject()) }
                // Someone who joins late gets what is being watched.
                sharedUrl?.let { realtime?.broadcast(topic, "app", JSONObject().put("url", it)) }
            }
            "app" -> payload.optString("url").takeIf { it.isNotEmpty() && it != sharedUrl }?.let { url ->
                sharedUrl = url
                onShared?.invoke(url)
            }
            "in" -> onRemoteInput?.invoke(payload)
            "s" -> {
                remoteMouth = payload.optDouble("m", 0.0).toFloat()
                remoteRound = payload.optDouble("r", .5).toFloat()
                remoteTalking = payload.optBoolean("t")
                remoteHands = decodeHands(payload.optString("h"))
                payload.optString("hv").takeIf { it.isNotEmpty() }?.let { encoded ->
                    runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()?.let { bytes ->
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { remoteHandImage = it }
                    }
                }
                payload.optString("a").takeIf { it.isNotEmpty() }?.let { play(Base64.decode(it, Base64.NO_WRAP)) }
            }
            "bye" -> reset("${peer?.name ?: "Собеседник"} завершил звонок")
        }
    }

    private fun enterCall() {
        val topic = room ?: return
        val ctx = context ?: return
        state = State.IN_CALL
        greeted = false
        decoder = Adpcm()
        realtime?.join(topic)
        realtime?.broadcast(topic, "hi", JSONObject())
        // Our voice: the same cleaned microphone the avatar's mouth uses.
        val v = VoiceHub.acquire(ctx)
        voice = v
        v.onPcm = { frame, count -> synchronized(pending) { pending.write(encoder.encode(frame, count)) } }
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_IN_COMMUNICATION
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(16_000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(16_000 * 2 / 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        sender = Executors.newSingleThreadScheduledExecutor().also { it.scheduleAtFixedRate(::sendState, 50, 50, TimeUnit.MILLISECONDS) }
        changed()
    }

    private fun endCall() {
        sender?.shutdownNow()
        sender = null
        voice?.onPcm = null
        if (voice != null) VoiceHub.release()
        voice = null
        track?.runCatching { stop(); release() }
        track = null
        context?.let { (it.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode = AudioManager.MODE_NORMAL }
        room?.let { realtime?.leave(it) }
        remoteHands = emptyList()
        remoteHandImage = null
        remoteMouth = 0f
        synchronized(pending) { pending.reset() }
    }

    /** 20 times a second: mouth, hands and the voice since the last message. */
    private fun sendState() {
        val topic = room ?: return
        val v = voice
        val audio = synchronized(pending) { pending.toByteArray().also { pending.reset() } }
        val hands = if (BuildConfig.LITE) emptyList() else localHands()
        val state = JSONObject()
            .put("m", if (muted) 0.0 else (v?.mouthOpen ?: 0f).toDouble())
            .put("r", (v?.mouthRound ?: .5f).toDouble())
            .put("t", !muted && v?.talking == true)
            .put("h", encodeHands(hands))
        val now = android.os.SystemClock.elapsedRealtime()
        if (!BuildConfig.LITE && hands.isNotEmpty() && now - lastHandImageAt >= 200L) {
            lastHandImageAt = now
            localHandImage()?.let { image ->
                val bytes = ByteArrayOutputStream().also {
                    @Suppress("DEPRECATION")
                    image.compress(Bitmap.CompressFormat.WEBP, 38, it)
                }.toByteArray()
                image.recycle()
                state.put("hv", Base64.encodeToString(bytes, Base64.NO_WRAP))
            }
        }
        if (!muted && audio.isNotEmpty()) state.put("a", Base64.encodeToString(audio, Base64.NO_WRAP))
        realtime?.broadcast(topic, "s", state)
    }

    private fun play(data: ByteArray) {
        val samples = decoder.decode(data)
        track?.write(samples, 0, samples.size, AudioTrack.WRITE_NON_BLOCKING)
    }

    // ---------------------------------------------------------------- Hands

    private fun encodeHands(hands: List<FloatArray>): String {
        val out = ByteArray(1 + hands.size.coerceAtMost(2) * 42)
        out[0] = hands.size.coerceAtMost(2).toByte()
        hands.take(2).forEachIndexed { h, points ->
            for (k in 0 until 21) {
                out[1 + h * 42 + k * 2] = (points[k * 3].coerceIn(0f, 1f) * 255).toInt().toByte()
                out[1 + h * 42 + k * 2 + 1] = (points[k * 3 + 1].coerceIn(0f, 1f) * 255).toInt().toByte()
            }
        }
        return Base64.encodeToString(out, Base64.NO_WRAP)
    }

    private fun decodeHands(text: String): List<FloatArray> {
        if (text.isEmpty()) return emptyList()
        val data = runCatching { Base64.decode(text, Base64.NO_WRAP) }.getOrNull() ?: return emptyList()
        val count = data.getOrNull(0)?.toInt() ?: return emptyList()
        return (0 until count.coerceAtMost(2)).mapNotNull { h ->
            if (data.size < 1 + (h + 1) * 42) return@mapNotNull null
            FloatArray(42) { (data[1 + h * 42 + it].toInt() and 0xff) / 255f }
        }
    }

    private const val LOBBY = "phonexr-lobby"
}
