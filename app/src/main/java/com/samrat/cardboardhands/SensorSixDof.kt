package com.samrat.cardboardhands

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.opengl.Matrix
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * PhoneXR's own 6DoF, without ARCore and without the camera: two parts that are added up.
 *
 * - **The neck.** The eyes sit in front of and above the neck they turn on, so every nod, tilt and
 *   turn of the head also moves them — by several centimetres. It comes from the rotation alone,
 *   so it is always right and never drifts; this is most of what makes near things move as they
 *   should when the head moves.
 * - **The body.** Leaning and short steps from the accelerometer (after AccelPositionTracker from
 *   the 6dof set). Integrated acceleration always runs away within seconds, so here the sensor's
 *   bias is learnt while the head is still and taken off, the speed dies out quickly, and the
 *   position eases back to the centre by itself: moves are felt, and nothing flies away.
 *
 * The acceleration is turned into the VR world with the head tracker's own rotation, so "forward"
 * here is the same "forward" the head looks in.
 */
class SensorSixDof(private val sensors: SensorManager, private val head: HeadTracker) : SensorEventListener {
    /** The body part of the position, metres. */
    private val body = FloatArray(3)
    private val velocity = FloatArray(3)
    private val bias = FloatArray(3)
    private val raw = FloatArray(3)
    private val world = FloatArray(3)
    private var lastNs = 0L
    private var stillFrames = 0

    val available get() = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) != null

    fun start() {
        sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST)
        }
    }

    fun stop() = sensors.unregisterListener(this)

    /** Back to the centre, standing still. */
    fun reset() = synchronized(body) {
        body.fill(0f)
        velocity.fill(0f)
        lastNs = 0L
        stillFrames = 0
    }

    /** Where the eyes are now (the neck and the body together), metres, into [out]. */
    fun current(out: FloatArray) {
        val m = FloatArray(16)
        head.copyHead(m)
        // The eyes relative to the neck, turned with the head, minus where they are looking ahead.
        val eye = floatArrayOf(0f, NECK_UP, -NECK_FORWARD, 0f)
        val turned = FloatArray(4)
        Matrix.multiplyMV(turned, 0, m, 0, eye, 0)
        synchronized(body) {
            out[0] = turned[0] - eye[0] + body[0]
            out[1] = turned[1] - eye[1] + body[1]
            out[2] = turned[2] - eye[2] + body[2]
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (lastNs == 0L) { lastNs = event.timestamp; return }
        val dt = ((event.timestamp - lastNs) / 1e9f).coerceIn(0f, .05f)
        lastNs = event.timestamp
        val a = event.values
        val magnitude = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        val still = magnitude < STILL
        stillFrames = if (still) stillFrames + 1 else 0
        // While still, what the sensor reads is its own bias: learn it slowly and take it off.
        if (still) for (i in 0..2) bias[i] += (a[i] - bias[i]) * .02f
        for (i in 0..2) raw[i] = a[i] - bias[i]
        if (!head.deviceToWorld(raw, world)) return
        val speedFade = exp(-dt / SPEED_FADE_S)
        val homeFade = exp(-dt / HOME_S)
        synchronized(body) {
            for (i in 0..2) {
                velocity[i] = if (stillFrames >= STILL_FRAMES) 0f else (velocity[i] + world[i] * dt) * speedFade
                body[i] = ((body[i] + velocity[i] * dt * GAIN) * homeFade).coerceIn(-MAX_REACH, MAX_REACH)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** From the neck's pivot to the eyes, metres: up and forward. */
        const val NECK_UP = .075f
        const val NECK_FORWARD = .08f
        /** m/s² under which the head counts as still. */
        const val STILL = .15f
        /** Still readings in a row before the speed is stopped. */
        const val STILL_FRAMES = 6
        /** How quickly a move's speed dies out, seconds. */
        const val SPEED_FADE_S = .35f
        /** How quickly the body eases back to the centre, seconds: no drift can build up. */
        const val HOME_S = 1.6f
        /** A little more movement than measured, so a lean is felt. */
        const val GAIN = 1.4f
        /** The body never goes further than this from the centre, metres. */
        const val MAX_REACH = .6f
    }
}
