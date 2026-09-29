package app.pillion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.pillion.R
import app.pillion.ui.components.drawBackgroundLayers
import app.pillion.ui.theme.Home
import app.pillion.ui.theme.HomeType
import java.text.DateFormat
import java.util.Date

/**
 * "Ride done", after End Ride (design/pillion-ride-done-handoff.html: light and dark phones,
 * `phone premium orderglass refined polish rd` [+ `darkmode softglow`]): the home screen's header,
 * the time on the road with the order and what it paid, the ride's safety events, and Done. Distance
 * isn't shown: only the safety code sees GPS during a ride.
 */
@Composable
fun RideDoneScreen(
    summary: TripSummary,
    sosEnabled: Boolean,
    onSos: () -> Unit,
    onDone: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenDebug: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    val c = Home.colors
    val minutes = ((summary.endedAt - summary.startedAt) / 60_000).toInt()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    // The glows sit relative to the summary card; the safety card's Android 12+ backdrop needs them too.
    var summaryAt by remember { mutableStateOf(Offset.Zero) }
    val behind: androidx.compose.ui.graphics.drawscope.DrawScope.(androidx.compose.ui.geometry.Size, Offset) -> Unit = { root, _ ->
        drawBackgroundLayers(c.page, root)
        translate(summaryAt.x, summaryAt.y) { drawCardGlows(c, GLOWS_BELOW) }
    }

    Column(Modifier.fillMaxSize()) {
        HomeBar(sosEnabled, onSos, onToggleTheme, onOpenDebug, onOpenSettings)
        // .rd .scroll: padding 8 20 24, gap 16.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // .hello margin-top 2; .rd .sub margin-top -10 under the 16 dp gap = 6.
            Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.summary_title), style = HomeType.hello, color = c.ink, modifier = Modifier.semantics { heading() })
                Text(
                    "${time.format(Date(summary.startedAt))} – ${time.format(Date(summary.endedAt))}",
                    style = HomeType.sub.copy(fontFeatureSettings = "tnum"),
                    color = c.sub,
                )
            }

            // .rd .card.glasscard: padding 20, gap 14.
            HomeGlassCard(
                modifier = Modifier
                    .onGloballyPositioned { summaryAt = it.positionInRoot() }
                    .drawBehind { drawCardGlows(c, GLOWS_BELOW) },
                behind = behind,
                spacing = 14.dp,
            ) {
                Column {
                    Text(
                        if (minutes < 1) stringResource(R.string.summary_under_minute) else pluralStringResource(R.plurals.minutes, minutes, minutes),
                        style = HomeType.duration.copy(fontFeatureSettings = "tnum"),
                        color = c.strong,
                    )
                    Text(stringResource(R.string.summary_ride_time), style = HomeType.sub, color = c.muted)
                }
                val order = summary.order
                // .rows: a rule above, and one under each row.
                Column(Modifier.fillMaxWidth().ruleAbove(c.rule)) {
                    SummaryRow(
                        stringResource(R.string.summary_order),
                        listOf(order.customerName, order.dropArea).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
                    )
                    SummaryRow(stringResource(R.string.summary_earned), rupees(order.payoutRupees))
                }
                if (order.payoutRupees == 0 && !order.isDemo) SummaryNote(stringResource(R.string.summary_no_earning))
                if (order.isDemo) SummaryNote(stringResource(R.string.summary_demo_note))
            }

            // .rsafe: a row, padding 16 18, gap 14.
            HomeGlassCard(behind = behind, padding = PaddingValues(horizontal = 18.dp, vertical = 16.dp), spacing = 14.dp) {
                val events = summary.safetyEvents
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .background(if (events.isEmpty()) c.okFill else c.alertFill, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            painterResource(if (events.isEmpty()) R.drawable.ic_home_shield_check else R.drawable.ic_home_shield),
                            contentDescription = null,
                            tint = if (events.isEmpty()) c.okIcon else c.alertIcon,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.summary_safety), style = HomeType.value, color = c.cardInk, modifier = Modifier.semantics { heading() })
                        Text(
                            if (events.isEmpty()) stringResource(R.string.summary_no_events) else pluralStringResource(R.plurals.summary_event_count, events.size, events.size),
                            style = HomeType.small,
                            color = c.muted,
                        )
                    }
                }
                // Oldest first reads as the story of the ride.
                events.reversed().forEach { SafetyEventRow(it, showNote = false) }
            }
        }
        HomeActionSheet(R.drawable.ic_home_check, stringResource(R.string.done), onDone)
    }
}

/** `.r`: label left, value right on one baseline, 12 dp above and below, a rule under it. */
@Composable
private fun SummaryRow(label: String, value: String) {
    val c = Home.colors
    Row(
        Modifier
            .fillMaxWidth()
            .ruleBelow(c.rule)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, style = HomeType.sub, color = c.muted, modifier = Modifier.alignByBaseline())
        Text(
            value,
            style = HomeType.value.copy(fontFeatureSettings = "tnum"),
            color = c.strong,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).alignByBaseline(),
        )
    }
}

/** `.rnote`: a 16 dp info icon (2 dp down) and the line, 14 / 1.45. */
@Composable
private fun SummaryNote(text: String) {
    val c = Home.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(painterResource(R.drawable.ic_home_info), contentDescription = null, tint = c.noteIcon, modifier = Modifier.padding(top = 2.dp).size(16.dp))
        Text(text, style = HomeType.hint, color = c.muted)
    }
}

private fun Modifier.ruleAbove(color: Color): Modifier = drawBehind { drawRect(color, size = size.copy(height = 1.dp.toPx())) }

private fun Modifier.ruleBelow(color: Color): Modifier = drawBehind {
    val line = 1.dp.toPx()
    drawRect(color, topLeft = Offset(0f, size.height - line), size = size.copy(height = line))
}

/**
 * The design's glows are fixed on the phone; this screen's first card sits 10 dp higher than the
 * home screen's order card, so they sit 10 dp lower relative to it (summary card top ≈ 196 px vs 206).
 */
private val GLOWS_BELOW = 10.dp
