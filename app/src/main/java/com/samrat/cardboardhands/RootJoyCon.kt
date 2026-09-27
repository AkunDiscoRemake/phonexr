package com.samrat.cardboardhands

import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import java.io.DataInputStream
import java.io.OutputStreamWriter
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Joy-Con gyroscope through root, for phones whose kernel has no hid-nintendo driver (so Android
 * gives buttons but no motion sensors). With `su`, PhoneXR opens the Joy-Con's raw HID device,
 * turns its IMU on and switches it to the full report mode (0x30): 60 reports a second with three
 * gyroscope and accelerometer samples each. In that mode Android's generic driver no longer sees
 * the buttons, so buttons and sticks are read from the same reports and handed to
 * [JoyConButtons] as if Android had sent them. Stopping puts the Joy-Con back to its simple mode.
 */
object RootJoyCon {
    class Pose(val x: Float, val y: Float, val z: Float, val w: Float, val degreesPerSecond: Float, val rateHz: Float)

    private class Pad(val left: Boolean, val node: String) {
        @Volatile var running = true
        var reader: Process? = null
        var shell: Process? = null
        var writer: OutputStreamWriter? = null
        var counter = 0
        @Volatile var q = floatArrayOf(0f, 0f, 0f, 1f)
        val bias = FloatArray(3)
        var biasSamples = 0
        @Volatile var dps = 0f
        @Volatile var reports = 0
        var rateStart = SystemClock.uptimeMillis()
        @Volatile var rate = 0f
        var buttons = 0
    }

    private const val TAG = "PhoneXR-RootJoyCon"
    private val pads = arrayOfNulls<Pad>(2)
    @Volatile var active = false
        private set

    /** True when `su` answers as root. Slow the first time (the root manager may ask), call off the main thread. */
    fun rootAvailable(): Boolean = runCatching {
        val process = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        process.waitFor() == 0 && text.contains("uid=0")
    }.getOrDefault(false)

    /** Finds the Joy-Con among the raw HID devices and starts reading them. Returns how many were found. */
    @Synchronized
    fun start(): Int {
        stop(restore = false)
        val listing = runCatching {
            val process = ProcessBuilder("su", "-c",
                "for d in /sys/class/hidraw/hidraw*; do echo \"@\${d##*/}\"; cat \$d/device/uevent; done").start()
            process.inputStream.bufferedReader().readText().also { process.waitFor() }
        }.getOrElse { return 0 }
        var found = 0
        for (block in listing.split('@').drop(1)) {
            val node = block.substringBefore('\n').trim()
            val id = Regex("HID_ID=([0-9A-Fa-f]+):([0-9A-Fa-f]+):([0-9A-Fa-f]+)").find(block) ?: continue
            if (!id.groupValues[2].endsWith("057E", true)) continue
            val product = id.groupValues[3].takeLast(4).uppercase()
            val left = when (product) { "2006" -> true; "2007" -> false; else -> continue }
            val pad = Pad(left, "/dev/$node")
            pads[if (left) 0 else 1] = pad
            open(pad)
            found++
        }
        // No raw HID (kernels built without hidraw): the hid-nintendo driver's IMU input devices.
        if (found == 0) found = openImuDevices()
        active = found > 0
        return found
    }

    /**
     * hid-nintendo gives every Joy-Con a second input device, "… Joy-Con (L) IMU", whose ABS_RX/RY/RZ
     * are the gyroscope. Android often does not attach it to the controller, so PhoneXR reads the
     * events itself (input_event is 24 bytes on a 64-bit kernel). The driver keeps the buttons.
     */
    private fun openImuDevices(): Int {
        val listing = runCatching {
            val process = ProcessBuilder("su", "-c", "getevent -pl").start()
            process.inputStream.bufferedReader().readText().also { process.waitFor() }
        }.getOrElse { return 0 }
        var found = 0
        for (block in listing.split("add device").drop(1)) {
            val path = Regex("(/dev/input/event\\d+)").find(block)?.groupValues?.get(1) ?: continue
            val name = Regex("name:\\s+\"([^\"]+)\"").find(block)?.groupValues?.get(1) ?: continue
            val lower = name.lowercase()
            if (!lower.contains("imu") || !(lower.contains("joy-con") || lower.contains("joycon") || lower.contains("nintendo"))) continue
            val left = lower.contains("left") || lower.contains("(l)")
            // Units per degree a second, from the axis' resolution (hid-nintendo: 14247).
            val resolution = Regex("ABS_RX\\s*:.*resolution (\\d+)").find(block)?.groupValues?.get(1)?.toFloatOrNull()?.takeIf { it > 0f } ?: 14247f
            val pad = Pad(left, path)
            pads[if (left) 0 else 1] = pad
            openEvdev(pad, resolution)
            found++
        }
        return found
    }

