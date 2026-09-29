package app.pillion.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.ui.theme.Home
import app.pillion.ui.theme.HomeType
import app.pillion.ui.theme.Pillion

/** One tab of [GlassTabBar]. */
class GlassTab(val label: String, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int)

/**
 * The tab bar of design/pillion-home-handoff.html (`.tabs.float` / `.tab` / `.tab.on`, C11 light and
 * C13 dark): a white (dark: graphite) rounded bar with a lavender pill under the open tab. The pill
 * slides between tabs on a spring (snaps with "Remove animations"), and the label it reaches turns
 * from grey to violet.
 *
 * Tap a tab, or slide along the bar: the pill follows the finger (a tick at each tab) and on release
 * springs to the nearest tab, or on to the next one after a quick flick, and opens it.
 */
@Composable
fun GlassTabBar(tabs: List<GlassTab>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val reduced = Pillion.reducedMotion
    val haptics = LocalHapticFeedback.current
    // The glow's centre, in tabs (0 = first): a spring slides it, a finger drags it.
    val position = remember { Animatable(selected.toFloat()) }
    var dragging by remember { mutableStateOf(false) }
    val glide = if (reduced) snap<Float>() else spring(dampingRatio = 0.8f, stiffness = 380f)
    LaunchedEffect(selected) {
        if (!dragging) position.animateTo(selected.toFloat(), glide)
    }
    val scope = rememberCoroutineScope()
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    // Watched before the tabs' own taps (the initial pass): once the finger moves past the touch
    // slop the bar takes the gesture and the tap under it is cancelled.
    val drag = Modifier.pointerInput(tabs.size, reduced) {
        // One tab and the 4 dp gap after it.
        val slot = (size.width + 4.dp.toPx()) / tabs.size
        val last = tabs.lastIndex.toFloat()
        val velocity = VelocityTracker()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            velocity.resetTracking()
            var sliding = false
            var travel = 0f
            var at = position.value
            var ticked = at.roundToInt()
            while (true) {
                val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) {
                    if (sliding) {
                        change.consume()
                        // Tabs per second; a flick carries the glow a little further before it settles.
                        val speed = velocity.calculateVelocity().x / slot
                        val target = (at + speed * 0.12f).roundToInt().coerceIn(0, tabs.lastIndex)
                        dragging = false
                        scope.launch {
                            if (reduced) position.snapTo(target.toFloat()) else position.animateTo(target.toFloat(), glide, initialVelocity = speed)
                        }
                        if (target != currentSelected) currentOnSelect(target)
                    }
                    break
                }
                velocity.addPosition(change.uptimeMillis, change.position)
                val dx = change.positionChange().x
                if (!sliding) {
                    travel += dx
                    if (abs(travel) < viewConfiguration.touchSlop) continue
                    sliding = true
                    dragging = true
                }
                change.consume()
                at = (at + dx / slot).coerceIn(0f, last)
                scope.launch { position.snapTo(at) }
                if (at.roundToInt() != ticked) {
                    ticked = at.roundToInt()
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                }
            }
            if (dragging) {
                // Cancelled (another pointer took over): back to the open tab.
                dragging = false
                scope.launch { position.animateTo(currentSelected.toFloat(), glide) }
            }
        }
    }

    // .tabs.float: padding 5 inside a 1 dp edge, 32 dp corners, 58 dp tabs 4 dp apart.
    val c = Home.colors
    val barShape = RoundedCornerShape(32.dp)
    Box(
        modifier
            .fillMaxWidth()
            .height(70.dp)
            .cssShadows(barShape, c.tabsShadows)
            .background(c.tabsFill, barShape)
            .border(1.dp, c.tabsBorder, barShape)
            .padding(6.dp)
            .drawBehind {
                val gap = 4.dp.toPx()
                val tab = (size.width - gap * (tabs.size - 1)) / tabs.size
                drawRoundRect(
                    c.tabOnFill,
                    topLeft = Offset(position.value * (tab + gap), 0f),
                    size = Size(tab, size.height),
                    cornerRadius = CornerRadius(27.dp.toPx()),
                )
            },
    ) {
        Row(Modifier.fillMaxWidth().fillMaxHeight().then(drag).selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { index, tab ->
                val isSelected = index == selected
                // Violet as the pill arrives under this tab, grey as it leaves.
                val nearness = (1f - abs(position.value - index)).coerceIn(0f, 1f)
                val content = lerp(c.tabInk, c.tabOnInk, nearness)
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(27.dp))
                        .selectable(
                            selected = isSelected,
                            role = Role.Tab,
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {
                                if (!isSelected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onSelect(index)
                            },
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                ) {
                    Icon(
                        painterResource(if (nearness > 0.5f) tab.selectedIcon else tab.icon),
                        contentDescription = null,
                        tint = content,
                        modifier = Modifier.size(24.dp),
                    )
                    Text(tab.label, style = HomeType.tab, color = content, maxLines = 1)
                }
            }
        }
    }
}
