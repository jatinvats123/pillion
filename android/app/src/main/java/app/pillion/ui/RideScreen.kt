package app.pillion.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import app.pillion.ui.components.PillButton
import app.pillion.ui.components.PillionCard
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.RideType
import app.pillion.ui.theme.Space
import app.pillion.ui.theme.Targets
import app.pillion.voice.Speaker
import app.pillion.voice.TranscriptLine
import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.produceState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import app.pillion.data.ThemeMode
import app.pillion.ui.components.GlassGlobe
import app.pillion.ui.components.GlobeLevel
import app.pillion.ui.components.cssShadows
import app.pillion.ui.components.glass
import app.pillion.ui.components.pillionBackground
import app.pillion.ui.components.rememberGlobeLevel
import app.pillion.ui.theme.CssShadow
import app.pillion.ui.theme.MicGradientEnd
import app.pillion.ui.theme.MicGradientStart
import app.pillion.ui.theme.MicRing
import app.pillion.ui.theme.Motion
import app.pillion.ui.theme.SosCenter
import app.pillion.ui.theme.SosEdge
import app.pillion.ui.theme.SosMid
import app.pillion.ui.theme.SosShadow
import kotlinx.coroutines.delay
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
    val setAsideOrder by viewModel.setAsideOrder.collectAsStateWithLifecycle()
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
    val voiceState by viewModel.voiceState.collectAsStateWithLifecycle()
    val voicePreview by viewModel.voicePreview.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = LocalActivity.current
    var micPrompt by rememberSaveable { mutableStateOf(MicPrompt.None) }
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

    val uiPrefs = context.pillion.uiPrefs
    val dark = Pillion.colors.isDark
    val onScanScreenshot = {
        if (viewModel.scanAllowed()) pickScreenshot.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }
    val onScanCamera = {
        if (viewModel.scanAllowed()) {
            if (context.granted(Manifest.permission.CAMERA)) onOpenCamera() else cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }
    val onTrySample = { if (viewModel.scanAllowed()) viewModel.scanSample() }
    RideScreen(
        state = state,
        voiceState = voiceState,
        previewing = voicePreview != null,
        safetyState = safetyState,
        riderName = riderName,
        today = today,
        order = order,
        rideStartedAt = viewModel.rideStartedAt,
        muted = muted,
        riderLevel = riderLevel,
        subtitles = if (subtitlesOn) subtitles else emptyMap(),
        tripSummary = tripSummary,
        onDoneSummary = viewModel::dismissTripSummary,
        notices = notices,
        // Home: its "N things to fix" pill opens it in a sheet; during a ride the chip in the conversation does.
        setupCard = if (setupIssues.isEmpty()) {
            null
        } else {
            { open -> SetupCard(setupIssues, onFixSetup, initiallyOpen = open, onDismiss = null) }
        },
        setupIssueCount = setupIssues.size,
        orderCard = {
            ActiveOrderCard(order = order, locatingDrop = locatingDrop, onScanScreenshot = onScanScreenshot, onScanCamera = onScanCamera, onUseDemo = viewModel::useDemoOrder, setAside = setAsideOrder, onRestore = viewModel::backToScannedOrder, onTrySample = onTrySample)
        },
        homeOrderCard = {
            HomeOrderCard(order = order, locatingDrop = locatingDrop, onScanScreenshot = onScanScreenshot, onScanCamera = onScanCamera, onUseDemo = viewModel::useDemoOrder, setAside = setAsideOrder, onRestore = viewModel::backToScannedOrder, onTrySample = onTrySample)
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
        onToggleTheme = { uiPrefs.setThemeMode(if (dark) ThemeMode.Light else ThemeMode.Dark) },
        onOpenSettings = onOpenSettings,
    )
}

private enum class Sheet { None, Transcript, Order, Debug, Setup }

@Composable
private fun RideScreen(
    state: RideUiState,
    voiceState: RideVoiceState,
    /** Debug: a voice state is being previewed (listening then uses the design's demo voice). */
    previewing: Boolean,
    safetyState: SafetyState,
    riderName: String,
    today: EarningsDb.DayTotal?,
    order: Order,
    rideStartedAt: Long,
    muted: Boolean,
    riderLevel: State<Float>,
    /** English for Hindi lines, by line text (empty when subtitles are off). */
    subtitles: Map<String, String>,
    tripSummary: TripSummary?,
    onDoneSummary: () -> Unit,
    notices: List<@Composable () -> Unit>,
    /** What keeps safety from working fully; null when all is set up. Its argument: shown open. */
    setupCard: (@Composable (Boolean) -> Unit)?,
    setupIssueCount: Int,
    /** The order card in a sheet (during a ride). */
    orderCard: @Composable () -> Unit,
    /** The order card on the home screen. */
    homeOrderCard: @Composable () -> Unit,
    /** Set while an order is read or checked: it takes the main area's place. */
    scanPanel: (@Composable (Modifier) -> Unit)?,
    debugTools: (@Composable () -> Unit)?,
    onStartRide: () -> Unit,
    onEndRide: () -> Unit,
    onSos: () -> Unit,
    onOpenAlert: () -> Unit,
    onRetryVoice: () -> Unit,
    onToggleMute: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = Pillion.colors
    val alertOn = safetyState != SafetyState.Idle
    // Ending keeps the ride layout ("Ending…") until the ride is really over.
    val inRide = state.rideActive || state.status == RideStatus.Ending
    val status = rideStatusOf(if (previewing) RideStatus.Listening else state.status, voiceState, alertOn, muted, inRide, state.serverWaking)
    val globeLevel = rememberGlobeLevel()
    var sheet by remember { mutableStateOf(Sheet.None) }
    LaunchedEffect(scanPanel != null) { if (scanPanel != null) sheet = Sheet.None }

    val icons = @Composable {
        GlassIconButton(
            if (colors.isDark) R.drawable.ic_light_mode else R.drawable.ic_dark_mode,
            stringResource(if (colors.isDark) R.string.theme_to_light else R.string.theme_to_dark),
            onToggleTheme,
        )
        if (inRide) GlassIconButton(R.drawable.ic_document_scanner, stringResource(R.string.scan_order_button), onClick = { sheet = Sheet.Order })
        if (debugTools != null) GlassIconButton(R.drawable.ic_bug_report, stringResource(R.string.debug_tools), onClick = { sheet = Sheet.Debug })
        GlassIconButton(R.drawable.ic_settings, stringResource(R.string.settings), onClick = onOpenSettings)
    }

    // Home (the Ride tab before a ride): its own design; MainActivity paints its page behind it.
    val home = !inRide && scanPanel == null && tripSummary == null
    if (home) {
        HomeScreen(
            riderName = riderName,
            today = today,
            setupIssueCount = setupIssueCount,
            onOpenSetup = { sheet = Sheet.Setup },
            alertBanner = if (alertOn) ({ AlertBanner(onOpenAlert) }) else null,
            notices = notices,
            orderCard = homeOrderCard,
            sosEnabled = !alertOn,
            onSos = onSos,
            onStartRide = onStartRide,
            onToggleTheme = onToggleTheme,
            onOpenDebug = if (debugTools != null) ({ sheet = Sheet.Debug }) else null,
            onOpenSettings = onOpenSettings,
        )
    } else if (!inRide && scanPanel == null && tripSummary != null) {
        // Ride done: its own design, sharing home's header, cards and page.
        RideDoneScreen(
            summary = tripSummary,
            sosEnabled = !alertOn,
            onSos = onSos,
            onDone = onDoneSummary,
            onToggleTheme = onToggleTheme,
            onOpenDebug = if (debugTools != null) ({ sheet = Sheet.Debug }) else null,
            onOpenSettings = onOpenSettings,
        )
    } else Box(
        Modifier
            .fillMaxSize()
            .pillionBackground(colors)
            .safeDrawingPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            val gutter = Modifier.padding(horizontal = Space.gutter)
            if (inRide) {
                Row(gutter.padding(top = 20.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    VoicePill(status)
                    Box(Modifier.weight(1f))
                    RideClock(rideStartedAt)
                }
            } else {
                Row(
                    Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.m).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    Wordmark()
                    Box(Modifier.weight(1f))
                    icons()
                }
            }

            when {
                scanPanel != null -> scanPanel(Modifier.weight(1f).then(gutter).padding(top = Space.m))
                inRide -> Column(Modifier.weight(1f)) {
                    // The globe takes the height the lines and cards leave, up to the design's 262 dp;
                    // the icons stand in a column under the timer, beside it.
                    Box(Modifier.padding(top = 18.dp).fillMaxWidth().weight(1f, fill = false).heightIn(max = 262.dp)) {
                        GlassGlobe(
                            state = voiceState,
                            micLevel = if (previewing) null else ({ riderLevel.value }),
                            description = stringResource(status.orbDescription),
                            level = globeLevel,
                            modifier = Modifier.fillMaxSize(),
                        )
                        // Room inside the scrolling column for the icons' soft shadows (it clips at its edges).
                        Column(
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(end = Space.gutter - ShadowRoom)
                                .verticalScroll(rememberScrollState())
                                .padding(start = ShadowRoom, end = ShadowRoom, bottom = ShadowRoom + 12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) { icons() }
                    }
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
                    if (problem != null && !previewing) {
                        VoiceOfflinePanel(problem, onRetryVoice, gutter.padding(top = 6.dp))
                    } else {
                        SayBlock(
                            lines = state.transcript,
                            subtitles = subtitles,
                            onExpand = { sheet = Sheet.Transcript },
                            onSetupChip = if (setupCard != null) ({ sheet = Sheet.Setup }) else null,
                            modifier = gutter.padding(top = 6.dp),
                        )
                    }
                    NextDropCard(order, state.transcript, onClick = { sheet = Sheet.Order }, modifier = gutter.padding(top = 20.dp))
                }
            }

            // Checking a scanned order before a ride: its own Cancel / Set buttons are the actions
            // (SOS is back as soon as it closes). During a ride End Ride and SOS always stay.
            if (inRide) RideControls(
                ending = state.status == RideStatus.Ending,
                alertOn = alertOn,
                muted = muted,
                listening = voiceState == RideVoiceState.Listening,
                globeLevel = globeLevel,
                onEndRide = onEndRide,
                onSos = onSos,
                onToggleMute = onToggleMute,
                modifier = Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.l, bottom = 28.dp),
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

/** The status pill's word and dot, and the globe's spoken description, from the ride and voice state. */
private data class ShownStatus(@StringRes val label: Int, val dot: Dot, @StringRes val orbDescription: Int)

private enum class Dot { Idle, Listening, Thinking, Speaking, Alert }

private fun rideStatusOf(
    status: RideStatus,
    voice: RideVoiceState,
    alertOn: Boolean,
    muted: Boolean,
    inRide: Boolean,
    serverWaking: Boolean,
): ShownStatus = when {
    !inRide -> ShownStatus(R.string.status_idle, Dot.Idle, R.string.orb_dormant)
    alertOn -> ShownStatus(R.string.status_alert, Dot.Alert, R.string.orb_alert)
    status == RideStatus.VoiceOffline -> ShownStatus(R.string.status_voice_offline, Dot.Idle, R.string.orb_offline)
    status == RideStatus.Reconnecting -> ShownStatus(R.string.status_reconnecting, Dot.Idle, R.string.orb_offline)
    status == RideStatus.Connecting && serverWaking -> ShownStatus(R.string.status_waking, Dot.Idle, R.string.orb_connecting)
    status == RideStatus.Connecting -> ShownStatus(R.string.status_connecting, Dot.Idle, R.string.orb_connecting)
    status == RideStatus.Ending -> ShownStatus(R.string.ending_ride, Dot.Idle, R.string.orb_offline)
    status == RideStatus.FamilyOnLine -> ShownStatus(R.string.status_family, Dot.Idle, R.string.orb_family)
    voice == RideVoiceState.Thinking -> ShownStatus(R.string.status_thinking, Dot.Thinking, R.string.orb_thinking)
    voice == RideVoiceState.Speaking -> ShownStatus(R.string.status_speaking, Dot.Speaking, R.string.orb_speaking)
    muted -> ShownStatus(R.string.status_mic_off, Dot.Idle, R.string.orb_muted)
    voice == RideVoiceState.Listening -> ShownStatus(R.string.status_listening, Dot.Listening, R.string.orb_listening)
    else -> ShownStatus(R.string.status_ready, Dot.Idle, R.string.orb_ready)
}

/**
 * The design's status pill (`.pill` + `.v7-dot`): glass, 36 dp, a glowing 8 dp dot and the word.
 * Listening breathes the dot (opacity 0.45 ↔ 1, 1 s). TalkBack reads each change.
 */
@Composable
private fun VoicePill(status: ShownStatus) {
    val colors = Pillion.colors
    val (dot, glow) = when (status.dot) {
        Dot.Idle -> colors.dotIdle to null
        Dot.Listening -> colors.dotListening to CssShadow(colors.dotListeningGlow, 0f, 10f)
        Dot.Thinking -> colors.dotThinking to CssShadow(colors.dotThinkingGlow, 0f, 10f)
        Dot.Speaking -> colors.dotSpeaking to colors.dotSpeakingGlow
        Dot.Alert -> colors.alert to null
    }
    val dotColor by animateColorAsState(dot, Motion.color(Pillion.reducedMotion), label = "pillDot")
    val breathing = status.dot == Dot.Listening && !Pillion.reducedMotion
    val alpha = if (breathing) {
        rememberInfiniteTransition(label = "pillDot").animateFloat(
            initialValue = 0.45f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1000, easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)), RepeatMode.Reverse),
            label = "pillDotAlpha",
        )
    } else {
        null
    }
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier = Modifier
            .height(36.dp)
            .glass(colors, shape)
            .padding(horizontal = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .graphicsLayer { this.alpha = alpha?.value ?: 1f }
                .cssShadows(CircleShape, listOfNotNull(glow))
                .background(dotColor, CircleShape),
        )
        Text(stringResource(status.label), style = RideType.pill, color = colors.ink, maxLines = 1)
    }
}

