package com.samrat.cardboardhands

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt

/** Quest-style hand gestures from MediaPipe landmarks, and a filter that keeps tracking calm. */
object HandGestures {
    data class Shape(
        /** Thumb and index tips together: click. */
        val pinch: Boolean,
        /** How close to a pinch, 0 (open) to 1 (touching). */
        val pinchStrength: Float,
        /** Fingers curled into a fist: grab and move. */
        val fist: Boolean,
        /** The palm faces the user (the camera sees the back of the hand): system menu when pinching. */
        val palmToFace: Boolean,
        /** Point between thumb and index tips, image coordinates 0..1. */
        val pinchX: Float,
        val pinchY: Float,
        /** Palm width in image widths; bigger is closer to the camera. */
        val palmWidth: Float,
        /**
         * Aim point between the thumb and index bases: follows the hand but not the fingertips, so
         * the cursor stays put while the fingers pinch (a pinch no longer "jumps" the click).
         */
        val aimX: Float = pinchX,
        val aimY: Float = pinchY,
        /** Thumb-index distance in palm widths; feed it to a [PinchLatch] for a steady click. */
        val pinchGap: Float = 1f,
        /** Fingertip cursor and pose used for tablet-like direct touch on app windows. */
        val indexX: Float = aimX,
        val indexY: Float = aimY,
        val indexExtended: Boolean = false,
        /** Middle of the palm (wrist and knuckles), image coordinates: where a fist grabs. */
        val palmX: Float = aimX,
        val palmY: Float = aimY,
        /** Index out, middle and ring folded: the pose that presses. An open hand never does. */
        val pointing: Boolean = false,
    )

    /**
     * [physicalLeft] is the user's real hand. MediaPipe's handedness assumes a mirrored selfie
     * image; with the back camera it is swapped, which the caller has already undone.
     */
    fun shape(p: List<NormalizedLandmark>, physicalLeft: Boolean): Shape {
        fun d(a: Int, b: Int): Float {
            val dx = p[a].x() - p[b].x()
            val dy = p[a].y() - p[b].y()
            return sqrt(dx * dx + dy * dy)
        }
        val palm = d(5, 17).coerceAtLeast(.02f)
        // Pinch hysteresis is applied by the caller; here only the distance relative to the palm.
        val gap = d(4, 8) / palm
        val strength = ((.9f - gap) / .6f).coerceIn(0f, 1f)
        val tips = intArrayOf(12, 16, 20)
        val pips = intArrayOf(10, 14, 18)
        val others = tips.indices.count { d(0, tips[it]) < d(0, pips[it]) * 1.04f }
        val indexCurled = d(0, 8) < d(0, 6) * 1.04f
        // A fist has every finger folded. A pinch with the index bent and the other fingers open
        // (or closed) is not a fist.
        val fist = indexCurled && others >= 2
        // Winding of wrist -> index base -> pinky base: for the back camera, a right hand shows its
        // back (palm toward the user) when this turns clockwise in image space.
        val ax = p[5].x() - p[0].x(); val ay = p[5].y() - p[0].y()
        val bx = p[17].x() - p[0].x(); val by = p[17].y() - p[0].y()
        val cross = ax * by - ay * bx
        val palmToFace = if (physicalLeft) cross < 0 else cross > 0
        return Shape(
            pinch = !fist && gap < .35f,
            pinchStrength = strength,
            fist = fist,
            palmToFace = palmToFace && abs(cross) > palm * palm * .15f,
            pinchX = (p[4].x() + p[8].x()) / 2,
            pinchY = (p[4].y() + p[8].y()) / 2,
            palmWidth = palm,
            aimX = p[2].x() * .3f + p[5].x() * .45f + (p[4].x() + p[8].x()) / 2 * .25f,
            aimY = p[2].y() * .3f + p[5].y() * .45f + (p[4].y() + p[8].y()) / 2 * .25f,
            pinchGap = gap,
            indexX = p[8].x(),
            indexY = p[8].y(),
            indexExtended = d(0, 8) > d(0, 6) * 1.12f,
            pointing = d(0, 8) > d(0, 6) * 1.12f && d(0, 12) < d(0, 10) * 1.08f && d(0, 16) < d(0, 14) * 1.08f,
            palmX = (p[0].x() + p[5].x() + p[9].x() + p[17].x()) / 4,
            palmY = (p[0].y() + p[5].y() + p[9].y() + p[17].y()) / 4,
        )
    }

