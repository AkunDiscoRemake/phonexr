package com.samrat.cardboardhands

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import kotlin.math.max
import kotlin.math.min

/**
 * First start of the headset: "Привет", "Hello" and "你好" on an app card that turns light, dark,
 * light every three seconds, then one card with hello in the world's languages, then
 * the hands, the user's name, the room
 * room scan (6DoF), a reach calibration for touching, and "Welcome" before the home screen appears.
 */
class Onboarding(private val context: Context, private val host: Host) {
    interface Host {
        val sixDof: Boolean
        /** The room scan has found a table (for the keyboard to lie on). */
        fun tableFound(): Boolean
        fun finish()
    }

    enum class Step { GREETING, HELLO, ACCOUNT, HANDS, NAME, ROOM, REACH, WELCOME }

    var step = Step.GREETING
        private set
    val bitmap: Bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bitmap)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val script: Typeface = runCatching { Typeface.createFromAsset(context.assets, "fonts/Borel-Regular.ttf") }.getOrDefault(Typeface.DEFAULT)
    private var stepStart = SystemClock.elapsedRealtime()
    private val buttons = ArrayList<Pair<RectF, () -> Unit>>()
    private val keyboard = KeyboardPanel()
    private var name = Settings.userName(context)
    private var hover: String? = null

    // Hands scan and reach calibration.
    private var bothHandsSince = 0L
    private var pushes = 0
    private var armed = true
    private var peak = 0f

    private fun go(next: Step) {
        step = next
        stepStart = SystemClock.elapsedRealtime()
        bothHandsSince = 0L
    }

    private fun elapsed() = (SystemClock.elapsedRealtime() - stepStart) / 1000f

    /** Hands from the camera: both open hands for the scan. */
    @Synchronized
    fun onHands(hands: List<Pair<Boolean, HandGestures.Shape>>, points: List<FloatArray>) {
        val now = SystemClock.elapsedRealtime()
        when (step) {
            Step.HANDS -> {
                val ready = hands.size >= 2 && hands.none { it.second.fist }
                if (!ready) { bothHandsSince = 0L; return }
                if (bothHandsSince == 0L) bothHandsSince = now
                if (now - bothHandsSince > SCAN_MS) {
                    HandProfile.save(context, points)
                    go(Step.NAME)
                }
            }
            else -> Unit
        }
    }

    /** The pointing fingertip's distance (see [HandGestures.tipDepth]): three pushes to the full reach. */
    @Synchronized
    fun onReach(depth: Float, pointing: Boolean) {
        if (step != Step.REACH || !pointing || depth <= 0f) return
        peak = max(peak, depth)
        if (armed && depth > peak * .92f && elapsed() > 1f) {
            armed = false
            pushes++
            if (pushes >= 3) {
                HandProfile.saveReach(context, peak)
                go(Step.WELCOME)
            }
        } else if (depth < peak * .7f) armed = true
    }

    /** A touch on the panel at (u, v). */
    @Synchronized
    fun press(u: Float, v: Float) {
        val x = u * WIDTH; val y = v * HEIGHT
        if (step == Step.ACCOUNT && accountMode != 0 && keyboardRect.contains(x, y)) {
            val key = keyboard.press((x - keyboardRect.left) / keyboardRect.width(), (y - keyboardRect.top) / keyboardRect.height())
            when (key) {
                null, KeyboardPanel.HIDE -> Unit
                KeyboardPanel.BACKSPACE -> type("backspace")
                KeyboardPanel.ENTER -> type("enter")
                else -> type(key)
            }
            return
        }
        if (step == Step.NAME && keyboardRect.contains(x, y)) {
            when (val key = keyboard.press((x - keyboardRect.left) / keyboardRect.width(), (y - keyboardRect.top) / keyboardRect.height())) {
                null -> Unit
                KeyboardPanel.BACKSPACE -> name = name.dropLast(1)
                KeyboardPanel.ENTER, KeyboardPanel.HIDE -> confirmName()
                else -> if (name.length < 24) name += key
            }
            return
        }
        buttons.firstOrNull { it.first.contains(x, y) }?.second?.invoke()
    }

    /** A real keyboard: the name is typed on it too. */
    @Synchronized
    fun type(key: String) {
        if (step == Step.ACCOUNT && accountMode != 0) {
            if (busy) return
            val value = when (field) { 0 -> email; 1 -> password; else -> name }
            val next = when (key) {
                "backspace" -> value.dropLast(1)
                "enter" -> {
                    // Enter goes to the next field, and from the last one signs in.
                    val last = if (accountMode == 2) 2 else 1
                    if (field < last) field++ else submitAccount()
                    return
                }
                else -> if (value.length < 64) value + key else value
            }
            when (field) { 0 -> email = next; 1 -> password = next; else -> name = next }
            message = null
            return
        }
        if (step != Step.NAME) { if (key == "enter") buttons.lastOrNull()?.second?.invoke(); return }
        when (key) {
            "backspace" -> name = name.dropLast(1)
            "enter" -> confirmName()
            else -> if (name.length < 24) name += key
        }
    }

    @Synchronized
    fun hover(u: Float, v: Float) {
        hover = if (step == Step.NAME || step == Step.ACCOUNT && accountMode != 0) {
            val x = u * WIDTH; val y = v * HEIGHT
            if (keyboardRect.contains(x, y)) keyboard.hovered((x - keyboardRect.left) / keyboardRect.width(), (y - keyboardRect.top) / keyboardRect.height()) else null
        } else null
    }

    // ------------------------------------------------------------------ the account, in VR

    /** 0: sign in, create or later; 1: signing in; 2: creating an account. */
    private var accountMode = 0
    /** The field being typed: 0 e-mail, 1 password, 2 name. */
    private var field = 0
    private var email = ""
    private var password = ""
    @Volatile private var busy = false
    @Volatile private var message: String? = null

    /**
     * The PhoneXR account, like on Quest: sign in or create one right here with the VR keyboard,
     * or leave it for later (the phone app asks again).
     */
    private fun account(t: Float) {
        card()
        if (accountMode == 0) {
            title(tr("Аккаунт PhoneXR"))
            body(tr("Войдите, чтобы звонить друзьям, видеть их в VR и сохранять настройки. Можно и позже — в приложении на телефоне."))
            button(RectF(WIDTH / 2f - 420f, 620f, WIDTH / 2f - 20f, 710f), tr("Войти")) { openForm(1) }
            button(RectF(WIDTH / 2f + 20f, 620f, WIDTH / 2f + 420f, 710f), tr("Создать аккаунт"), FAINT, INK) { openForm(2) }
            button(RectF(WIDTH / 2f - 150f, 760f, WIDTH / 2f + 150f, 830f), tr("Позже"), FAINT, INK) { go(Step.HANDS) }
            return
        }
        text(if (accountMode == 1) tr("Вход") else tr("Регистрация"), WIDTH / 2f, 105f, 56f, INK, bold = true)
        val rows = if (accountMode == 2) listOf(tr("Почта"), tr("Пароль"), tr("Имя")) else listOf(tr("Почта"), tr("Пароль"))
        val fieldH = 64f
        val gap = 12f
        rows.forEachIndexed { i, label ->
            val top = 135f + i * (fieldH + gap)
            val rect = RectF(300f, top, WIDTH - 300f, top + fieldH)
            paint.color = if (i == field) Color.argb(60, 255, 255, 255) else FAINT
            canvas.drawRoundRect(rect, fieldH / 2, fieldH / 2, paint)
            val value = when (i) { 0 -> email; 1 -> "•".repeat(password.length); else -> name }
            val cursor = if (i == field && (t * 2).toInt() % 2 == 0) "|" else ""
            paint.textAlign = Paint.Align.LEFT
            paint.textSize = 34f
            paint.typeface = Typeface.DEFAULT
            paint.color = if (value.isEmpty()) SOFT else INK
            canvas.drawText(if (value.isEmpty() && i != field) label else value + cursor, rect.left + 36f, rect.centerY() + 12f, paint)
            paint.textAlign = Paint.Align.LEFT
            buttons += rect to { field = i }
        }
        val actions = 135f + rows.size * (fieldH + gap) + 4f
        button(RectF(300f, actions, 560f, actions + 58f), tr("Назад"), FAINT, INK) { accountMode = 0; message = null }
        val go = if (busy) "…" else if (accountMode == 1) tr("Войти") else tr("Создать")
        button(RectF(WIDTH - 560f, actions, WIDTH - 300f, actions + 58f), go) { submitAccount() }
        message?.let { text(it, WIDTH / 2f, actions + 40f, 28f, Color.rgb(211, 47, 47)) }
        keyboard.draw(hover)
        canvas.drawBitmap(keyboard.bitmap, null, keyboardRect, paint)
    }

    private fun openForm(mode: Int) {
        accountMode = mode
        field = 0
        message = null
        // E-mail and passwords are typed in Latin letters.
        keyboard.setRussian(false)
    }

    /** Signs in or creates the account on a background thread; on success setup goes on. */
    private fun submitAccount() {
        if (busy) return
        if (!email.contains('@') || password.length < 6) {
            message = tr("Введите почту и пароль (не короче 6 символов)")
            return
        }
        if (accountMode == 2 && name.isBlank()) { message = tr("Введите имя"); field = 2; return }
        busy = true
        message = null
        val signUp = accountMode == 2
        Thread {
            val error = if (signUp) Account.signUp(context, email.trim(), password, name.trim()) else Account.signIn(context, email.trim(), password)
            synchronized(this) {
                busy = false
                if (error == null) {
                    password = ""
                    name = Settings.userName(context)
                    keyboard.setRussian(true)
                    go(Step.HANDS)
                } else message = error
            }
        }.start()
    }

    private fun confirmName() {
        if (name.isBlank()) return
        Settings.setUserName(context, name.trim())
        if (host.sixDof) go(Step.ROOM) else go(Step.WELCOME)
    }

    /** Redraws the panel for this moment; returns false once setup is over. */
    @Synchronized
    fun draw(): Boolean {
        buttons.clear()
        bitmap.eraseColor(Color.TRANSPARENT)
        val t = elapsed()
        when (step) {
            Step.GREETING -> {
                greeting(t)
                if (t > GREETINGS.size * GREETING_SECONDS) go(Step.HELLO)
            }
            Step.HELLO -> {
                hello(t)
                if (t > 1.5f) button(RectF(WIDTH / 2f - 200f, 850f, WIDTH / 2f + 200f, 930f), tr("Продолжить")) {
                    go(if (Account.current(context) == null) Step.ACCOUNT else Step.HANDS)
                }
            }
            Step.ACCOUNT -> account(t)
            Step.HANDS -> {
                card()
                title(tr("Покажите руки"))
                body(tr("Держите обе руки перед собой, пальцы раскрыты. Не двигайтесь пару секунд."))
                val progress = if (bothHandsSince == 0L) 0f else ((SystemClock.elapsedRealtime() - bothHandsSince) / SCAN_MS.toFloat()).coerceIn(0f, 1f)
                bar(progress)
                button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), "Пропустить", FAINT, INK) { go(Step.NAME) }
            }
            Step.NAME -> {
                card()
                title("Как вас зовут?")
                paint.color = FAINT
                canvas.drawRoundRect(RectF(300f, 220f, WIDTH - 300f, 330f), 55f, 55f, paint)
                text(if (name.isEmpty()) "Имя пользователя" else name + if ((t * 2).toInt() % 2 == 0) "|" else "",
                    WIDTH / 2f, 295f, 60f, if (name.isEmpty()) SOFT else INK)
                button(RectF(WIDTH / 2f - 200f, 360f, WIDTH / 2f + 200f, 440f), "Готово") { confirmName() }
                keyboard.draw(hover)
                canvas.drawBitmap(keyboard.bitmap, null, keyboardRect, paint)
            }
            Step.ROOM -> {
                card()
                title(tr("Сканирование комнаты"))
                body(tr("Осмотрите пол, стены и стол — PhoneXR покроет их сеткой и запомнит, где стоит стол: на нём будет клавиатура."))
                text(if (host.tableFound()) tr("Стол найден ✓") else tr("Ищу стол…"), WIDTH / 2f, 640f, 44f, if (host.tableFound()) Color.rgb(11, 138, 27) else SOFT, bold = true)
                button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), if (host.tableFound()) tr("Готово") else tr("Пропустить"), if (host.tableFound()) BLUE else FAINT, if (host.tableFound()) Color.WHITE else INK) { go(Step.WELCOME) }
            }
            Step.REACH -> {
                card()
                title("Касание")
                body("Окна нажимаются пальцем: вытяните указательный палец, остальные согните, и коротко толкните руку вперёд. Три раза.")
                button(RectF(WIDTH / 2f - 220f, 760f, WIDTH / 2f + 220f, 860f), "Пропустить", FAINT, INK) { go(Step.WELCOME) }
                for (i in 0 until 3) {
                    paint.color = if (i < pushes) Color.rgb(11, 138, 27) else FAINT
                    canvas.drawCircle(WIDTH / 2f + (i - 1) * 90f, 600f, 30f, paint)
                }
            }
            Step.WELCOME -> {
                val hi = Settings.userName(context).takeIf { it.isNotBlank() }
                card()
                written(tr("Добро пожаловать"), t, 150f, HEIGHT / 2f + 20f)
                if (hi != null && t > 1f) text(hi, WIDTH / 2f, HEIGHT / 2f + 150f, 64f, Color.argb(((t - 1f).coerceIn(0f, 1f) * 179).toInt(), 245, 246, 247))
                if (t > 3f) {
                    Settings.setSetupDone(context)
                    host.finish()
                    return false
                }
            }
        }
        return true
    }

    /**
     * The first seconds: an app card in front of the user, light with "Привет", dark with "Hello",
     * light again with "你好" — each for three seconds, the change a quick cross-fade.
     */
    private fun greeting(t: Float) {
        val index = (t / GREETING_SECONDS).toInt().coerceAtMost(GREETINGS.size - 1)
        val local = t - index * GREETING_SECONDS
        val dark = true
        // The card grows in at the very start.
        val appear = (t / .6f).coerceIn(0f, 1f)
        val inset = (1f - appear) * 120f
        val rect = RectF(60f + inset, 40f + inset, WIDTH - 60f - inset, HEIGHT - 40f - inset)
        glass(rect, dark)
        val fadeIn = (local / .4f).coerceIn(0f, 1f)
        val fadeOut = ((GREETING_SECONDS - local) / .4f).coerceIn(0f, 1f)
        val alpha = (minOf(fadeIn, fadeOut) * appear * 255).toInt()
        val ink = INK
        val (word, code) = GREETINGS[index]
        text(word, WIDTH / 2f, HEIGHT / 2f + 60f, 190f, Color.argb(alpha, Color.red(ink), Color.green(ink), Color.blue(ink)), bold = true)
        text(code, WIDTH / 2f, HEIGHT / 2f + 170f, 40f, Color.argb(alpha * 5 / 10, Color.red(ink), Color.green(ink), Color.blue(ink)))
    }

    /** One card with hello in the world's languages, the user's own in the middle, large. */
    private fun hello(t: Float) {
        glass(RectF(60f, 40f, WIDTH - 60f, HEIGHT - 40f), true)
        val columns = 7
        val left = 150f
        val pitchX = (WIDTH - 2 * left) / (columns - 1)
        var index = 0
        for (row in 0 until 5) {
            for (column in 0 until columns) {
                // The middle row leaves room for the big word, the row under it a gap below it.
                if (row == 2 && column in 2..4) continue
                if (row == 3 && column == 3) continue
                val (word, code) = WORLD.getOrNull(index++) ?: continue
                val appear = ((t - index * .025f) / .5f).coerceIn(0f, 1f)
                val x = left + column * pitchX
                val y = 150f + row * 150f
                text(word, x, y, 44f, Color.argb((appear * 255).toInt(), 245, 246, 247))
                text(code, x, y + 42f, 26f, Color.argb((appear * 150).toInt(), 245, 246, 247))
            }
        }
        val big = when (L10n.current) {
            L10n.Lang.RU -> "Привет"
            L10n.Lang.EN -> "Hello"
            else -> "Olá"
        }
        val appear = (t / .6f).coerceIn(0f, 1f)
        text(big, WIDTH / 2f, 150f + 2 * 150f + 40f, 150f, Color.argb((appear * 255).toInt(), 255, 255, 255), bold = true)
    }

    /** The Horizon card: white (or dark) glass with a soft shadow. */
    private fun glass(rect: RectF, dark: Boolean) {
        paint.color = Color.argb(70, 0, 0, 0)
        paint.maskFilter = android.graphics.BlurMaskFilter(30f, android.graphics.BlurMaskFilter.Blur.NORMAL)
        canvas.drawRoundRect(RectF(rect.left, rect.top + 12f, rect.right, rect.bottom + 12f), 80f, 80f, paint)
        paint.maskFilter = null
        // A shader still takes the paint's alpha: full, or the card turns see-through.
        paint.color = Color.WHITE
        paint.shader = if (dark) LinearGradient(0f, rect.top, 0f, rect.bottom, Color.argb(240, 43, 47, 54), Color.argb(240, 29, 32, 38), Shader.TileMode.CLAMP)
        else LinearGradient(0f, rect.top, 0f, rect.bottom, Color.WHITE, Color.rgb(242, 242, 242), Shader.TileMode.CLAMP)
        canvas.drawRoundRect(rect, 80f, 80f, paint)
        paint.shader = null
    }

    /**
     * Script text drawn as if written: revealed from left to right, then held; white, round and a
     * little lit from above like the visionOS lettering.
     */
    private fun written(value: String, t: Float, size: Float, baseline: Float, fadeAt: Float = Float.MAX_VALUE) {
        paint.typeface = script
        paint.textSize = size
        paint.textAlign = Paint.Align.CENTER
        val width = paint.measureText(value)
        val reveal = (t / 1.3f).coerceIn(0f, 1f)
        val eased = 1f - (1f - reveal) * (1f - reveal)
        val alpha = if (t > fadeAt) (1f - (t - fadeAt) / .45f).coerceIn(0f, 1f) else 1f
        val left = WIDTH / 2f - width / 2 - 20f
        canvas.save()
        canvas.clipRect(left, 0f, left + (width + 40f) * eased, HEIGHT.toFloat())
        paint.shader = null
        paint.color = Color.argb((alpha * 25).toInt(), 0, 0, 0)
        canvas.drawText(value, WIDTH / 2f + 4f, baseline + 6f, paint)
        paint.shader = LinearGradient(0f, baseline - size, 0f, baseline, Color.WHITE, Color.rgb(205, 210, 218), Shader.TileMode.CLAMP)
        paint.alpha = (alpha * 255).toInt()
        canvas.drawText(value, WIDTH / 2f, baseline, paint)
        paint.shader = null
        canvas.restore()
        paint.textAlign = Paint.Align.LEFT
        paint.typeface = Typeface.DEFAULT
        paint.alpha = 255
    }

    private fun card() = glass(RectF(60f, 40f, WIDTH - 60f, HEIGHT - 40f), true)

    private fun title(value: String) = text(value, WIDTH / 2f, 170f, 76f, INK, bold = true)

    private fun body(value: String) {
        paint.textSize = 44f
        var line = ""
        var row = 0
        for (word in value.split(' ')) {
            val next = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(next) > WIDTH - 360f) {
                text(line, WIDTH / 2f, 300f + row * 62f, 44f, Color.argb(200, 39, 39, 39)); row++; line = word
            } else line = next
        }
        text(line, WIDTH / 2f, 300f + row * 62f, 44f, Color.argb(200, 39, 39, 39))
    }

    private fun bar(progress: Float) {
        paint.color = FAINT
        canvas.drawRoundRect(RectF(400f, 560f, WIDTH - 400f, 590f), 15f, 15f, paint)
        paint.color = INK
        canvas.drawRoundRect(RectF(400f, 560f, 400f + (WIDTH - 800f) * progress, 590f), 15f, 15f, paint)
    }

    /** A Horizon pill: solid ink with white text, or a faint one with ink text. */
    private fun button(rect: RectF, label: String, color: Int = BLUE, ink: Int = Color.WHITE, action: () -> Unit) {
        paint.color = color
        canvas.drawRoundRect(rect, rect.height() / 2, rect.height() / 2, paint)
        text(label, rect.centerX(), rect.centerY() + 16f, 44f, ink, bold = true)
        buttons += rect to action
    }

    private fun text(value: String, x: Float, y: Float, size: Float, color: Int, bold: Boolean = false) {
        paint.color = color
        paint.textSize = size
        paint.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(value, x, y, paint)
        paint.textAlign = Paint.Align.LEFT
    }

    private val keyboardRect = RectF(40f, 470f, WIDTH - 40f, 470f + (WIDTH - 80f) * KeyboardPanel.HEIGHT / KeyboardPanel.WIDTH)

    companion object {
        const val WIDTH = 1600
        const val HEIGHT = 1000
        private const val SCAN_MS = 2000L
        /** PhoneXR is dark only: white ink on dark glass, Meta's blue for the main button. */
        private val INK = Color.rgb(245, 246, 247)
        private val SOFT = Color.argb(170, 245, 246, 247)
        private val FAINT = Color.argb(34, 255, 255, 255)
        private val BLUE = Color.rgb(24, 119, 242)
        private const val GREETING_SECONDS = 3f
        private val GREETINGS = listOf("Привет" to "RU", "Hello" to "EN", "你好" to "ZH")
        /** Hello in the world's languages, row by row, for the card. */
        private val WORLD = listOf(
            "Hello" to "EN", "Hola" to "ES", "Bonjour" to "FR", "Hallo" to "DE", "Ciao" to "IT", "Olá" to "PT", "こんにちは" to "JA",
            "안녕하세요" to "KO", "你好" to "ZH", "Xin chào" to "VI", "สวัสดี" to "TH", "Selamat" to "ID", "Merhaba" to "TR", "Здравствуйте" to "RU",
            "नमस्ते" to "HI", "مرحبا" to "AR", "Salve" to "LA", "Dia duit" to "GA",
            "Kamusta" to "TL", "Ahoj" to "CS", "Sveiki" to "LV", "Szia" to "HU", "Tere" to "ET", "Kaixo" to "EU",
            "Bok" to "HR", "Hej" to "SV", "Γειά σου" to "EL", "Здравей" to "BG", "Halló" to "IS", "Sawubona" to "ZU", "Molo" to "XH",
        )
    }
}