/** Time on this ride ("42:18", past an hour "1:42:18") over "on ride". */
@Composable
private fun RideClock(startedAt: Long) {
    val colors = Pillion.colors
    val now by produceState(System.currentTimeMillis(), startedAt) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000 - value % 1_000)
        }
    }
    val seconds = ((now - startedAt) / 1_000).coerceAtLeast(0)
    val text = if (seconds >= 3_600) {
        "%d:%02d:%02d".format(seconds / 3_600, seconds / 60 % 60, seconds % 60)
    } else {
        "%02d:%02d".format(seconds / 60, seconds % 60)
    }
    val description = pluralStringResource(R.plurals.ride_time_description, (seconds / 60).toInt(), (seconds / 60).toInt())
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.clearAndSetSemantics { contentDescription = description }) {
        Text(text, style = RideType.figure, color = colors.ink)
        Text(stringResource(R.string.on_ride), style = RideType.caption.copy(fontSize = 12.sp, lineHeight = 16.sp), color = colors.inkSecondary)
    }
}

/** A 44 dp glass circle with a 22 dp icon (theme, scan order, debug, settings); Compose widens its touch area to 48 dp. */
@Composable
private fun GlassIconButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    val colors = Pillion.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .glass(colors, CircleShape)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Wordmark() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(14.dp).background(Brush.linearGradient(listOf(MicGradientStart, MicGradientEnd)), CircleShape))
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge, color = Pillion.colors.ink)
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
 * The design's `.say` block: the line before (small, grey, "आप · …"), the newest line large, and
 * under it the English subtitle of a Hindi line. Something Pillion did after that shows as a chip.
 * Tap for the whole conversation.
 */
