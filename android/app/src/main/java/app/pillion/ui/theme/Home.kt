package app.pillion.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.pillion.R

/**
 * The home screen (Ride tab before a ride) from design/pillion-home-handoff.html: light = C11
 * (`phone premium orderglass refined polish c8card`), dark = C13 (the same + `darkmode softglow`).
 * Every value is the one the CSS rules for those classes resolve to; the comments name the rule.
 */
@Immutable
data class HomeColors(
    val isDark: Boolean,
    /** `.phone` / `.phone.darkmode.softglow` background: two elliptical spots over a flat colour. */
    val page: BackgroundLayers,
    /** `.refined{color}` / `.darkmode .hello`. */
    val ink: Color,
    /** `.refined .sub` / `.darkmode .sub`. */
    val sub: Color,
    /** `.bar` / `.darkmode .bar`. */
    val bar: Color,
    /** `.capsule` (+ `.polish .capsule`) / `.darkmode .capsule`; `.refined .capbtn` / `.darkmode .capbtn`. */
    val capsuleFill: Color,
    val capsuleBorder: Color,
    val capsuleShadows: List<CssShadow>,
    val capsuleIcon: Color,
    /** `.refined .sos.pill` / `.darkmode .sos.pill`. */
    val sosFill: Color,
    val sosBorder: Color,
    val sosShadows: List<CssShadow>,
    /** `.refined .fixchip` / `.darkmode .fixchip`. */
    val fixFill: Color,
    val fixBorder: Color,
    val fixInk: Color,
    /** `.orderglass::before` (violet) and `::after` (blue), with their `filter: blur()` in dp. */
    val glowViolet: Color,
    val glowBlue: Color,
    val glowBlur: Float,
    /** `.refined .card.glasscard` / `.darkmode .card.glasscard`: 140° gradient, edge, inset top line, shadows. */
    val cardTop: Color,
    val cardBottom: Color,
    val cardBorder: Color,
    val cardHighlight: Color,
    val cardShadows: List<CssShadow>,
    /** "Current order" (`.polish.c8card .glasscard .chip`), the name, the meta row and its icons. */
    val label: Color,
    val name: Color,
    val meta: Color,
    val metaIcon: Color,
    /** `.polish.c8card .glasscard .note`. */
    val noteFill: Color,
    val noteBorder: Color,
    val noteInk: Color,
    /** `.refined .glasscard .soft` / `.polish.c8card.darkmode .glasscard .soft`. */
    val softFill: Color,
    val softBorder: Color,
    val softInk: Color,
    val softHighlight: Color,
    val softShadows: List<CssShadow>,
    /** `.refined .glasscard .link`, `.refined .glasscard .hint`. */
    val link: Color,
    val hint: Color,
    /** `.sheet.clear`: transparent → [sheetMid] at 26 % → [sheetEnd]. */
    val sheetMid: Color,
    val sheetEnd: Color,
    /** `.polish .start.big` / `.darkmode .start.big`. */
    val startShadows: List<CssShadow>,
    /** `.refined .tabs.float` / `.darkmode .tabs.float`, `.refined .tab(.on)` / `.darkmode .tab(.on)`. */
    val tabsFill: Color,
    val tabsBorder: Color,
    val tabsShadows: List<CssShadow>,
    val tabInk: Color,
    val tabOnFill: Color,
    val tabOnInk: Color,
    // Ride done (design/pillion-ride-done-handoff.html, `.rd` and its `.darkmode` rules).
    /** `.rows` / `.r` borders. */
    val rule: Color,
    /** `.dur b`, `.r .v` (dark: white). */
    val strong: Color,
    /** Text in a glass card without a colour of its own (`.darkmode .card.glasscard{color}`). */
    val cardInk: Color,
    /** `.dur span`, `.r .k`, `.rnote`, `.rsafe .grow small`. */
    val muted: Color,
    /** `.rnote svg`. */
    val noteIcon: Color,
    /** `.okic`: the green circle behind the shield-check. */
    val okFill: Color,
    val okIcon: Color,
    /** `.shieldic` (light; dark uses `.darkmode .fixchip`'s red): a ride with safety events. */
    val alertFill: Color,
    val alertIcon: Color,
)

/** `.polish .start.big`: linear-gradient(100deg, #8B5CF6 0%, #A454E0 55%, #C24DB4 100%). */
val HomeStartGradient = listOf(0f to Color(0xFF8B5CF6), 0.55f to Color(0xFFA454E0), 1f to Color(0xFFC24DB4))

