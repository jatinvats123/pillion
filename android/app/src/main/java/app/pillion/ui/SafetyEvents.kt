package app.pillion.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.pillion.R
import app.pillion.data.EarningsDb
import app.pillion.ui.components.Tag
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.PillionColors
import app.pillion.ui.theme.Space
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

/** How a safety-log kind (SafetyMonitor's KIND_*, plus the seeded demo kinds) reads and looks. */
private data class EventLook(@StringRes val label: Int, @DrawableRes val icon: Int, val tint: (PillionColors) -> Color)

private fun lookOf(kind: String): EventLook = when (kind) {
    "crash_detected" -> EventLook(R.string.event_crash_detected, R.drawable.ic_car_crash) { it.danger }
    "crash_cancelled" -> EventLook(R.string.event_crash_cancelled, R.drawable.ic_check_circle) { it.success }
    "manual_sos" -> EventLook(R.string.event_manual_sos, R.drawable.ic_sos) { it.danger }
    "sos_cancelled" -> EventLook(R.string.event_sos_cancelled, R.drawable.ic_check_circle) { it.success }
    "sos_sent" -> EventLook(R.string.event_sos_sent, R.drawable.ic_sms) { it.danger }
    "sos_failed" -> EventLook(R.string.event_sos_failed, R.drawable.ic_error) { it.danger }
    "sos_ended" -> EventLook(R.string.event_sos_ended, R.drawable.ic_check_circle) { it.success }
    "fatigue_reminder" -> EventLook(R.string.event_fatigue, R.drawable.ic_bedtime) { it.ink }
    "hard_brake" -> EventLook(R.string.event_hard_brake, R.drawable.ic_warning) { it.caution }
    "fall_check" -> EventLook(R.string.event_fall_check, R.drawable.ic_info) { it.ink }
    else -> EventLook(R.string.event_other, R.drawable.ic_info) { it.ink }
}

/** One safety event: icon in a soft circle, what happened, when, and the log's note. */
@Composable
fun SafetyEventRow(event: EarningsDb.SafetyEvent, modifier: Modifier = Modifier, showNote: Boolean = true) {
    val colors = Pillion.colors
    val look = lookOf(event.kind)
    val tint = look.tint(colors)
    Row(modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(40.dp).background(tint.copy(alpha = 0.14f), CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(look.icon), contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(look.label), style = MaterialTheme.typography.titleSmall, color = colors.ink, modifier = Modifier.weight(1f, fill = false))
                if (event.seeded) Tag(stringResource(R.string.sample))
            }
            Text(whenText(event.at), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            if (showNote && event.note.isNotBlank()) {
                // Notes can be long (an SOS lists every contact and the map link): two lines are enough here.
                Text(event.note, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** "Today 18:42", "Yesterday 09:10", else "22 Sep 09:10" (the phone's own 12/24 h setting). */
@Composable
fun whenText(at: Long): String {
    val time = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at))
    return when (daysAgo(at)) {
        0 -> stringResource(R.string.today_at, time)
        1 -> stringResource(R.string.yesterday_at, time)
        else -> "${android.text.format.DateFormat.format("d MMM", at)} $time"
    }
}

fun daysAgo(at: Long, now: Long = System.currentTimeMillis()): Int {
    fun day(time: Long) = Calendar.getInstance().apply {
        timeInMillis = time
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    // Rounded: a day across a daylight-saving change is 23 or 25 hours.
    return Math.round((day(now) - day(at)) / 86_400_000.0).toInt()
}