@Composable
private fun SayBlock(
    lines: List<TranscriptLine>,
    subtitles: Map<String, String>,
    onExpand: () -> Unit,
    onSetupChip: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = Pillion.colors
    val spoken = lines.filter { it.speaker != Speaker.Action }
    val latest = spoken.lastOrNull()
    val before = spoken.getOrNull(spoken.lastIndex - 1)
    val action = lines.lastOrNull()?.takeIf { it.speaker == Speaker.Action }
    // Pinned to the newest line; only when the lines don't fit does the top fade out.
    val scroll = rememberScrollState()
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .clickable(onClickLabel = stringResource(R.string.transcript_expand), onClick = onExpand)
            .fadeTopEdge { scroll.maxValue > 0 }
            .verticalScroll(scroll, enabled = false, reverseScrolling = true),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (latest == null) {
            Text(stringResource(R.string.transcript_listening_hint), style = RideType.secondary, color = colors.inkSecondary)
        } else {
            if (before != null) key(before.key) {
                Text(
                    "${speakerName(before)} · ${before.text}",
                    style = RideType.secondary,
                    color = colors.inkSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(if (before.isFinal) 1f else 0.72f),
                )
            }
            key(latest.key) {
                val interrupted = if (latest.interrupted) " — ${stringResource(R.string.interrupted)}" else ""
                val speaker = speakerName(latest)
                Text(
                    latest.text + interrupted,
                    style = RideType.latest,
                    color = colors.ink,
                    modifier = Modifier
                        .alpha(if (latest.isFinal) 1f else 0.72f)
                        .semantics { contentDescription = "$speaker: ${latest.text}$interrupted" },
                )
                subtitles[latest.text]?.let { subtitle ->
                    val subtitleDescription = stringResource(R.string.subtitle_description, subtitle)
                    Text(
                        subtitle,
                        style = RideType.subtitle,
                        color = colors.inkSecondary,
                        modifier = Modifier.semantics { contentDescription = subtitleDescription },
                    )
                }
            }
        }
        if (action != null) key(action.key) { ActionChip(action, onClick = onSetupChip.takeIf { action.isSetupWarning() }) }
    }
}

