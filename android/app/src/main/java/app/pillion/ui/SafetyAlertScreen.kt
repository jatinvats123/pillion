package app.pillion.ui

import android.os.SystemClock
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.R
import app.pillion.safety.ContactStatus
import app.pillion.safety.DeliveryStatus
import app.pillion.safety.SafetyState
import app.pillion.safety.SosProblem
import app.pillion.safety.SosTrigger
import app.pillion.ui.components.PillButton
import app.pillion.ui.components.PillionCard
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.PillionDisplayFont
import app.pillion.ui.theme.RideType
import app.pillion.ui.theme.Space
import app.pillion.ui.theme.Targets
import kotlinx.coroutines.delay

private val OkGreen = Color(0xFF22C55E)

/**
 * Full-screen alert content, loud on purpose (the one place red fills the screen): the countdown
 * with one giant "I'M OK", then the SOS results.
 */
@Composable
fun SafetyAlertScreen(
    state: SafetyState,
    voiceConnected: Boolean,
    onOk: () -> Unit,
    onSendNow: () -> Unit,
    onDial: (String) -> Unit,
) {
    val now = rememberElapsedNow()
    val colors = Pillion.colors
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (state is SafetyState.Countdown) colors.alertScreen else colors.background,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = Space.gutter, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            when (state) {
                is SafetyState.Countdown -> Countdown(state, now, voiceConnected, onOk, onSendNow)
                is SafetyState.Sos -> SosResult(state, now, onOk, onDial)
                SafetyState.Idle -> Unit
            }
        }
    }
}

