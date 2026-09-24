package app.pillion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Pillion's colour tokens: warm neutrals, one accent (marigold) and state colours. Every text pair
 * is at least 4.5:1 (measured); state colours fill the orb and dots, never carry text on their own.
 * Red is for safety only.
 */
@Immutable
data class PillionColors(
    val isDark: Boolean,
    val background: Color,
    /** Cards. */
    val surface: Color,
    /** Grey round icon buttons, chips, the status pill. */
    val surfaceHigh: Color,
    val hairline: Color,
    val ink: Color,
    val inkSecondary: Color,
    /** Marigold: the primary action and "listening". Text on it is [onAccent], never white. */
    val accent: Color,
    val onAccent: Color,
    val listening: Color,
    val thinking: Color,
    val speaking: Color,
    val offline: Color,
    val alert: Color,
    /** Second hue inside the orb per state, for depth. */
    val listeningGlow: Color,
    val thinkingGlow: Color,
    val speakingGlow: Color,
    val offlineGlow: Color,
    val alertGlow: Color,
    /** Text colours for done / failed / needs-a-check. */
    val success: Color,
    val danger: Color,
    val caution: Color,
    /** The crash countdown's full-screen red (white text). */
    val alertScreen: Color,
)

val LightColors = PillionColors(
    isDark = false,
    background = Color(0xFFF6F3EE),
    surface = Color(0xFFFFFDFA),
    surfaceHigh = Color(0xFFECE8E1),
    hairline = Color(0xFFE3DED5),
    ink = Color(0xFF17150F),
    inkSecondary = Color(0xFF5F5A51),
    accent = Color(0xFFF2A900),
    onAccent = Color(0xFF17150F),
    listening = Color(0xFFF2A900),
    thinking = Color(0xFF8C7AE6),
    speaking = Color(0xFF1FA88A),
    offline = Color(0xFF9C978E),
    alert = Color(0xFFD92D20),
    listeningGlow = Color(0xFFFF7A3D),
    thinkingGlow = Color(0xFFE08BD6),
    speakingGlow = Color(0xFF8EDB7A),
    offlineGlow = Color(0xFFD3CFC7),
    alertGlow = Color(0xFFFF8A3D),
    success = Color(0xFF17735C),
    danger = Color(0xFFB42318),
    caution = Color(0xFF8A5300),
    alertScreen = Color(0xFFB3140F),
)

val DarkColors = PillionColors(
    isDark = true,
    background = Color(0xFF111110),
    surface = Color(0xFF1B1A18),
    surfaceHigh = Color(0xFF272522),
    hairline = Color(0xFF33302C),
    ink = Color(0xFFF3F0EA),
    inkSecondary = Color(0xFFA9A49A),
    accent = Color(0xFFFFB81F),
    onAccent = Color(0xFF17150F),
    listening = Color(0xFFFFB81F),
    thinking = Color(0xFFA698FF),
    speaking = Color(0xFF3CCFAE),
    offline = Color(0xFF7D786F),
    alert = Color(0xFFFF5247),
    listeningGlow = Color(0xFFFF7A3D),
    thinkingGlow = Color(0xFFE08BD6),
    speakingGlow = Color(0xFF8EDB7A),
    offlineGlow = Color(0xFFB5B0A6),
    alertGlow = Color(0xFFFF8A3D),
    success = Color(0xFF5FD4B4),
    danger = Color(0xFFFF8A7F),
    caution = Color(0xFFFFC94D),
    alertScreen = Color(0xFFB3140F),
)

/**
 * The same tokens as Material roles, so stock components (fields, switches, sheets) match. Stock
 * "primary" is ink (black buttons, readable text buttons); marigold is applied by hand where it
 * means something (Start Ride, the selected tab, listening), since it is too light for text.
 */
fun PillionColors.toColorScheme(): ColorScheme {
    val scheme = if (isDark) darkColorScheme() else lightColorScheme()
    return scheme.copy(
        primary = ink,
        onPrimary = background,
        primaryContainer = accent,
        onPrimaryContainer = onAccent,
        secondary = ink,
        onSecondary = background,
        secondaryContainer = surfaceHigh,
        onSecondaryContainer = ink,
        tertiary = caution,
        onTertiary = background,
        background = background,
        onBackground = ink,
        surface = background,
        onSurface = ink,
        surfaceVariant = surfaceHigh,
        onSurfaceVariant = inkSecondary,
        surfaceTint = Color.Transparent,
        surfaceBright = surface,
        surfaceDim = background,
        surfaceContainerLowest = surface,
        surfaceContainerLow = surface,
        surfaceContainer = surface,
        surfaceContainerHigh = surface,
        surfaceContainerHighest = surfaceHigh,
        inverseSurface = ink,
        inverseOnSurface = background,
        inversePrimary = accent,
        outline = inkSecondary,
        outlineVariant = hairline,
        error = danger,
        onError = background,
        errorContainer = surfaceHigh,
        onErrorContainer = ink,
        scrim = Color.Black,
    )
}
