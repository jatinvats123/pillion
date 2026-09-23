package app.pillion.voice

import android.content.Context
import android.util.Log
import app.pillion.BuildConfig
import app.pillion.data.RideCredentials
import io.agora.rtc2.ChannelMediaOptions
import io.agora.rtc2.Constants
import io.agora.rtc2.IRtcEngineEventHandler
import io.agora.rtc2.IRtcEngineEventHandler.AudioVolumeInfo
import io.agora.rtc2.RtcEngine
import io.agora.rtc2.RtcEngineConfig
import io.agora.rtm.ErrorInfo
import io.agora.rtm.MessageEvent
import io.agora.rtm.PresenceEvent
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmEventListener
import io.agora.rtm.SubscribeOptions
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Agent state as published by Agora ConvoAI over RTM. */
enum class AgentState { Unknown, Idle, Listening, Thinking, Speaking, Silent }

enum class Speaker { Rider, Pillion }

data class TranscriptLine(
    val key: String,
    val speaker: Speaker,
    val text: String,
    val isFinal: Boolean,
    val interrupted: Boolean = false,
)

enum class ConnectionState { Disconnected, Connecting, Connected, Reconnecting }

/** A voice-connection failure with a message fit to show the rider. */
class VoiceException(message: String) : IOException(message)

sealed interface VoiceEvent {
    /** The agent left the channel while the rider was still in it. */
    data object AgentLeft : VoiceEvent

    /** RTC gave up reconnecting (e.g. token rejected, network gone for too long). */
    data class ConnectionFailed(val reason: Int) : VoiceEvent
}

/**
 * One ride's realtime connection to the Pillion agent.
 *
 * - **RTC** carries audio: the rider's mic goes up, the agent's voice comes down.
 * - **RTM** carries data: the agent publishes transcripts (`user.transcription`,
 *   `assistant.transcription`), its state (`message.state` / presence) and interrupts
 *   (`message.interrupt`) as JSON messages on a channel with the same name.
 *
 * Barge-in is handled by the agent (server-side VAD interruption). The client's job is to keep
 * the mic open while the agent speaks and to cancel the agent's own voice from the mic (AEC).
 */
class VoiceSession(context: Context) {

    private val appContext = context.applicationContext

    private val _agentState = MutableStateFlow(AgentState.Unknown)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _agentPresent = MutableStateFlow(false)
    val agentPresent: StateFlow<Boolean> = _agentPresent.asStateFlow()

    private val _connection = MutableStateFlow(ConnectionState.Disconnected)
    val connection: StateFlow<ConnectionState> = _connection.asStateFlow()

    private val _transcript = MutableStateFlow<List<TranscriptLine>>(emptyList())
    val transcript: StateFlow<List<TranscriptLine>> = _transcript.asStateFlow()

    private val _events = MutableSharedFlow<VoiceEvent>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private val _latency = MutableSharedFlow<TurnLatency>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Per-reply latency breakdown (needs `enable_metrics` on the agent). */
    val latency: SharedFlow<TurnLatency> = _latency.asSharedFlow()
    private val latencyTracker = LatencyTracker { report ->
        Log.i(TAG, "Latency $report")
        _latency.tryEmit(report)
    }

    private var rtcEngine: RtcEngine? = null
    private var rtmClient: RtmClient? = null
    private var ride: RideCredentials? = null
    private var joinResult: CompletableDeferred<Unit>? = null
    private val transcriptLines = LinkedHashMap<String, TranscriptLine>()