val HomeLight = HomeColors(
    isDark = false,
    page = BackgroundLayers(
        top = Color(0xFFF6F5F9),
        bottom = Color(0xFFF6F5F9),
        spots = listOf(
            BackgroundLayers.Spot(Color(0xFFFFFFFF), cx = 0.15f, cy = 0.10f, rx = 0.90f, ry = 0.50f, stop = 0.60f),
            BackgroundLayers.Spot(Color(0xFFE7E1F4), cx = 0.90f, cy = 1.00f, rx = 0.80f, ry = 0.45f, stop = 0.70f),
        ),
    ),
    ink = Color(0xFF15131C),
    sub = Color(0xFF4A4757),
    bar = Color(0xC7F6F5F9),
    capsuleFill = Color(0xD9FFFFFF),
    capsuleBorder = Color(0xF2FFFFFF),
    capsuleShadows = listOf(CssShadow(Color(0x0F1E143C), 1f, 2f), CssShadow(Color(0x141E143C), 6f, 16f)),
    capsuleIcon = Color(0xFF24212E),
    sosFill = Color(0xFFD92D20),
    sosBorder = Color(0xFFB42318),
    sosShadows = listOf(CssShadow(Color(0x40B42318), 2f, 6f)),
    fixFill = Color(0xFFFDECEC),
    fixBorder = Color(0xFFF1B8B8),
    fixInk = Color(0xFF8E1B1B),
    glowViolet = Color(0x8C8B5CF6),
    glowBlue = Color(0x7360A5FA),
    glowBlur = 8f,
    cardTop = Color(0xBDFFFFFF),
    cardBottom = Color(0x70FFFFFF),
    cardBorder = Color(0xE6FFFFFF),
    cardHighlight = Color(0xF2FFFFFF),
    cardShadows = listOf(CssShadow(Color(0x1F3C288C), 16f, 36f), CssShadow(Color(0x143C288C), 1f, 3f)),
    label = Color(0xFF15131C),
    name = Color(0xFF15131C),
    meta = Color(0xFF24212E),
    metaIcon = Color(0xFF4A4757),
    noteFill = Color(0x8CFFFFFF),
    noteBorder = Color(0xE6FFFFFF),
    noteInk = Color(0xFF3A3746),
    softFill = Color(0xB8FFFFFF),
    softBorder = Color(0xF2FFFFFF),
    softInk = Color(0xFF15131C),
    softHighlight = Color(0xFFFFFFFF),
    softShadows = listOf(CssShadow(Color(0x143C288C), 2f, 8f)),
    link = Color(0xFF5B21B6),
    hint = Color(0xFF4A4757),
    sheetMid = Color(0xEBF6F5F9),
    sheetEnd = Color(0xFFF3F1F7),
    startShadows = listOf(CssShadow(Color(0x297C3AED), 4f, 12f), CssShadow(Color(0x1F7C3AED), 1f, 2f)),
    tabsFill = Color(0xF7FFFFFF),
    tabsBorder = Color(0x0F15131C),
    tabsShadows = listOf(CssShadow(Color(0x0F1E143C), 1f, 2f), CssShadow(Color(0x1A1E143C), 10f, 26f)),
    tabInk = Color(0xFF3A3746),
    tabOnFill = Color(0xFFEDE5FF),
    tabOnInk = Color(0xFF4C1D95),
    rule = Color(0x1415131C),
    strong = Color(0xFF15131C),
    cardInk = Color(0xFF15131C),
    muted = Color(0xFF4A4757),
    noteIcon = Color(0xFF6B6878),
    okFill = Color(0xFFE3F5EA),
    okIcon = Color(0xFF157F3D),
    alertFill = Color(0xFFFDECEC),
    alertIcon = Color(0xFFC62828),
)

val HomeDark = HomeColors(
    isDark = true,
    page = BackgroundLayers(
        top = Color(0xFF0B0A10),
        bottom = Color(0xFF0B0A10),
        spots = listOf(
            BackgroundLayers.Spot(Color(0x147C3AED), cx = 0.10f, cy = 0.05f, rx = 0.90f, ry = 0.50f, stop = 0.60f),
            BackgroundLayers.Spot(Color(0x144338CA), cx = 0.95f, cy = 1.00f, rx = 0.80f, ry = 0.45f, stop = 0.70f),
        ),
    ),
    ink = Color(0xFFF4F2F8),
    sub = Color(0xFFA9A6B5),
    bar = Color(0xCC0B0A10),
    capsuleFill = Color(0x0FFFFFFF),
    capsuleBorder = Color(0x17FFFFFF),
    capsuleShadows = emptyList(),
    capsuleIcon = Color(0xFFE9E7F0),
    sosFill = Color(0xFFD92D20),
    sosBorder = Color(0xFFF04438),
    sosShadows = listOf(CssShadow(Color(0x66000000), 2f, 8f)),
    fixFill = Color(0x1FEF4444),
    fixBorder = Color(0x61EF4444),
    fixInk = Color(0xFFFCA5A5),
    glowViolet = Color(0x1A7C3AED),
    glowBlue = Color(0x0F3B82F6),
    glowBlur = 18f,
    cardTop = Color(0x1AFFFFFF),
    cardBottom = Color(0x0AFFFFFF),
    cardBorder = Color(0x1FFFFFFF),
    cardHighlight = Color(0x24FFFFFF),
    cardShadows = listOf(CssShadow(Color(0x73000000), 18f, 40f)),
    label = Color(0xFFF4F2F8),
    name = Color(0xFFFFFFFF),
    meta = Color(0xFFDAD7E3),
    metaIcon = Color(0xFFA9A6B5),
    noteFill = Color(0x0DFFFFFF),
    noteBorder = Color(0x17FFFFFF),
    noteInk = Color(0xFFCBC8D6),
    softFill = Color(0x14FFFFFF),
    softBorder = Color(0x21FFFFFF),
    softInk = Color(0xFFFFFFFF),
    softHighlight = Color(0x14FFFFFF),
    softShadows = emptyList(),
    link = Color(0xFFC4B5FD),
    hint = Color(0xFFA09DAE),
    sheetMid = Color(0xEB0B0A10),
    sheetEnd = Color(0xFF0B0A10),
    startShadows = listOf(CssShadow(Color(0x477C3AED), 6f, 18f)),
    tabsFill = Color(0xF01A1822),
    tabsBorder = Color(0x14FFFFFF),
    tabsShadows = listOf(CssShadow(Color(0x66000000), 10f, 26f)),
    tabInk = Color(0xFFB8B5C4),
    tabOnFill = Color(0x388B5CF6),
    tabOnInk = Color(0xFFDDD6FE),
    rule = Color(0x17FFFFFF),
    strong = Color(0xFFFFFFFF),
    cardInk = Color(0xFFF4F2F8),
    muted = Color(0xFFA9A6B5),
    noteIcon = Color(0xFF8E8A9C),
    okFill = Color(0x2422C55E),
    okIcon = Color(0xFF4ADE80),
    alertFill = Color(0x1FEF4444),
    alertIcon = Color(0xFFFCA5A5),
)

