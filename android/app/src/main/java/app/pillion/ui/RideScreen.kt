package app.pillion.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
import app.pillion.order.ScanSource
import app.pillion.order.ScanState
import app.pillion.order.loadOrderImage
import app.pillion.pillion
import app.pillion.safety.SafetyState
import app.pillion.voice.Speaker
import app.pillion.voice.TranscriptLine

private enum class MicPrompt { None, Rationale, Denied }

/** What keeps crash detection or the SOS from working fully; each has a fix. */
private enum class SetupIssue { NoContacts, Sms, Location, Notifications, FullScreenAlerts, BatteryOptimization, HindiVoice }

/** Screen entry point: owns the permission flows and wires the ViewModel. */
@Composable
fun RideRoute(onOpenSafety: () -> Unit, onOpenCamera: () -> Unit, viewModel: RideViewModel = viewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val locatingDrop by viewModel.locatingDrop.collectAsStateWithLifecycle()
    val stopToScan by viewModel.stopToScan.collectAsStateWithLifecycle()
    val safetyState by viewModel.safetyState.collectAsStateWithLifecycle()
    val contactCount by viewModel.emergencyContactCount.collectAsStateWithLifecycle()
    val gpsAvailable by viewModel.gpsAvailable.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var micPrompt by rememberSaveable { mutableStateOf(MicPrompt.None) }
    var setupDismissed by rememberSaveable { mutableStateOf(false) }
    var cameraRefused by rememberSaveable { mutableStateOf(false) }

    // Order scan: a screenshot from the system photo picker (no storage permission), or the camera.
    val appContext = context.applicationContext
    val pickScreenshot = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) viewModel.scanImage(ScanSource.Gallery) { loadOrderImage(appContext, uri) }
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraRefused = !granted
        if (granted) onOpenCamera()
    }
    BackHandler(enabled = scanState != ScanState.Idle, onBack = viewModel::dismissScan)

    // Optional extras are only asked once the mic is granted; whatever the answer, the ride starts.
    val optionalPermissionsLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { viewModel.startRide(caller = "permission prompts after Start Ride") }

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

    // Asked again from a card after an action failed or for safety setup. If Android no longer
    // shows the dialog ("Don't ask again"), only Settings can fix it.
    val actionPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.none { it }) context.openAppSettings()
        viewModel.dismissPermission()
    }

    // Permissions and settings change outside the app; re-check on every return.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (context.hasMicPermission()) micPrompt = MicPrompt.None
        resumes++
    }
    val setupIssues = remember(contactCount, resumes) { context.safetySetupIssues(contactCount) }

    RideScreen(
        state = state,
        micPrompt = micPrompt,
        safetyState = safetyState,
        gpsAvailable = gpsAvailable,
        setupIssues = if (setupDismissed) emptyList() else setupIssues,
        onStartRide = {
            viewModel.requestStart(caller = "Start Ride button")
            when {
                context.hasMicPermission() -> optionalPermissionsLauncher.launch(optionalRidePermissions())
                activity?.shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) == true ->
                    micPrompt = MicPrompt.Rationale
                else -> micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        },
        onEndRide = viewModel::endRide,
        onSos = viewModel::sos,
        onOpenAlert = { SafetyAlertActivity.launch(context) },
        onRetryVoice = viewModel::retryVoice,
        onOpenSafety = onOpenSafety,
        onAllowMic = {
            viewModel.requestStart(caller = "Allow microphone card")
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onOpenSettings = { context.openAppSettings() },
        onDismissMicPrompt = { micPrompt = MicPrompt.None },
        onGrantPermission = { actionPermissionLauncher.launch(it.manifestNames) },
        onDismissPermission = viewModel::dismissPermission,
        onFixSetup = { issue ->
            when (issue) {
                SetupIssue.NoContacts -> onOpenSafety()
                SetupIssue.Sms -> actionPermissionLauncher.launch(RidePermission.Sms.manifestNames)
                SetupIssue.Location -> actionPermissionLauncher.launch(RidePermission.Location.manifestNames)
                SetupIssue.Notifications ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        actionPermissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                    }
                SetupIssue.FullScreenAlerts -> context.openFullScreenAlertSettings()
                SetupIssue.BatteryOptimization -> context.askToIgnoreBatteryOptimization()
                SetupIssue.HindiVoice -> context.startSafely(Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA))
            }
        },
        onDismissSetup = { setupDismissed = true },
        orderCard = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (stopToScan) {
                    MessageCard(
                        title = stringResource(R.string.order_stop_to_scan_title),
                        body = stringResource(R.string.order_stop_to_scan_body),
                        onDismiss = viewModel::dismissStopToScan,
                        isError = true,
                    )
                }
                if (cameraRefused) {
                    MessageCard(
                        title = stringResource(R.string.camera_denied_title),
                        body = stringResource(R.string.camera_denied_body),
                        actionLabel = stringResource(R.string.allow),
                        onAction = {
                            // No rationale after a refusal means "Don't ask again": only Settings can fix it.
                            if (activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == true) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            } else {
                                context.openAppSettings()
                            }
                        },
                        onDismiss = { cameraRefused = false },
                        isError = true,
                    )
                }
                ActiveOrderCard(
                    order = order,
                    locatingDrop = locatingDrop,
                    onScanScreenshot = {
                        if (viewModel.scanAllowed()) pickScreenshot.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onScanCamera = {
                        if (viewModel.scanAllowed()) {
                            if (context.granted(Manifest.permission.CAMERA)) onOpenCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onUseDemo = viewModel::useDemoOrder,
                )
            }
        },
        scanPanel = if (scanState == ScanState.Idle) {
            null
        } else {
            { modifier ->
                OrderScanPanel(
                    state = scanState,
                    onConfirm = viewModel::confirmOrder,
                    onTypeIn = viewModel::enterOrderManually,
                    onDismiss = viewModel::dismissScan,
                    modifier = modifier,
                )
            }
        },
        testOrder = order.takeIf { BuildConfig.DEBUG && it.isDemo },
        onSaveTestPhone = viewModel::setTestCustomerPhone,
        debug = if (BuildConfig.DEBUG) {
            {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    DebugSafetyCard(
                        viewModel = viewModel,
                        rideActive = state.rideActive,
                    )
                    DebugScanCard(onScan = viewModel::scanImage)
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun RideScreen(
    state: RideUiState,
    micPrompt: MicPrompt,
    safetyState: SafetyState,
    gpsAvailable: Boolean,
    setupIssues: List<SetupIssue>,
    onStartRide: () -> Unit,
    onEndRide: () -> Unit,
    onSos: () -> Unit,
    onOpenAlert: () -> Unit,
    onRetryVoice: () -> Unit,
    onOpenSafety: () -> Unit,
    onAllowMic: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismissMicPrompt: () -> Unit,
    onGrantPermission: (RidePermission) -> Unit,
    onDismissPermission: () -> Unit,
    onFixSetup: (SetupIssue) -> Unit,
    onDismissSetup: () -> Unit,
    orderCard: @Composable () -> Unit,
    /** Set while an order is read or checked: it takes the transcript's place. */
    scanPanel: (@Composable (Modifier) -> Unit)?,
    testOrder: Order?,
    onSaveTestPhone: (String) -> Unit,
    debug: (@Composable () -> Unit)?,
) {
    // Surface supplies the theme's content colour; bare Text would otherwise default to black.
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Header(state.status, onOpenSafety)

            // Always in view: what the rider must know right now.
            if (safetyState != SafetyState.Idle) {
                MessageCard(
                    title = stringResource(R.string.safety_alert_active),
                    actionLabel = stringResource(R.string.open),
                    onAction = onOpenAlert,
                    isError = true,
                )
            }
            state.voiceProblem?.let { problem ->
                MessageCard(
                    title = stringResource(R.string.voice_offline_title),
                    body = stringResource(R.string.voice_offline_body, problem),
                    actionLabel = stringResource(R.string.retry_voice),
                    onAction = onRetryVoice,
                )
            }

            // Cards that scroll away with the transcript.
            val cards = buildList<@Composable () -> Unit> {
                when (micPrompt) {
                    MicPrompt.Rationale -> add {
                        MessageCard(
                            title = stringResource(R.string.mic_rationale_title),
                            body = stringResource(R.string.mic_rationale_body),
                            actionLabel = stringResource(R.string.mic_allow),
                            onAction = onAllowMic,
                            onDismiss = onDismissMicPrompt,
                        )
                    }
                    MicPrompt.Denied -> add {
                        MessageCard(
                            title = stringResource(R.string.mic_denied_title),
                            body = stringResource(R.string.mic_denied_body),
                            actionLabel = stringResource(R.string.open_settings),
                            onAction = onOpenSettings,
                            onDismiss = onDismissMicPrompt,
                            isError = true,
                        )
                    }
                    MicPrompt.None -> Unit
                }
                if (state.rideActive && !gpsAvailable) add {
                    MessageCard(
                        title = stringResource(R.string.gps_unavailable_title),
                        body = stringResource(R.string.gps_unavailable_body),
                        isError = true,
                    )
                }
                state.permissionNeeded?.let { permission ->
                    add {
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
                }
                if (setupIssues.isNotEmpty()) add { SetupCard(setupIssues, onFixSetup, onDismissSetup) }
                add(orderCard)
                if (testOrder != null && !state.rideActive) add { TestOrderCard(testOrder, onSaveTestPhone) }
                debug?.let { add(it) }
            }
            if (scanPanel != null) {
                scanPanel(Modifier.weight(1f))
            } else {
                Transcript(cards = cards, lines = state.transcript, modifier = Modifier.weight(1f))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                SosButton(enabled = safetyState == SafetyState.Idle, onClick = onSos)
                RideButton(
                    active = state.rideActive,
                    ending = state.status == RideStatus.Ending,
                    alertOn = safetyState != SafetyState.Idle,
                    onStartRide = onStartRide,
                    onEndRide = onEndRide,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun Header(status: RideStatus, onOpenSafety: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
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
        TextButton(onClick = onOpenSafety) { Text(stringResource(R.string.safety_button)) }
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
        RideStatus.VoiceOffline -> R.string.status_voice_offline to Color(0xFFFB923C)
        RideStatus.Ending -> R.string.ending_ride to MaterialTheme.colorScheme.onSurfaceVariant
        RideStatus.Ended -> R.string.status_ended to MaterialTheme.colorScheme.onSurfaceVariant
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

/** Cards first, then the conversation; follows the newest line. */
@Composable
private fun Transcript(cards: List<@Composable () -> Unit>, lines: List<TranscriptLine>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LaunchedEffect(lines.size, lines.lastOrNull()?.text) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(cards.size + lines.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(cards.size) { cards[it]() }
        if (lines.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.transcript_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
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

/** Something Pillion did ("✓ SMS sent to Rahul", "⚠ Crash detected"): a compact line, not a speech bubble. */
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

@Composable
private fun SetupCard(issues: List<SetupIssue>, onFix: (SetupIssue) -> Unit, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer, contentColor = MaterialTheme.colorScheme.onErrorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            issues.forEach { issue ->
                val (text, action) = when (issue) {
                    SetupIssue.NoContacts -> R.string.setup_no_contacts to R.string.add
                    SetupIssue.Sms -> R.string.setup_sms to R.string.allow
                    SetupIssue.Location -> R.string.setup_location to R.string.allow
                    SetupIssue.Notifications -> R.string.setup_notifications to R.string.allow
                    SetupIssue.FullScreenAlerts -> R.string.setup_full_screen to R.string.allow
                    SetupIssue.BatteryOptimization -> R.string.setup_battery to R.string.fix
                    SetupIssue.HindiVoice -> R.string.setup_hindi_voice to R.string.install
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
                        if (issue == SetupIssue.BatteryOptimization && isColorOsFamily()) {
                            Text(stringResource(R.string.setup_battery_oem), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    TextButton(onClick = { onFix(issue) }) { Text(stringResource(action)) }
                }
            }
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.dismiss)) }
        }
    }
}

/** DEBUG BUILDS ONLY — Phase 3 test switches, clearly labelled. */
@Composable
private fun DebugSafetyCard(viewModel: RideViewModel, rideActive: Boolean) {
    val context = LocalContext.current
    val demo by viewModel.debug.demoMode.collectAsStateWithLifecycle()
    val fatigue by viewModel.debug.fatigueInTwoMinutes.collectAsStateWithLifecycle()
    val recording by viewModel.debug.recording.collectAsStateWithLifecycle()
    val needsRide = stringResource(R.string.debug_simulate_needs_ride)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.debug_safety_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = {
                if (!rideActive || !viewModel.simulateCrash()) Toast.makeText(context, needsRide, Toast.LENGTH_LONG).show()
            }) { Text(stringResource(R.string.debug_simulate_crash)) }
            DebugSwitch(stringResource(R.string.debug_record), recording, viewModel.debug::setRecording)
            DebugSwitch(stringResource(R.string.debug_demo), demo, viewModel.debug::setDemoMode)
            DebugSwitch(stringResource(R.string.debug_fatigue), fatigue, viewModel.debug::setFatigueInTwoMinutes)
        }
    }
}

@Composable
private fun DebugSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
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

/** Works with or without a ride, voice or internet: a 5-second cancel window, then SMS. */
@Composable
private fun SosButton(enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.sos_button_description)
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(width = 96.dp, height = 72.dp)
            .semantics { contentDescription = description },
        shape = RoundedCornerShape(20.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626), contentColor = Color.White),
    ) {
        Text(stringResource(R.string.sos_button), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
    }
}

@Composable
private fun RideButton(
    active: Boolean,
    ending: Boolean,
    alertOn: Boolean,
    onStartRide: () -> Unit,
    onEndRide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = if (active) onEndRide else onStartRide,
        // During a safety alert the rider answers it first.
        enabled = !ending && !(active && alertOn),
        modifier = modifier.height(72.dp),
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
    body: String? = null,
    onDismiss: (() -> Unit)? = null,
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
            body?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
            if (onDismiss != null || actionLabel != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    onDismiss?.let { TextButton(onClick = it) { Text(stringResource(R.string.dismiss)) } }
                    if (actionLabel != null) {
                        TextButton(onClick = onAction) { Text(actionLabel) }
                    }
                }
            }
        }
    }
}