    /** Logs into RTM, then joins RTC. Throws if either fails; call [leave] to clean up. */
    suspend fun join(credentials: RideCredentials) = withContext(Dispatchers.Main) {
        ride = credentials
        resetState()
        _connection.value = ConnectionState.Connecting

        // RTM first, so the greeting's transcript and state aren't missed.
        val rtm = RtmClient.create(
            RtmConfig.Builder(credentials.appId, credentials.uid.toString()).build()
        )
        rtmClient = rtm
        rtm.addEventListener(rtmListener)
        awaitRtm { rtm.login(credentials.token, it) }
        val subscribeOptions = SubscribeOptions().apply {
            setWithMessage(true)
            setWithPresence(true)
        }
        awaitRtm { rtm.subscribe(credentials.channel, subscribeOptions, it) }

        val engine = RtcEngine.create(
            RtcEngineConfig().apply {
                mContext = appContext
                mAppId = credentials.appId
                mEventHandler = rtcHandler
                mChannelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
                // Agora's audio scenario for talking to an AI agent (buffering tuned for synthesized speech).
                mAudioScenario = Constants.AUDIO_SCENARIO_AI_CLIENT
            }
        ) ?: throw VoiceException("Agora voice engine failed to start.")
        rtcEngine = engine

        // AI echo cancellation keeps the agent from hearing (and interrupting) itself on speaker;
        // AI noise suppression handles wind and traffic.
        engine.loadExtensionProvider("ai_echo_cancellation_extension")
        engine.loadExtensionProvider("ai_noise_suppression_extension")
        engine.enableAudio()
        engine.setDefaultAudioRoutetoSpeakerphone(true)
        applyAiAudioParameters(engine, Constants.AUDIO_ROUTE_DEFAULT)
        // Debug builds track the rider's voice activity (200 ms resolution) to time replies end to end.
        if (BuildConfig.DEBUG) engine.enableAudioVolumeIndication(200, 3, true)

        val joined = CompletableDeferred<Unit>()
        joinResult = joined
        val options = ChannelMediaOptions().apply {
            channelProfile = Constants.CHANNEL_PROFILE_LIVE_BROADCASTING
            clientRoleType = Constants.CLIENT_ROLE_BROADCASTER
            publishMicrophoneTrack = true
            autoSubscribeAudio = true
        }
        val result = engine.joinChannel(credentials.token, credentials.channel, credentials.uid, options)
        if (result != Constants.ERR_OK) {
            throw VoiceException("Could not join the voice channel (${RtcEngine.getErrorDescription(result)}).")
        }
        withTimeout(JOIN_TIMEOUT_MS) { joined.await() }
    }

    /** Leaves RTC and RTM and releases both SDKs. Safe to call more than once. */
    suspend fun leave() = withContext(Dispatchers.IO) {
        joinResult?.cancel()
        joinResult = null

        val channel = ride?.channel
        rtmClient?.let { rtm ->
            runCatching { rtm.removeEventListener(rtmListener) }
            if (channel != null) runCatching { rtm.unsubscribe(channel, noopCallback) }
            runCatching { rtm.logout(noopCallback) }
            runCatching { rtm.release() }
        }
        rtmClient = null

        rtcEngine?.let { runCatching { it.leaveChannel() } }
        rtcEngine = null
        runCatching { RtcEngine.destroy() }

        ride = null
        _connection.value = ConnectionState.Disconnected
        _agentPresent.value = false
        _agentState.value = AgentState.Unknown
    }

    private fun resetState() {
        synchronized(transcriptLines) { transcriptLines.clear() }
        latencyTracker.reset()
        _transcript.value = emptyList()
        _agentState.value = AgentState.Unknown
        _agentPresent.value = false
    }

    /**
     * Agora's recommended 3A settings for ConvoAI clients. Re-applied on every audio route
     * change: headset/earpiece/Bluetooth use a lighter echo canceller than the loudspeaker.
     */
    private fun applyAiAudioParameters(engine: RtcEngine, routing: Int) {
        with(engine) {
            setParameters("""{"che.audio.aec.split_srate_for_48k":16000}""")
            setParameters("""{"che.audio.sf.enabled":true}""")
            setParameters("""{"che.audio.sf.stftType":6}""")
            setParameters("""{"che.audio.sf.ainlpLowLatencyFlag":1}""")
            setParameters("""{"che.audio.sf.ainsLowLatencyFlag":1}""")
            setParameters("""{"che.audio.sf.procChainMode":1}""")
            setParameters("""{"che.audio.sf.nlpDynamicMode":1}""")
            val nlpRoute = if (routing in HEADSET_LIKE_ROUTES) 0 else 1
            setParameters("""{"che.audio.sf.nlpAlgRoute":$nlpRoute}""")
            setParameters("""{"che.audio.sf.ainlpModelPref":10}""")
            setParameters("""{"che.audio.sf.nsngAlgRoute":12}""")
            setParameters("""{"che.audio.sf.ainsModelPref":10}""")
            setParameters("""{"che.audio.sf.nsngPredefAgg":11}""")
            setParameters("""{"che.audio.agc.enable":false}""")
        }
    }

