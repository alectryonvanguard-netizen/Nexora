package com.nexorastudio.app

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Remembers that the boot screen already ran in this app process, so opening a notification does not replay it. */
object BootState {
    @Volatile var shown = false
}

/**
 * Boot screen. A burst of moving particles settles down while "WELCOME" and the NEXORA wordmark
 * fade in with the letters pulling together. It is deliberately different from the app icon.
 * Tap to skip.
 */
@Composable
fun BootScreen(onFinished: () -> Unit) {
    val aura = LocalAura.current
    val energy = remember { Animatable(1f) }
    val reveal = remember { Animatable(0f) }
    val tagline = remember { Animatable(0f) }
    val bar = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { energy.animateTo(0.15f, tween(2800, easing = FastOutSlowInEasing)) }
            launch { reveal.animateTo(1f, tween(1500, easing = FastOutSlowInEasing)) }
            launch {
                delay(900)
                tagline.animateTo(1f, tween(700))
            }
            launch { bar.animateTo(1f, tween(2300, easing = LinearEasing)) }
        }
        delay(250)
        onFinished()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Void)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onFinished() }
    ) {
        ParticleBackdrop(Modifier.fillMaxSize(), energy.asState())

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val r = reveal.value

            Text(
                "WELCOME",
                color = InkDim,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = (20f - 12f * r).sp,
                modifier = Modifier.alpha(r)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "NEXORA",
                style = TextStyle(
                    brush = Brush.horizontalGradient(listOf(aura.c1, aura.c2, aura.c3)),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (26f - 16f * r).sp
                ),
                modifier = Modifier.alpha(r)
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "AI STUDIO",
                color = InkDim,
                fontSize = 11.sp,
                letterSpacing = 9.sp,
                modifier = Modifier.alpha(tagline.value)
            )
            Spacer(Modifier.height(34.dp))
            Box(
                Modifier
                    .width(120.dp)
                    .height(2.dp)
                    .clip(RoundedCornerShape(1.dp))
                    .background(Color(0x22FFFFFF))
                    .drawBehind {
                        drawRect(
                            Brush.horizontalGradient(listOf(aura.c1, aura.c2, aura.c3)),
                            size = Size(size.width * bar.value, size.height)
                        )
                    }
            )
        }
    }
}
