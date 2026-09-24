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
import androidx.annotation.StringRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pillion.BuildConfig
import app.pillion.R
import app.pillion.data.EarningsDb
import app.pillion.data.Order
import app.pillion.device.RidePermission
import app.pillion.order.ScanSource
import app.pillion.order.ScanState
import app.pillion.order.loadOrderImage
import app.pillion.pillion
import app.pillion.safety.SafetyState
import app.pillion.ui.components.NoticeCard
import app.pillion.ui.components.Orb
import app.pillion.ui.components.OrbMood
import app.pillion.ui.components.PillButton
import app.pillion.ui.components.PillionCard
import app.pillion.ui.components.RoundIconButton
import app.pillion.ui.components.StatusPill
import app.pillion.ui.components.dotColor
import app.pillion.ui.components.orbBackdrop
import app.pillion.ui.components.rememberOrbColor
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.RideType
import app.pillion.ui.theme.Space
import app.pillion.ui.theme.Targets
import app.pillion.voice.Speaker
import app.pillion.voice.TranscriptLine
import java.text.NumberFormat
import java.util.Locale

private enum class MicPrompt { None, Rationale, Denied }

/** What keeps crash detection or the SOS from working fully; each has a fix. */
private enum class SetupIssue { NoContacts, Sms, Location, Notifications, FullScreenAlerts, BatteryOptimization, HindiVoice }