    private val rtcHandler = object : IRtcEngineEventHandler() {
        override fun onJoinChannelSuccess(channel: String?, uid: Int, elapsed: Int) {
            _connection.value = ConnectionState.Connected
            joinResult?.complete(Unit)
        }

        override fun onUserJoined(uid: Int, elapsed: Int) {
            if (uid == ride?.agentUid) _agentPresent.value = true
        }

        override fun onUserOffline(uid: Int, reason: Int) {
            if (uid == ride?.agentUid) {
                _agentPresent.value = false
                _events.tryEmit(VoiceEvent.AgentLeft)
            }
        }

        override fun onConnectionStateChanged(state: Int, reason: Int) {
            when (state) {
                Constants.CONNECTION_STATE_CONNECTED -> _connection.value = ConnectionState.Connected
                Constants.CONNECTION_STATE_RECONNECTING -> _connection.value = ConnectionState.Reconnecting
                Constants.CONNECTION_STATE_FAILED -> {
                    joinResult?.completeExceptionally(
                        VoiceException("Voice connection failed (${RtcEngine.getErrorDescription(reason)}).")
                    )
                    _events.tryEmit(VoiceEvent.ConnectionFailed(reason))
                }
            }
        }

        override fun onAudioRouteChanged(routing: Int) {
            rtcEngine?.let { applyAiAudioParameters(it, routing) }
        }

        override fun onError(err: Int) {
            Log.w(TAG, "RTC error $err: ${RtcEngine.getErrorDescription(err)}")
        }

        override fun onLocalAudioStateChanged(state: Int, reason: Int) {
            Log.i(TAG, "Local audio state=$state reason=$reason")
        }

        // uid 0 is the local mic; vad 1 means the rider is speaking.
        override fun onAudioVolumeIndication(speakers: Array<out AudioVolumeInfo>?, totalVolume: Int) {
            if (speakers.orEmpty().any { it.uid == 0 && it.vad == 1 }) {
                latencyTracker.onRiderVoice(System.currentTimeMillis())
            }
        }
    }

    private val rtmListener = object : RtmEventListener {
        override fun onMessageEvent(event: MessageEvent) {
            val raw = when (val data = event.message.data) {
                is String -> data
                is ByteArray -> data.toString(Charsets.UTF_8)
                else -> return
            }
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return
            handleAgentMessage(json)
        }

        override fun onPresenceEvent(event: PresenceEvent) {
            event.stateItems["state"]?.let {
                if (BuildConfig.DEBUG) Log.d(TAG, "Event presence state=$it turn=${event.stateItems["turn_id"]}")
                onAgentState(it, event.stateItems["turn_id"]?.toLongOrNull())
            }
        }
    }

    private fun handleAgentMessage(json: JSONObject) {
        val turnId = json.optLong("turn_id", -1)
        if (BuildConfig.DEBUG) logAgentEvent(json, turnId)
        trackLatency(json, turnId)
        when (json.optString("object")) {
            "user.transcription" -> upsertLine(
                speaker = Speaker.Rider,
                turnId = turnId,
                text = json.optString("text"),
                isFinal = json.optBoolean("final", true),
            )

            // turn_status: 0 = in progress, 1 = finished, 2 = interrupted.
            "assistant.transcription" -> {
                val status = json.optInt("turn_status", 1)
                upsertLine(
                    speaker = Speaker.Pillion,
                    turnId = turnId,
                    text = json.optString("text"),
                    isFinal = status != 0,
                    interrupted = status == 2,
                )
            }

            "message.interrupt" -> markInterrupted(turnId)
            "message.state" -> onAgentState(json.optString("state"), turnId.takeIf { it >= 0 })
            "message.error" -> Log.w(TAG, "Agent error: $json")
        }
    }

