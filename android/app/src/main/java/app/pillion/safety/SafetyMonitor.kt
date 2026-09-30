package app.pillion.safety

import android.content.Context
import android.location.Location
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import app.pillion.BuildConfig
import app.pillion.data.EarningsDb
import app.pillion.device.RiderLocation
import app.pillion.device.SmsSender
import app.pillion.device.startPhoneCall
import app.pillion.ride.RideService
import app.pillion.ui.SafetyAlertActivity
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class SosTrigger { Crash, Button, Voice }

/** Why an SOS couldn't go out at all. */
enum class SosProblem { NoContacts, NoSmsPermission, NothingSent }

enum class DeliveryStatus { Sending, Sent, Delivered, NotDelivered, Failed }

data class ContactStatus(val contact: EmergencyContact, val status: DeliveryStatus) {
    val reached get() = status == DeliveryStatus.Sent || status == DeliveryStatus.Delivered
}

sealed interface SafetyState {
    data object Idle : SafetyState

    /** Counting down to an SOS; the rider can cancel. Times on the elapsedRealtime clock. */
    data class Countdown(
        val trigger: SosTrigger,
        val endsAtMs: Long,
        val durationMs: Long,
        /** The SOS won't be able to go out (shown during the countdown). */
        val problem: SosProblem?,
    ) : SafetyState

    /** The SOS went out (per-contact results), or couldn't ([problem]). Ends when the rider taps "I'm OK now". */
    data class Sos(
        val trigger: SosTrigger,
        val contacts: List<ContactStatus>,
        val problem: SosProblem?,
        /** Next location update, or null when none is scheduled. */
        val nextUpdateAtMs: Long?,
        val updatesSent: Int,
        val updatesTotal: Int,
    ) : SafetyState
}

sealed interface ManualSosResult {
    data class Started(val seconds: Int, val contacts: Int) : ManualSosResult
    data object AlreadyActive : ManualSosResult
    data object NoContacts : ManualSosResult
    data object NoSmsPermission : ManualSosResult
}

/** Pillion's Agora voice, when a ride has one. Optional: safety never waits on it. */
fun interface SafetyVoice {
    /** Asks the agent to speak [text] now. True if it took it (and started speaking). */
    suspend fun say(text: String, interrupt: Boolean): Boolean
}

/** Live Guardian, when the ride's voice is up and the feature is on. Optional: the SOS never waits on it. */
interface LiveGuardianLink {
    /** A new link for this SOS, made on the phone at once (the backend learns it in the background). */
    fun newLink(riderName: String): String

    /** I'M OK NOW: the family's page says so and stops following the rider. */
    fun riderOk()

    /** The ride's voice is gone: stop sharing. */
    fun close()
}

/** A line for the ride transcript ("⚠ Crash detected", "✓ SOS sent to 2 contacts"). */
data class ActionLine(val text: String, val failed: Boolean = false)

/**
 * The crash → check → SOS flow, fatigue reminders and the safety log. Runs entirely on the phone:
 * sensors, SIM (SMS), GPS and on-device TTS. Agora ([voice]) makes it better when connected — the
 * agent speaks the check and the rider can answer by voice — but nothing here depends on it, the
 * backend, or the internet. Call from the main thread.
 */
