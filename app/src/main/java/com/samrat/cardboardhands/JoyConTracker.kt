package com.samrat.cardboardhands

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.InputDevice
import kotlin.math.sqrt

/** Reads motion sensors exposed by paired Joy-Con through Android's controller API. */
class JoyConTracker(context: Context) : AutoCloseable, InputManager.InputDeviceListener {
    data class Pose(
        val connected: Boolean = false,
        val x: Float = 0f,
        val y: Float = 0f,
        val z: Float = 0f,
        val w: Float = 1f
    )

    /** What the gyro check shows: which motion sensors Android gave and whether data really flows. */
    data class Motion(
        val connected: Boolean = false,
        val sensors: List<String> = emptyList(),
        /** Events per second over the last second; 0 when nothing arrives. */
        val rateHz: Float = 0f,
        /** Rotation speed from the gyroscope, degrees per second. */
        val degreesPerSecond: Float = 0f,
        val events: Long = 0
    )

    private val inputManager = context.getSystemService(InputManager::class.java)
    private val thread = HandlerThread("PhoneXR Joy-Con").apply { start() }
    private val handler = Handler(thread.looper)
    private val slots = arrayOf(Slot(), Slot())

    init {
        inputManager.registerInputDeviceListener(this, handler)
        handler.post { refresh() }
    }

    /** Android's own Joy-Con sensors when the kernel has them, otherwise the root reader's gyroscope. */
    fun pose(left: Boolean): Pose {
        val slot = slots[if (left) 0 else 1]
        if (slot.hasMotion) return slot.snapshot()
        val root = RootJoyCon.pose(left) ?: return slot.snapshot()
        return Pose(true, root.x, root.y, root.z, root.w)
    }

    /** True when this Joy-Con is paired and a gyroscope for it is read (Android's or through root). */
    fun hasMotion(left: Boolean): Boolean = slots[if (left) 0 else 1].hasMotion || RootJoyCon.pose(left) != null

    fun motion(left: Boolean): Motion {
        val slot = slots[if (left) 0 else 1]
        val own = slot.motion()
        if (slot.hasMotion) return own
        val root = RootJoyCon.pose(left) ?: return own
        return Motion(true, listOf("гироскоп (root)", "акселерометр (root)"), root.rateHz * 3, root.degreesPerSecond, own.events)
    }

    /** Makes the current Joy-Con orientation the new "straight ahead". */
    fun recenter() = handler.post { slots.forEach { it.recenter() }; RootJoyCon.recenter() }

    override fun onInputDeviceAdded(deviceId: Int) = refresh()
    override fun onInputDeviceRemoved(deviceId: Int) = refresh()
    override fun onInputDeviceChanged(deviceId: Int) = refresh()

    private fun refresh() {
        if (Build.VERSION.SDK_INT < 31) return
        slots.forEach { it.detach() }
        for (deviceId in InputDevice.getDeviceIds()) {
            val device = InputDevice.getDevice(deviceId) ?: continue
            if (!JoyConButtons.isJoyCon(device)) continue
            slots[if (JoyConButtons.isLeft(device)) 0 else 1].attach(device)
        }
    }

    private inner class Slot : SensorEventListener {
        @Volatile private var pose = Pose()
        var attached = false
            private set
        private var manager: SensorManager? = null
        private var rotation: Sensor? = null
        private var gyro: Sensor? = null
        private var lastGyroNs = 0L
        private var reference: FloatArray? = null
        private var sensorNames = emptyList<String>()
        @Volatile private var events = 0L
        @Volatile private var degreesPerSecond = 0f
        /** Event timestamps (ms, uptime) of the last second, for the rate. */
        private val recent = ArrayDeque<Long>()

        fun attach(device: InputDevice) {
            if (Build.VERSION.SDK_INT < 31) return
            // Buttons work without motion sensors; some kernels (e.g. Samsung 5.10) expose no Joy-Con IMU.
            if (!attached) pose = Pose(connected = true)
            attached = true
            if (manager != null) return
            val sensors = device.sensorManager
            rotation = sensors.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
                ?: sensors.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
            sensorNames = sensors.getSensorList(Sensor.TYPE_ALL).map { sensorName(it) }.distinct()
            val sensor = rotation ?: gyro ?: return
            if (sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, handler)) manager = sensors
            // The gyroscope also feeds the rotation speed when orientation comes from a rotation vector.
            if (rotation != null) gyro?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        }