/** Screen entry point: owns the permission flows and wires the ViewModel. */
@Composable
fun RideRoute(
    onOpenSafety: () -> Unit,
    onOpenCamera: () -> Unit,
    onOpenSettings: () -> Unit,
    viewModel: RideViewModel = viewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val order by viewModel.order.collectAsStateWithLifecycle()
    val scanState by viewModel.scanState.collectAsStateWithLifecycle()
    val locatingDrop by viewModel.locatingDrop.collectAsStateWithLifecycle()
    val stopToScan by viewModel.stopToScan.collectAsStateWithLifecycle()
    val safetyState by viewModel.safetyState.collectAsStateWithLifecycle()
    val contactCount by viewModel.emergencyContactCount.collectAsStateWithLifecycle()
    val gpsAvailable by viewModel.gpsAvailable.collectAsStateWithLifecycle()
    val riderName by viewModel.riderName.collectAsStateWithLifecycle()
    val today by viewModel.today.collectAsStateWithLifecycle()
    val muted by viewModel.micMuted.collectAsStateWithLifecycle()
    val subtitles by viewModel.subtitles.collectAsStateWithLifecycle()
    val subtitlesOn by LocalContext.current.pillion.uiPrefs.subtitles.collectAsStateWithLifecycle()
    val tripSummary by viewModel.tripSummary.collectAsStateWithLifecycle()
    // Read by the orb at draw time only: ten level updates a second don't recompose the screen.
    val riderLevel = viewModel.riderLevel.collectAsStateWithLifecycle()
    val agentLevel = viewModel.agentLevel.collectAsStateWithLifecycle()
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
        viewModel.refreshToday()
        resumes++
    }
    val setupIssues = remember(contactCount, resumes) { context.safetySetupIssues(contactCount) }

    val onFixSetup: (SetupIssue) -> Unit = { issue ->
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
    }

    // What the rider should know or fix, most urgent first. They sit above the order card, or
    // above the conversation during a ride.
    val notices = buildList<@Composable () -> Unit> {
        when (micPrompt) {
            MicPrompt.Rationale -> add {
                NoticeCard(
                    title = stringResource(R.string.mic_rationale_title),
                    body = stringResource(R.string.mic_rationale_body),
                    icon = R.drawable.ic_mic,
                    actionLabel = stringResource(R.string.mic_allow),
                    onAction = {
                        viewModel.requestStart(caller = "Allow microphone card")
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onDismiss = { micPrompt = MicPrompt.None },
                )
            }
            MicPrompt.Denied -> add {
                NoticeCard(
                    title = stringResource(R.string.mic_denied_title),
                    body = stringResource(R.string.mic_denied_body),
                    icon = R.drawable.ic_mic_off,
                    actionLabel = stringResource(R.string.open_settings),
                    onAction = { context.openAppSettings() },
                    onDismiss = { micPrompt = MicPrompt.None },
                )
            }
            MicPrompt.None -> Unit
        }
        if (state.rideActive && !gpsAvailable) add {
            NoticeCard(
                title = stringResource(R.string.gps_unavailable_title),
                body = stringResource(R.string.gps_unavailable_body),
                icon = R.drawable.ic_location_on,
                safety = true,
            )
        }
        state.permissionNeeded?.let { permission ->
            add {
                val (title, body, icon) = when (permission) {
                    RidePermission.Location -> Triple(R.string.permission_location_title, R.string.permission_location_body, R.drawable.ic_location_on)
                    RidePermission.Sms -> Triple(R.string.permission_sms_title, R.string.permission_sms_body, R.drawable.ic_sms)
                    RidePermission.Call -> Triple(R.string.permission_call_title, R.string.permission_call_body, R.drawable.ic_call)
                }
                NoticeCard(
                    title = stringResource(title),
                    body = stringResource(body),
                    icon = icon,
                    actionLabel = stringResource(R.string.permission_allow),
                    onAction = { actionPermissionLauncher.launch(permission.manifestNames) },
                    onDismiss = viewModel::dismissPermission,
                )
            }
        }
        if (stopToScan) add {
            NoticeCard(
                title = stringResource(R.string.order_stop_to_scan_title),
                body = stringResource(R.string.order_stop_to_scan_body),
                icon = R.drawable.ic_two_wheeler,
                onDismiss = viewModel::dismissStopToScan,
            )
        }
        if (cameraRefused) add {
            NoticeCard(
                title = stringResource(R.string.camera_denied_title),
                body = stringResource(R.string.camera_denied_body),
                icon = R.drawable.ic_photo_camera,
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
            )
        }
    }

    RideScreen(
        state = state,
        safetyState = safetyState,
        riderName = riderName,
        today = today,
        muted = muted,
        riderLevel = riderLevel,
        agentLevel = agentLevel,
        subtitles = if (subtitlesOn) subtitles else emptyMap(),
        tripSummary = tripSummary,
        onDoneSummary = viewModel::dismissTripSummary,
        notices = notices,
        // The idle screen shows it as a card; during a ride only its chip in the conversation opens it.
        setupCard = if (setupIssues.isEmpty()) {
            null
        } else {
            { open -> SetupCard(setupIssues, onFixSetup, initiallyOpen = open, onDismiss = if (open) null else ({ setupDismissed = true })) }
        },
        setupDismissed = setupDismissed,
        orderCard = {
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
        debugTools = if (BuildConfig.DEBUG) {
            {
                DebugSafetyCard(viewModel = viewModel, rideActive = state.rideActive)
                DebugScanCard(onScan = viewModel::scanImage)
                if (order.isDemo && !state.rideActive) TestOrderCard(order, viewModel::setTestCustomerPhone)
            }
        } else {
            null
        },
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
        onToggleMute = { viewModel.setMicMuted(!muted) },
        onOpenSettings = onOpenSettings,
    )
}

private enum class Sheet { None, Transcript, Order, Debug, Setup }

@Composable
private fun RideScreen(
    state: RideUiState,
    safetyState: SafetyState,
    riderName: String,
    today: EarningsDb.DayTotal?,
    muted: Boolean,
    riderLevel: State<Float>,
    agentLevel: State<Float>,
    /** English for Hindi lines, by line text (empty when subtitles are off). */
    subtitles: Map<String, String>,
    tripSummary: TripSummary?,
    onDoneSummary: () -> Unit,
    notices: List<@Composable () -> Unit>,
    /** What keeps safety from working fully; null when all is set up. Its argument: shown open. */
    setupCard: (@Composable (Boolean) -> Unit)?,
    setupDismissed: Boolean,
    orderCard: @Composable () -> Unit,
    /** Set while an order is read or checked: it takes the main area's place. */
    scanPanel: (@Composable (Modifier) -> Unit)?,
    debugTools: (@Composable () -> Unit)?,
    onStartRide: () -> Unit,
    onEndRide: () -> Unit,
    onSos: () -> Unit,
    onOpenAlert: () -> Unit,
    onRetryVoice: () -> Unit,
    onToggleMute: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = Pillion.colors
    val alertOn = safetyState != SafetyState.Idle
    // Ending keeps the ride layout ("Ending…") until the ride is really over.
    val inRide = state.rideActive || state.status == RideStatus.Ending
    val status = rideStatusOf(state.status, alertOn, muted, inRide)
    val orbColor = rememberOrbColor(status.mood)
    var sheet by remember { mutableStateOf(Sheet.None) }
    LaunchedEffect(scanPanel != null) { if (scanPanel != null) sheet = Sheet.None }

    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .orbBackdrop({ orbColor.value }, colors.isDark, centerY = if (inRide) 0.32f else 0.3f)
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            TopBar {
                if (inRide) {
                    StatusPill(stringResource(status.label), status.mood.dotColor())
                } else {
                    Wordmark()
                }
                Box(Modifier.weight(1f))
                if (inRide) RoundIconButton(R.drawable.ic_document_scanner, stringResource(R.string.scan_order_button), onClick = { sheet = Sheet.Order })
                if (debugTools != null) RoundIconButton(R.drawable.ic_bug_report, stringResource(R.string.debug_tools), onClick = { sheet = Sheet.Debug })
                RoundIconButton(R.drawable.ic_settings, stringResource(R.string.settings), onClick = onOpenSettings)
            }

            val gutter = Modifier.padding(horizontal = Space.gutter)
            when {
                scanPanel != null -> scanPanel(Modifier.weight(1f).then(gutter))
                inRide -> {
                    Orb(
                        mood = status.mood,
                        level = {
                            when (status.mood) {
                                OrbMood.Listening -> riderLevel.value
                                OrbMood.Speaking -> agentLevel.value
                                else -> 0f
                            }
                        },
                        description = stringResource(status.orbDescription),
                        modifier = Modifier.fillMaxWidth().weight(0.9f),
                    )
                    Column(gutter, verticalArrangement = Arrangement.spacedBy(Space.m)) {
                        if (alertOn) AlertBanner(onOpenAlert)
                        if (notices.isNotEmpty()) {
                            Column(
                                Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(Space.m),
                            ) { notices.forEach { it() } }
                        }
                    }
                    val problem = state.voiceProblem
                    if (problem != null) {
                        VoiceOfflinePanel(problem, onRetryVoice, Modifier.weight(1f).then(gutter))
                    } else {
                        LiveTranscript(
                            lines = state.transcript,
                            subtitles = subtitles,
                            onExpand = { sheet = Sheet.Transcript },
                            onSetupChip = if (setupCard != null) ({ sheet = Sheet.Setup }) else null,
                            modifier = Modifier.weight(1f).then(gutter),
                        )
                    }
                }
                tripSummary != null -> Box(Modifier.weight(1f)) {
                    TripSummaryContent(
                        tripSummary,
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .then(gutter)
                            .padding(bottom = Space.xl),
                    )
                    BottomFade(Modifier.align(Alignment.BottomCenter))
                }
                else -> BoxWithConstraints(Modifier.weight(1f)) {
                    val viewport = maxHeight
                    Column(
                        Modifier
                            .verticalScroll(rememberScrollState())
                            .heightIn(min = viewport)
                            .then(gutter)
                            .padding(bottom = Space.xl),
                        verticalArrangement = Arrangement.spacedBy(Space.m),
                    ) {
                        Greeting(riderName, today)
                        if (alertOn) AlertBanner(onOpenAlert)
                        // The orb takes the height that's left (up to 200 dp) and steps aside when
                        // cards need the room, so the order card is never cut off.
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f).heightIn(max = 200.dp)) {
                            if (maxHeight >= 96.dp) {
                                Orb(
                                    mood = OrbMood.Dormant,
                                    level = { 0f },
                                    description = stringResource(R.string.orb_dormant),
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        }
                        notices.forEach { it() }
                        if (!setupDismissed) setupCard?.invoke(false)
                        orderCard()
                    }
                    BottomFade(Modifier.align(Alignment.BottomCenter))
                }
            }

            // Checking a scanned order before a ride: its own Cancel / Set buttons are the actions
            // (SOS is back as soon as it closes). During a ride End Ride and SOS always stay.
            if (scanPanel == null || inRide) RideControls(
                inRide = inRide,
                summaryShown = tripSummary != null && !inRide && scanPanel == null,
                ending = state.status == RideStatus.Ending,
                alertOn = alertOn,
                muted = muted,
                onDoneSummary = onDoneSummary,
                onStartRide = onStartRide,
                onEndRide = onEndRide,
                onSos = onSos,
                onToggleMute = onToggleMute,
                modifier = Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.m, bottom = Space.l),
            )
        }
    }

    when (sheet) {
        Sheet.None -> Unit
        Sheet.Transcript -> TranscriptSheet(
            lines = state.transcript,
            subtitles = subtitles,
            onSetupChip = if (setupCard != null) ({ sheet = Sheet.Setup }) else null,
            onDismiss = { sheet = Sheet.None },
        )
        Sheet.Order -> PillionSheet(onDismiss = { sheet = Sheet.None }) { orderCard() }
        Sheet.Debug -> PillionSheet(onDismiss = { sheet = Sheet.None }) { debugTools?.invoke() }
        Sheet.Setup -> PillionSheet(onDismiss = { sheet = Sheet.None }) { setupCard?.invoke(true) }
    }
}

