package app.pillion.safety

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The phone's own alarm for a safety alert, independent of Agora and the internet: a two-tone siren
 * and beeps on the alarm stream (loudspeaker, even with earphones in), vibration, and Android's
 * on-device text-to-speech in Hindi or English. Main thread.
 */
class SafetyAlarm(context: Context) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val alarmAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var siren: AudioTrack? = null
    private var tones: ToneGenerator? = null
    private var savedAlarmVolume: Int? = null

    /** False until the TTS engine is up, or when it has no Hindi voice installed. */
    var hindiVoice = false
        private set

    /** True once the TTS engine is up and it has no Hindi voice (alerts then speak English). */
    val hindiVoiceMissing: Boolean get() = ttsReady && !hindiVoice

    /** Starts the on-device TTS engine early (it takes a moment). Safe to call again. */
    fun prepare() {
        if (tts != null) return
        connect()
    }

    private fun connect(onInit: (Boolean) -> Unit = {}) {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            val engine = tts ?: return@TextToSpeech
            if (ttsReady) {
                engine.setAudioAttributes(alarmAttributes)
                hindiVoice = engine.isLanguageAvailable(HINDI) >= TextToSpeech.LANG_AVAILABLE
            }
            Log.i(TAG, "On-device TTS ready=$ttsReady hindi=$hindiVoice")
            onInit(ttsReady)
        }
    }

    /** A fresh binding starts the engine's process now; Android's own restart of a killed engine comes ~13 s later. */
    private suspend fun reconnect(): Boolean {
        tts?.shutdown()
        tts = null
        ttsReady = false
        return withTimeoutOrNull(4_000) {
            suspendCancellableCoroutine { continuation ->
                connect { ready -> if (continuation.isActive) continuation.resume(ready) }
            }
        } ?: false
    }

    fun release() {
        stopAlert()
        tts?.shutdown()
        tts = null
        ttsReady = false
    }

    /** Alarm volume to full and repeating vibration, until [stopAlert]. */
    fun startAlert() {
        if (savedAlarmVolume == null) {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            // Throws in some Do Not Disturb setups; the alert still runs at the current volume.
            runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0) }
        }
        runCatching {
            @Suppress("DEPRECATION")
            vibrator().vibrate(VibrationEffect.createWaveform(longArrayOf(0, 700, 300), 0), alarmAttributes)
        }.onFailure { Log.w(TAG, "No vibration", it) }
    }

    fun stopAlert() {
        runCatching { siren?.stop() }
        siren?.release()
        siren = null
        tones?.release()
        tones = null
        runCatching { vibrator().cancel() }
        savedAlarmVolume?.let { volume -> runCatching { audio.setStreamVolume(AudioManager.STREAM_ALARM, volume, 0) } }
        savedAlarmVolume = null
    }

    /** Plays the two-tone siren for [ms]. */
    suspend fun siren(ms: Long) {
        val track = siren ?: buildSiren()?.also { siren = it } ?: return
        runCatching {
            track.reloadStaticData()
            track.play()
        }.onFailure { Log.w(TAG, "Siren failed", it) }
        try {
            delay(ms)
        } finally {
            runCatching { track.pause() }
        }
    }

    /** A short countdown tick. */
    fun beep() {
        val generator = tones ?: runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 100) }.getOrNull()?.also { tones = it }
        generator?.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
    }

    /**
     * Speaks [line] on the phone in [language] (Hindi falls back to English without a Hindi voice)
     * and returns when done. False if on-device TTS isn't available.
     */
    suspend fun speak(line: SafetyLine, language: RiderLanguage): Boolean {
        val started = System.currentTimeMillis()
        if (speakOnce(line, language)) return true
        // Failed at once: the engine's process was killed in the background (ColorOS does this to an
        // idle engine; seen at the start of a ring on the Realme). Reconnect and try once more.
        if (System.currentTimeMillis() - started > 1_000) return false
        Log.w(TAG, "On-device TTS not bound: reconnecting")
        return reconnect() && speakOnce(line, language)
    }

    private suspend fun speakOnce(line: SafetyLine, language: RiderLanguage): Boolean {
        val engine = tts?.takeIf { ttsReady } ?: return false
        val parts = when {
            language == RiderLanguage.English || !hindiVoice -> listOf(line.english to ENGLISH)
            language == RiderLanguage.Hindi -> listOf(line.hindi to HINDI)
            else -> listOf(line.hindi to HINDI, line.english to ENGLISH)
        }
        for ((text, locale) in parts) {
            engine.language = locale
            val done = withTimeoutOrNull(12_000) { speakAndWait(engine, text) } ?: false
            if (!done) return false
        }
        return true
    }

    private suspend fun speakAndWait(engine: TextToSpeech, text: String): Boolean = suspendCancellableCoroutine { continuation ->
        val id = UUID.randomUUID().toString()
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) {
                if (utteranceId == id && continuation.isActive) continuation.resume(true)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (utteranceId == id && continuation.isActive) continuation.resume(false)
            }
        })
        continuation.invokeOnCancellation { engine.stop() }
        if (engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) != TextToSpeech.SUCCESS && continuation.isActive) {
            continuation.resume(false)
        }
    }

    // One second of a two-tone siren (960 Hz / 770 Hz), looped.
    private fun buildSiren(): AudioTrack? = runCatching {
        val rate = 22_050
        val samples = ShortArray(rate) { i ->
            val frequency = if (i < rate / 2) 960.0 else 770.0
            // Clipped sine: close to a square wave, louder than a pure tone at the same volume.
            val value = (sin(2 * PI * frequency * i / rate) * 1.6).coerceIn(-1.0, 1.0)
            (value * Short.MAX_VALUE * 0.9).toInt().toShort()
        }
        AudioTrack.Builder()
            .setAudioAttributes(alarmAttributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(samples.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
            .apply {
                write(samples, 0, samples.size)
                setLoopPoints(0, samples.size, -1)
            }
    }.onFailure { Log.w(TAG, "Siren unavailable", it) }.getOrNull()

    private fun vibrator(): Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            appContext.getSystemService(Vibrator::class.java)
        }

    private companion object {
        const val TAG = "SafetyAlarm"
        val HINDI: Locale = Locale.forLanguageTag("hi-IN")
        val ENGLISH: Locale = Locale.forLanguageTag("en-IN")
    }
}
