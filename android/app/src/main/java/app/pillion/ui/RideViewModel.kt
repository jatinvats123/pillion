package app.pillion.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.pillion.BuildConfig
import app.pillion.data.BackendException
import app.pillion.data.RideCredentials
import app.pillion.ride.RideRepository
import app.pillion.ride.RideService
import app.pillion.voice.AgentState
import app.pillion.voice.ConnectionState
import app.pillion.voice.TranscriptLine
import app.pillion.voice.VoiceEvent
import app.pillion.voice.VoiceException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class RideStatus { Idle, Connecting, Listening, Thinking, Speaking, Reconnecting, Ending, Ended, Error }

data class RideUiState(
    val status: RideStatus = RideStatus.Idle,
    val transcript: List<TranscriptLine> = emptyList(),
    val errorMessage: String? = null,
) {
    val rideActive: Boolean
        get() = status in setOf(
            RideStatus.Connecting, RideStatus.Listening, RideStatus.Thinking,
            RideStatus.Speaking, RideStatus.Reconnecting,
        )
}

class RideViewModel(application: Application) : AndroidViewModel(application) {

    private sealed interface Phase {
        data object Idle : Phase
        data object Starting : Phase
        data object Active : Phase
        data object Ending : Phase
        data object Ended : Phase
        data class Failed(val message: String) : Phase
    }

    private val repository = RideRepository(application)
    private val voice = repository.voice
    private val phase = MutableStateFlow<Phase>(Phase.Idle)
    private var ride: RideCredentials? = null
    private var endRequested = false

    val uiState: StateFlow<RideUiState> = combine(
        phase, voice.connection, voice.agentState, voice.agentPresent, voice.transcript,
    ) { phase, connection, agentState, agentPresent, transcript ->
        RideUiState(
            status = statusFor(phase, connection, agentState, agentPresent),
            transcript = transcript,
            errorMessage = (phase as? Phase.Failed)?.message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RideUiState())

    init {
        viewModelScope.launch {
            voice.events.collect { event ->
                if (phase.value != Phase.Active) return@collect
                val message = when (event) {
                    VoiceEvent.AgentLeft -> "Pillion disconnected. Start the ride again."
                    is VoiceEvent.ConnectionFailed -> "Connection lost. Check your internet and start again."
                }
                finishRide(failure = message)
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

    fun startRide() {
        if (phase.value in setOf(Phase.Starting, Phase.Active, Phase.Ending)) return
        endRequested = false
        phase.value = Phase.Starting
        viewModelScope.launch {
            try {
                ride = repository.start()
                phase.value = Phase.Active
                if (endRequested) finishRide() else RideService.start(getApplication())
            } catch (error: TimeoutCancellationException) {
                onStartFailed(error)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                onStartFailed(error)
            }
        }
    }

    private fun onStartFailed(error: Exception) {
        Log.w(TAG, "Ride start failed", error)
        phase.value = if (endRequested) Phase.Ended else Phase.Failed(error.toUserMessage())
    }

    fun endRide() {
        when (phase.value) {
            // Let the start finish, then end straight away so the agent is always stopped.
            Phase.Starting -> {
                endRequested = true
                phase.value = Phase.Ending
            }
            Phase.Active -> finishRide()
            else -> Unit
        }
    }

    fun dismissError() {
        if (phase.value is Phase.Failed) phase.value = Phase.Idle
    }

    private fun finishRide(failure: String? = null) {
        val current = ride ?: return
        ride = null
        phase.value = Phase.Ending
        RideService.stop(getApplication())
        viewModelScope.launch {
            runCatching { repository.end(current.agentId) }
                .onFailure { Log.w(TAG, "Agent stop failed; it will idle-stop on its own", it) }
            phase.value = failure?.let { Phase.Failed(it) } ?: Phase.Ended
        }
    }

    override fun onCleared() {
        // Activity finished mid-ride: clean up outside the (now cancelled) viewModelScope.
        val current = ride ?: return
        ride = null
        RideService.stop(getApplication())
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
        Phase.Starting -> RideStatus.Connecting
        Phase.Ending -> RideStatus.Ending
        Phase.Ended -> RideStatus.Ended
        is Phase.Failed -> RideStatus.Error
        Phase.Active -> when {
            connection == ConnectionState.Reconnecting -> RideStatus.Reconnecting
            agentState == AgentState.Speaking -> RideStatus.Speaking
            agentState == AgentState.Thinking -> RideStatus.Thinking
            agentState == AgentState.Unknown && !agentPresent -> RideStatus.Connecting
            else -> RideStatus.Listening
        }
    }

    private fun Throwable.toUserMessage(): String = when (this) {
        is BackendException, is VoiceException -> message ?: "Something went wrong starting the ride."
        is TimeoutCancellationException -> "Voice connection timed out. Check your internet and try again."
        is IOException -> "Can't reach the Pillion server. Is it running?"
        else -> "Something went wrong starting the ride."
    }

    private companion object {
        const val TAG = "RideViewModel"
    }
}