class SafetyMonitor(
    context: Context,
    private val db: EarningsDb,
    val contacts: EmergencyContacts,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val debug = SafetyDebug(appContext)
    private var config = SafetyConfig.forBuild(BuildConfig.DEBUG, debug.fatigueInTwoMinutes.value)
    val alarm = SafetyAlarm(appContext)
    private val sms = SmsSender(appContext)
    private val location = RiderLocation(appContext)
    private val notifications = SafetyNotifications(appContext)
    private val wakeLock = appContext.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pillion:safety-alert")
        .apply { setReferenceCounted(false) }

    private val _state = MutableStateFlow<SafetyState>(SafetyState.Idle)
    val state: StateFlow<SafetyState> = _state.asStateFlow()

    private val _actionLines = MutableSharedFlow<ActionLine>(extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val actionLines: SharedFlow<ActionLine> = _actionLines.asSharedFlow()

    private val _gpsAvailable = MutableStateFlow(true)
    /** False during a ride with no GPS fix for 30 s (crash detection then needs a harder impact). */
    val gpsAvailable: StateFlow<Boolean> = _gpsAvailable.asStateFlow()

    private val _approximateLocation = MutableStateFlow(false)
    /** A Wi-Fi / cell fix of the last minute: without GPS, the SOS still sends roughly where the rider is. */
    val approximateLocation: StateFlow<Boolean> = _approximateLocation.asStateFlow()

    /** Set while a ride's Agora voice is connected. */
    var voice: SafetyVoice? = null
        set(value) {
            field = value
            _voiceConnected.value = value != null
        }

    /** Set with [voice] when Live Guardian is on; replacing or clearing it closes the old one. */
    var liveGuardian: LiveGuardianLink? = null
        set(value) {
            if (field !== value) field?.close()
            field = value
        }

    private val _voiceConnected = MutableStateFlow(false)
    /** The rider can answer the alert by voice (Agora connected). */
    val voiceConnected: StateFlow<Boolean> = _voiceConnected.asStateFlow()

    /** The ride's sensors (set by the ride service), for the debug "Simulate crash". */
    var sensors: SafetySensors? = null

    val rideActive: Boolean get() = rideStartedAtMs != null

    private var language = RiderLanguage.Unknown
    private var latestFix: Location? = null
    private var latestNetworkFix: Location? = null
    private var fatigue: FatigueTracker? = null
    private var rideStartedAtMs: Long? = null
    private var rideJob: Job? = null
    private var alertJob: Job? = null
    private var sosLogId: Long? = null
    private var sosFix: SosFix? = null
    private var sosLink: String? = null
    /** The phone is speaking through its loudspeaker: transcripts now may be Pillion's own words. */
    private var deafUntilMs = 0L

    init {
        // Ready before any alert, including an SOS button press outside a ride.
        alarm.prepare()
        scope.launch { state.collect { notifications.show(it) } }
    }

    // ---- Ride lifecycle (the ride service's sensors run between these).

    fun onRideStarted() {
        if (rideActive) return
        rideStartedAtMs = now()
        config = SafetyConfig.forBuild(BuildConfig.DEBUG, debug.fatigueInTwoMinutes.value)
        fatigue = FatigueTracker(config.fatigueAfterMs, config.fatigueRepeatMs, config.fatigueResetAfterStopMs)
        language = RiderLanguage.Unknown
        latestFix = null
        latestNetworkFix = null
        _gpsAvailable.value = true
        _approximateLocation.value = false
        alarm.prepare()
        rideJob = scope.launch {
            while (true) {
                delay(TICK_MS)
                tick()
            }
        }
    }

    /** Stops the ride's sensors now, or once a running alert is over. */
    fun onRideEnded() {
        rideStartedAtMs = null
        rideJob?.cancel()
        rideJob = null
        voice = null
        liveGuardian = null
        fatigue = null
        if (_state.value == SafetyState.Idle) stopRideService()
    }

    /** Once per ride: without an emergency contact the SOS can't work. */
    fun announceIfNotSetUp() {
        if (contacts.contacts.value.isNotEmpty()) return
        emit("⚠ SOS not set up: add an emergency contact", failed = true)
        scope.launch { say(SafetyLine.NOT_SET_UP, interrupt = false) }
    }

    // ---- Inputs.

    fun onLocation(fix: Location) {
        latestFix = fix
        if (fix.hasSpeed()) fatigue?.onSpeed(fix.elapsedRealtimeNanos / 1_000_000, fix.speed * 3.6f)
    }

    /** Wi-Fi / cell position: only where the rider is, never speed (crash detection and fatigue ignore it). */
    fun onNetworkLocation(fix: Location) {
        latestNetworkFix = fix
        if (rideActive) _approximateLocation.value = true
    }

    fun onDetectorEvent(event: DetectorEvent) {
        when (event) {
            is DetectorEvent.Crash -> onCrash(event)
            is DetectorEvent.Rejected -> Log.i(TAG, "Detector: rejected, ${event.reason} (peak ${event.peakG} g, tipped ${event.tumbleDeg}°)")
            is DetectorEvent.ImpactIgnored -> Log.i(TAG, "Detector: ${event.peakG} g ignored, ${event.reason}")
        }
    }

    /** Every final rider transcript: sets the language, and during a countdown may cancel or send. */
    fun onRiderTurn(text: String) {
        SafetyPhrases.languageOf(text).takeIf { it != RiderLanguage.Unknown }?.let { language = it }
        if (_state.value !is SafetyState.Countdown) return
        if (now() < deafUntilMs) {
            Log.i(TAG, "Reply ignored (phone was speaking): \"$text\"")
            return
        }
        when (SafetyPhrases.classify(text)) {
            Reply.Cancel -> cancelCountdown("Rider said \"$text\"")
            Reply.Help -> sendNow("Rider said \"$text\"")
            Reply.Unclear -> Log.i(TAG, "Reply unclear, countdown continues: \"$text\"")
        }
    }

    /** "I'M OK" (countdown) or "I'M OK NOW" (after an SOS), from the alert screen or notification. */
    fun riderIsOk() {
        when (_state.value) {
            is SafetyState.Countdown -> cancelCountdown("Rider tapped I'M OK")
            is SafetyState.Sos -> endSos()
            SafetyState.Idle -> Unit
        }
    }

    /** Skip the rest of the countdown. */
    fun sendNow(why: String = "Rider tapped Send now") {
        val countdown = _state.value as? SafetyState.Countdown ?: return
        Log.i(TAG, "Sending SOS now: $why")
        alertJob?.cancel()
        alertJob = scope.launch {
            launch { say(SafetyLine.SENDING_NOW, interrupt = true) }
            sendSos(countdown.trigger, why)
        }
    }

    /** The rider asked for help (voice tool or SOS button): 5 s to cancel, then the SOS. */
    fun startManualSos(trigger: SosTrigger): ManualSosResult {
        if (_state.value != SafetyState.Idle) return ManualSosResult.AlreadyActive
        db.addSafetyEvent(KIND_MANUAL_SOS, if (trigger == SosTrigger.Voice) "Rider asked for SOS by voice" else "Rider pressed SOS")
        emit("⚠ SOS requested", failed = true)
        val problem = sosProblem()
        if (problem != null) {
            // By voice, the agent tells the rider what's wrong (from the tool result).
            scope.launch { failWith(trigger, problem, speak = trigger != SosTrigger.Voice) }
            enterAlert()
            return if (problem == SosProblem.NoContacts) ManualSosResult.NoContacts else ManualSosResult.NoSmsPermission
        }
        startCountdown(trigger, config.manualCountdownMs)
        return ManualSosResult.Started((config.manualCountdownMs / 1000).toInt(), contacts.contacts.value.size)
    }

    /**
     * Feeds a synthetic crash trace through the ride's real detector: the debug sheet's "Simulate
     * crash" and Settings' "Try the crash check". False when no ride is running.
     */
    fun simulateCrash(): Boolean = sensors?.replay(SyntheticTraces.crash()) ?: false

    /** Debug builds: a note in the ride transcript (e.g. the sensor recorder's file). */
    fun debugNote(text: String) {
        if (BuildConfig.DEBUG) emit(text)
    }

    /** Android 14+ only lets some apps show full-screen alerts by default. */
    fun canShowOverLockScreen(): Boolean = notifications.canShowOverLockScreen()

    // ---- The flow.

    private fun onCrash(crash: DetectorEvent.Crash) {
        Log.w(TAG, "CRASH detected: ${crash.describe()}")
        if (_state.value != SafetyState.Idle) return
        db.addSafetyEvent(KIND_CRASH_DETECTED, crash.describe())
        emit("⚠ Crash detected", failed = true)
        startCountdown(SosTrigger.Crash, config.crashCountdownMs)
    }

    private fun startCountdown(trigger: SosTrigger, durationMs: Long) {
        val endsAt = now() + durationMs
        _state.value = SafetyState.Countdown(trigger, endsAt, durationMs, sosProblem())
        enterAlert()
        alertJob = scope.launch {
            alarm.startAlert()
            when (trigger) {
                SosTrigger.Crash -> announce(SafetyLine.ARE_YOU_OK)
                SosTrigger.Button -> say(SafetyLine.MANUAL_COUNTDOWN, interrupt = true)
                SosTrigger.Voice -> Unit // the agent's reply to the tool says it
            }
            var repeated = trigger != SosTrigger.Crash
            while (true) {
                val left = endsAt - now()
                if (left <= 0) break
                if (!repeated && left <= config.repeatPromptAtLeftMs) {
                    repeated = true
                    announce(SafetyLine.ARE_YOU_OK_AGAIN)
                    continue
                }
                alarm.beep()
                delay(min(1_000, left))
            }
            sendSos(trigger, "No answer in ${durationMs / 1000} s")
        }
    }

    private fun cancelCountdown(why: String) {
        val countdown = _state.value as? SafetyState.Countdown ?: return
        alertJob?.cancel()
        alarm.stopAlert()
        db.addSafetyEvent(if (countdown.trigger == SosTrigger.Crash) KIND_CRASH_CANCELLED else KIND_SOS_CANCELLED, why)
        emit("✓ You're OK: SOS cancelled")
        setIdle()
        scope.launch { say(SafetyLine.CANCELLED, interrupt = true) }
    }

    private suspend fun sendSos(trigger: SosTrigger, why: String) {
        alarm.stopAlert() // quiet, so the rider can hear Pillion and be heard
        val problem = sosProblem()
        if (problem != null) {
            failWith(trigger, problem, speak = true)
            return
        }
        val list = contacts.contacts.value
        val name = contacts.riderName.value
        val reason = if (trigger == SosTrigger.Crash) SosMessages.Reason.Crash else SosMessages.Reason.RiderAsked
        _state.value = SafetyState.Sos(trigger, list.map { ContactStatus(it, DeliveryStatus.Sending) }, null, null, 0, config.followUpCount)

        val fix = currentFix().also { sosFix = it }
        sosLink = runCatching { liveGuardian?.newLink(name) }.getOrNull()
        val text = SosMessages.first(name, reason, System.currentTimeMillis(), fix, sosLink)
        val results = sendToAll(list, trackDelivery = true) { text }
        updateSos { it.copy(contacts = list.mapIndexed { i, c -> ContactStatus(c, if (results[i]) DeliveryStatus.Sent else DeliveryStatus.Failed) }) }

        val sent = results.count { it }
        sosLogId = db.addSafetyEvent(if (sent > 0) KIND_SOS_SENT else KIND_SOS_FAILED, sosNote(why))
        if (sent > 0) emit("✓ SOS sent to $sent ${if (sent == 1) "contact" else "contacts"}")
        list.filterIndexed { i, _ -> !results[i] }.forEach { emit("✗ SOS to ${it.name} failed", failed = true) }
        if (sent == 0) updateSos { it.copy(problem = SosProblem.NothingSent) }
        say(if (sent == 0) SafetyLine.SOS_FAILED else SafetyLine.sosSent(sent, list.size), interrupt = true)

        if (sent > 0 && config.autoCallFirstContact) callFirstReached()
        followUps(name, reason)
    }

    /** Location updates; a contact whose first SOS failed gets the full SOS again. */
    private suspend fun followUps(name: String, reason: SosMessages.Reason) {
        for (round in 1..config.followUpCount) {
            val next = now() + config.followUpIntervalMs
            updateSos { it.copy(nextUpdateAtMs = next) }
            delay(config.followUpIntervalMs)
            val sos = _state.value as? SafetyState.Sos ?: return
            val fix = currentFix()
            val at = System.currentTimeMillis()
            val results = sendToAll(sos.contacts.map { it.contact }, trackDelivery = false) { i ->
                if (sos.contacts[i].reached) SosMessages.followUp(name, at, fix) else SosMessages.first(name, reason, at, fix, sosLink)
            }
            updateSos { current ->
                current.copy(
                    updatesSent = round,
                    contacts = current.contacts.mapIndexed { i, c -> if (!c.reached && results[i]) c.copy(status = DeliveryStatus.Sent) else c },
                    problem = if (results.any { it }) null else current.problem,
                )
            }
            val sent = results.count { it }
            emit(if (sent > 0) "↻ Location update $round/${config.followUpCount} sent to $sent" else "✗ Location update $round failed", failed = sent == 0)
            sosFix = fix
            sosLogId?.let { db.updateSafetyNote(it, sosNote("follow-ups: $round")) }
        }
        updateSos { it.copy(nextUpdateAtMs = null) }
        releaseWakeLock() // nothing is scheduled any more; the screen stays until the rider taps
    }

    /** "I'm OK now": stops follow-ups and tells the contacts who got the SOS. */
    private fun endSos() {
        val sos = _state.value as? SafetyState.Sos ?: return
        alertJob?.cancel()
        alarm.stopAlert()
        val told = sos.contacts.filter { it.reached }
        runCatching { liveGuardian?.riderOk() }
        setIdle()
        if (told.isEmpty()) {
            db.addSafetyEvent(KIND_SOS_ENDED, "Rider closed the SOS screen")
            return
        }
        scope.launch {
            val text = SosMessages.riderOk(contacts.riderName.value, System.currentTimeMillis())
            val results = sendToAll(told.map { it.contact }, trackDelivery = false) { text }
            val ok = results.count { it }
            db.addSafetyEvent(KIND_SOS_ENDED, "Rider pressed I'M OK NOW; told $ok of ${told.size} contacts")
            emit(if (ok > 0) "✓ Told $ok ${if (ok == 1) "contact" else "contacts"} you're OK" else "✗ Couldn't tell your contacts you're OK", failed = ok == 0)
            if (ok > 0) say(SafetyLine.RIDER_OK_TOLD, interrupt = true)
        }
    }

    private suspend fun failWith(trigger: SosTrigger, problem: SosProblem, speak: Boolean) {
        alarm.stopAlert()
        _state.value = SafetyState.Sos(trigger, contacts.contacts.value.map { ContactStatus(it, DeliveryStatus.Failed) }, problem, null, 0, 0)
        val why = if (problem == SosProblem.NoContacts) "no emergency contact" else "SMS permission is off"
        db.addSafetyEvent(KIND_SOS_FAILED, "SOS not sent: $why")
        emit("✗ SOS not sent: $why", failed = true)
        releaseWakeLock()
        if (speak) say(if (problem == SosProblem.NoContacts) SafetyLine.NO_CONTACTS else SafetyLine.NO_SMS_PERMISSION, interrupt = true)
    }

    private fun onDelivery(contact: EmergencyContact, delivered: Boolean) {
        updateSos { sos ->
            sos.copy(contacts = sos.contacts.map {
                if (it.contact == contact && it.status == DeliveryStatus.Sent) {
                    it.copy(status = if (delivered) DeliveryStatus.Delivered else DeliveryStatus.NotDelivered)
                } else {
                    it
                }
            })
        }
        emit(if (delivered) "✓ SOS delivered to ${contact.name}" else "✗ SOS to ${contact.name} not delivered", failed = !delivered)
        sosLogId?.let { db.updateSafetyNote(it, sosNote("delivery report")) }
    }

    private fun tick() {
        val start = rideStartedAtMs ?: return
        val fixAge = latestFix?.let { location.ageMs(it) }
        _gpsAvailable.value = fixAge?.let { it < GPS_LOST_MS } ?: (now() - start < GPS_LOST_MS)
        _approximateLocation.value = latestNetworkFix?.let { location.ageMs(it) < APPROXIMATE_FIX_MS } ?: false
        val minutes = fatigue?.check(now()) ?: return
        if (_state.value != SafetyState.Idle) return
        db.addSafetyEvent(KIND_FATIGUE, "$minutes min of continuous riding")
        emit("☕ Break reminder: $minutes min of riding")
        scope.launch { say(SafetyLine.takeABreak(minutes), interrupt = false) }
    }

    // ---- Helpers.

    private fun sosProblem(): SosProblem? = when {
        contacts.contacts.value.isEmpty() -> SosProblem.NoContacts
        !sms.hasPermission() -> SosProblem.NoSmsPermission
        else -> null
    }

    /** Texts every contact at once; true per contact whose SMS Android reports sent. */
    private suspend fun sendToAll(list: List<EmergencyContact>, trackDelivery: Boolean, text: (Int) -> String): List<Boolean> = coroutineScope {
        list.mapIndexed { i, contact ->
            async {
                sms.send(contact.number, text(i), SMS_TIMEOUT_MS) { delivered -> if (trackDelivery) onDelivery(contact, delivered) }
            }
        }.awaitAll()
    }

    /** A fresh fix if one comes within a few seconds, else the newest known one (its age goes in the SMS). */
    private suspend fun currentFix(): SosFix? {
        val live = latestFix?.takeIf { location.ageMs(it) <= LIVE_FIX_MS }
            ?: latestNetworkFix?.takeIf { location.ageMs(it) <= LIVE_FIX_MS }
        val fix = live
            ?: (if (location.hasPermission()) withTimeoutOrNull(FIX_WAIT_MS) { location.current() } else null)
            ?: location.newestKnown()
            ?: latestFix
        return fix?.let { SosFix(it.latitude, it.longitude, it.accuracy, location.ageMs(it)) }
    }

    private fun callFirstReached() {
        val sos = _state.value as? SafetyState.Sos ?: return
        val first = sos.contacts.firstOrNull { it.reached }?.contact ?: return
        runCatching { startPhoneCall(appContext, first.number) }
            .onSuccess { emit("✓ Calling ${first.name}") }
            .onFailure { emit("✗ Couldn't call ${first.name}", failed = true) }
    }

    /** Speaks through the Agora agent if connected, else on the phone. */
    private suspend fun say(line: SafetyLine, interrupt: Boolean) {
        if (sayViaAgora(line, interrupt)) return
        speakOnPhone(line)
    }

    /** The siren first; the agent speaks at the same time (earphones), or the phone right after. */
    private suspend fun announce(line: SafetyLine) = coroutineScope {
        val viaAgora = async { sayViaAgora(line, interrupt = true) }
        alarm.siren(SIREN_MS)
        if (!viaAgora.await()) speakOnPhone(line)
    }

    private suspend fun sayViaAgora(line: SafetyLine, interrupt: Boolean): Boolean {
        val agent = voice ?: return false
        val text = line.forLanguage(language)
        return withTimeoutOrNull(AGORA_SAY_TIMEOUT_MS) { runCatching { agent.say(text, interrupt) }.getOrDefault(false) } == true
    }

    private suspend fun speakOnPhone(line: SafetyLine) {
        deafUntilMs = Long.MAX_VALUE
        try {
            if (!alarm.speak(line, language)) Log.w(TAG, "On-device TTS unavailable: \"${line.english}\" not spoken")
        } finally {
            deafUntilMs = now() + DEAF_AFTER_SPEAKING_MS
        }
    }

    private fun enterAlert() {
        // Bounded: countdown + all follow-ups + margin. Released as soon as nothing is scheduled.
        wakeLock.acquire(config.crashCountdownMs + config.followUpCount * config.followUpIntervalMs + 2 * 60_000L)
        SafetyAlertActivity.launch(appContext)
    }

    private fun setIdle() {
        _state.value = SafetyState.Idle
        sosLogId = null
        releaseWakeLock()
        if (!rideActive) stopRideService()
    }

    private fun stopRideService() {
        alarm.stopAlert()
        RideService.stop(appContext)
    }

    private fun releaseWakeLock() {
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    private fun updateSos(change: (SafetyState.Sos) -> SafetyState.Sos) {
        _state.update { if (it is SafetyState.Sos) change(it) else it }
    }

    private fun sosNote(why: String): String {
        val sos = _state.value as? SafetyState.Sos
        val who = sos?.contacts?.joinToString { "${it.contact.name}: ${it.status.name.lowercase()}" } ?: "-"
        val where = sosFix?.let { SosMessages.location(it) } ?: "no location"
        return "${sos?.trigger?.name ?: "?"} SOS ($why). $who. $where"
    }

    private fun emit(text: String, failed: Boolean = false) {
        Log.i(TAG, text)
        _actionLines.tryEmit(ActionLine(text, failed))
    }

    private fun now() = SystemClock.elapsedRealtime()

    companion object {
        private const val TAG = "Safety"
        private const val TICK_MS = 10_000L
        private const val GPS_LOST_MS = 30_000L
        private const val APPROXIMATE_FIX_MS = 60_000L
        private const val SIREN_MS = 2_000L
        private const val AGORA_SAY_TIMEOUT_MS = 4_000L
        private const val DEAF_AFTER_SPEAKING_MS = 1_500L
        private const val LIVE_FIX_MS = 10_000L
        private const val FIX_WAIT_MS = 5_000L
        private const val SMS_TIMEOUT_MS = 20_000L

        // safety_alerts.kind values (the seeded history uses hard_brake and fall_check).
        const val KIND_CRASH_DETECTED = "crash_detected"
        const val KIND_CRASH_CANCELLED = "crash_cancelled"
        const val KIND_MANUAL_SOS = "manual_sos"
        const val KIND_SOS_CANCELLED = "sos_cancelled"
        const val KIND_SOS_SENT = "sos_sent"
        const val KIND_SOS_FAILED = "sos_failed"
        const val KIND_SOS_ENDED = "sos_ended"
        const val KIND_FATIGUE = "fatigue_reminder"
    }
}
