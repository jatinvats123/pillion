package app.pillion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pillion.BuildConfig
import app.pillion.R
import app.pillion.data.Order
import app.pillion.device.RidePermission
import app.pillion.voice.Speaker
import app.pillion.voice.TranscriptLine

private enum class MicPrompt { None, Rationale, Denied }

/** Screen entry point: owns the microphone permission flow and wires the ViewModel. */
@Composable
fun RideRoute(viewModel: RideViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val testOrder by viewModel.testOrder.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var micPrompt by rememberSaveable { mutableStateOf(MicPrompt.None) }

    // Optional extras are only asked once the mic is granted; whatever the answer, the ride starts.
    val optionalPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.startRide() }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            micPrompt = MicPrompt.None
            optionalPermissionsLauncher.launch(optionalRidePermissions())
        } else {
            // No rationale after a denial means "Don't ask again": only Settings can fix it.
            val canAskAgain = activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true
            micPrompt = if (canAskAgain) MicPrompt.Rationale else MicPrompt.Denied
        }
    }

    // Asked again from the permission card after an action failed. If Android no longer shows the
    // dialog ("Don't ask again"), only Settings can fix it.
    val actionPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.none { it }) context.openAppSettings()
        viewModel.dismissPermission()
    }

    // Returning from Settings with the mic now allowed clears the prompt.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (context.hasMicPermission()) micPrompt = MicPrompt.None
    }

    RideScreen(
        state = state,
        micPrompt = micPrompt,
        onStartRide = {
            when {
                context.hasMicPermission() -> optionalPermissionsLauncher.launch(optionalRidePermissions())
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true ->
                    micPrompt = MicPrompt.Rationale
                else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onEndRide = viewModel::endRide,
        onAllowMic = { micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
        onOpenSettings = { context.openAppSettings() },
        onDismissMicPrompt = { micPrompt = MicPrompt.None },
        onDismissError = viewModel::dismissError,
        onGrantPermission = { actionPermissionLauncher.launch(it.manifestNames) },
        onDismissPermission = viewModel::dismissPermission,
        testOrder = testOrder.takeIf { BuildConfig.DEBUG },
        onSaveTestPhone = viewModel::setTestCustomerPhone,
    )
}

@Composable
private fun RideScreen(
    state: RideUiState,
    micPrompt: MicPrompt,
    onStartRide: () -> Unit,
    onEndRide: () -> Unit,
    onAllowMic: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissMicPrompt: () -> Unit,
    onDismissError: () -> Unit,
    onGrantPermission: (RidePermission) -> Unit,
    onDismissPermission: () -> Unit,
    testOrder: Order?,
    onSaveTestPhone: (String) -> Unit,
) {
    // Surface supplies the theme's content colour; bare Text would otherwise default to black.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Header(state.status)

            when (micPrompt) {
                MicPrompt.Rationale -> MessageCard(
                    title = stringResource(R.string.mic_rationale_title),
                    body = stringResource(R.string.mic_rationale_body),
                    actionLabel = stringResource(R.string.mic_allow),
                    onAction = onAllowMic,
                    onDismiss = onDismissMicPrompt,
                )
                MicPrompt.Denied -> MessageCard(
                    title = stringResource(R.string.mic_denied_title),
                    body = stringResource(R.string.mic_denied_body),
                    actionLabel = stringResource(R.string.open_settings),
                    onAction = onOpenSettings,
                    onDismiss = onDismissMicPrompt,
                    isError = true,
                )
                MicPrompt.None -> Unit
            }

            state.errorMessage?.let { message ->
                MessageCard(
                    title = stringResource(R.string.status_error),
                    body = message,
                    onDismiss = onDismissError,
                    isError = true,
                )
            }

            state.permissionNeeded?.let { permission ->
                val (title, body) = when (permission) {
                    RidePermission.Location -> R.string.permission_location_title to R.string.permission_location_body
                    RidePermission.Sms -> R.string.permission_sms_title to R.string.permission_sms_body
                    RidePermission.Call -> R.string.permission_call_title to R.string.permission_call_body
                }
                MessageCard(
                    title = stringResource(title),
                    body = stringResource(body),
                    actionLabel = stringResource(R.string.permission_allow),
                    onAction = { onGrantPermission(permission) },
                    onDismiss = onDismissPermission,
                    isError = true,
                )
            }

            if (testOrder != null && !state.rideActive) TestOrderCard(testOrder, onSaveTestPhone)

            Transcript(lines = state.transcript, modifier = Modifier.weight(1f))

            RideButton(
                active = state.rideActive,
                ending = state.status == RideStatus.Ending,
                onStartRide = onStartRide,
                onEndRide = onEndRide,
            )
        }
    }
}