    private fun openEvdev(pad: Pad, perDps: Float) {
        val reader = ProcessBuilder("su", "-c", "exec cat ${pad.node}").start()
        pad.reader = reader
        thread(name = "PhoneXR Joy-Con IMU ${if (pad.left) "L" else "R"}") {
            val input = DataInputStream(reader.inputStream)
            val event = ByteArray(24)
            val gyro = FloatArray(3)
            var lastUs = 0L
            fun u(i: Int) = event[i].toLong() and 0xff
            fun le64(i: Int) = (0..7).fold(0L) { acc, k -> acc or (u(i + k) shl (8 * k)) }
            try {
                while (pad.running) {
                    input.readFully(event)
                    val type = (u(16) or (u(17) shl 8)).toInt()
                    val code = (u(18) or (u(19) shl 8)).toInt()
                    val value = (u(20) or (u(21) shl 8) or (u(22) shl 16) or (u(23) shl 24)).toInt()
                    when {
                        // ABS_RX, ABS_RY, ABS_RZ: the gyroscope.
                        type == 3 && code in 3..5 -> gyro[code - 3] = value / perDps
                        type == 0 && code == 0 -> {
                            val us = le64(0) * 1_000_000L + le64(8)
                            val dt = if (lastUs == 0L) 0f else ((us - lastUs) / 1e6f).coerceIn(0f, .05f)
                            lastUs = us
                            if (dt > 0f) integrateDps(pad, gyro[0], gyro[1], gyro[2], dt)
                            pad.reports++
                            val now = SystemClock.uptimeMillis()
                            if (now - pad.rateStart >= 1000) {
                                pad.rate = pad.reports * 1000f / (now - pad.rateStart) / 3f
                                pad.reports = 0
                                pad.rateStart = now
                            }
                        }
                    }
                }
            } catch (error: Throwable) {
                if (pad.running) Log.w(TAG, "Joy-Con IMU read stopped", error)
            }
        }
    }

    /** Degrees a second already in the driver's axes (the same ones Android's sensors use). */
    private fun integrateDps(pad: Pad, x: Float, y: Float, z: Float, dt: Float) {
        val scale = PI.toFloat() / 180f
        rotateBy(pad, x * scale, y * scale, z * scale, dt)
    }

    /**
     * Stops reading. [restore] puts the Joy-Con back into the simple mode for Android's own driver;
     * leave it off while another PhoneXR process still reads the full reports.
     */
    @Synchronized
    fun stop(restore: Boolean = true) {
        for (i in pads.indices) {
            val pad = pads[i] ?: continue
            pad.running = false
            if (restore) {
                runCatching { subcommand(pad, 0x03, 0x3F); pad.writer?.flush() }
                SystemClock.sleep(60)
            }
            runCatching { pad.writer?.write("exit\n"); pad.writer?.flush() }
            pad.reader?.destroy()
            pad.shell?.destroy()
            pads[i] = null
        }
        JoyConButtons.clear()
        active = false
    }

    fun pose(left: Boolean): Pose? {
        val pad = pads[if (left) 0 else 1] ?: return null
        val q = pad.q
        return Pose(q[0], q[1], q[2], q[3], pad.dps, pad.rate)
    }

    fun recenter() = pads.forEach { it?.q = floatArrayOf(0f, 0f, 0f, 1f) }

