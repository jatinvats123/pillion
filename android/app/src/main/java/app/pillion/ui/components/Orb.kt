package app.pillion.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.pillion.ui.theme.Motion
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.PillionColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive

/** What the orb shows; each has its own colour, pace and label (the status pill says it in words). */
enum class OrbMood { Dormant, Connecting, Listening, Thinking, Speaking, Offline, Alert }

/**
 * Pillion's orb: a glassy sphere drawn with layered gradients (no blur, no shaders, so it looks
 * the same on Android 11). Colour follows [mood]; size and ripples follow [level] (Agora's volume
 * indication, 0..1). Brushes are built only when the size or colour changes; each frame only
 * moves, scales and fades them, in the orb's own layer.
 */
@Composable
fun Orb(mood: OrbMood, level: () -> Float, description: String, modifier: Modifier = Modifier) {
    val reduced = Pillion.reducedMotion
    val body = rememberOrbColor(mood)
    val glow = animateColorAsState(mood.colors(Pillion.colors).second, Motion.color(reduced), label = "orbGlow")
    val speed = animateFloatAsState(mood.speed, Motion.state(reduced), label = "orbSpeed")
    val ripples = animateFloatAsState(if (mood == OrbMood.Listening || mood == OrbMood.Speaking) 1f else 0f, Motion.state(reduced), label = "orbRipples")
    val pulse = animateFloatAsState(if (mood == OrbMood.Alert) 1f else 0f, Motion.state(reduced), label = "orbPulse")

    // Agora reports every 100 ms; a spring turns the steps into a smooth swell.
    val currentLevel by rememberUpdatedState(level)
    val loudness = remember { Animatable(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        snapshotFlow { shape(currentLevel()) }.collectLatest { loudness.animateTo(it, spring(dampingRatio = 0.8f, stiffness = 260f)) }
    }

    // The orb's own clock, in seconds, sped up or slowed by mood; wraps every loop without a jump.
    val clock = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(reduced) {
        if (reduced) return@LaunchedEffect
        var last = withFrameNanos { it }
        while (isActive) {
            withFrameNanos { now ->
                clock.floatValue = (clock.floatValue + (now - last) / 1e9f * speed.value) % Motion.ORB_LOOP_S
                last = now
            }
        }
    }

    Spacer(
        modifier
            .semantics {
                contentDescription = description
                role = Role.Image
            }
            // Its own layer: the per-frame redraw doesn't re-record the rest of the screen.
            .graphicsLayer()
            .drawWithCache {
                val c = body.value
                val g = glow.value
                val r = size.minDimension * BODY_RATIO
                val halo = Brush.radialGradient(
                    0f to c.copy(alpha = 0.42f), 0.5f to c.copy(alpha = 0.14f), 1f to Color.Transparent,
                    center = Offset.Zero, radius = r * HALO_RATIO,
                )
                // Lit from the upper left: pale there, the full colour, then a rim deepened toward
                // the second hue (not toward black, which turns warm colours muddy).
                val sphere = Brush.radialGradient(
                    0f to lerp(c, Color.White, 0.65f),
                    0.3f to lerp(c, Color.White, 0.18f),
                    0.62f to c,
                    1f to lerp(lerp(c, g, 0.45f), Color.Black, 0.12f),
                    center = Offset(-0.38f * r, -0.42f * r), radius = r * 1.75f,
                )
                // Two soft inner lights that drift; they fade out before the edge, so no clipping.
                val tint = Brush.radialGradient(listOf(g.copy(alpha = 0.8f), g.copy(alpha = 0f)), Offset.Zero, r * 0.62f)
                val light = Brush.radialGradient(listOf(lerp(c, Color.White, 0.7f).copy(alpha = 0.75f), Color.White.copy(alpha = 0f)), Offset.Zero, r * 0.55f)
                val highlight = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.85f), Color.White.copy(alpha = 0f)), Offset.Zero, r * 0.34f)
                val rim = Brush.sweepGradient(
                    listOf(Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0f), Color.White.copy(alpha = 0.25f), Color.White.copy(alpha = 0f)),
                    Offset.Zero,
                )
                val rimStroke = Stroke(width = 1.5.dp.toPx())
                val ringStroke = Stroke(width = 2.dp.toPx())

                onDrawBehind {
                    val t = clock.floatValue
                    val loud = loudness.value
                    val beat = pulse.value * (0.5f + 0.5f * sin(t * TURN * 96)) // 1.6 Hz
                    val breath = sin(t * TURN * 10) // one breath in 6 s
                    val grow = 1f + 0.025f * breath + 0.12f * loud + 0.08f * beat

                    translate(center.x, center.y) {
                        scale(grow + 0.2f * loud + 0.3f * beat, Offset.Zero) { drawCircle(halo, r * HALO_RATIO, Offset.Zero) }

                        val rippleStrength = ripples.value * (0.25f + 0.75f * loud)
                        if (rippleStrength > 0.01f) {
                            for (i in 0..1) {
                                val p = (t * 0.6f + i * 0.5f) % 1f // a ring every 0.83 s
                                drawCircle(c, r * grow * (1f + 0.6f * p), Offset.Zero, alpha = (1f - p).pow(2) * 0.5f * rippleStrength, style = ringStroke)
                            }
                        }

                        scale(grow, Offset.Zero) {
                            drawCircle(sphere, r, Offset.Zero)
                            translate(0.34f * r * cos(t * TURN * 7), 0.3f * r * sin(t * TURN * 5)) { drawCircle(tint, r * 0.62f, Offset.Zero) }
                            translate(0.3f * r * cos(t * TURN * 4 + 2f), 0.32f * r * sin(t * TURN * 6 + 1f)) { drawCircle(light, r * 0.55f, Offset.Zero) }
                            translate(-0.36f * r, -0.4f * r) { drawCircle(highlight, r * 0.34f, Offset.Zero) }
                            rotate(t * 6f, Offset.Zero) { drawCircle(rim, r - rimStroke.width / 2, Offset.Zero, style = rimStroke) }
                        }
                    }
                }
            },
    )
}