// SafetyMonitor.announceIfNotSetUp's line: during a ride, the one way to the safety setup fixes.
private fun TranscriptLine.isSetupWarning() = speaker == Speaker.Action && text.startsWith("⚠ SOS not set up")

/**
 * Something Pillion did ("✓ SMS sent to Rahul", "⚠ Crash detected"): a glass chip with a real icon,
 * not a speech line. With [onClick] (the "SOS not set up" line) it opens the safety setup fixes.
 */
@Composable
private fun ActionChip(line: TranscriptLine, onClick: (() -> Unit)? = null) {
    val colors = Pillion.colors
    val (icon, tint, meaning) = when {
        line.text.startsWith("↻") -> Triple(R.drawable.ic_sync, colors.inkSecondary, R.string.action_update)
        line.text.startsWith("☕") -> Triple(R.drawable.ic_bedtime, colors.ink, R.string.action_reminder)
        line.text.startsWith("👤") -> Triple(R.drawable.ic_call, colors.ink, R.string.action_family)
        line.text.startsWith("⚠") -> Triple(R.drawable.ic_warning, colors.danger, R.string.action_warning)
        line.failed || line.text.startsWith("✗") -> Triple(R.drawable.ic_error, colors.danger, R.string.action_failed)
        else -> Triple(R.drawable.ic_check_circle, colors.success, R.string.action_done)
    }
    val openLabel = stringResource(R.string.setup_open)
    Row(
        modifier = Modifier
            .glass(colors, CircleShape)
            .clip(CircleShape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = openLabel, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = if (onClick != null) 48.dp else 0.dp)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(meaning), tint = tint, modifier = Modifier.size(20.dp))
        // The leading ✓ / ✗ / ⚠ / ↻ / ☕ / 👤 is shown as the icon instead.
        Text(line.text.trimStart { !it.isLetterOrDigit() }, style = MaterialTheme.typography.labelLarge, color = colors.ink, modifier = Modifier.weight(1f, fill = false))
        if (onClick != null) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, tint = colors.ink, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun speakerName(line: TranscriptLine): String = when {
    line.speaker != Speaker.Rider -> stringResource(R.string.speaker_pillion)
    line.text.any(::isDevanagari) -> stringResource(R.string.speaker_rider_hindi)
    else -> stringResource(R.string.speaker_rider)
}

private fun isDevanagari(char: Char) = char in 'ऀ'..'ॿ'

/** Clips to the box and, while [overflowing], fades the top 32 dp so long lines slide out softly. */
private fun Modifier.fadeTopEdge(overflowing: () -> Boolean): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
        clip = true
    }
    .drawWithCache {
        val fade = Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black, startY = 0f, endY = 32.dp.toPx())
        onDrawWithContent {
            drawContent()
            if (overflowing()) drawRect(fade, blendMode = BlendMode.DstIn)
        }
    }

