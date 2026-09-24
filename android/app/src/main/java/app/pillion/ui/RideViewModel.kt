package app.pillion.ui

import android.app.Application
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pillion.BuildConfig
import app.pillion.data.BackendException
import app.pillion.data.Order
import app.pillion.data.RideCredentials
import app.pillion.device.RidePermission
import app.pillion.order.LoadedImage
import app.pillion.order.OrderDraft
import app.pillion.order.OrderLines
import app.pillion.order.ScanSource
import app.pillion.order.ScanState
import app.pillion.pillion
import app.pillion.ride.RideRepository
import app.pillion.ride.RideService
import app.pillion.ride.StartGate
import app.pillion.safety.SafetyState
import app.pillion.safety.SosTrigger
import app.pillion.voice.AgentState
import app.pillion.voice.ConnectionState
import app.pillion.voice.TranscriptLine
import app.pillion.voice.VoiceEvent
import app.pillion.voice.VoiceException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RideStatus { Idle, Connecting, Listening, Thinking, Speaking, Reconnecting, VoiceOffline, Ending, Ended }

data class RideUiState(
    val status: RideStatus = RideStatus.Idle,
    val transcript: List<TranscriptLine> = emptyList(),
    /** An action failed for lack of this permission; the screen offers to grant it. */
    val permissionNeeded: RidePermission? = null,
    /** The ride is on without Pillion's voice (server, Agora or internet down); safety still runs. */
    val voiceProblem: String? = null,
) {
    val rideActive: Boolean
        get() = status in setOf(
            RideStatus.Connecting, RideStatus.Listening, RideStatus.Thinking,
            RideStatus.Speaking, RideStatus.Reconnecting, RideStatus.VoiceOffline,
        )
}

/**
 * A ride = safety (crash detection, SOS, fatigue; on the phone, always on) + Pillion's voice
 * (backend + Agora; optional). Start Ride starts safety first, then tries the voice; if the voice
 * can't connect or drops, the ride carries on without it and the rider can retry.
 */
class RideViewModel(application: Application) : AndroidViewModel(application) {

    private sealed interface Phase {
        data object Idle : Phase
        data object Active : Phase
        data object Ending : Phase
        data object Ended : Phase
    }

    private val repository = RideRepository(application)
    private val voice = repository.voice
    private val safety = repository.safety
    private val phase = MutableStateFlow<Phase>(Phase.Idle)
    private val permissionNeeded = MutableStateFlow<RidePermission?>(null)
    private val voiceProblem = MutableStateFlow<String?>(null)
    private val voiceConnecting = MutableStateFlow(false)
    private var ride: RideCredentials? = null
    private var rideGeneration = 0
    private var rideStartedAt = 0L
    private var rideServices: Job? = null
    private val startGate = StartGate()

    private val scanner = application.pillion.scanner

    /** The scanned order, or the seeded demo order (whose number debug builds can set). */
    val order: StateFlow<Order> = repository.orders.order
    val scanState: StateFlow<ScanState> = scanner.state
    private val _locatingDrop = MutableStateFlow(false)
    /** The drop of a just-set order is being looked up on the map. */
    val locatingDrop: StateFlow<Boolean> = _locatingDrop.asStateFlow()
    private val _stopToScan = MutableStateFlow(false)
    /** Camera or gallery was refused because the rider is moving. */
    val stopToScan: StateFlow<Boolean> = _stopToScan.asStateFlow()

    val safetyState: StateFlow<SafetyState> = safety.state
    val emergencyContactCount: StateFlow<Int> = safety.contacts.contacts.map { it.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, safety.contacts.contacts.value.size)
    val gpsAvailable: StateFlow<Boolean> = safety.gpsAvailable
    val debug = safety.debug

    val uiState: StateFlow<RideUiState> = combine(
        phase, voice.connection, voice.agentState, voice.agentPresent, voice.transcript,
    ) { phase, connection, agentState, agentPresent, transcript ->
        RideUiState(
            status = statusFor(phase, connection, agentState, agentPresent),
            transcript = transcript,
        )
    }.combine(combine(permissionNeeded, voiceProblem, voiceConnecting, ::Triple)) { state, (permission, problem, connecting) ->
        val status = when {
            state.status !in VOICE_STATUSES -> state.status
            problem != null -> RideStatus.VoiceOffline
            connecting -> RideStatus.Connecting
            else -> state.status
        }
        state.copy(status = status, permissionNeeded = permission, voiceProblem = problem)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RideUiState())

