package app.pillion.safety

import android.content.Context
import androidx.core.content.edit
import app.pillion.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * DEBUG BUILDS ONLY — Phase 3 test switches, shown on the ride screen's debug card. Always off in
 * release builds. Demo mode and the 2-minute fatigue reminder apply from the next ride.
 */
class SafetyDebug(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("safety_debug", Context.MODE_PRIVATE)

    private val _demoMode = MutableStateFlow(BuildConfig.DEBUG && prefs.getBoolean(KEY_DEMO, false))
    /** No speed gate and a 2.5 g impact ([CrashConfig.DEMO]): drop the phone on a mattress to test. */
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private val _fatigueInTwoMinutes = MutableStateFlow(BuildConfig.DEBUG && prefs.getBoolean(KEY_FATIGUE, false))
    val fatigueInTwoMinutes: StateFlow<Boolean> = _fatigueInTwoMinutes.asStateFlow()

    private val _recording = MutableStateFlow(false)
    /** Record accelerometer/gyroscope/GPS speed to CSV during rides (Android/data/app.pillion/files/traces). */
    val recording: StateFlow<Boolean> = _recording.asStateFlow()

    fun setDemoMode(on: Boolean) {
        _demoMode.value = BuildConfig.DEBUG && on
        prefs.edit { putBoolean(KEY_DEMO, on) }
    }

    fun setFatigueInTwoMinutes(on: Boolean) {
        _fatigueInTwoMinutes.value = BuildConfig.DEBUG && on
        prefs.edit { putBoolean(KEY_FATIGUE, on) }
    }

    fun setRecording(on: Boolean) {
        _recording.value = BuildConfig.DEBUG && on
    }

    private companion object {
        const val KEY_DEMO = "demo_mode"
        const val KEY_FATIGUE = "fatigue_2_min"
    }
}