    private fun open(pad: Pad) {
        val shell = ProcessBuilder("su").start()
        pad.shell = shell
        pad.writer = OutputStreamWriter(shell.outputStream)
        // conv=sync pads every read to 64 bytes: one HID report per 64-byte block, whatever its length.
        val reader = ProcessBuilder("su", "-c", "exec dd if=${pad.node} bs=64 conv=sync 2>/dev/null").start()
        pad.reader = reader
        thread(name = "PhoneXR Joy-Con ${if (pad.left) "L" else "R"}") {
            val input = DataInputStream(reader.inputStream)
            val report = ByteArray(64)
            try {
                while (pad.running) {
                    input.readFully(report)
                    if (report[0].toInt() and 0xff == 0x30) onReport(pad, report)
                }
            } catch (error: Throwable) {
                if (pad.running) Log.w(TAG, "Joy-Con read stopped", error)
            }
        }
        thread(name = "PhoneXR Joy-Con setup") {
            // IMU on, then the full report mode that carries it.
            subcommand(pad, 0x40, 0x01)
            SystemClock.sleep(80)
            subcommand(pad, 0x03, 0x30)
        }
    }

    /** Output report 0x01: a neutral rumble and one subcommand, written by the root shell. */
    private fun subcommand(pad: Pad, id: Int, argument: Int) {
        val bytes = ByteArray(49)
        bytes[0] = 0x01
        bytes[1] = (pad.counter++ and 0x0F).toByte()
        val rumble = byteArrayOf(0x00, 0x01, 0x40, 0x40, 0x00, 0x01, 0x40, 0x40)
        rumble.copyInto(bytes, 2)
        bytes[10] = id.toByte()
        bytes[11] = argument.toByte()
        val escaped = bytes.joinToString("") { "\\x%02x".format(it.toInt() and 0xff) }
        pad.writer?.apply {
            write("printf '$escaped' > ${pad.node}\n")
            flush()
        }
    }

    private fun onReport(pad: Pad, r: ByteArray) {
        fun u(i: Int) = r[i].toInt() and 0xff
        fun s16(i: Int) = ((u(i + 1) shl 8) or u(i)).toShort().toFloat()
        // Three IMU samples, 5 ms apart.
        for (sample in 0..2) {
            val o = 13 + sample * 12
            val gx = s16(o + 6); val gy = s16(o + 8); val gz = s16(o + 10)
            integrate(pad, gx, gy, gz, .005f)
        }
        pad.reports++
        val now = SystemClock.uptimeMillis()
        if (now - pad.rateStart >= 1000) {
            pad.rate = pad.reports * 1000f / (now - pad.rateStart)
            pad.reports = 0
            pad.rateStart = now
        }
        buttons(pad, u(3), u(4), u(5))
        sticks(pad, if (pad.left) 6 else 9, r)
    }

    /** Gyroscope (±2000 °/s, 0.061 °/s per unit) into the orientation, with its bias learned while still. */
    private fun integrate(pad: Pad, rx: Float, ry: Float, rz: Float, dt: Float) {
        val scale = (0.06103f * PI.toFloat() / 180f)
        val x = rx * scale; val y = ry * scale; val z = rz * scale
        // Joy-Con axes to the controller frame used by PhoneXR (y up along the Joy-Con, z towards the user).
        rotateBy(pad, -y, z, -x, dt)
    }

    /** Turns the Joy-Con's orientation by an angular speed (rad/s) over [dt], learning the bias while still. */
    private fun rotateBy(pad: Pad, rx: Float, ry: Float, rz: Float, dt: Float) {
        var ax = rx; var ay = ry; var az = rz
        if (sqrt(ax * ax + ay * ay + az * az) < .05f) {
            val k = if (pad.biasSamples < 100) 1f / (++pad.biasSamples) else .01f
            pad.bias[0] += (ax - pad.bias[0]) * k; pad.bias[1] += (ay - pad.bias[1]) * k; pad.bias[2] += (az - pad.bias[2]) * k
        }
        ax -= pad.bias[0]; ay -= pad.bias[1]; az -= pad.bias[2]
        pad.dps = Math.toDegrees(sqrt(ax * ax + ay * ay + az * az).toDouble()).toFloat()
        val q = pad.q
        val h = dt * .5f
        val dq = floatArrayOf(ax * h, ay * h, az * h, 1f)
        val nx = q[3] * dq[0] + q[0] * dq[3] + q[1] * dq[2] - q[2] * dq[1]
        val ny = q[3] * dq[1] - q[0] * dq[2] + q[1] * dq[3] + q[2] * dq[0]
        val nz = q[3] * dq[2] + q[0] * dq[1] - q[1] * dq[0] + q[2] * dq[3]
        val nw = q[3] * dq[3] - q[0] * dq[0] - q[1] * dq[1] - q[2] * dq[2]
        val l = sqrt(nx * nx + ny * ny + nz * nz + nw * nw).coerceAtLeast(1e-6f)
        pad.q = floatArrayOf(nx / l, ny / l, nz / l, nw / l)
    }