/**
 * The design's next-drop panel (`.drop.glass`): who the drop is for and, once Pillion has worked
 * out the route, its distance and minutes ("6.9 km · 25 min"); before that, the drop's area.
 * Tap for the order.
 */
@Composable
private fun NextDropCard(order: Order, lines: List<TranscriptLine>, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pillion.colors
    val route = remember(lines) {
        lines.lastOrNull { it.speaker == Speaker.Action && !it.failed && NEXT_DROP in it.text }
            ?.text?.substringAfter(NEXT_DROP)?.trim()
    }
    val shape = RoundedCornerShape(24.dp)
    Row(
        modifier
            .fillMaxWidth()
            .glass(colors, shape)
            .clip(shape)
            .clickable(onClickLabel = stringResource(R.string.order_open), onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).background(colors.chipFill, CircleShape), contentAlignment = Alignment.Center) {
            Icon(painterResource(R.drawable.ic_line_route), contentDescription = null, tint = colors.chipIcon, modifier = Modifier.size(22.dp))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.next_drop_for, order.customerName), style = RideType.caption, color = colors.inkSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(route ?: order.dropArea, style = RideType.figure, color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val NEXT_DROP = "Next drop ·"

/** Space for a glass panel's light-theme shadow (blur 30, 10 down) where a parent clips. */
private val ShadowRoom = 20.dp

@Composable
private fun VoiceOfflinePanel(problem: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = Pillion.colors
    Column(
        modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Icon(painterResource(R.drawable.ic_cloud_off), contentDescription = null, tint = colors.inkSecondary, modifier = Modifier.size(22.dp))
            Text(stringResource(R.string.voice_offline_title), style = RideType.latest.copy(fontSize = 24.sp, lineHeight = 32.sp), color = colors.ink)
        }
        Text(stringResource(R.string.voice_offline_body, problem), style = RideType.subtitle, color = colors.inkSecondary)
        GlassPill(
            text = stringResource(R.string.retry_voice),
            onClick = onRetry,
            icon = R.drawable.ic_refresh,
            height = 56.dp,
        )
    }
}

/**
 * The design's control row during a ride: mic (mute), End ride, SOS (home and Ride done have their
 * own). SOS is always there and works offline. At large font sizes the main button takes its own row.
 */
@Composable
private fun RideControls(
    ending: Boolean,
    alertOn: Boolean,
    muted: Boolean,
    listening: Boolean,
    globeLevel: GlobeLevel,
    onEndRide: () -> Unit,
    onSos: () -> Unit,
    onToggleMute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHapticFeedback.current
    val largeText = LocalDensity.current.fontScale > 1.3f
    val main = @Composable { buttonModifier: Modifier ->
        GlassPill(
            text = stringResource(if (ending) R.string.ending_ride else R.string.end_ride),
            onClick = {
                haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                onEndRide()
            },
            // During a safety alert the rider answers it first.
            enabled = !ending && !alertOn,
            stopSquare = true,
            modifier = buttonModifier,
        )
    }
    val mic = @Composable {
        MicButton(muted = muted, listening = listening, level = globeLevel, enabled = !ending, onToggle = onToggleMute)
    }
    val sos = @Composable { SosButton(enabled = !alertOn, onClick = onSos) }
    if (largeText) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            main(Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                mic()
                sos()
            }
        }
    } else {
        Row(
            modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            mic()
            main(Modifier.weight(1f))
            sos()
        }
    }
}