/** The orb's main colour for [mood], faded between states. */
@Composable
fun rememberOrbColor(mood: OrbMood): State<Color> =
    animateColorAsState(mood.colors(Pillion.colors).first, Motion.color(Pillion.reducedMotion), label = "orbColor")

/** The status pill's dot: the orb's colour. */
@Composable
fun OrbMood.dotColor(): Color = colors(Pillion.colors).first

/**
 * The screen's very soft glow in the orb's colour, behind everything (the orb itself stays in its
 * bounds). [color] is read at draw time; the brush is rebuilt only when it changes.
 */
fun Modifier.orbBackdrop(color: () -> Color, isDark: Boolean, centerY: Float): Modifier = drawWithCache {
    val center = Offset(size.width / 2, size.height * centerY)
    val brush = Brush.radialGradient(
        listOf(color().copy(alpha = if (isDark) 0.16f else 0.14f), Color.Transparent),
        center,
        radius = size.width * 0.95f,
    )
    onDrawBehind { drawRect(brush) }
}

private const val BODY_RATIO = 0.3f
private const val HALO_RATIO = 1.65f
/** One cycle of the orb loop, in radians per second: frequencies are whole multiples of it. */
private const val TURN = (2 * PI / 60).toFloat()

/** Quiet mic noise stays still; normal speech fills most of the range. */
private fun shape(volume: Float): Float = ((volume - 0.03f) / 0.35f).coerceIn(0f, 1f).pow(0.7f)

private val OrbMood.speed: Float
    get() = when (this) {
        OrbMood.Dormant -> 0.5f
        OrbMood.Connecting -> 1.4f
        OrbMood.Listening -> 1f
        OrbMood.Thinking -> 2.4f
        OrbMood.Speaking -> 1.3f
        OrbMood.Offline -> 0.35f
        OrbMood.Alert -> 1f
    }

private fun OrbMood.colors(c: PillionColors): Pair<Color, Color> = when (this) {
    OrbMood.Dormant -> c.accent to c.listeningGlow
    // Grey warming up to marigold from the inside.
    OrbMood.Connecting -> c.offline to c.accent
    OrbMood.Listening -> c.listening to c.listeningGlow
    OrbMood.Thinking -> c.thinking to c.thinkingGlow
    OrbMood.Speaking -> c.speaking to c.speakingGlow
    OrbMood.Offline -> c.offline to c.offlineGlow
    OrbMood.Alert -> c.alert to c.alertGlow
}