/** Content slides softly under the ride buttons instead of being cut off. */
@Composable
private fun BottomFade(modifier: Modifier = Modifier) {
    val background = Pillion.colors.background
    Box(
        modifier
            .fillMaxWidth()
            .height(Space.xl)
            .background(Brush.verticalGradient(listOf(Color.Transparent, background))),
    )
}

/** The status pill's word, the orb's mood and the orb's spoken description, from the ride state. */
private data class ShownStatus(@StringRes val label: Int, val mood: OrbMood, @StringRes val orbDescription: Int)

private fun rideStatusOf(status: RideStatus, alertOn: Boolean, muted: Boolean, inRide: Boolean): ShownStatus = when {
    !inRide -> ShownStatus(R.string.status_idle, OrbMood.Dormant, R.string.orb_dormant)
    alertOn -> ShownStatus(R.string.status_alert, OrbMood.Alert, R.string.orb_alert)
    status == RideStatus.VoiceOffline -> ShownStatus(R.string.status_voice_offline, OrbMood.Offline, R.string.orb_offline)
    status == RideStatus.Reconnecting -> ShownStatus(R.string.status_reconnecting, OrbMood.Offline, R.string.orb_offline)
    status == RideStatus.Connecting -> ShownStatus(R.string.status_connecting, OrbMood.Connecting, R.string.orb_connecting)
    status == RideStatus.Ending -> ShownStatus(R.string.ending_ride, OrbMood.Offline, R.string.orb_offline)
    status == RideStatus.Thinking -> ShownStatus(R.string.status_thinking, OrbMood.Thinking, R.string.orb_thinking)
    status == RideStatus.Speaking -> ShownStatus(R.string.status_speaking, OrbMood.Speaking, R.string.orb_speaking)
    muted -> ShownStatus(R.string.status_mic_off, OrbMood.Offline, R.string.orb_muted)
    else -> ShownStatus(R.string.status_listening, OrbMood.Listening, R.string.orb_listening)
}