@Composable
private fun Header(status: RideStatus) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = stringResource(R.string.app_name),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.tagline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StatusLabel(status)
    }
}

@Composable
private fun StatusLabel(status: RideStatus) {
    val (label, color) = when (status) {
        RideStatus.Idle -> R.string.status_idle to MaterialTheme.colorScheme.onSurfaceVariant
        RideStatus.Connecting -> R.string.status_connecting to MaterialTheme.colorScheme.onSurfaceVariant
        RideStatus.Listening -> R.string.status_listening to Color(0xFF4ADE80)
        RideStatus.Thinking -> R.string.status_thinking to Color(0xFF60A5FA)
        RideStatus.Speaking -> R.string.status_speaking to MaterialTheme.colorScheme.primary
        RideStatus.Reconnecting -> R.string.status_reconnecting to Color(0xFFFB923C)
        RideStatus.Ending -> R.string.ending_ride to MaterialTheme.colorScheme.onSurfaceVariant
        RideStatus.Ended -> R.string.status_ended to MaterialTheme.colorScheme.onSurfaceVariant
        RideStatus.Error -> R.string.status_error to MaterialTheme.colorScheme.error
    }
    Row(
        modifier = Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 8.dp)
            // TalkBack announces status changes without the rider having to find the label.
            .semantics { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Text(
            text = stringResource(label),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun Transcript(lines: List<TranscriptLine>, modifier: Modifier = Modifier) {
    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.transcript_empty),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        listState.animateScrollToItem(lines.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(lines, key = { it.key }) { line -> TranscriptBubble(line) }
    }
}

@Composable
private fun TranscriptBubble(line: TranscriptLine) {
    if (line.speaker == Speaker.Action) {
        ActionLine(line)
        return
    }
    val isRider = line.speaker == Speaker.Rider
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = if (isRider) Alignment.End else Alignment.Start,
    ) {
        Text(
            text = stringResource(if (isRider) R.string.speaker_rider else R.string.speaker_pillion),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
        )
        val interrupted = if (line.interrupted) " — ${stringResource(R.string.interrupted)}" else ""
        Text(
            text = line.text + interrupted,
            style = MaterialTheme.typography.bodyLarge,
            color = if (isRider) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .widthIn(max = 320.dp)
                .background(
                    if (isRider) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                    RoundedCornerShape(16.dp),
                )
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .alpha(if (line.isFinal) 1f else 0.7f),
        )
    }
}

/** Something Pillion did ("✓ SMS sent to Rahul"): a compact line, not a speech bubble. */
@Composable
private fun ActionLine(line: TranscriptLine) {
    Text(
        text = line.text,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = if (line.failed) MaterialTheme.colorScheme.error else Color(0xFF4ADE80),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

/** Debug builds: the seeded order, with its customer number editable so SMS/call tests reach you. */
@Composable
private fun TestOrderCard(order: Order, onSavePhone: (String) -> Unit) {
    var phone by rememberSaveable(order.customerPhone) { mutableStateOf(order.customerPhone) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                stringResource(R.string.test_order_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text("${order.customerName} · ${order.dropAddress}", style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text(stringResource(R.string.test_order_phone)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onSavePhone(phone) }, enabled = phone != order.customerPhone) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

@Composable
private fun RideButton(active: Boolean, ending: Boolean, onStartRide: () -> Unit, onEndRide: () -> Unit) {
    Button(
        onClick = if (active) onEndRide else onStartRide,
        enabled = !ending,
        modifier = Modifier.fillMaxWidth().height(72.dp),
        shape = RoundedCornerShape(20.dp),
        colors = if (active) {
            ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = Color.White,
            )
        } else {
            ButtonDefaults.buttonColors()
        },
    ) {
        Text(
            text = stringResource(
                when {
                    ending -> R.string.ending_ride
                    active -> R.string.end_ride
                    else -> R.string.start_ride
                }
            ),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MessageCard(
    title: String,
    body: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    isError: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant,
            contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(body, style = MaterialTheme.typography.bodyMedium)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.dismiss)) }
                if (actionLabel != null) {
                    TextButton(onClick = onAction) { Text(actionLabel) }
                }
            }
        }
    }
}

/**
 * Asked once before riding, since the rider can't tap dialogs on the road: location (ETA, nearby
 * places), SMS and phone (message/call the customer, pause Pillion during calls), Bluetooth
 * (earphone routing on Android 12+) and notifications (ride indicator). Any can be refused; the
 * action that needs it then says so and shows a card. Already-decided ones return without a dialog.
 */
private fun optionalRidePermissions(): Array<String> = buildList {
    RidePermission.entries.forEach { addAll(it.manifestNames) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.hasMicPermission() =
    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}
