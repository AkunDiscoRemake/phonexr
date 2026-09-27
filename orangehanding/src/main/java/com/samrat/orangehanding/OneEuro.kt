package com.samrat.orangehanding

import kotlin.math.PI
import kotlin.math.abs

/** One Euro filter (Casiez et al.): calm while still, little lag while moving fast. */
class OneEuro(
    private val minCutoff: Float = 1.2f,
    private val beta: Float = .6f,
    private val derivativeCutoff: Float = 1f,
) {
    private var value = Float.NaN
    private var derivative = 0f
    private var lastNs = 0L

    fun filter(raw: Float, timeNs: Long): Float {
        if (value.isNaN() || lastNs == 0L || timeNs <= lastNs) {
            if (value.isNaN() || lastNs == 0L) value = raw
            lastNs = timeNs
            return value
        }
        val dt = ((timeNs - lastNs) / 1e9f).coerceIn(1e-3f, .3f)
        lastNs = timeNs
        derivative += alpha(derivativeCutoff, dt) * ((raw - value) / dt - derivative)
        value += alpha(minCutoff + beta * abs(derivative), dt) * (raw - value)
        return value
    }

    fun reset() {
        value = Float.NaN
        derivative = 0f
        lastNs = 0L
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