        fun detach() {
            manager?.unregisterListener(this)
            manager = null
            rotation = null
            gyro = null
            attached = false
            lastGyroNs = 0L
            reference = null
            pose = Pose()
            sensorNames = emptyList()
            events = 0
            degreesPerSecond = 0f
            synchronized(recent) { recent.clear() }
        }

        fun motion(): Motion {
            val now = android.os.SystemClock.uptimeMillis()
            val rate = synchronized(recent) {
                while (recent.isNotEmpty() && now - recent.first() > 1000) recent.removeFirst()
                recent.size.toFloat()
            }
            return Motion(attached, sensorNames, rate, if (rate > 0) degreesPerSecond else 0f, events)
        }

        fun snapshot() = pose

        val hasMotion: Boolean get() = manager != null

        fun recenter() {
            reference = null
            lastGyroNs = 0L
            if (attached) pose = Pose(connected = true)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        override fun onSensorChanged(event: SensorEvent) {
            events++
            synchronized(recent) { recent.addLast(android.os.SystemClock.uptimeMillis()) }
            if (event.sensor.type == Sensor.TYPE_GYROSCOPE) {
                val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                degreesPerSecond = Math.toDegrees(sqrt(x * x + y * y + z * z).toDouble()).toFloat()
                // With a rotation vector the orientation comes from it, the gyroscope only reports speed.
                if (rotation != null) return
            }
            if (event.sensor.type == Sensor.TYPE_GAME_ROTATION_VECTOR || event.sensor.type == Sensor.TYPE_ROTATION_VECTOR) {
                val raw = FloatArray(4)
                SensorManager.getQuaternionFromVector(raw, event.values)
                val current = floatArrayOf(raw[1], raw[2], raw[3], raw[0])
                if (reference == null) reference = floatArrayOf(-current[0], -current[1], -current[2], current[3])
                val relative = multiply(reference!!, current)
                pose = Pose(true, relative[0], relative[1], relative[2], relative[3])
                return
            }
            if (event.sensor.type != Sensor.TYPE_GYROSCOPE) return
            val previous = lastGyroNs
            lastGyroNs = event.timestamp
            if (previous == 0L) return
            val dt = ((event.timestamp - previous) / 1_000_000_000.0f).coerceIn(0f, .05f)
            val current = pose
            val delta = floatArrayOf(event.values[0] * dt * .5f, event.values[1] * dt * .5f, event.values[2] * dt * .5f, 1f)
            val next = multiply(floatArrayOf(current.x, current.y, current.z, current.w), delta)
            pose = Pose(true, next[0], next[1], next[2], next[3])
        }

        private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
            val x = a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1]
            val y = a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0]
            val z = a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3]
            val w = a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2]
            val length = sqrt(x * x + y * y + z * z + w * w).coerceAtLeast(.0001f)
            return floatArrayOf(x / length, y / length, z / length, w / length)
        }
    }

    private fun sensorName(sensor: Sensor) = when (sensor.type) {
        Sensor.TYPE_GYROSCOPE -> "гироскоп"
        Sensor.TYPE_ACCELEROMETER -> "акселерометр"
        Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> "ориентация"
        else -> sensor.stringType.substringAfterLast('.')
    }

    override fun close() {
        inputManager.unregisterInputDeviceListener(this)
        slots.forEach { it.detach() }
        thread.quitSafely()
    }
}