    /** Report bits as the key codes Android would have sent, so the user's bindings still apply. */
    private fun buttons(pad: Pad, right: Int, shared: Int, left: Int) {
        val pressed = HashSet<Int>()
        if (pad.left) {
            if (left and 0x01 != 0) pressed += KeyEvent.KEYCODE_DPAD_DOWN
            if (left and 0x02 != 0) pressed += KeyEvent.KEYCODE_DPAD_UP
            if (left and 0x04 != 0) pressed += KeyEvent.KEYCODE_DPAD_RIGHT
            if (left and 0x08 != 0) pressed += KeyEvent.KEYCODE_DPAD_LEFT
            if (left and 0x40 != 0) pressed += KeyEvent.KEYCODE_BUTTON_L1
            if (left and 0x80 != 0) pressed += KeyEvent.KEYCODE_BUTTON_L2
            if (shared and 0x01 != 0) pressed += KeyEvent.KEYCODE_BUTTON_SELECT
            if (shared and 0x08 != 0) pressed += KeyEvent.KEYCODE_BUTTON_THUMBL
        } else {
            if (right and 0x01 != 0) pressed += KeyEvent.KEYCODE_BUTTON_Y
            if (right and 0x02 != 0) pressed += KeyEvent.KEYCODE_BUTTON_X
            if (right and 0x04 != 0) pressed += KeyEvent.KEYCODE_BUTTON_B
            if (right and 0x08 != 0) pressed += KeyEvent.KEYCODE_BUTTON_A
            if (right and 0x40 != 0) pressed += KeyEvent.KEYCODE_BUTTON_R1
            if (right and 0x80 != 0) pressed += KeyEvent.KEYCODE_BUTTON_R2
            if (shared and 0x02 != 0) pressed += KeyEvent.KEYCODE_BUTTON_START
            if (shared and 0x04 != 0) pressed += KeyEvent.KEYCODE_BUTTON_THUMBR
            if (shared and 0x10 != 0) pressed += KeyEvent.KEYCODE_BUTTON_MODE
        }
        val mask = Settings.KNOWN_KEYS.foldIndexed(0) { i, acc, key -> if (key in pressed) acc or (1 shl i) else acc }
        if (mask == pad.buttons) return
        val device = InputDevice.getDeviceIds().firstOrNull { id ->
            InputDevice.getDevice(id)?.let { JoyConButtons.isJoyCon(it) && JoyConButtons.isLeft(it) == pad.left } == true
        } ?: return
        val now = SystemClock.uptimeMillis()
        Settings.KNOWN_KEYS.forEachIndexed { i, key ->
            val was = pad.buttons and (1 shl i) != 0
            val isDown = mask and (1 shl i) != 0
            if (was != isDown) {
                JoyConButtons.onKey(KeyEvent(now, now, if (isDown) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP, key, 0, 0, device, 0))
            }
        }
        pad.buttons = mask
    }

    /** The stick, 12 bits a side around 2048, as −1..1 with the Joy-Con held upright. */
    private fun sticks(pad: Pad, at: Int, r: ByteArray) {
        val b0 = r[at].toInt() and 0xff; val b1 = r[at + 1].toInt() and 0xff; val b2 = r[at + 2].toInt() and 0xff
        val x = ((b0 or ((b1 and 0x0F) shl 8)) - 2048) / 1500f
        val y = (((b1 shr 4) or (b2 shl 4)) - 2048) / 1500f
        JoyConButtons.setStick(pad.left, dead(x), dead(y))
    }

    private fun dead(v: Float): Float {
        val c = v.coerceIn(-1f, 1f)
        return if (abs(c) < .12f) 0f else c
    }
}
