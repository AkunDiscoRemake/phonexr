package com.samrat.cardboardhands

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.sin

/** Taps on the version: the fifth in a row opens the space easter egg. */
class VersionTaps {
    private var count = 0
    private var last = 0L

    /** Taps still needed, after the last one (0: the egg opens now). */
    var left = 5
        private set

    /**
     * True on the tap that opens the egg. Up to three seconds between taps: in VR a press with the
     * finger takes longer than a tap on the phone.
     */
    fun tap(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        count = if (now - last > 3000L) 1 else count + 1
        last = now
        left = 5 - count
        if (count >= 5) { count = 0; left = 5; return true }
        return false
    }
}

/**
 * The easter egg: flying through space — stars rushing past, a soft nebula — and in the middle
 * "2.0.1" and "PhoneXR". A tap anywhere goes back.
 */
@Composable
fun SpaceEasterEgg(onClose: () -> Unit) {
    val stars = remember {
        val random = java.util.Random(2001)
        List(260) { floatArrayOf(random.nextFloat() * 2 - 1, random.nextFloat() * 2 - 1, random.nextFloat(), .4f + random.nextFloat() * .6f) }
    }
    val time = rememberInfiniteTransition(label = "space")
    val travel by time.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), label = "travel")
    val glow by time.animateFloat(0f, 1f, infiniteRepeatable(tween(2600), RepeatMode.Reverse), label = "glow")
    Box(
        Modifier.fillMaxSize()
            .background(Brush.radialGradient(listOf(Color(0xFF1B1446), Color(0xFF07061A), Color.Black)))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val cx = size.width / 2; val cy = size.height / 2
            val reach = maxOf(size.width, size.height) * .75f
            // A nebula that breathes.
            drawCircle(
                Brush.radialGradient(listOf(Color(0x557B4DFF), Color(0x222E8BFF), Color.Transparent), Offset(cx, cy), reach * (.55f + .08f * glow)),
                reach * (.55f + .08f * glow), Offset(cx, cy)
            )
            // Stars fly out from the middle: depth goes round, nearer ones are bigger and brighter.
            for (star in stars) {
                val depth = (star[2] + travel) % 1f
                val scale = depth * depth
                val x = cx + star[0] * reach * scale
                val y = cy + star[1] * reach * scale
                val twinkle = .7f + .3f * sin((travel * 12f + star[3] * 20f) * PI.toFloat())
                val alpha = (depth * 1.4f).coerceAtMost(1f) * star[3] * twinkle
                drawCircle(Color.White.copy(alpha = alpha), (.6f + depth * 3.2f).dp.toPx(), Offset(x, y))
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            BasicText(
                BuildConfig.VERSION_NAME.removeSuffix("-lite"),
                style = TextStyle(color = Color.White, fontSize = 88.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp),
                modifier = Modifier.graphicsLayer { alpha = .85f + .15f * glow }
            )
            BasicText(
                "PhoneXR",
                style = TextStyle(color = Color(0xCCFFFFFF), fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = 6.sp)
            )
        }
    }
}
