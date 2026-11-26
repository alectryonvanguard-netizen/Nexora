package com.nexorastudio.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate

/** Full-screen living background: drifting aurora blobs + a scrolling perspective grid. */
@Composable
fun AuroraBackdrop(modifier: Modifier = Modifier) {
    val aura = LocalAura.current
    val t = rememberInfiniteTransition(label = "backdrop")
    val phase = t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "phase"
    )
    val scroll = t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(6000, easing = LinearEasing)),
        label = "scroll"
    )

    Canvas(modifier.fillMaxSize()) {
        drawRect(Void)
        val p = phase.value
        val w = size.width
        val h = size.height
        val r = size.maxDimension * 0.55f

        val b1 = Offset(w * (0.15f + 0.55f * p), h * (0.12f + 0.10f * p))
        val b2 = Offset(w * (0.85f - 0.50f * p), h * (0.50f - 0.12f * p))
        val b3 = Offset(w * (0.30f + 0.30f * p), h * (0.92f - 0.10f * p))

        drawCircle(Brush.radialGradient(listOf(aura.c1.copy(alpha = 0.34f), Color.Transparent), center = b1, radius = r), radius = r, center = b1)
        drawCircle(Brush.radialGradient(listOf(aura.c2.copy(alpha = 0.26f), Color.Transparent), center = b2, radius = r), radius = r, center = b2)
        drawCircle(Brush.radialGradient(listOf(aura.c3.copy(alpha = 0.22f), Color.Transparent), center = b3, radius = r), radius = r, center = b3)

        // perspective grid in the lower half
        val horizon = h * 0.58f
        val lineColor = aura.c1.copy(alpha = 0.10f)
        val rows = 12
        for (i in 0 until rows) {
            val f = ((i + scroll.value) % rows) / rows
            val y = horizon + (h - horizon) * f * f
            drawLine(lineColor, Offset(0f, y), Offset(w, y), strokeWidth = 1.2f)
        }
        val cols = 14
        for (j in -cols..cols) {
            val x = w / 2f + j * (w / cols) * 1.6f
            drawLine(lineColor, Offset(w / 2f + j * 6f, horizon), Offset(x, h), strokeWidth = 1.2f)
        }
        // dark fade so text stays readable
        drawRect(Brush.verticalGradient(listOf(Color.Transparent, Void.copy(alpha = 0.55f)), startY = h * 0.35f, endY = h))
    }
}

/** Glowing, breathing orb. Its colors are the live Aura. */
@Composable
fun AuraOrb(modifier: Modifier = Modifier) {
    val aura = LocalAura.current
    val t = rememberInfiniteTransition(label = "orb")
    val pulse = t.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    val spin = t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing)),
        label = "spin"
    )
    Canvas(modifier) {
        val r = size.minDimension / 2f
        val c = center
        drawCircle(
            Brush.radialGradient(listOf(aura.c1.copy(alpha = 0.55f), Color.Transparent), center = c, radius = r * 1.15f),
            radius = r * 1.15f, center = c
        )
        rotate(spin.value, c) {
            drawCircle(
                Brush.sweepGradient(listOf(aura.c1, aura.c2, aura.c3, aura.c1), center = c),
                radius = r * 0.72f * pulse.value, center = c, style = Stroke(r * 0.09f)
            )
        }
        val core = r * 0.5f * pulse.value
        drawCircle(
            Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f), aura.c2.copy(alpha = 0.6f), Color.Transparent), center = c, radius = core),
            radius = core, center = c
        )
    }
}
