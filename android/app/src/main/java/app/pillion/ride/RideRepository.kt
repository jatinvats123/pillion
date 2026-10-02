package app.pillion.ride

import android.content.Context
import android.util.Log
import app.pillion.BuildConfig
import app.pillion.data.BackendApi
import app.pillion.data.installId
import app.pillion.data.DropLocation
import app.pillion.data.Order
import app.pillion.data.RideCredentials
import app.pillion.device.DeviceActions
import app.pillion.device.RiderLocation
import app.pillion.device.RidePermission
import app.pillion.device.CallState
import app.pillion.device.Caller
import app.pillion.device.callQuestion
import app.pillion.device.callState
import app.pillion.device.identifyCaller
import app.pillion.device.pausesPillion
import app.pillion.pillion
import app.pillion.safety.RiderLanguage
import app.pillion.safety.SafetyLine
import app.pillion.safety.SafetyPhrases
import app.pillion.safety.SafetyState
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
    private val api: BackendApi = BackendApi(installId(context)),
) {
    private val appContext = context.applicationContext
    val safety = appContext.pillion.safety
    val voice = VoiceSession(appContext)
    val orders = appContext.pillion.orders
    /** The server is slow to answer at a ride start (a hosted server starting up). */
    val serverWaking = api.waking
    private val location = RiderLocation(appContext)
    private val earnings = appContext.pillion.db
    private val actions = DeviceActions(
        appContext,
        orders,
        earnings,
        safety,
        onSmsDelivery = { customer, delivered ->
            voice.showActionLine(
                if (delivered) "✓ Delivered to $customer" else "✗ SMS to $customer not delivered",
                failed = !delivered,
            )
        },
        ringingSinceMs = { ringingSinceMs },
        lastRiderTurn = { callReply },
    )

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
    /** The rider's latest words while the phone rings (not while it asked), and when (wall clock). */
    @Volatile
    private var callReply: Pair<String, Long>? = null
    /** The phone is asking about a call through its speaker until then; Long.MAX_VALUE while it speaks. */
    @Volatile
    private var callDeafUntilMs = 0L

    /** When the phone started ringing (wall clock), 0 when it isn't. */
    @Volatile
    private var ringingSinceMs = 0L
    private val uiPrefs = appContext.pillion.uiPrefs
    private val contacts = appContext.pillion.contacts

    suspend fun start(): RideCredentials {
        val ride = api.startAgent()
        voice.keepAudioWhileRinging = ride.callAnswer && uiPrefs.answerCalls.value
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
                // While the phone rings: the rider's answer, unless the phone was asking just then.
                if (ringingSinceMs > 0L) {
                    val now = System.currentTimeMillis()
                    val deaf = now < callDeafUntilMs
                    if (!deaf) callReply = turn.text to now
                    Log.i(CALL_TAG, "Transcript while ringing, ${now - ringingSinceMs} ms in${if (deaf) " (phone was speaking: ignored)" else ""}" + if (BuildConfig.DEBUG) ": ${turn.text}" else "")
                }
                launch {
                    runCatching { api.postTurn(ride.rideToken, turn.turnId, turn.text) }
                        .onFailure { Log.w(TAG, "Turn not posted", it) }
                }
            }
        }
        launch {
            voice.serverRequests.collect { request -> launch { answer(ride, request) } }
        }
        launch { followPhoneCalls(ride) }
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

    /**
     * Phone calls during a ride. Any call, ringing or on, hands the mic and speaker to the call, as
     * before. With "Answer calls by voice" live for a ring ([canAskAboutCall]), Pillion stays on
     * while the phone rings and the backend has the agent ask the rider whether to answer (Agora
     * think); Pillion steps aside once the call is on, and the backend hears when it has ended.
     */
    private suspend fun followPhoneCalls(ride: RideCredentials) = coroutineScope {
        var ringing = false
        var askWhileRinging = false
        var number: String? = null
        var ask: Job? = null
        var asked = false
        var answered = false
        var paused: Boolean? = null
        callState(appContext, withNumber = uiPrefs.answerCalls.value).collect { state ->
            when (state) {
                is CallState.Ringing -> {
                    // Android may report a ring twice, the second time with the number.
                    number = state.number ?: number
                    if (!ringing) {
                        ringing = true
                        ringingSinceMs = System.currentTimeMillis()
                        askWhileRinging = canAskAboutCall(ride)
                        Log.i(CALL_TAG, "Ringing: ${if (askWhileRinging) "Pillion asks the rider" else "Pillion steps aside"}")
                        if (askWhileRinging) {
                            ask = launch {
                                delay(RING_SETTLE_MS)
                                asked = true
                                val order = orders.activeOrder()
                                val caller = identifyCaller(number, order, contacts.contacts.value)
                                Log.i(CALL_TAG, "Caller ${caller.kind.wireName} (number ${if (number != null) "given" else "not given"})")
                                launch { reportCall(ride, ringingEvent(caller, orderActive = order != null)) }
                                speakCallQuestion(callQuestion(caller, orderActive = order != null))
                            }
                        }
                    }
                }
                CallState.Offhook -> {
                    ask?.cancel()
                    answered = asked
                }
                CallState.Idle -> {
                    ask?.cancel()
                    if (asked) {
                        val event = JSONObject().put("state", "ended").put("answered", answered)
                        launch { reportCall(ride, event) }
                    }
                    ringing = false
                    ringingSinceMs = 0L
                    callReply = null
                    callDeafUntilMs = 0L
                    askWhileRinging = false
                    number = null
                    ask = null
                    asked = false
                    answered = false
                }
            }
            val pause = pausesPillion(state, askWhileRinging)
            if (pause != paused) {
                paused = pause
                voice.setPhoneCallActive(pause)
            }
        }
    }

    // A crash alert or SOS wins over a call; family on the line, a muted mic or no voice: as before.
    private fun canAskAboutCall(ride: RideCredentials) =
        ride.callAnswer && uiPrefs.answerCalls.value &&
            voice.connection.value == ConnectionState.Connected && voice.agentPresent.value &&
            !voice.familyPresent.value && !voice.micMuted.value && safety.state.value == SafetyState.Idle

    /** Who is calling, decided on the phone: by kind and name, never the number. */
    private fun ringingEvent(caller: Caller, orderActive: Boolean) = JSONObject()
        .put("state", "ringing")
        .put("caller", caller.kind.wireName)
        .put("order_active", orderActive)
        .apply { caller.name?.let { put("name", it) } }

    /**
     * Android mutes other apps' media sound while the phone rings, Pillion's Agora voice included,
     * so the phone asks itself, on the alarm channel (on-device TTS, as the crash check). Pillion's
     * mic hears that too: words heard meanwhile and just after don't count as the rider's answer.
     */
    private suspend fun speakCallQuestion(line: SafetyLine) {
        val language = lastRiderText?.let(SafetyPhrases::languageOf)?.takeIf { it != RiderLanguage.Unknown } ?: RiderLanguage.Hindi
        callDeafUntilMs = Long.MAX_VALUE
        try {
            if (!safety.alarm.speak(line, language)) Log.w(CALL_TAG, "On-device TTS unavailable: question not spoken")
        } finally {
            callDeafUntilMs = System.currentTimeMillis() + CALL_DEAF_AFTER_MS
        }
    }

    private suspend fun reportCall(ride: RideCredentials, event: JSONObject) {
        runCatching { api.callEvent(ride.rideToken, event) }
            .onFailure { Log.w(CALL_TAG, "Call event ${event.optString("state")} not delivered", it) }
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

    /** Debug only: a typed question, handled by the agent as if spoken. */
    suspend fun debugThink(ride: RideCredentials, text: String) = api.debugThink(ride.rideToken, text)

    /** Debug only: mirror a per-turn latency line into the backend log. */
    suspend fun reportLatency(line: String) = api.reportLatency(line)

    private companion object {
        const val TAG = "RideRepository"
        const val CALL_TAG = "IncomingCall"
        // After the first ring callback: Android may send the caller's number in a second one.
        const val RING_SETTLE_MS = 400L
        // After the phone's own question: the mic may still be finishing it (ASR, room echo).
        const val CALL_DEAF_AFTER_MS = 1_500L
        const val SAY_START_TIMEOUT_MS = 3_000L
        const val AGENT_BACK_TIMEOUT_MS = 20_000L
        const val NEAR_MAX_AGE_MS = 30 * 60_000L
    }
}
