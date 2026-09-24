package app.pillion.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThemeMode { System, Light, Dark }

/** How the app looks for this rider; stored only on the phone. */
class UiPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("ui", Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(
        prefs.getString(KEY_THEME, null)?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.System
    )
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    fun setThemeMode(mode: ThemeMode) {
        prefs.edit { putString(KEY_THEME, mode.name) }
        _themeMode.value = mode
    }

    private val _subtitles = MutableStateFlow(prefs.getBoolean(KEY_SUBTITLES, true))
    /** English subtitles under Hindi lines (each one is a Sarvam call through the backend). */
    val subtitles: StateFlow<Boolean> = _subtitles.asStateFlow()

    fun setSubtitles(on: Boolean) {
        prefs.edit { putBoolean(KEY_SUBTITLES, on) }
        _subtitles.value = on
    }

    private val _firstRunDone = MutableStateFlow(prefs.getBoolean(KEY_FIRST_RUN_DONE, false))
    /** The one-screen welcome (name, emergency contact) was finished or skipped. */
    val firstRunDone: StateFlow<Boolean> = _firstRunDone.asStateFlow()

    fun setFirstRunDone(done: Boolean) {
        prefs.edit { putBoolean(KEY_FIRST_RUN_DONE, done) }
        _firstRunDone.value = done
    }

    private companion object {
        const val KEY_THEME = "theme"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_FIRST_RUN_DONE = "first_run_done"
    }
}
