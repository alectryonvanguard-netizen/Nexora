package com.nexorastudio.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Neutral surfaces only. Every accent color comes from the animated Aura below.
val Ink = Color(0xFFE9EEF7)
val InkDim = Color(0xFF8E98AD)
val Void = Color(0xFF05060A)
val GlassFill = Color(0xB30B0D16)

/**
 * Living color palette. Hue = mode base hue + slow drift, so nothing is ever a fixed color.
 * Only State objects are held, so colors are read during the DRAW phase (no recomposition per frame).
 */
class Aura(private val base: State<Float>, private val drift: State<Float>) {
    private fun hue(offset: Float): Float {
        val rad = Math.toRadians(drift.value.toDouble())
        val h = base.value + (sin(rad) * 38.0).toFloat() + (cos(rad * 2.0) * 14.0).toFloat() + offset
        return ((h % 360f) + 360f) % 360f
    }

    val c1: Color get() = Color.hsv(hue(0f), 0.82f, 1f)
    val c2: Color get() = Color.hsv(hue(62f), 0.78f, 1f)
    val c3: Color get() = Color.hsv(hue(-58f), 0.70f, 0.96f)
}

val LocalAura = staticCompositionLocalOf<Aura> { error("Aura not provided") }

@Composable
fun rememberAura(mode: Mode): Aura {
    val base = animateFloatAsState(mode.hue, tween(1600, easing = FastOutSlowInEasing), label = "baseHue")
    val t = rememberInfiniteTransition(label = "aura")
    val drift = t.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(26000, easing = LinearEasing)),
        label = "drift"
    )
    return remember { Aura(base, drift) }
}

fun Modifier.auraGlowBorder(aura: Aura, radius: Dp = 20.dp, width: Dp = 1.2.dp): Modifier = this.drawBehind {
    val brush = Brush.linearGradient(
        colors = listOf(aura.c1, aura.c2, aura.c3),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    drawRoundRect(
        brush = brush,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width.toPx()),
        alpha = 0.9f
    )
}

fun Modifier.auraFill(aura: Aura, radius: Dp = 20.dp, alpha: Float = 0.2f): Modifier = this.drawBehind {
    val brush = Brush.linearGradient(
        colors = listOf(aura.c1, aura.c2),
        start = Offset.Zero,
        end = Offset(size.width, size.height)
    )
    drawRoundRect(brush = brush, cornerRadius = CornerRadius(radius.toPx()), alpha = alpha)
}

@Composable
fun Glass(
    modifier: Modifier = Modifier,
    radius: Dp = 20.dp,
    border: Boolean = true,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val aura = LocalAura.current
    val shape = RoundedCornerShape(radius)
    Box(
        modifier
            .clip(shape)
            .background(GlassFill)
            .then(if (border) Modifier.auraGlowBorder(aura, radius) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        content = content
    )
}

@Composable
fun NexoraTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Ink,
            onPrimary = Void,
            background = Void,
            onBackground = Ink,
            surface = Color(0xFF0B0D16),
            onSurface = Ink,
            surfaceVariant = Color(0xFF151927),
            onSurfaceVariant = InkDim,
            outline = Color(0x44FFFFFF),
            error = Color(0xFFFF6B7A)
        ),
        content = content
    )
}
