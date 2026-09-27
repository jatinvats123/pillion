package app.pillion.ride

import android.content.Context
import android.util.Log
import app.pillion.data.BackendApi
import app.pillion.data.DropLocation
import app.pillion.data.Order
import app.pillion.data.RideCredentials
import app.pillion.device.DeviceActions
import app.pillion.device.RiderLocation
import app.pillion.device.RidePermission
import app.pillion.device.phoneCallActive
import app.pillion.pillion
import app.pillion.safety.RiderLanguage
import app.pillion.safety.SafetyPhrases
import app.pillion.safety.SafetyVoice
import app.pillion.voice.AgentState
import app.pillion.voice.ConnectionState
import app.pillion.voice.VoiceSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Starts and ends a ride: the backend owns the agent, [voice] owns the realtime connection. */
class RideRepository(
    context: Context,
    private val api: BackendApi = BackendApi(),
) {
    private val appContext = context.applicationContext
    val safety = appContext.pillion.safety
    val voice = VoiceSession(appContext)
    val orders = appContext.pillion.orders
    private val location = RiderLocation(appContext)
    private val earnings = appContext.pillion.db
    private val actions = DeviceActions(appContext, orders, earnings, safety) { customer, delivered ->
        voice.showActionLine(
            if (delivered) "✓ Delivered to $customer" else "✗ SMS to $customer not delivered",
            failed = !delivered,
        )
    }

    private val _permissionNeeded = MutableSharedFlow<RidePermission>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** An action failed because the rider hasn't granted this permission. */
    val permissionNeeded: SharedFlow<RidePermission> = _permissionNeeded.asSharedFlow()

    private val _lastLiveLink = MutableStateFlow<String?>(null)
    /** Debug builds: the latest SOS live link, to open it without the SMS. */
    val lastLiveLink: StateFlow<String?> = _lastLiveLink.asStateFlow()

    /** The rider's latest final transcript: what Pillion says outside the LLM follows its language. */
    @Volatile
    private var lastRiderText: String? = null

    suspend fun start(): RideCredentials {
        val ride = api.startAgent()
        try {
            voice.join(ride)
        } catch (error: Throwable) {
            // Never leave an agent running (and billing) if we couldn't connect to it.
            withContext(NonCancellable) {
                voice.leave()
                runCatching { api.stopAgent(ride.agentId) }
            }
            throw error
        }
        return ride
    }

    /**
     * The ride's Agora agent as the voice of a safety alert: Agora's speak API (via the backend)
     * with interrupt priority. True once the agent has taken the text and is speaking.
     */
    fun safetyVoice(ride: RideCredentials) = SafetyVoice { text, interrupt ->
        if (voice.connection.value != ConnectionState.Connected || !voice.agentPresent.value) return@SafetyVoice false
        val wasSpeaking = voice.agentState.value == AgentState.Speaking
        try {
            api.say(ride.rideToken, text, interrupt)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Agora say failed; the phone speaks instead", error)
            return@SafetyVoice false
        }
        // An interrupting say while the agent talks may not change its state; otherwise wait for it.
        wasSpeaking || withTimeoutOrNull(SAY_START_TIMEOUT_MS) { voice.agentState.first { it == AgentState.Speaking } } != null
    }

    /** This ride's Live Guardian, or null when the backend has it off. */
    fun liveGuardian(ride: RideCredentials): LiveGuardian? = ride.guardianBaseUrl?.let { base ->
        LiveGuardian(ride, base, api, location) { _lastLiveLink.value = it }
    }

    /**
     * Everything a live ride does besides audio, until cancelled: gives final transcripts to the
     * safety check (on the phone) and the backend (Jev), answers the backend's device requests,
     * and pauses Pillion during phone calls. Collect from the main thread.
     */
    suspend fun serveRide(ride: RideCredentials): Nothing = coroutineScope {
        launch {
            voice.riderTurns.collect { turn ->
                safety.onRiderTurn(turn.text)
                lastRiderText = turn.text
                launch {
                    runCatching { api.postTurn(ride.rideToken, turn.turnId, turn.text) }
                        .onFailure { Log.w(TAG, "Turn not posted", it) }
                }
            }
        }
        launch {
            voice.serverRequests.collect { request -> launch { answer(ride, request) } }
        }
        launch {
            phoneCallActive(appContext).collect { voice.setPhoneCallActive(it) }
        }
        if (ride.guardianBaseUrl != null) launch { handOverToFamily(ride) }
        awaitCancellation()
    }

    /**
     * Live Guardian: family joined or left the channel. The backend has Pillion say so and step
     * aside, then brings it back; if it isn't back in 20 s the voice counts as lost (Retry).
     */
    private suspend fun handOverToFamily(ride: RideCredentials): Nothing = coroutineScope {
        var present = false
        var watchdog: Job? = null
        voice.familyPresent.collect { now ->
            if (now == present) return@collect
            present = now
            voice.showActionLine(if (now) "👤 Family joined" else "👤 Family left", failed = false)
            launch {
                runCatching { api.guardianPresence(ride.rideToken, now) }
                    .onFailure { Log.w(TAG, "Family presence not reported", it) }
            }
            watchdog?.cancel()
            if (!now) {
                watchdog = launch {
                    delay(AGENT_BACK_TIMEOUT_MS)
                    voice.reportAgentGone()
                }
            }
        }
    }

    private suspend fun answer(ride: RideCredentials, request: JSONObject) {
        val reply = actions.handle(request)
        if (reply.optString("error") == "permission_denied") {
            RidePermission.entries.firstOrNull { it.wireName == reply.optString("permission") }
                ?.let { _permissionNeeded.tryEmit(it) }
        }
        runCatching { api.postDeviceResult(ride.rideToken, reply) }
            .onFailure { Log.w(TAG, "Device result for ${request.optString("action")} not delivered", it) }
    }

    /**
     * Looks up a scanned order's drop on the map (through the backend's maps provider). Unchecked
     * if the server can't be reached: it then looks the address up when ETA is asked. Returns the
     * drop and the locality to call it by.
     */
    suspend fun locateDrop(order: Order): Pair<DropLocation, String> {
        if (order.dropAddress.isBlank()) return DropLocation.NotFound to order.dropArea
        val near = location.newestKnown()?.takeIf { location.ageMs(it) <= NEAR_MAX_AGE_MS }
        val result = try {
            api.geocode(order.dropAddress, order.dropArea, near)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Drop not looked up; the server will when ETA is asked", error)
            return DropLocation.Unchecked to order.dropArea
        }
        val drop = when (result.optString("status")) {
            "found", "approximate" -> DropLocation.Found(
                result.getDouble("lat"), result.getDouble("lng"), approximate = result.optString("status") == "approximate",
            )
            else -> DropLocation.NotFound
        }
        val area = order.dropArea.ifBlank { result.optString("area").takeIf { drop is DropLocation.Found && it != "null" }.orEmpty() }
        return drop to area
    }

    /**
     * Pillion says [line] (Hindi or English, by the rider's last words) through Agora's speak API,
     * after whatever it is saying. False if the voice isn't connected or the call failed.
     */
    suspend fun sayToRider(ride: RideCredentials, line: (hindi: Boolean) -> String): Boolean {
        if (voice.connection.value != ConnectionState.Connected || !voice.agentPresent.value) return false
        val english = lastRiderText?.let(SafetyPhrases::languageOf) == RiderLanguage.English
        return try {
            api.say(ride.rideToken, line(!english), interrupt = false)
            true
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Agora say failed", error)
            false
        }
    }

    /** Leaves the channel, then stops the agent. The agent also self-stops 30 s after we leave. */
    suspend fun end(agentId: String) = withContext(NonCancellable) {
        voice.leave()
        api.stopAgent(agentId)
    }

    /** Adds the finished ride to the trip history, paid at the active order's payout (seeded for now). */
    suspend fun recordTrip(startedAt: Long, endedAt: Long) = withContext(Dispatchers.IO) {
        val order = orders.activeOrder()
        earnings.addLiveTrip(startedAt, endedAt, order.dropArea, order.payoutRupees)
    }

    /** English subtitle for a Hindi line; throws if the server or Sarvam can't. */
    suspend fun translate(ride: RideCredentials, text: String): String = api.translate(ride.rideToken, text)

    /** km/h from a GPS fix of the last 10 s, or null. */
    fun riderSpeedKmh(): Float? = location.recentSpeedKmh()

    /** Debug only: mirror a per-turn latency line into the backend log. */
    suspend fun reportLatency(line: String) = api.reportLatency(line)

    private companion object {
        const val TAG = "RideRepository"
        const val SAY_START_TIMEOUT_MS = 3_000L
        const val AGENT_BACK_TIMEOUT_MS = 20_000L
        const val NEAR_MAX_AGE_MS = 30 * 60_000L
    }
}