    /**
     * Thumb–index gap in palm widths from MediaPipe's metric landmarks: the same whichever way the
     * hand is turned. Scaled to match [Shape.pinchGap] (fingertips touching ≈ 0.2).
     */
    fun pinchGap3d(world: List<com.google.mediapipe.tasks.components.containers.Landmark>): Float? {
        if (world.size < 21) return null
        fun d(a: Int, b: Int): Float {
            val x = world[a].x() - world[b].x(); val y = world[a].y() - world[b].y(); val z = world[a].z() - world[b].z()
            return sqrt(x * x + y * y + z * z)
        }
        val palm = d(5, 17)
        if (palm < 1e-3f) return null
        return d(4, 8) / palm * .8f
    }

    /**
     * How far the index fingertip is from the eyes, from the hand's size: MediaPipe gives the hand
     * in metres ([world]) and on the picture ([image], mapped to view tangents by [scaleX] and
     * [scaleY]); their ratio is the distance. Several bones are summed so a turned hand still
     * measures right. The unit follows the view mapping, so it is compared with a reach measured
     * the same way rather than with real centimetres.
     */
    fun tipDepth(image: List<NormalizedLandmark>, world: List<com.google.mediapipe.tasks.components.containers.Landmark>, scaleX: Float, scaleY: Float): Float {
        if (image.size < 21 || world.size < 21) return 0f
        var metres = 0f
        var tangent = 0f
        for (k in DEPTH_BONES.indices step 2) {
            val a = DEPTH_BONES[k]; val b = DEPTH_BONES[k + 1]
            metres += kotlin.math.hypot(world[a].x() - world[b].x(), world[a].y() - world[b].y())
            tangent += kotlin.math.hypot((image[a].x() - image[b].x()) * 2f * scaleX, (image[a].y() - image[b].y()) * 2f * scaleY)
        }
        if (tangent < 1e-4f) return 0f
        val palm = metres / tangent
        val centre = (world[0].z() + world[5].z() + world[9].z() + world[13].z() + world[17].z()) / 5f
        // MediaPipe's z grows away from the camera, so a finger pushed forward adds to the palm's distance.
        return palm + (world[8].z() - centre)
    }

    /**
     * The hand in 3D around the head, in metres (x right, y up, −z ahead), for WebXR pages:
     * the hand's shape from MediaPipe's metric landmarks, placed at the distance its size on the
     * picture says, along the ray through the middle knuckle. 63 floats, or null.
     */
    fun headPoints(image: List<NormalizedLandmark>, world: List<com.google.mediapipe.tasks.components.containers.Landmark>, scaleX: Float, scaleY: Float): FloatArray? {
        if (image.size < 21 || world.size < 21) return null
        var metres = 0f
        var tangent = 0f
        for (k in DEPTH_BONES.indices step 2) {
            val a = DEPTH_BONES[k]; val b = DEPTH_BONES[k + 1]
            metres += kotlin.math.hypot(world[a].x() - world[b].x(), world[a].y() - world[b].y())
            tangent += kotlin.math.hypot((image[a].x() - image[b].x()) * 2f * scaleX, (image[a].y() - image[b].y()) * 2f * scaleY)
        }
        if (tangent < 1e-4f) return null
        val distance = (metres / tangent).coerceIn(.12f, 1.2f)
        val ax = (image[9].x() - .5f) * 2f * scaleX * distance
        val ay = (.5f - image[9].y()) * 2f * scaleY * distance
        val out = FloatArray(63)
        for (i in 0 until 21) {
            out[i * 3] = ax + (world[i].x() - world[9].x())
            out[i * 3 + 1] = ay - (world[i].y() - world[9].y())
            out[i * 3 + 2] = -distance - (world[i].z() - world[9].z())
        }
        return out
    }

