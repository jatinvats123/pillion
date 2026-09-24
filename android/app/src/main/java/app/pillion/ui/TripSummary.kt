package app.pillion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import app.pillion.R
import app.pillion.ui.components.PillionCard
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.RideType
import app.pillion.ui.theme.Space
import java.text.DateFormat
import java.util.Date

/**
 * After End Ride: how long, which order and what it paid, and any safety events. Distance isn't
 * shown: only the safety code sees GPS during a ride, and this phase doesn't change it.
 */
@Composable
fun TripSummaryContent(summary: TripSummary, modifier: Modifier = Modifier) {
    val colors = Pillion.colors
    val minutes = ((summary.endedAt - summary.startedAt) / 60_000).toInt()
    val time = DateFormat.getTimeInstance(DateFormat.SHORT)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.l)) {
        Column(Modifier.padding(top = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Text(
                stringResource(R.string.summary_title),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                "${time.format(Date(summary.startedAt))} – ${time.format(Date(summary.endedAt))}",
                style = MaterialTheme.typography.bodyLarge.merge(RideType.tabular),
                color = colors.inkSecondary,
            )
        }
        PillionCard {
            Text(
                if (minutes < 1) stringResource(R.string.summary_under_minute) else pluralStringResource(R.plurals.minutes, minutes, minutes),
                style = MaterialTheme.typography.displaySmall.merge(RideType.tabular),
                color = colors.ink,
            )
            Text(stringResource(R.string.summary_ride_time), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(vertical = Space.s))
            val order = summary.order
            SummaryRow(
                stringResource(R.string.summary_order),
                listOf(order.customerName, order.dropArea).filter { it.isNotBlank() }.joinToString(" · ").ifBlank { "—" },
            )
            SummaryRow(stringResource(R.string.summary_earned), rupees(order.payoutRupees))
            if (order.payoutRupees == 0 && !order.isDemo) {
                Text(stringResource(R.string.summary_no_earning), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
            if (order.isDemo) {
                Text(stringResource(R.string.summary_demo_note), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }
        }
        PillionCard {
            Text(stringResource(R.string.summary_safety), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.semantics { heading() })
            if (summary.safetyEvents.isEmpty()) {
                Text(stringResource(R.string.summary_no_events), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            } else {
                // Oldest first reads as the story of the ride.
                summary.safetyEvents.reversed().forEach { SafetyEventRow(it, showNote = false) }
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.Top) {
        Text(label, style = MaterialTheme.typography.bodyLarge, color = Pillion.colors.inkSecondary)
        Text(
            value,
            style = MaterialTheme.typography.titleMedium.merge(RideType.tabular),
            color = Pillion.colors.ink,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}
