package app.pillion.ui

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pillion.R
import app.pillion.data.EarningsDb
import app.pillion.ui.components.PillionCard
import app.pillion.ui.components.ScreenHeader
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.RideType
import app.pillion.ui.theme.Space
import java.util.Date

@Composable
fun EarningsRoute(onOpenSettings: () -> Unit, viewModel: EarningsViewModel = viewModel()) {
    val week by viewModel.week.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.nav_earnings), onOpenSettings = onOpenSettings)
        week?.let { EarningsContent(it, onSelect = viewModel::select) }
    }
}

@Composable
private fun EarningsContent(week: EarningsWeek, onSelect: (Int) -> Unit) {
    val colors = Pillion.colors
    val trips = week.days.sumOf { it.trips }
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.l),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(stringResource(R.string.earnings_week), style = MaterialTheme.typography.titleMedium, color = colors.inkSecondary)
            Text(
                rupees(week.days.sumOf { it.rupees }),
                style = MaterialTheme.typography.displayMedium.merge(RideType.tabular),
                color = colors.ink,
            )
            Text(pluralStringResource(R.plurals.trips_count, trips, trips), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
        }
        PillionCard { WeekChart(week, onSelect) }
        DayCard(week.days[week.selected], isToday = week.selected == week.days.lastIndex, trips = week.trips)
        if (week.hasSample) {
            Text(stringResource(R.string.earnings_sample_note), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
        }
    }
}

/**
 * Seven bars drawn on a Canvas, today in marigold, the chosen day in ink. Each day is one tap
 * target (the full column) with its own spoken summary; the chosen day's amount is written above it.
 */
@Composable
private fun WeekChart(week: EarningsWeek, onSelect: (Int) -> Unit) {
    val colors = Pillion.colors
    val max = week.days.maxOf { it.rupees }.coerceAtLeast(1)
    Row(Modifier.fillMaxWidth().height(CHART_HEIGHT).selectableGroup()) {
        week.days.forEachIndexed { index, day ->
            val today = index == week.days.lastIndex
            val selected = index == week.selected
            val barColor = when {
                today -> colors.accent
                selected -> colors.ink
                else -> colors.chartBar
            }
            val label = if (today) stringResource(R.string.today_short) else DateFormat.format("EEE", day.dayStart).toString()
            val description = stringResource(
                R.string.earnings_bar_description,
                DateFormat.format("EEEE d MMMM", day.dayStart),
                rupees(day.rupees),
                pluralStringResource(R.plurals.trips_count, day.trips, day.trips),
            )
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clickable(role = Role.Tab, onClickLabel = stringResource(R.string.earnings_show_day)) { onSelect(index) }
                    .semantics(mergeDescendants = true) {
                        contentDescription = description
                        this.selected = selected
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (selected) rupees(day.rupees) else "",
                    style = MaterialTheme.typography.labelMedium.merge(RideType.tabular),
                    color = colors.ink,
                    maxLines = 1,
                )
                val fraction = day.rupees.toFloat() / max
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 7.dp, vertical = Space.s)
                        .drawBehind {
                            val height = (size.height * fraction).coerceAtLeast(3.dp.toPx())
                            drawRoundRect(
                                color = barColor,
                                topLeft = Offset(0f, size.height - height),
                                size = Size(size.width, height),
                                cornerRadius = CornerRadius(size.width / 2, size.width / 2),
                            )
                        },
                )
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selected || today) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected || today) colors.ink else colors.inkSecondary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun DayCard(day: EarningsDb.DayTotal, isToday: Boolean, trips: List<EarningsDb.Trip>) {
    val colors = Pillion.colors
    val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
    PillionCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    if (isToday) stringResource(R.string.today_title) else DateFormat.format("EEEE, d MMM", day.dayStart).toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.ink,
                    modifier = Modifier.semantics { heading() },
                )
                Text(pluralStringResource(R.plurals.trips_count, day.trips, day.trips), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            }
            Text(rupees(day.rupees), style = MaterialTheme.typography.headlineSmall.merge(RideType.tabular), color = colors.ink)
        }
        if (trips.isEmpty()) {
            Text(stringResource(R.string.earnings_no_trips), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        }
        trips.forEach { trip ->
            HorizontalDivider(color = colors.hairline)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .semantics(mergeDescendants = true) {},
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(trip.area, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                    Text(
                        "${time.format(Date(trip.startedAt))} – ${time.format(Date(trip.endedAt))}",
                        style = MaterialTheme.typography.bodySmall.merge(RideType.tabular),
                        color = colors.inkSecondary,
                    )
                }
                Text(rupees(trip.rupees), style = MaterialTheme.typography.titleMedium.merge(RideType.tabular), color = colors.ink)
            }
        }
        Spacer(Modifier.height(2.dp))
    }
}

private val CHART_HEIGHT = 200.dp