object Home {
    /** Follows the app's theme (Settings, the moon/sun button, night during a ride). */
    val colors: HomeColors
        @Composable @ReadOnlyComposable get() = if (Pillion.colors.isDark) HomeDark else HomeLight
}

/** `.phone.premium`: Instrument Sans for everything, Instrument Serif for the wordmark and the greeting. */
@OptIn(ExperimentalTextApi::class)
val InstrumentSans = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.instrument_sans_variable, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    }
)
val InstrumentSerif = FontFamily(Font(R.font.instrument_serif_regular, FontWeight.Normal))

/** The home screen's type, from the same rules (sizes in sp = the design's px). */
object HomeType {
    private fun sans(size: Float, weight: FontWeight, lineHeight: Float? = null, tracking: Float = 0f) = TextStyle(
        fontFamily = InstrumentSans,
        fontSize = size.sp,
        fontWeight = weight,
        lineHeight = lineHeight?.sp ?: TextStyle.Default.lineHeight,
        letterSpacing = tracking.em,
    )

    /** `.phone.premium .brand`: 27, serif, -0.01em. */
    val brand = TextStyle(fontFamily = InstrumentSerif, fontSize = 27.sp, letterSpacing = (-0.01).em)
    /** `.phone.premium .hello`: 38 / 1.05, serif, -0.015em. */
    val hello = TextStyle(fontFamily = InstrumentSerif, fontSize = 38.sp, lineHeight = 39.9.sp, letterSpacing = (-0.015).em)
    /** `.refined .sub`: 15. */
    val sub = sans(15f, FontWeight.Normal)
    /** `.polish .fixchip`: 13, 600. */
    val fix = sans(13f, FontWeight.SemiBold)
    /** `.refined .sos.pill`: 15, 700, 0.06em. */
    val sos = sans(15f, FontWeight.Bold, tracking = 0.06f)
    /** `.refined .glasscard .chip`: 13, 700, 0.08em, upper case. */
    val label = sans(13f, FontWeight.Bold, tracking = 0.08f)
    /** `.refined .glasscard .oname`: 32 / 1.1, 700, -0.02em. */
    val name = sans(32f, FontWeight.Bold, lineHeight = 35.2f, tracking = -0.02f)
    /** `.refined .glasscard .meta`: 16, 500. */
    val meta = sans(16f, FontWeight.Medium)
    /** `.polish.c8card .glasscard .note`: 15 / 1.4. */
    val note = sans(15f, FontWeight.Normal, lineHeight = 21f)
    /** `.polish.c8card .glasscard .soft` / `.link`: 16, 600, -0.005em. */
    val button = sans(16f, FontWeight.SemiBold, tracking = -0.005f)
    /** `.refined .glasscard .hint`: 14 / 1.45. */
    val hint = sans(14f, FontWeight.Normal, lineHeight = 20.3f)
    /** `.polish .start.big`: 19, 600, -0.005em. */
    val start = sans(19f, FontWeight.SemiBold, tracking = -0.005f)
    /** `.refined .tab`: 14, 600, -0.005em. */
    val tab = sans(14f, FontWeight.SemiBold, tracking = -0.005f)
    /** Ride done `.dur b`: 30 / 1.1, 700, -0.02em. */
    val duration = sans(30f, FontWeight.Bold, lineHeight = 33f, tracking = -0.02f)
    /** Ride done `.r .v` and `.rsafe .grow b`: 16, 600. */
    val value = sans(16f, FontWeight.SemiBold)
    /** Ride done `.rsafe .grow small`: 14. */
    val small = sans(14f, FontWeight.Normal)
}
