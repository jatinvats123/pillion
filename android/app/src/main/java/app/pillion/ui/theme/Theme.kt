package app.pillion.ui.theme

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

private val LocalPillionColors = staticCompositionLocalOf { LightColors }
private val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun PillionTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val colors = if (dark) DarkColors else LightColors
    val context = LocalContext.current
    val reducedMotion = remember(context) { animationsOff(context) }
    CompositionLocalProvider(LocalPillionColors provides colors, LocalReducedMotion provides reducedMotion) {
        MaterialTheme(
            colorScheme = remember(colors) { colors.toColorScheme() },
            typography = PillionTypography,
            shapes = PillionShapes,
            content = content,
        )
    }
}

/** "Remove animations" (Accessibility) sets the animator scale to 0. */
fun animationsOff(context: Context): Boolean =
    Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

object Pillion {
    val colors: PillionColors
        @Composable @ReadOnlyComposable get() = LocalPillionColors.current

    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReducedMotion.current
}