@Composable
private fun TopBar(content: @Composable RowScope.() -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = Space.gutter, end = Space.m, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        content = content,
    )
}

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(14.dp).background(Pillion.colors.accent, CircleShape))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = Pillion.colors.ink)
    }
}

@Composable
private fun Greeting(name: String, today: EarningsDb.DayTotal?) {
    val colors = Pillion.colors
    Column(Modifier.padding(top = Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            text = if (name.isBlank()) stringResource(R.string.greeting_no_name) else stringResource(R.string.greeting, name.trim().substringBefore(' ')),
            style = MaterialTheme.typography.headlineLarge,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        if (today != null) {
            val trips = pluralStringResource(R.plurals.trips_count, today.trips, today.trips)
            val amount = rupees(today.rupees)
            val todayWord = stringResource(R.string.today_word)
            Text(
                text = if (today.trips == 0) {
                    buildAnnotatedString { append(stringResource(R.string.today_none)) }
                } else {
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = colors.ink, fontWeight = FontWeight.SemiBold)) { append(amount) }
                        append(" $todayWord · $trips")
                    }
                },
                style = MaterialTheme.typography.titleMedium.merge(RideType.tabular).copy(fontWeight = FontWeight.Normal),
                color = colors.inkSecondary,
            )
        }
    }
}