@Composable
private fun ColumnScope.Countdown(
    state: SafetyState.Countdown,
    now: Long,
    voiceConnected: Boolean,
    onOk: () -> Unit,
    onSendNow: () -> Unit,
) {
    val crash = state.trigger == SosTrigger.Crash
    val secondsLeft = ((state.endsAtMs - now + 999) / 1000).coerceAtLeast(0)
    // The texts scroll if the font is very large; the two buttons always stay on screen.
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(painterResource(if (crash) R.drawable.ic_car_crash else R.drawable.ic_sos), contentDescription = null, modifier = Modifier.size(32.dp))
            Text(
                text = stringResource(if (crash) R.string.alert_crash_title else R.string.alert_manual_title),
                style = MaterialTheme.typography.headlineLarge,
                modifier = Modifier.semantics { heading() },
            )
        }
        if (crash) Text(stringResource(R.string.alert_crash_question), style = MaterialTheme.typography.headlineSmall)

        val countdownDescription = pluralStringResource(R.plurals.alert_countdown_description, secondsLeft.toInt(), secondsLeft)
        Text(
            text = secondsLeft.toString(),
            fontFamily = PillionDisplayFont,
            fontSize = 168.sp,
            lineHeight = 176.sp,
            fontWeight = FontWeight.Black,
            style = RideType.tabular,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .semantics {
                    contentDescription = countdownDescription
                    liveRegion = LiveRegionMode.Polite
                },
        )
        Text(
            text = state.problem?.let { problemText(it) } ?: stringResource(R.string.alert_countdown_body),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (state.problem != null) FontWeight.Bold else FontWeight.Normal,
        )
        if (voiceConnected) Text(stringResource(R.string.alert_voice_hint), style = MaterialTheme.typography.titleMedium)
    }

    BigButton(
        label = stringResource(if (crash) R.string.alert_im_ok else R.string.alert_cancel_sos),
        container = Color.White,
        content = Color(0xFF17150F),
        onClick = onOk,
        modifier = Modifier.heightIn(min = 200.dp),
    )
    PillButton(
        text = stringResource(R.string.alert_send_now),
        onClick = onSendNow,
        container = Color.Transparent,
        content = Color.White,
        border = BorderStroke(2.dp, Color.White),
        minHeight = 64.dp,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ColumnScope.SosResult(state: SafetyState.Sos, now: Long, onOk: () -> Unit, onDial: (String) -> Unit) {
    val colors = Pillion.colors
    Column(
        Modifier
            .weight(1f)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        Text(
            text = stringResource(if (state.problem == null) R.string.alert_sos_sent_title else R.string.alert_sos_not_sent_title),
            style = MaterialTheme.typography.displaySmall,
            color = if (state.problem == null) colors.success else colors.danger,
            modifier = Modifier.semantics {
                heading()
                liveRegion = LiveRegionMode.Polite
            },
        )
        state.problem?.let { Text(problemText(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = colors.ink) }
        if (state.contacts.isNotEmpty()) {
            PillionCard {
                state.contacts.forEachIndexed { index, contact ->
                    if (index > 0) HorizontalDivider(color = colors.hairline)
                    ContactRow(contact)
                }
            }
        }
        val next = state.nextUpdateAtMs
        when {
            next != null -> {
                val left = ((next - now) / 1000).coerceAtLeast(0)
                Text(
                    stringResource(R.string.alert_next_update, "%d:%02d".format(left / 60, left % 60), state.updatesSent, state.updatesTotal),
                    style = MaterialTheme.typography.bodyLarge.merge(RideType.tabular),
                    color = colors.inkSecondary,
                )
            }
            state.updatesTotal > 0 && state.updatesSent == state.updatesTotal ->
                Text(stringResource(R.string.alert_updates_done), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
        PillButton(
            text = stringResource(R.string.alert_call_112),
            onClick = { onDial("112") },
            icon = R.drawable.ic_call,
            container = colors.alertScreen,
            content = Color.White,
            minHeight = Targets.rideSmall,
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.weight(1f),
        )
        state.contacts.firstOrNull()?.let { first ->
            PillButton(
                text = stringResource(R.string.alert_call_contact, first.contact.name),
                onClick = { onDial(first.contact.number) },
                container = Color.Transparent,
                content = colors.ink,
                border = BorderStroke(2.dp, colors.ink),
                minHeight = Targets.rideSmall,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
        }
    }
    BigButton(
        label = stringResource(R.string.alert_im_ok_now),
        container = OkGreen,
        content = Color(0xFF17150F),
        onClick = onOk,
        modifier = Modifier.heightIn(min = 140.dp),
    )
}

@Composable
private fun ContactRow(status: ContactStatus) {
    val colors = Pillion.colors
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalAlignment = Alignment.CenterVertically) {
        Text(status.contact.name, style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.weight(1f))
        val (label, color) = when (status.status) {
            DeliveryStatus.Sending -> R.string.delivery_sending to colors.inkSecondary
            DeliveryStatus.Sent -> R.string.delivery_sent to colors.success
            DeliveryStatus.Delivered -> R.string.delivery_delivered to colors.success
            DeliveryStatus.NotDelivered -> R.string.delivery_not_delivered to colors.danger
            DeliveryStatus.Failed -> R.string.delivery_failed to colors.danger
        }
        Text(stringResource(label), color = color, style = MaterialTheme.typography.titleMedium)
    }
}

/** The one thing to press: a huge rounded slab with big, bilingual text. */
@Composable
private fun BigButton(label: String, container: Color, content: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(48.dp),
        color = container,
        contentColor = content,
        modifier = modifier.fillMaxWidth(),
    ) {
        // Surface stretches this to the button's minimum height, so the label sits in the middle.
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                label,
                fontFamily = PillionDisplayFont,
                fontSize = 36.sp,
                lineHeight = 46.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(Space.l),
            )
        }
    }
}

@Composable
private fun problemText(problem: SosProblem): String = when (problem) {
    SosProblem.NoContacts -> stringResource(R.string.alert_problem_no_contacts)
    SosProblem.NoSmsPermission -> stringResource(R.string.alert_problem_no_sms)
    SosProblem.NothingSent -> stringResource(R.string.alert_problem_nothing_sent)
}

@Composable
private fun rememberElapsedNow(): Long {
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(250)
        }
    }
    return now
}
