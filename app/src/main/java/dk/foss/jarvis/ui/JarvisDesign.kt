package dk.foss.jarvis.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

object JarvisColors {
    val Backdrop = Color(0xFF0A0A0A)
    val Canvas = Backdrop
    val WindowBg = Color(0xFF0A0A0A)
    val Surface = Color(0xFF111111)
    val SurfaceRaised = Color(0xFF161616)
    val Blue = Color(0xFF4934E1)
    val Cyan = Blue // compatibility name; Helmcode has one signal hue
    val CyanText = Color(0xFF818CF8)
    val ThinkBlue = Color(0xFF818CF8)
    val Muted = Color(0x8CFFFFFF)
    val Muted2 = Color(0x59FFFFFF)
    val TextPrimary = Color.White
    val TextPrimaryAlpha = Color.White.copy(alpha = .95f)
    val TextSecondary = Color(0x8CFFFFFF)
    val TextTertiary = Color(0x59FFFFFF)
    val AccentText = Color(0xFF818CF8)
    val BorderSubtle = Color(0x14FFFFFF)
    val ErrorOrange = Color(0xFFFF5F56)
    val ErrorOrangeGlow = Color(0x26FF5F56)
    val LogoChipStart = Surface
    val LogoChipEnd = SurfaceRaised
    val CyanBorder = Color(0x14FFFFFF)
    val CyanBorder30 = Color(0x33FFFFFF)
    val GlassBg = Surface
    val GlassBorder = Color(0x14FFFFFF)
}

// Android's bundled sans and monospace faces are Roboto and Roboto Mono.
val RobotoSans: FontFamily = FontFamily.Default
val RobotoMono: FontFamily = FontFamily.Monospace
val SpaceGrotesk = RobotoSans // source compatibility for existing screens
val DmSans = RobotoSans
val JetBrainsMono = RobotoMono

@Composable
fun DeepSpaceBackground(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier = modifier.fillMaxSize().background(JarvisColors.Backdrop)) { content() }
}

@Composable
fun StatusTag(text: String, color: Color, modifier: Modifier = Modifier) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Text("// ${text.uppercase()}", fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 12.sp,
            letterSpacing = 1.2.sp, color = if (color == JarvisColors.Cyan) JarvisColors.CyanText else color)
    }
}

/** Kept as a compatibility helper; new voice UI uses BrickVisualizer instead. */
@Composable
fun Waveform(barCount: Int, barWidth: Dp, minH: Dp, maxH: Dp, modifier: Modifier = Modifier,
             color1: Color = JarvisColors.Cyan, color2: Color = JarvisColors.Blue) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(barCount) { Box(Modifier.width(barWidth).height(minH).background(color1.copy(alpha = .45f))) }
    }
}

/** Compatibility wrapper, intentionally flat and non-pulsing. */
@Composable
fun PulseRings(modifier: Modifier = Modifier, ringColor1: Color = JarvisColors.Cyan,
               ringColor2: Color = JarvisColors.Blue, content: @Composable () -> Unit) {
    Box(modifier, contentAlignment = Alignment.Center) { content() }
}

@Composable fun ThinkingOrbs(modifier: Modifier = Modifier) { BrickVisualizer(modifier = modifier.size(120.dp), label = "Processing") }

@Composable
fun BrickVisualizer(modifier: Modifier = Modifier, label: String = "Voice visualizer") {
    val transition = rememberInfiniteTransition(label = "brickCarrier")
    val phase by transition.animateFloat(0f, (2f * PI).toFloat(), infiniteRepeatable(tween(5930, easing = LinearEasing)), label = "phase")
    Canvas(modifier.semantics { contentDescription = label }) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = size.minDimension / 2f - 4.dp.toPx()
        val inner = 30.dp.toPx()
        val radialStep = (maxRadius - inner) / 6f
        for (ring in 0 until 7) {
            val radius = inner + radialStep * ring
            val count = (2 * PI * radius / 25.dp.toPx()).toInt().coerceAtLeast(6)
            val brickH = 4.dp.toPx()
            for (index in 0 until count) {
                val angle = (index.toFloat() / count) * (2f * PI.toFloat()) + ring * (9f * PI.toFloat() / 180f)
                val wave = ((cos((angle - phase).toDouble()).toFloat() + 1f) / 2f)
                val shaped = wave * wave
                val alpha = .11f + shaped * .82f
                val width = (16 + ((index + ring) % 3) * 4).dp.toPx()
                withTransform({
                    rotate(angle * 180f / PI.toFloat() + 90f, center)
                }) {
                    val point = Offset(
                        center.x + cos(angle.toDouble()).toFloat() * radius,
                        center.y + sin(angle.toDouble()).toFloat() * radius,
                    )
                    drawRect(Color(0xFF4934E1).copy(alpha = alpha.coerceIn(.1f, .93f)),
                        topLeft = Offset(point.x - width / 2f, point.y - brickH / 2f), size = Size(width, brickH))
                }
            }
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.background(JarvisColors.Surface).border(.5.dp, JarvisColors.CyanBorder).padding(16.dp)) { content() }
}

@Composable
fun PillButton(text: String, onClick: () -> Unit, accent: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    TextButton(onClick, enabled = enabled, modifier = modifier.heightIn(min = 44.dp).border(.5.dp, if (accent) JarvisColors.Blue else JarvisColors.CyanBorder)) {
        Text(text, fontFamily = RobotoSans, fontWeight = FontWeight.Medium, fontSize = 14.sp,
            color = if (enabled && accent) Color.White else JarvisColors.TextSecondary)
    }
}

@Composable fun JarvisMark(modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        listOf(10, 16, 14, 20).forEach { h -> Box(Modifier.width(3.dp).height(h.dp).background(JarvisColors.Blue)) }
    }
}