/** ₹ with Indian digit grouping (₹1,23,456). */
fun rupees(amount: Int): String = "₹" + NumberFormat.getIntegerInstance(Locale("en", "IN")).format(amount)

/** Loud on purpose: a safety alert is running and the rider should answer it. */
@Composable
private fun AlertBanner(onOpen: () -> Unit) {
    PillButton(
        text = stringResource(R.string.safety_alert_banner),
        onClick = onOpen,
        icon = R.drawable.ic_warning,
        container = Pillion.colors.alertScreen,
        content = Color.White,
        minHeight = Targets.rideSmall,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The last few lines, newest at the bottom and largest; older ones fade out at the top. Tap for
 * the whole conversation.
 */
@Composable
private fun LiveTranscript(
    lines: List<TranscriptLine>,
    subtitles: Map<String, String>,
    onExpand: () -> Unit,
    onSetupChip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = Pillion.colors
    val recent = lines.takeLast(4)
    val newest = recent.indexOfLast { it.speaker != Speaker.Action }
    Box(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.transcript_expand), onClick = onExpand)
            .fadeTopEdge(),
    ) {
        if (recent.isEmpty()) {
            Text(
                stringResource(R.string.transcript_listening_hint),
                style = RideType.secondary,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .wrapContentHeight(Alignment.Bottom, unbounded = true)
                    .padding(bottom = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.m),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                recent.forEachIndexed { index, line ->
                    key(line.key) {
                        if (line.speaker == Speaker.Action) {
                            ActionChip(line, onClick = onSetupChip.takeIf { line.isSetupWarning() })
                        } else {
                            SpokenLine(line, subtitles[line.text], latest = index == newest)
                        }
                    }
                }
            }
        }
    }
}

/** Clips to the box and fades the top 56 dp, so long lines slide out softly. */
private fun Modifier.fadeTopEdge(): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }
    .drawWithCache {
        val fade = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = 56.dp.toPx())
        onDrawWithContent {
            drawContent()
            drawRect(fade, blendMode = BlendMode.DstIn)
        }
    }

/** A line, and under it its English subtitle when it's Hindi and the translation came back. */
@Composable
private fun SpokenLine(line: TranscriptLine, subtitle: String?, latest: Boolean) {
    val colors = Pillion.colors
    val rider = line.speaker == Speaker.Rider
    Column(
        Modifier
            .fillMaxWidth()
            .alpha(if (line.isFinal) 1f else 0.72f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            stringResource(if (rider) R.string.speaker_rider else R.string.speaker_pillion),
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkSecondary,
        )
        val interrupted = if (line.interrupted) " — ${stringResource(R.string.interrupted)}" else ""
        Text(
            text = line.text + interrupted,
            style = if (latest) RideType.latest else RideType.secondary,
            color = if (latest) colors.ink else colors.inkSecondary,
            textAlign = TextAlign.Center,
        )
        if (subtitle != null) {
            val subtitleDescription = stringResource(R.string.subtitle_description, subtitle)
            Text(
                text = subtitle,
                style = if (latest) RideType.secondary else MaterialTheme.typography.bodyLarge,
                color = colors.inkSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics { contentDescription = subtitleDescription },
            )
        }
    }
}

// SafetyMonitor.announceIfNotSetUp's line: during a ride, the one way to the safety setup fixes.
private fun TranscriptLine.isSetupWarning() = speaker == Speaker.Action && text.startsWith("⚠ SOS not set up")

/**
 * Something Pillion did ("✓ SMS sent to Rahul", "⚠ Crash detected"): a chip with a real icon, not
 * a speech line. With [onClick] (the "SOS not set up" line) it opens the safety setup fixes.
 */
