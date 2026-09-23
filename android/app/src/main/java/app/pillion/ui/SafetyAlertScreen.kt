package app.pillion.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import kotlinx.coroutines.delay

private val AlertRed = Color(0xFF8B0F0F)
private val OkGreen = Color(0xFF22C55E)

/** Full-screen alert content: the countdown with one huge "I'M OK", then the SOS results. */
@Composable
fun SafetyAlertScreen(
    state: SafetyState,
    voiceConnected: Boolean,
    onOk: () -> Unit,
    onSendNow: () -> Unit,
    onDial: (String) -> Unit,
) {
    val now = rememberElapsedNow()
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = if (state is SafetyState.Countdown) AlertRed else MaterialTheme.colorScheme.background,
        contentColor = Color.White,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
    Text(
        text = stringResource(if (crash) R.string.alert_crash_title else R.string.alert_manual_title),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
    if (crash) Text(stringResource(R.string.alert_crash_question), style = MaterialTheme.typography.titleLarge)

    val countdownDescription = pluralStringResource(R.plurals.alert_countdown_description, secondsLeft.toInt(), secondsLeft)
    Text(
        text = secondsLeft.toString(),
        fontSize = 120.sp,
        fontWeight = FontWeight.Black,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
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

    Spacer(Modifier.weight(1f))
    BigButton(
        label = stringResource(if (crash) R.string.alert_im_ok else R.string.alert_cancel_sos),
        container = Color.White,
        content = Color.Black,
        onClick = onOk,
        modifier = Modifier.heightIn(min = 220.dp),
    )
    TextButton(onClick = onSendNow, modifier = Modifier.align(Alignment.CenterHorizontally)) {
        Text(stringResource(R.string.alert_send_now), color = Color.White, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ColumnScope.SosResult(state: SafetyState.Sos, now: Long, onOk: () -> Unit, onDial: (String) -> Unit) {
    Text(
        text = stringResource(if (state.problem == null) R.string.alert_sos_sent_title else R.string.alert_sos_not_sent_title),
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
        color = if (state.problem == null) OkGreen else MaterialTheme.colorScheme.error,
        modifier = Modifier.semantics {
            heading()
            liveRegion = LiveRegionMode.Polite
        },
    )
    state.problem?.let { Text(problemText(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
    state.contacts.forEach { ContactRow(it) }
    val next = state.nextUpdateAtMs
    when {
        next != null -> {
            val left = ((next - now) / 1000).coerceAtLeast(0)
            Text(
                stringResource(R.string.alert_next_update, "%d:%02d".format(left / 60, left % 60), state.updatesSent, state.updatesTotal),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        state.updatesTotal > 0 && state.updatesSent == state.updatesTotal ->
            Text(stringResource(R.string.alert_updates_done), style = MaterialTheme.typography.bodyLarge)
    }

    Spacer(Modifier.weight(1f))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { onDial("112") },
            modifier = Modifier.weight(1f).height(64.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error, contentColor = Color.White),
        ) {
            Text(stringResource(R.string.alert_call_112), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        state.contacts.firstOrNull()?.let { first ->
            OutlinedButton(onClick = { onDial(first.contact.number) }, modifier = Modifier.weight(1f).height(64.dp)) {
                Text(stringResource(R.string.alert_call_contact, first.contact.name), color = Color.White, maxLines = 1)
            }
        }
    }
    BigButton(
        label = stringResource(R.string.alert_im_ok_now),
        container = OkGreen,
        content = Color.Black,
        onClick = onOk,
        modifier = Modifier.heightIn(min = 140.dp),
    )
}

@Composable
private fun ContactRow(status: ContactStatus) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(status.contact.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        val (label, color) = when (status.status) {
            DeliveryStatus.Sending -> R.string.delivery_sending to MaterialTheme.colorScheme.onSurfaceVariant
            DeliveryStatus.Sent -> R.string.delivery_sent to OkGreen
            DeliveryStatus.Delivered -> R.string.delivery_delivered to OkGreen
            DeliveryStatus.NotDelivered -> R.string.delivery_not_delivered to MaterialTheme.colorScheme.error
            DeliveryStatus.Failed -> R.string.delivery_failed to MaterialTheme.colorScheme.error
        }
        Text(stringResource(label), color = color, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BigButton(label: String, container: Color, content: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
    ) {
        Text(label, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.Black, textAlign = TextAlign.Center)
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