/**
 * The user's hands as scanned in setup: bone lengths relative to the palm, used to keep the
 * see-through hands steady; and the pinch distances measured in calibration.
 */
object HandProfile {
    private const val PREFS = "hand_profile"

    fun save(context: Context, hands: List<FloatArray>) {
        val hand = hands.firstOrNull() ?: return
        fun d(a: Int, b: Int) = kotlin.math.hypot(hand[a * 3] - hand[b * 3], hand[a * 3 + 1] - hand[b * 3 + 1])
        val palm = d(5, 17).coerceAtLeast(1e-4f)
        val ratios = GhostHand.BONES.joinToString(",") { (a, b) -> "%.4f".format(java.util.Locale.US, d(a, b) / palm) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("bones", ratios).apply()
    }

    /** Bone length / palm width per bone of [GhostHand.BONES], or null before the scan. */
    fun bones(context: Context): FloatArray? = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        .getString("bones", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }?.toFloatArray()
        ?.takeIf { it.size == GhostHand.BONES.size }

    fun savePinch(context: Context, closed: Float, open: Float) {
        val close = (closed + (open - closed) * .3f).coerceIn(.2f, .45f)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("pinch_close", close).putFloat("pinch_open", (close + .16f).coerceAtMost(.7f)).apply()
    }

    /**
     * Where hands cut through the VR content: how much wider than the fingers the cut is and how
     * far it is shifted (head-space tangent units), set in Settings → Калибровка рук.
     */
    data class Mask(val grow: Float, val dx: Float, val dy: Float)

    fun mask(context: Context): Mask {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Mask(prefs.getFloat("mask_grow", 1.1f), prefs.getFloat("mask_dx", 0f), prefs.getFloat("mask_dy", 0f))
    }

    fun saveMask(context: Context, mask: Mask) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putFloat("mask_grow", mask.grow.coerceIn(.7f, 2f))
            .putFloat("mask_dx", mask.dx.coerceIn(-.3f, .3f))
            .putFloat("mask_dy", mask.dy.coerceIn(-.3f, .3f))
            .apply()
    }

    /** Furthest the pointing fingertip went in the setup (see [HandGestures.tipDepth]); a guess before it. */
    fun reach(context: Context): Float = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("reach", .22f)

    fun saveReach(context: Context, reach: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("reach", reach.coerceAtLeast(.05f)).apply()
    }

    /** How far forward (a share of the hand's distance, 0.06..0.30) a finger pushes to press; VR Settings → Руки и касания. */
    fun touchShare(context: Context): Float = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat("touch_push", .14f)

    fun setTouchShare(context: Context, share: Float) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat("touch_push", share.coerceIn(.06f, .3f)).apply()
    }

    /** A pinch latch tuned to this user's fingers. */
    fun latch(context: Context): HandGestures.PinchLatch {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return HandGestures.PinchLatch(prefs.getFloat("pinch_close", .30f), prefs.getFloat("pinch_open", .48f))
    }
}