@Composable
private fun ActionChip(line: TranscriptLine, onClick: (() -> Unit)? = null) {
    val colors = Pillion.colors
    val (icon, tint, meaning) = when {
        line.text.startsWith("↻") -> Triple(R.drawable.ic_sync, colors.inkSecondary, R.string.action_update)
        line.text.startsWith("☕") -> Triple(R.drawable.ic_bedtime, colors.ink, R.string.action_reminder)
        line.text.startsWith("⚠") -> Triple(R.drawable.ic_warning, colors.danger, R.string.action_warning)
        line.failed || line.text.startsWith("✗") -> Triple(R.drawable.ic_error, colors.danger, R.string.action_failed)
        else -> Triple(R.drawable.ic_check_circle, colors.success, R.string.action_done)
    }
    val openLabel = stringResource(R.string.setup_open)
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(colors.surfaceHigh)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = if (onClick != null) 48.dp else 0.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(meaning), tint = tint, modifier = Modifier.size(20.dp))
        // The leading ✓ / ✗ / ⚠ / ↻ / ☕ is shown as the icon instead.
        Text(line.text.trimStart { !it.isLetterOrDigit() }, style = MaterialTheme.typography.labelLarge, color = colors.ink, modifier = Modifier.weight(1f, fill = false))
        if (onClick != null) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun VoiceOfflinePanel(problem: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pillion.colors
    Column(
        modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.m, Alignment.CenterVertically),
    ) {
        Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(32.dp))
        Text(stringResource(R.string.voice_offline_title), style = MaterialTheme.typography.headlineSmall, color = colors.ink, textAlign = TextAlign.Center)
        Text(stringResource(R.string.voice_offline_body, problem), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary, textAlign = TextAlign.Center)
        PillButton(
            text = stringResource(R.string.retry_voice),
            onClick = onRetry,
            icon = R.drawable.ic_refresh,
            container = colors.surfaceHigh,
            content = colors.ink,
            minHeight = Targets.rideSmall,
            style = RideType.control,
        )
    }
}

/**
 * Start / End Ride, SOS (always there, works offline) and, during a ride, mute. Big targets with
 * wide gaps; at large font sizes the main button takes its own row.
 */
@Composable
private fun RideControls(
    inRide: Boolean,
    /** The trip summary is up: its Done takes Start Ride's place. */
    summaryShown: Boolean,
    ending: Boolean,
    alertOn: Boolean,
    muted: Boolean,
    onDoneSummary: () -> Unit,
    onStartRide: () -> Unit,
    onEndRide: () -> Unit,
    onSos: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Pillion.colors
    val haptics = LocalHapticFeedback.current
    val largeText = LocalDensity.current.fontScale > 1.3f
    val main = @Composable { buttonModifier: Modifier ->
        if (summaryShown) {
            PillButton(
                text = stringResource(R.string.done),
                onClick = onDoneSummary,
                icon = R.drawable.ic_check,
                container = colors.ink,
                content = colors.background,
                minHeight = Targets.ride,
                style = RideType.control,
                modifier = buttonModifier,
            )
        } else if (inRide) {
            PillButton(
                text = stringResource(if (ending) R.string.ending_ride else R.string.end_ride),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onEndRide()
                },
                // No icon: it shares the row with SOS and mute, and the words must fit on one line.
                // During a safety alert the rider answers it first.
                enabled = !ending && !alertOn,
                container = colors.ink,
                content = colors.background,
                minHeight = Targets.ride,
                style = RideType.control,
                horizontalPadding = Space.m,
                modifier = buttonModifier,
            )
        } else {
            PillButton(
                text = stringResource(R.string.start_ride),
                onClick = {
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onStartRide()
                },
                icon = R.drawable.ic_two_wheeler,
                container = colors.accent,
                content = colors.onAccent,
                minHeight = Targets.ride,
                style = RideType.control,
                modifier = buttonModifier,
            )
        }
    }
    val secondary = @Composable {
        SosButton(enabled = !alertOn, onClick = onSos)
        if (inRide) MuteButton(muted = muted, enabled = !ending, onToggle = onToggleMute)
    }
    if (largeText) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Targets.rideGap)) {
            main(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(Targets.rideGap), verticalAlignment = Alignment.CenterVertically) { secondary() }
        }
    } else {
        Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Targets.rideGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            main(Modifier.weight(1f))
            secondary()
        }
    }
}