    // Debug timeline of agent events, to measure where response latency goes.
    private fun onAgentState(raw: String, turnId: Long?) {
        val state = raw.toAgentState()
        _agentState.value = state
        if (state == AgentState.Speaking && turnId != null) {
            latencyTracker.onAgentSpeaking(turnId, System.currentTimeMillis())
        }
    }

    private fun trackLatency(json: JSONObject, turnId: Long) {
        val now = System.currentTimeMillis()
        when (json.optString("object")) {
            "user.transcription" -> if (json.optBoolean("final", true)) latencyTracker.onUserFinalTranscript(turnId, now)
            "message.metrics" -> latencyTracker.onMetric(
                turnId = turnId,
                module = json.optString("module"),
                name = json.optString("metric_name"),
                latencyMs = json.optInt("latency_ms"),
            )
        }
    }

    private fun logAgentEvent(json: JSONObject, turnId: Long) {
        val detail = when (json.optString("object")) {
            "user.transcription" -> "final=${json.optBoolean("final")} \"${json.optString("text")}\""
            "assistant.transcription" -> "status=${json.optInt("turn_status")} \"${json.optString("text").take(40)}\""
            "message.state" -> "state=${json.optString("state")}"
            "message.metrics" -> json.toString()
            else -> ""
        }
        Log.d(TAG, "Event ${json.optString("object")} turn=$turnId $detail")
    }

    // Each transcription message carries the full text so far for that turn, so replace, don't append.
    private fun upsertLine(speaker: Speaker, turnId: Long, text: String, isFinal: Boolean, interrupted: Boolean = false) {
        val clean = text.trim()
        val key = "$speaker:$turnId"
        synchronized(transcriptLines) {
            val existing = transcriptLines[key]
            if (existing == null && clean.isEmpty()) return
            transcriptLines[key] = TranscriptLine(
                key = key,
                speaker = speaker,
                text = clean.ifEmpty { existing?.text.orEmpty() },
                isFinal = isFinal,
                interrupted = interrupted || existing?.interrupted == true,
            )
            publishTranscript()
        }
    }

    private fun markInterrupted(turnId: Long) {
        synchronized(transcriptLines) {
            val key = "${Speaker.Pillion}:$turnId"
            val line = transcriptLines[key] ?: return
            transcriptLines[key] = line.copy(interrupted = true, isFinal = true)
            publishTranscript()
        }
    }

    private fun publishTranscript() {
        while (transcriptLines.size > MAX_LINES) transcriptLines.remove(transcriptLines.keys.first())
        _transcript.value = transcriptLines.values.toList()
    }

    private fun String.toAgentState() = when (lowercase()) {
        "idle" -> AgentState.Idle
        "listening" -> AgentState.Listening
        "thinking" -> AgentState.Thinking
        "speaking" -> AgentState.Speaking
        "silent" -> AgentState.Silent
        else -> AgentState.Unknown
    }

    private suspend fun awaitRtm(call: (ResultCallback<Void>) -> Unit) =
        suspendCancellableCoroutine { continuation ->
            call(object : ResultCallback<Void> {
                override fun onSuccess(result: Void?) {
                    if (continuation.isActive) continuation.resume(Unit)
                }

                override fun onFailure(errorInfo: ErrorInfo) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(
                            VoiceException("Transcript channel error: ${errorInfo.errorReason} (${errorInfo.errorCode}).")
                        )
                    }
                }
            })
        }

    private val noopCallback = object : ResultCallback<Void> {
        override fun onSuccess(result: Void?) = Unit
        override fun onFailure(errorInfo: ErrorInfo) = Unit
    }

    private companion object {
        const val TAG = "VoiceSession"
        const val JOIN_TIMEOUT_MS = 15_000L
        const val MAX_LINES = 200

        // Agora audio routes where the mic is not next to a loudspeaker.
        val HEADSET_LIKE_ROUTES = setOf(
            Constants.AUDIO_ROUTE_HEADSET,
            Constants.AUDIO_ROUTE_EARPIECE,
            Constants.AUDIO_ROUTE_HEADSETNOMIC,
            Constants.AUDIO_ROUTE_BLUETOOTH_DEVICE_HFP,
            Constants.AUDIO_ROUTE_BLUETOOTH_DEVICE_A2DP,
        )
    }
}