    private val DEPTH_BONES = intArrayOf(0, 5, 0, 17, 5, 17, 0, 9, 5, 9, 9, 13, 13, 17)

    /**
     * Tablet-like touch in the air, as a push: the pointing fingertip presses when it moves forward
     * by a share of its distance ([push], about 14 % — some 6–7 cm at arm's length) from where it
     * has been resting, and lets go when it comes back half of that. Measured against the hand's own
     * resting place, it needs no calibration and works whatever unit the distance is in, and a
     * hand that is merely raised or held out never presses anything.
     */
    class TouchLatch(private val push: () -> Float) {
        var touching = false
            private set
        /** 0 (resting) .. 1 (pressing): how far into a press the fingertip is. */
        var closeness = 0f
            private set
        private var baseline = 0f
        private var frames = 0

        fun update(depth: Float, pointing: Boolean): Boolean {
            if (depth <= 0f || !pointing) {
                reset()
                return false
            }
            if (frames++ == 0) baseline = depth
            val share = push()
            if (!touching) {
                // The resting place drifts with the hand (about a second), but not while pressing.
                baseline += (depth - baseline) * .06f
                // A few frames to settle after the hand appears or starts pointing.
                touching = frames > 6 && depth > baseline * (1f + share)
            } else {
                touching = depth > baseline * (1f + share * .5f)
                if (!touching) baseline = depth
            }
            closeness = ((depth / baseline - 1f) / share).coerceIn(0f, 1f)
            return touching
        }

        fun reset() {
            touching = false
            closeness = 0f
            frames = 0
        }
    }

    /**
     * A pinch that starts when the fingers really touch and ends only once they clearly open, so a
     * click does not flicker on and off when the fingers hover near each other.
     */
    class PinchLatch(private val close: Float = .30f, private val open: Float = .48f) {
        var pinching = false
            private set

        fun update(shape: Shape): Boolean {
            pinching = if (shape.fist) false else if (pinching) shape.pinchGap < open else shape.pinchGap < close
            return pinching
        }

        fun reset() {
            pinching = false
        }
    }

    /**
     * One Euro filter (Casiez et al.): strong smoothing when the hand is still, little lag when it
     * moves fast. This is what keeps the cursor and controllers from shaking.
     */
    class OneEuro(
        private val minCutoff: Float = 1.2f,
        private val beta: Float = .6f,
        private val derivativeCutoff: Float = 1f,
        /** Changes smaller than this are ignored while the hand rests: no tremor at all when still. */
        private val deadZone: Float = 0f,
    ) {
        private var value = Float.NaN
        private var shown = Float.NaN
        private var derivative = 0f
        private var lastNs = 0L

        fun filter(raw: Float, timeNs: Long): Float {
            if (value.isNaN() || lastNs == 0L) {
                value = raw
                shown = raw
                lastNs = timeNs
                return raw
            }
            val dt = ((timeNs - lastNs) / 1e9f).coerceIn(1e-3f, .2f)
            lastNs = timeNs
            val rawDerivative = (raw - value) / dt
            derivative += alpha(derivativeCutoff, dt) * (rawDerivative - derivative)
            val cutoff = minCutoff + beta * abs(derivative)
            value += alpha(cutoff, dt) * (raw - value)
            // Hysteresis: the output follows only once the filtered value leaves the dead zone.
            val gap = value - shown
            if (abs(gap) > deadZone) shown = value - deadZone * kotlin.math.sign(gap)
            return shown
        }

        fun reset() {
            value = Float.NaN
            shown = Float.NaN
            derivative = 0f
            lastNs = 0L
        }

        private fun alpha(cutoff: Float, dt: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * cutoff)
            return 1f / (1f + tau / dt)
        }
    }
}