/** Works with or without a ride, voice or internet: a 5-second cancel window, then SMS. */
@Composable
private fun SosButton(enabled: Boolean, onClick: () -> Unit) {
    val colors = Pillion.colors
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.sos_button_description)
    Surface(
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        enabled = enabled,
        shape = CircleShape,
        color = colors.background,
        border = BorderStroke(3.dp, colors.alert.copy(alpha = if (enabled) 1f else 0.38f)),
        modifier = Modifier
            .heightIn(min = Targets.ride)
            .widthIn(min = Targets.ride)
            .semantics { contentDescription = description },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(Space.s)) {
            Text(
                stringResource(R.string.sos_button),
                style = RideType.control.copy(fontWeight = FontWeight.Black),
                color = colors.danger.copy(alpha = if (enabled) 1f else 0.38f),
                modifier = Modifier.clearAndSetSemantics {},
            )
        }
    }
}

/**
 * Pillion stops hearing the rider (the mic isn't sent) until tapped again. Muted: filled dark
 * circle, crossed-out mic and the word "Muted"; the status pill says "Mic off" too.
 */
@Composable
private fun MuteButton(muted: Boolean, enabled: Boolean, onToggle: () -> Unit) {
    val colors = Pillion.colors
    val description = stringResource(R.string.mute_description)
    val stateText = stringResource(if (muted) R.string.mute_state_on else R.string.mute_state_off)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
        modifier = Modifier
            .clip(RoundedCornerShape(Space.l))
            .toggleable(value = muted, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .semantics(mergeDescendants = true) {
                contentDescription = description
                stateDescription = stateText
            },
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(Targets.rideSmall)
                .background(if (muted) colors.ink else colors.surfaceHigh, CircleShape),
        ) {
            Icon(
                painterResource(if (muted) R.drawable.ic_mic_off else R.drawable.ic_mic),
                contentDescription = null,
                tint = if (muted) colors.background else colors.ink,
                modifier = Modifier.size(32.dp),
            )
        }
        Text(
            stringResource(if (muted) R.string.mute_label_on else R.string.mute_label_off),
            style = MaterialTheme.typography.labelMedium,
            color = if (muted) colors.ink else colors.inkSecondary,
            modifier = Modifier.clearAndSetSemantics {},
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PillionSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Pillion.colors.background) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) { content() }
    }
}

/** The whole conversation, following the newest line. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranscriptSheet(
    lines: List<TranscriptLine>,
    subtitles: Map<String, String>,
    onSetupChip: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    val colors = Pillion.colors
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = lines.lastIndex.coerceAtLeast(0))
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = colors.background) {
        Text(
            stringResource(R.string.transcript_title),
            style = MaterialTheme.typography.headlineSmall,
            color = colors.ink,
            modifier = Modifier
                .padding(horizontal = Space.gutter)
                .semantics { heading() },
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = Space.gutter, end = Space.gutter, top = Space.m, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            if (lines.isEmpty()) {
                item { Text(stringResource(R.string.transcript_empty), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary) }
            }
            items(lines, key = { it.key }) { line ->
                if (line.speaker == Speaker.Action) {
                    ActionChip(line, onClick = onSetupChip.takeIf { line.isSetupWarning() })
                } else {
                    TranscriptBubble(line, subtitles[line.text])
                }
            }
        }
    }
}

@Composable
private fun TranscriptBubble(line: TranscriptLine, subtitle: String?) {
    val colors = Pillion.colors
    val rider = line.speaker == Speaker.Rider
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (rider) Alignment.End else Alignment.Start) {
        Text(
            stringResource(if (rider) R.string.speaker_rider else R.string.speaker_pillion),
            style = MaterialTheme.typography.labelMedium,
            color = colors.inkSecondary,
            modifier = Modifier.padding(horizontal = Space.xs, vertical = 2.dp),
        )
        Column(
            Modifier
                .widthIn(max = 320.dp)
                .background(if (rider) colors.surfaceHigh else colors.surface, RoundedCornerShape(20.dp))
                .padding(horizontal = Space.m, vertical = 12.dp)
                .alpha(if (line.isFinal) 1f else 0.72f),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            val interrupted = if (line.interrupted) " — ${stringResource(R.string.interrupted)}" else ""
            Text(line.text + interrupted, style = MaterialTheme.typography.bodyLarge, color = colors.ink)
            if (subtitle != null) {
                val description = stringResource(R.string.subtitle_description, subtitle)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                    modifier = Modifier.semantics { contentDescription = description },
                )
            }
        }
    }
}

/**
 * One quiet line ("Safety setup · 2 things to fix"); tap the card to see and fix each item. In
 * the ride's sheet it starts open.
 */