/**
 * Asked once before riding, since the rider can't tap dialogs on the road: location (ETA, nearby
 * places, crash detection, SOS location), SMS and phone (customer messages and calls, SOS), Bluetooth
 * (earphone routing on Android 12+) and notifications (ride indicator, lock-screen crash alert). Any
 * can be refused; what needs it then says so and shows a card. Already-decided ones return at once.
 */
private fun optionalRidePermissions(): Array<String> = buildList {
    RidePermission.entries.forEach { addAll(it.manifestNames) }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(Manifest.permission.BLUETOOTH_CONNECT)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
}.toTypedArray()

private fun Context.safetySetupIssues(contactCount: Int): List<SetupIssue> = buildList {
    if (contactCount == 0) add(SetupIssue.NoContacts)
    if (!granted(Manifest.permission.SEND_SMS)) add(SetupIssue.Sms)
    if (!granted(Manifest.permission.ACCESS_FINE_LOCATION) && !granted(Manifest.permission.ACCESS_COARSE_LOCATION)) add(SetupIssue.Location)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !granted(Manifest.permission.POST_NOTIFICATIONS)) add(SetupIssue.Notifications)
    if (!pillion.safety.canShowOverLockScreen()) add(SetupIssue.FullScreenAlerts)
    if (!getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) add(SetupIssue.BatteryOptimization)
    if (pillion.safety.alarm.hindiVoiceMissing) add(SetupIssue.HindiVoice)
}

// Realme, OPPO and OnePlus (ColorOS) stop background apps beyond Android's own battery optimisation.
private fun isColorOsFamily() = Build.MANUFACTURER.lowercase() in setOf("realme", "oppo", "oneplus")

private fun Context.granted(permission: String) =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasMicPermission() = granted(Manifest.permission.RECORD_AUDIO)

private fun Context.openAppSettings() {
    startSafely(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
}

private fun Context.openFullScreenAlertSettings() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startSafely(Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.fromParts("package", packageName, null)))
    }
}

/** Android's own "stop optimising battery usage?" dialog; the list screen if a phone lacks it. */
private fun Context.askToIgnoreBatteryOptimization() {
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
    if (!startSafely(ask)) startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun Context.startSafely(intent: Intent): Boolean =
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