/**
 * The glass pill button (the design's "End ride": `.grow.glass`, 88 dp, 20 sp): with [stopSquare]
 * the 14 dp stop square before the word, else an optional icon.
 */
@Composable
private fun GlassPill(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    stopSquare: Boolean = false,
    enabled: Boolean = true,
    height: Dp = Targets.ride,
) {
    val colors = Pillion.colors
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .heightIn(min = height)
            .alpha(if (enabled) 1f else 0.45f)
            .glass(colors, shape)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = Space.m),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stopSquare) Box(Modifier.size(14.dp).background(colors.ink, RoundedCornerShape(3.dp)))
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = colors.ink, modifier = Modifier.size(24.dp))
        Text(text, style = RideType.control, color = colors.ink, textAlign = TextAlign.Center)
    }
}

/** CSS `linear-gradient(135deg, #8B5CF6 0%, #EC4899 100%)` over a box of [size]. */
private fun css135(size: Size): Brush {
    val half = (size.width + size.height) * 0.7071f / 2f
    val center = Offset(size.width / 2, size.height / 2)
    val step = Offset(half * 0.7071f, half * 0.7071f)
    return Brush.linearGradient(listOf(MicGradientStart, MicGradientEnd), start = center - step, end = center + step)
}

/**
 * The design's mic button (`.mic`, 76 dp): violet-to-pink with a soft violet shadow; while the
 * rider talks, a pink ring grows with their voice (`0 0 0 (4 + level·14)px`). It mutes Pillion:
 * muted, it turns to grey glass with a crossed-out mic (the pill says "Mic off").
 */