@Composable
private fun SetupCard(
    issues: List<SetupIssue>,
    onFix: (SetupIssue) -> Unit,
    initiallyOpen: Boolean = false,
    onDismiss: (() -> Unit)?,
) {
    val colors = Pillion.colors
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    val collapsible = !initiallyOpen
    PillionCard(
        onClick = if (!open) ({ open = true }) else null,
        onClickLabel = stringResource(R.string.expand),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .then(
                    if (open && collapsible) {
                        Modifier.clickable(onClickLabel = stringResource(R.string.collapse)) { open = false }
                    } else {
                        Modifier
                    }
                ),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(R.drawable.ic_shield), contentDescription = null, tint = colors.danger, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(stringResource(R.string.setup_title), style = MaterialTheme.typography.titleMedium, color = colors.ink, modifier = Modifier.semantics { heading() })
                Text(
                    pluralStringResource(R.plurals.setup_count, issues.size, issues.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.inkSecondary,
                )
            }
            if (collapsible) {
                Icon(
                    painterResource(if (open) R.drawable.ic_expand_less else R.drawable.ic_expand_more),
                    contentDescription = null,
                    tint = colors.ink,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (!open) return@PillionCard
        Text(stringResource(R.string.setup_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
        issues.forEach { issue ->
            HorizontalDivider(color = colors.hairline)
            val (text, action) = when (issue) {
                SetupIssue.NoContacts -> R.string.setup_no_contacts to R.string.add
                SetupIssue.Sms -> R.string.setup_sms to R.string.allow
                SetupIssue.Location -> R.string.setup_location to R.string.allow
                SetupIssue.Notifications -> R.string.setup_notifications to R.string.allow
                SetupIssue.FullScreenAlerts -> R.string.setup_full_screen to R.string.allow
                SetupIssue.BatteryOptimization -> R.string.setup_battery to R.string.fix
                SetupIssue.HindiVoice -> R.string.setup_hindi_voice to R.string.install
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
                    if (issue == SetupIssue.BatteryOptimization && isColorOsFamily()) {
                        Text(stringResource(R.string.setup_battery_oem), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    }
                }
                PillButton(stringResource(action), onClick = { onFix(issue) }, minHeight = 48.dp, container = colors.surfaceHigh, content = colors.ink)
            }
        }
        if (onDismiss != null) {
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text(stringResource(R.string.dismiss), color = colors.inkSecondary)
            }
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
    PillionCard {
        Text(stringResource(R.string.debug_safety_title), style = MaterialTheme.typography.titleMedium)
        PillButton(
            text = stringResource(R.string.debug_simulate_crash),
            onClick = {
                if (!rideActive || !viewModel.simulateCrash()) Toast.makeText(context, needsRide, Toast.LENGTH_LONG).show()
            },
            icon = R.drawable.ic_car_crash,
            container = Pillion.colors.surfaceHigh,
            content = Pillion.colors.ink,
            minHeight = 48.dp,
        )
        DebugSwitch(stringResource(R.string.debug_record), recording, viewModel.debug::setRecording)
        DebugSwitch(stringResource(R.string.debug_demo), demo, viewModel.debug::setDemoMode)
        DebugSwitch(stringResource(R.string.debug_fatigue), fatigue, viewModel.debug::setFatigueInTwoMinutes)
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
    PillionCard {
        Text(stringResource(R.string.test_order_title), style = MaterialTheme.typography.titleMedium)
        Text("${order.customerName} · ${order.dropAddress}", style = MaterialTheme.typography.bodyMedium)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
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
internal fun isColorOsFamily() = Build.MANUFACTURER.lowercase() in setOf("realme", "oppo", "oneplus")

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
fun Context.askToIgnoreBatteryOptimization() {
    val ask = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null))
    if (!startSafely(ask)) startSafely(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
}

private fun Context.startSafely(intent: Intent): Boolean =
    runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
