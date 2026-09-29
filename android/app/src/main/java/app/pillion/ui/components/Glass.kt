package app.pillion.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pillion.ui.theme.BackgroundLayers
import app.pillion.ui.theme.CssShadow
import app.pillion.ui.theme.PillionColors

/**
 * A glass panel (the design's `.pill` / `.glass`): translucent fill, a 1 dp edge and, in the light
 * theme, a soft two-layer shadow.
 *
 * The design also blurs what's behind a panel (`backdrop-filter: blur`). The panels sit on the flat
 * black (A7) or the smooth lavender gradient (A8), where a blur changes nothing, and Compose's
 * `Modifier.blur` would blur the panel's own text instead; so no blur, on any Android version.
 */
fun Modifier.glass(colors: PillionColors, shape: Shape): Modifier = this
    .cssShadows(shape, colors.glassShadows)
    .background(colors.glassFill, shape)
    .border(1.dp, colors.glassBorder, shape)

/**
 * CSS `box-shadow` semantics: each shadow is the element's shape, grown by [spread], moved down by
 * its offset and blurred (CSS blur radius = 2σ), drawn only outside the element (so a translucent
 * fill doesn't turn grey). Blurred shadows need Android 9+ (hardware-accelerated mask filters);
 * on 8.x they're left out rather than drawn hard.
 */
fun Modifier.cssShadows(shape: Shape, shadows: List<CssShadow>, spread: () -> Dp = { 0.dp }): Modifier =
    if (shadows.isEmpty()) this else drawWithCache {
        val element = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
        val paints = shadows.map { shadow ->
            Paint().asFrameworkPaint().apply {
                isAntiAlias = true
                color = shadow.color.toArgb()
                val blurPx = shadow.blur.dp.toPx()
                if (blurPx > 0f && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // BlurMaskFilter takes a radius where σ = 0.57735·r + 0.5.
                    maskFilter = BlurMaskFilter(((blurPx / 2f - 0.5f) / 0.57735f).coerceAtLeast(0.5f), BlurMaskFilter.Blur.NORMAL)
                }
            }
        }
        val blurredWithoutSupport = Build.VERSION.SDK_INT < Build.VERSION_CODES.P && shadows.any { it.blur > 0f }
        onDrawBehind {
            if (blurredWithoutSupport) return@onDrawBehind
            val grow = spread().toPx()
            val outline = shape.createOutline(Size(size.width + 2 * grow, size.height + 2 * grow), layoutDirection, this)
            val shadowPath = Path().apply { addOutline(outline) }
            clipPath(element, ClipOp.Difference) {
                drawIntoCanvas { canvas ->
                    shadows.forEachIndexed { i, shadow ->
                        canvas.save()
                        canvas.translate(-grow, shadow.y.dp.toPx() - grow)
                        canvas.nativeCanvas.drawPath(shadowPath.asAndroidPath(), paints[i])
                        canvas.restore()
                    }
                }
            }
        }
    }

/**
 * The page behind every screen: flat black (A7), or A8's pearl lavender (a vertical gradient under
 * two soft elliptical light spots). CSS elliptical `radial-gradient`s are drawn as circles
 * squashed to the ellipse.
 */
fun Modifier.pillionBackground(colors: PillionColors): Modifier = drawWithCache {
    val layers = colors.backgroundLayers
    val base = if (layers.top == layers.bottom) null else Brush.verticalGradient(listOf(layers.top, layers.bottom))
    val spots = layers.spots.map { spot ->
        val rx = spot.rx * size.width
        val ry = spot.ry * size.height
        val center = Offset(spot.cx * size.width, spot.cy * size.height)
        Triple(center, ry / rx, Brush.radialGradient(0f to spot.color, spot.stop to spot.color.copy(alpha = 0f), center = center, radius = rx))
    }
    onDrawBehind {
        if (base == null) drawRect(layers.top) else drawRect(base)
        spots.forEach { (center, squash, brush) ->
            scale(1f, squash, pivot = center) {
                drawRect(brush, topLeft = Offset(0f, center.y - size.height / squash), size = Size(size.width, 2 * size.height / squash))
            }
        }
    }
}

/** A radial CSS glow (`radial-gradient(closest-side, color, transparent)`) of [diameter], centred. */
fun glowBrush(color: Color, center: Offset, radius: Float): Brush =
    Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = center, radius = radius)

/**
 * CSS `linear-gradient(<angle>deg, …)` over a box of [size]: 0° points up, 90° right; the line
 * runs through the centre and is as long as CSS makes it (so the corners get the end colours).
 */
fun cssLinearGradient(angleDegrees: Float, size: Size, stops: List<Pair<Float, Color>>): Brush {
    val radians = Math.toRadians(angleDegrees.toDouble())
    val dx = kotlin.math.sin(radians).toFloat()
    val dy = -kotlin.math.cos(radians).toFloat()
    val half = (kotlin.math.abs(size.width * dx) + kotlin.math.abs(size.height * dy)) / 2f
    val center = Offset(size.width / 2, size.height / 2)
    return Brush.linearGradient(
        *stops.toTypedArray(),
        start = center - Offset(dx * half, dy * half),
        end = center + Offset(dx * half, dy * half),
    )
}

/** CSS `box-shadow: inset 0 1px 0 <color>`: a 1 dp light along the top inside edge, following the corners. */
fun Modifier.insetTopHighlight(shape: Shape, color: Color): Modifier = drawWithCache {
    val outline = Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val shifted = Path().apply {
        addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache))
        translate(Offset(0f, 1.dp.toPx()))
    }
    onDrawWithContent {
        drawContent()
        clipPath(outline) {
            clipPath(shifted, ClipOp.Difference) { drawRect(color) }
        }
    }
}

/**
 * A [BackgroundLayers] page over [area]: its vertical gradient (or flat colour), then each CSS
 * elliptical `radial-gradient` spot as a circle squashed to the ellipse.
 */
fun DrawScope.drawBackgroundLayers(layers: BackgroundLayers, area: Size = size) {
    if (layers.top == layers.bottom) {
        drawRect(layers.top, size = area)
    } else {
        drawRect(Brush.verticalGradient(listOf(layers.top, layers.bottom), startY = 0f, endY = area.height), size = area)
    }
    layers.spots.forEach { spot ->
        val rx = spot.rx * area.width
        val center = Offset(spot.cx * area.width, spot.cy * area.height)
        scale(1f, spot.ry * area.height / rx, pivot = center) {
            drawCircle(Brush.radialGradient(0f to spot.color, spot.stop to spot.color.copy(alpha = 0f), center = center, radius = rx), rx, center)
        }
    }
}

/** A full-size [BackgroundLayers] page behind the content. */
fun Modifier.layeredBackground(layers: BackgroundLayers): Modifier = drawBehind { drawBackgroundLayers(layers) }