@Composable
private fun MicButton(muted: Boolean, listening: Boolean, level: GlobeLevel, enabled: Boolean, onToggle: () -> Unit) {
    val colors = Pillion.colors
    val description = stringResource(R.string.mute_description)
    val stateText = stringResource(if (muted) R.string.mute_state_on else R.string.mute_state_off)
    val ring = remember { listOf(CssShadow(MicRing, 0f, 0f)) }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Targets.rideSmall)
            // Its own layer: the ring's per-frame redraw doesn't re-record the rest of the screen.
            .graphicsLayer()
            .then(
                if (muted) {
                    Modifier.glass(colors, CircleShape)
                } else {
                    Modifier
                        .cssShadows(CircleShape, listOf(colors.micShadow))
                        .cssShadows(CircleShape, ring, spread = { if (listening) (4f + level.value * 14f).dp else 0.dp })
                        .drawBehind { drawCircle(css135(size)) }
                },
            )
            .clip(CircleShape)
            .toggleable(value = muted, enabled = enabled, role = Role.Switch, onValueChange = { onToggle() })
            .semantics {
                contentDescription = description
                stateDescription = stateText
            },
    ) {
        Icon(
            painterResource(if (muted) R.drawable.ic_line_mic_off else R.drawable.ic_line_mic),
            contentDescription = null,
            tint = if (muted) colors.ink else Color.White,
            modifier = Modifier.size(28.dp),
        )
    }
}

/**
 * SOS (88 dp): the design's red sphere, `radial-gradient(circle at 35% 30%, #FF6B5E, #D92D20 60%,
 * #A8160C)`, with a red shadow. Works with or without a ride, voice or internet: a 5-second cancel
 * window, then SMS.
 */
@Composable
private fun SosButton(enabled: Boolean, onClick: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.sos_button_description)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(Targets.ride)
            .alpha(if (enabled) 1f else 0.45f)
            .cssShadows(CircleShape, listOf(SosShadow))
            .drawBehind {
                val focus = Offset(size.width * 0.35f, size.height * 0.30f)
                // "circle" with no size = farthest-corner: to the bottom-right corner from the focus.
                val radius = (Offset(size.width, size.height) - focus).getDistance()
                drawCircle(Brush.radialGradient(0f to SosCenter, 0.6f to SosMid, 1f to SosEdge, center = focus, radius = radius))
            }
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = description },
    ) {
        Text(
            stringResource(R.string.sos_button),
            style = RideType.control.copy(fontWeight = FontWeight.Bold),
            color = Color.White,
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
        val liveLink by viewModel.lastLiveLink.collectAsStateWithLifecycle()
        liveLink?.let { link ->
            val copied = stringResource(R.string.debug_live_link_copied)
            PillButton(
                text = stringResource(R.string.debug_copy_live_link),
                onClick = {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Live link", link))
                    Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                },
                container = Pillion.colors.surfaceHigh,
                content = Pillion.colors.ink,
                minHeight = 48.dp,
            )
        }
        PillButton(
            text = stringResource(R.string.debug_ask),
            onClick = { if (!viewModel.debugAsk("नेक्स्ट ड्रॉप कितना दूर है?")) Toast.makeText(context, needsRide, Toast.LENGTH_LONG).show() },
            container = Pillion.colors.surfaceHigh,
            content = Pillion.colors.ink,
            minHeight = 48.dp,
        )
        val preview by viewModel.voicePreview.collectAsStateWithLifecycle()
        PillButton(
            text = stringResource(R.string.debug_voice_preview, preview?.name ?: stringResource(R.string.debug_voice_preview_off)),
            onClick = {
                val order = listOf(null) + RideVoiceState.entries
                viewModel.previewVoice(order[(order.indexOf(preview) + 1) % order.size])
            },
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