    init {
        viewModelScope.launch {
            repository.permissionNeeded.collect { permissionNeeded.value = it }
        }
        viewModelScope.launch {
            safety.actionLines.collect { voice.showActionLine(it.text, it.failed) }
        }
        viewModelScope.launch {
            voice.events.collect { event ->
                if (phase.value != Phase.Active) return@collect
                dropVoice(
                    when (event) {
                        VoiceEvent.AgentLeft -> "Pillion's voice disconnected."
                        is VoiceEvent.ConnectionFailed -> "Voice connection lost."
                    }
                )
            }
        }
        if (BuildConfig.DEBUG) {
            viewModelScope.launch {
                voice.latency.collect { report ->
                    runCatching { repository.reportLatency(report.toString()) }
                }
            }
        }
    }

    /** The rider tapped Start Ride (or Allow microphone on its card); the permission prompts come next. */
    fun requestStart(caller: String) {
        startGate.request(SystemClock.elapsedRealtime())
        Log.i(TAG, "Start requested by $caller")
    }

    /**
     * Starts the ride, but only right after [requestStart]: never from a share, an activity
     * re-creation or a re-delivered permission result (each ride runs an Agora agent). Every call
     * is logged with its caller and call site (logcat tag RideViewModel), allowed or not.
     */
    fun startRide(caller: String) {
        val sinceRequest = startGate.consume(SystemClock.elapsedRealtime())
        val site = Throwable().stackTrace.drop(1).take(6).joinToString(" < ") { "${it.fileName}:${it.lineNumber}" }
        if (sinceRequest == null) {
            Log.w(TAG, "startRide from $caller REFUSED: no Start Ride tap before it · $site")
            return
        }
        Log.i(TAG, "startRide from $caller, $sinceRequest ms after the tap · $site")
        if (phase.value == Phase.Active || phase.value == Phase.Ending) return
        phase.value = Phase.Active
        rideGeneration++
        rideStartedAt = System.currentTimeMillis()
        voice.clearTranscript()
        // Safety first: it needs neither the backend nor the internet.
        RideService.start(getApplication())
        safety.onRideStarted()
        connectVoice(announceSetup = true)
    }

    /** After the voice dropped or couldn't connect. */
    fun retryVoice() {
        if (phase.value == Phase.Active && ride == null) connectVoice(announceSetup = false)
    }

