package app.pillion.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Pillion's colour tokens, taken from the glass-orb design (design/pillion-glass-orb-handoff.html):
 * dark = A7 (black), light = A8 (pearl lavender). One accent, the violet-to-pink of the mic
 * button; red is for safety only.
 */
@Immutable
data class PillionColors(
    val isDark: Boolean,
    /** Flat page colour (sheets, window); the page itself draws [pillionBackground]. */
    val background: Color,
    /** Solid cards and sheets where glass would sit on something busy. */
    val surface: Color,
    /** Chips and small solid fills. */
    val surfaceHigh: Color,
    val hairline: Color,
    val ink: Color,
    val inkSecondary: Color,
    /** Violet: selected tab, the wordmark dot. Text on it is [onAccent]. */
    val accent: Color,
    val onAccent: Color,
    val alert: Color,
    /** Neutral chart bars (3:1 against cards). */
    val chartBar: Color,
    /** Text colours for done / failed / needs-a-check. */
    val success: Color,
    val danger: Color,
    val caution: Color,
    /** The crash countdown's full-screen red (white text). */
    val alertScreen: Color,
    /** Glass panels (.pill, .glass): translucent fill, 1 dp edge, and in light a soft two-layer shadow. */
    val glassFill: Color,
    val glassBorder: Color,
    val glassShadows: List<CssShadow>,
    /** The round icon chip in a panel (.ic). */
    val chipFill: Color,
    val chipIcon: Color,
    /** The mic button (.mic) and its shadow. */
    val micShadow: CssShadow,
    /** The glow behind the globe (.v5-orb::before). */
    val orbGlow: Color,
    /** The status pill's dot per voice state (.v7-dot) and its glow. */
    val dotIdle: Color,
    val dotListening: Color,
    val dotListeningGlow: Color,
    val dotThinking: Color,
    val dotThinkingGlow: Color,
    val dotSpeaking: Color,
    val dotSpeakingGlow: CssShadow,
    /** The page: flat black (A7) or three soft gradients (A8). */
    val backgroundLayers: BackgroundLayers,
)

/** A CSS box-shadow: offset down [y] dp, blur [blur] dp. */
@Immutable
data class CssShadow(val color: Color, val y: Float, val blur: Float)

/** CSS `radial-gradient(RX% RY% at CX% CY%, color 0%, transparent STOP%)` over a vertical linear gradient. */
@Immutable
data class BackgroundLayers(val top: Color, val bottom: Color, val spots: List<Spot>) {
    @Immutable
    data class Spot(val color: Color, val cx: Float, val cy: Float, val rx: Float, val ry: Float, val stop: Float)
}

/** The mic button's gradient (135°) and SOS button's radial red, the same in both themes. */
val MicGradientStart = Color(0xFF8B5CF6)
val MicGradientEnd = Color(0xFFEC4899)
/** Mic ring while listening: `0 0 0 (4 + level × 14)px rgba(236,72,153,.22)`. */
val MicRing = Color(0x38EC4899)
val SosCenter = Color(0xFFFF6B5E)
val SosMid = Color(0xFFD92D20)
val SosEdge = Color(0xFFA8160C)
val SosShadow = CssShadow(Color(0x59FF3C28), y = 10f, blur = 28f)

val LightColors = PillionColors(
    isDark = false,
    background = Color(0xFFF1EEF5),
    surface = Color(0xFFFFFFFF),
    surfaceHigh = Color(0xFFEAE6F1),
    hairline = Color(0xFFE2DDEA),
    ink = Color(0xFF1A1A24),
    inkSecondary = Color(0xFF5E5B6B),
    accent = Color(0xFF8B5CF6),
    onAccent = Color(0xFFFFFFFF),
    alert = Color(0xFFD92D20),
    chartBar = Color(0xFF8E8A9A),
    success = Color(0xFF17735C),
    danger = Color(0xFFB42318),
    caution = Color(0xFF8A5300),
    alertScreen = Color(0xFFB3140F),
    glassFill = Color(0x9EFFFFFF),
    glassBorder = Color(0xF2FFFFFF),
    glassShadows = listOf(
        CssShadow(Color(0x0F281E50), y = 1f, blur = 2f),
        CssShadow(Color(0x1A3C2878), y = 10f, blur = 30f),
    ),
    chipFill = Color(0x1A7C3AED),
    chipIcon = Color(0xFF6D28D9),
    micShadow = CssShadow(Color(0x598B5CF6), y = 10f, blur = 26f),
    orbGlow = Color(0x338B5CF6),
    dotIdle = Color(0xFF8E8E93),
    dotListening = Color(0xFFDB2777),
    dotListeningGlow = Color(0xE6EC4899),
    dotThinking = Color(0xFFA78BFA),
    dotThinkingGlow = Color(0xE6A78BFA),
    dotSpeaking = Color(0xFF1A1A24),
    dotSpeakingGlow = CssShadow(Color(0x591A1A24), y = 0f, blur = 8f),
    backgroundLayers = BackgroundLayers(
        top = Color(0xFFF5F3F8),
        bottom = Color(0xFFECE8F2),
        spots = listOf(
            BackgroundLayers.Spot(Color(0xFFFFFFFF), cx = 0.20f, cy = 0.15f, rx = 0.90f, ry = 0.55f, stop = 0.60f),
            BackgroundLayers.Spot(Color(0xFFE4DEF3), cx = 0.90f, cy = 0.95f, rx = 0.80f, ry = 0.50f, stop = 0.70f),
        ),
    ),
)

val DarkColors = PillionColors(
    isDark = true,
    background = Color(0xFF000000),
    surface = Color(0xFF1C1C1E),
    surfaceHigh = Color(0xFF2C2C2E),
    hairline = Color(0xFF2C2C2E),
    ink = Color(0xFFFFFFFF),
    inkSecondary = Color(0xFF8E8E93),
    accent = Color(0xFF8B5CF6),
    onAccent = Color(0xFFFFFFFF),
    alert = Color(0xFFFF453A),
    chartBar = Color(0xFF636366),
    success = Color(0xFF5FD4B4),
    danger = Color(0xFFFF8A7F),
    caution = Color(0xFFFFC94D),
    alertScreen = Color(0xFFB3140F),
    glassFill = Color(0x731C1C1E),
    glassBorder = Color(0x0AFFFFFF),
    glassShadows = emptyList(),
    chipFill = Color(0x0DFFFFFF),
    chipIcon = Color(0xFFF59E0B),
    micShadow = CssShadow(Color(0x598B5CF6), y = 10f, blur = 28f),
    orbGlow = Color(0x38A78BFA),
    dotIdle = Color(0xFF8E8E93),
    dotListening = Color(0xFFEC4899),
    dotListeningGlow = Color(0xE6EC4899),
    dotThinking = Color(0xFFA78BFA),
    dotThinkingGlow = Color(0xE6A78BFA),
    dotSpeaking = Color(0xFFFFFFFF),
    dotSpeakingGlow = CssShadow(Color(0xE6FFFFFF), y = 0f, blur = 10f),
    backgroundLayers = BackgroundLayers(top = Color(0xFF000000), bottom = Color(0xFF000000), spots = emptyList()),
)

/**
 * The same tokens as Material roles, so stock components (fields, switches, sheets) match. Stock
 * "primary" is ink (readable text buttons, switches); the violet is applied by hand.
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
