package com.nexorastudio.app

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.runtime.withFrameNanos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** Drifting nodes. Positions are fractions of the screen so it works at any size. */
class ParticleField(private val count: Int) {
    val x = FloatArray(count)
    val y = FloatArray(count)
    private val vx = FloatArray(count)
    private val vy = FloatArray(count)
    val tri = BooleanArray(count)
    val ang = FloatArray(count)
    private val spin = FloatArray(count)
    val size = FloatArray(count)

    init {
        val r = Random(11)
        for (i in 0 until count) {
            x[i] = r.nextFloat()
            y[i] = r.nextFloat()
            val a = r.nextFloat() * 6.2831855f
            val sp = 0.02f + r.nextFloat() * 0.04f
            vx[i] = cos(a) * sp
            vy[i] = sin(a) * sp
            tri[i] = r.nextFloat() < 0.2f
            ang[i] = r.nextFloat() * 6.2831855f
            spin[i] = (r.nextFloat() - 0.5f) * 0.9f
            size[i] = 1.5f + r.nextFloat() * 1.8f
        }
    }

    fun step(dt: Float, speed: Float) {
        for (i in 0 until count) {
            x[i] += vx[i] * dt * speed
            y[i] += vy[i] * dt * speed
            ang[i] += spin[i] * dt * speed
            if (x[i] < -0.05f) x[i] = 1.05f
            if (x[i] > 1.05f) x[i] = -0.05f
            if (y[i] < -0.05f) y[i] = 1.05f
            if (y[i] > 1.05f) y[i] = -0.05f
        }
    }

    val n: Int get() = count
}

/**
 * Dark network of moving points and thin lines with a few green triangles,
 * like the NVIDIA key page. Everything drifts all the time; it moves faster when the AI talks.
 */
@Composable
fun ParticleBackdrop(modifier: Modifier = Modifier, energy: State<Float>) {
    val field = remember { ParticleField(58) }
    var frame by remember { mutableLongStateOf(0L) }

    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val dt = ((now - last) / 1_000_000_000f).coerceAtMost(0.05f)
                field.step(dt, 1f + energy.value * 2.2f)
            }
            last = now
            frame = now
        }
    }

    Canvas(modifier) {
        val tick = frame
        val seconds = (tick / 1_000_000L) / 1000f
        val e = energy.value
        val w = size.width
        val h = size.height

        drawRect(Color(0xFF080A09))

        // slow glow at the bottom whose color keeps shifting between green and teal
        val hue = 125f + 35f * sin(seconds * 0.25f)
        val glowCenter = Offset(w * 0.45f, h * 1.02f)
        val glowRadius = maxOf(w, h) * 0.85f
        drawCircle(
            Brush.radialGradient(
                listOf(Color.hsv(hue, 0.75f, 0.42f, 0.38f + 0.2f * e), Color.Transparent),
                center = glowCenter,
                radius = glowRadius
            ),
            radius = glowRadius,
            center = glowCenter
        )
        val glow2 = Offset(w * 0.95f, h * 0.8f)
        drawCircle(
            Brush.radialGradient(
                listOf(Color.hsv(hue + 95f, 0.7f, 0.4f, 0.22f), Color.Transparent),
                center = glow2,
                radius = glowRadius * 0.6f
            ),
            radius = glowRadius * 0.6f,
            center = glow2
        )

        val n = field.n
        val maxDist = minOf(w, h) * 0.30f
        val lineBoost = 0.22f + 0.28f * e

        // lines between nearby points
        for (i in 0 until n) {
            val xi = field.x[i] * w
            val yi = field.y[i] * h
            for (j in i + 1 until n) {
                val dx = xi - field.x[j] * w
                val dy = yi - field.y[j] * h
                val d = sqrt(dx * dx + dy * dy)
                if (d < maxDist) {
                    val a = (1f - d / maxDist) * lineBoost
                    drawLine(
                        Color(0xFFC9D1CA).copy(alpha = a),
                        Offset(xi, yi),
                        Offset(field.x[j] * w, field.y[j] * h),
                        strokeWidth = 1.1f
                    )
                }
            }
        }

        // nodes: small gray dots and green triangles
        val triColor = Color.hsv(hue, 0.85f, 0.85f, 0.9f)
        val dotColor = Color(0xFFC9D1CA).copy(alpha = 0.55f)
        for (i in 0 until n) {
            val cx = field.x[i] * w
            val cy = field.y[i] * h
            if (field.tri[i]) {
                val r = 5.5f * density * field.size[i] / 2.2f
                val path = Path()
                for (k in 0..2) {
                    val a = field.ang[i] + k * 2.0943952f
                    val px = cx + cos(a) * r
                    val py = cy + sin(a) * r
                    if (k == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                path.close()
                drawCircle(triColor.copy(alpha = 0.10f + 0.1f * e), radius = r * 2.2f, center = Offset(cx, cy))
                drawPath(path, triColor)
            } else {
                drawCircle(dotColor, radius = field.size[i] * density * 0.7f, center = Offset(cx, cy))
            }
        }
    }
}