    private fun connectVoice(announceSetup: Boolean) {
        if (voiceConnecting.value) return
        val generation = rideGeneration
        voiceProblem.value = null
        voiceConnecting.value = true
        viewModelScope.launch {
            try {
                val started = repository.start()
                if (generation != rideGeneration || phase.value != Phase.Active) {
                    // The ride ended while connecting: never leave an agent running.
                    runCatching { repository.end(started.agentId) }
                } else {
                    ride = started
                    rideServices = viewModelScope.launch { serveRide(started) }
                    safety.voice = repository.safetyVoice(started)
                }
            } catch (error: TimeoutCancellationException) {
                onVoiceFailed(generation, error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onVoiceFailed(generation, error)
            } finally {
                voiceConnecting.value = false
            }
            if (generation != rideGeneration) {
                // A new ride started while this (older) attempt was running: connect that one now.
                if (phase.value == Phase.Active && ride == null && voiceProblem.value == null) connectVoice(announceSetup = true)
                return@launch
            }
            if (announceSetup && phase.value == Phase.Active) safety.announceIfNotSetUp()
        }
    }

    private fun onVoiceFailed(generation: Int, error: Exception) {
        Log.w(TAG, "Voice didn't connect; the ride goes on without it", error)
        if (generation == rideGeneration && phase.value == Phase.Active) voiceProblem.value = error.toUserMessage()
    }

    /** The voice is gone mid-ride; crash detection and SOS carry on. */
    private fun dropVoice(message: String) {
        val current = ride ?: return
        ride = null
        rideServices?.cancel()
        rideServices = null
        safety.voice = null
        voiceProblem.value = message
        viewModelScope.launch {
            runCatching { repository.end(current.agentId) }
                .onFailure { Log.w(TAG, "Agent stop failed; it will idle-stop on its own", it) }
        }
    }

    /** Refused while a safety alert is on: the rider answers it first. */
    fun endRide() {
        if (phase.value != Phase.Active || safety.state.value != SafetyState.Idle) return
        finishRide()
    }

    fun dismissPermission() {
        permissionNeeded.value = null
    }

    fun setTestCustomerPhone(number: String) {
        repository.orders.setDemoCustomerPhone(number)
    }

    /**
     * Camera and gallery need the rider looking at the screen, so not while GPS says they're
     * moving; Pillion says so if the voice is on. (Sharing a screenshot isn't gated: one tap.)
     */
    fun scanAllowed(): Boolean {
        val speed = repository.riderSpeedKmh()
        if (speed == null || speed < MOVING_KMH) return true
        _stopToScan.value = true
        ride?.let { current -> viewModelScope.launch { repository.sayToRider(current, OrderLines::scanWhenStopped) } }
        return false
    }

    fun dismissStopToScan() {
        _stopToScan.value = false
    }

    fun scanImage(source: ScanSource, load: suspend () -> LoadedImage) {
        _stopToScan.value = false
        scanner.scan(source, load)
    }

    /** OCR found nothing usable: the rider types the order in. */
    fun enterOrderManually() = scanner.enterManually()

    fun dismissScan() = scanner.dismiss()

    /**
     * The rider checked the scanned order and set it: it's active at once (SMS and call work),
     * then its drop is looked up for ETA, and Pillion confirms by voice if connected.
     */
    fun confirmOrder(draft: OrderDraft) {
        val order = draft.toOrder()
        repository.orders.set(order)
        scanner.dismiss()
        _locatingDrop.value = true
        viewModelScope.launch {
            val located = try {
                val (drop, area) = repository.locateDrop(order)
                repository.orders.updateDrop(order, drop, area)
                order.copy(drop = drop, dropArea = area)
            } finally {
                _locatingDrop.value = false
            }
            ride?.let { current -> repository.sayToRider(current) { hindi -> OrderLines.orderSet(located, hindi) } }
        }
    }

    fun useDemoOrder() = repository.orders.clear()

    /** The on-screen SOS button: works with or without a ride, voice or internet. */
    fun sos() {
        safety.startManualSos(SosTrigger.Button)
    }

    /** Debug builds: a synthetic crash trace through the ride's real detector. */
    fun simulateCrash(): Boolean = safety.simulateCrash()

    private suspend fun serveRide(started: RideCredentials) {
        try {
            repository.serveRide(started)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // Voice keeps working; only the phone-side actions stop.
            Log.e(TAG, "Ride actions stopped", error)
        }
    }

    private fun finishRide() {
        phase.value = Phase.Ending
        val current = ride
        ride = null
        rideServices?.cancel()
        rideServices = null
        voiceProblem.value = null
        safety.voice = null
        safety.onRideEnded() // stops the ride service and its sensors
        val startedAt = rideStartedAt
        viewModelScope.launch {
            runCatching { repository.recordTrip(startedAt, System.currentTimeMillis()) }
                .onFailure { Log.w(TAG, "Trip not recorded", it) }
            if (current != null) {
                runCatching { repository.end(current.agentId) }
                    .onFailure { Log.w(TAG, "Agent stop failed; it will idle-stop on its own", it) }
            }
            phase.value = Phase.Ended
        }
    }

    override fun onCleared() {
        // Activity finished mid-ride: clean up outside the (now cancelled) viewModelScope. A running
        // safety alert keeps the ride service (and its sensors) until it's over.
        if (phase.value == Phase.Active) {
            safety.voice = null
            safety.onRideEnded()
        }
        val current = ride ?: return
        ride = null
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { repository.end(current.agentId) }
        }
    }

    private fun statusFor(
        phase: Phase,
        connection: ConnectionState,
        agentState: AgentState,
        agentPresent: Boolean,
    ): RideStatus = when (phase) {
        Phase.Idle -> RideStatus.Idle
        Phase.Ending -> RideStatus.Ending
        Phase.Ended -> RideStatus.Ended
        Phase.Active -> when {
            connection == ConnectionState.Reconnecting -> RideStatus.Reconnecting
            agentState == AgentState.Speaking -> RideStatus.Speaking
            agentState == AgentState.Thinking -> RideStatus.Thinking
            agentState == AgentState.Unknown && !agentPresent -> RideStatus.Connecting
            else -> RideStatus.Listening
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is BackendException, is VoiceException -> message ?: "Pillion's voice couldn't connect."
        is TimeoutCancellationException -> "Voice connection timed out."
        is IOException -> "Can't reach the Pillion server."
        else -> "Pillion's voice couldn't connect."
    }

    private companion object {
        const val TAG = "RideViewModel"
        const val MOVING_KMH = 10f
        val VOICE_STATUSES = setOf(
            RideStatus.Connecting, RideStatus.Listening, RideStatus.Thinking, RideStatus.Speaking, RideStatus.Reconnecting,
        )
    }
}
