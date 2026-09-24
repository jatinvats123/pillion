package app.pillion.ui.theme

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.material3.Typography
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.pillion.R
import java.nio.ByteBuffer

/**
 * Inter for Latin, Noto Sans Devanagari for Hindi, bundled (res/font, variable, OFL) so Hinglish
 * lines read as one family offline. Android 10+ joins them per character with a custom fallback
 * chain (Inter first); Android 8–9 get Inter with the system's Devanagari font.
 *
 * Inter's optical size axis: 14 for text, 32 for big display text (tighter, crisper).
 */
private val TextWeights = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)
private val DisplayWeights = listOf(FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.Black)

val PillionFont: FontFamily = family(TextWeights, opticalSize = 14f)
val PillionDisplayFont: FontFamily = family(DisplayWeights, opticalSize = 32f)

// Resource fonts with variation settings are still marked experimental; stable in practice since Compose 1.2.
@OptIn(ExperimentalTextApi::class)
private fun family(weights: List<FontWeight>, opticalSize: Float): FontFamily = FontFamily(
    weights.map { weight ->
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            InterWithDevanagari(weight, opticalSize)
        } else {
            Font(
                resId = R.font.inter_variable,
                weight = weight,
                variationSettings = FontVariation.Settings(
                    FontVariation.weight(weight.weight),
                    FontVariation.Setting("opsz", opticalSize),
                ),
            )
        }
    }
)

/**
 * Reads the font files (1.5 MB) ahead of the first frame; call off the main thread at app start.
 * Measured on the emulator: the first text otherwise waits ~470 ms for them.
 */
fun preloadFonts(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) FallbackLoader.preload(context)
}

@RequiresApi(Build.VERSION_CODES.Q)
private data class InterWithDevanagari(override val weight: FontWeight, val opticalSize: Float) :
    AndroidFont(FontLoadingStrategy.Blocking, FallbackLoader, FontVariation.Settings()) {
    override val style: FontStyle = FontStyle.Normal
}

@RequiresApi(Build.VERSION_CODES.Q)
private object FallbackLoader : AndroidFont.TypefaceLoader {
    // Each font file is read once and shared by every weight.
    private val files = HashMap<Int, ByteBuffer>()

    @Synchronized
    override fun loadBlocking(context: Context, font: AndroidFont): Typeface {
        font as InterWithDevanagari
        val weight = font.weight.weight
        val latin = android.graphics.fonts.Font.Builder(file(context, R.font.inter_variable))
            .setWeight(weight)
            .setFontVariationSettings("'wght' $weight, 'opsz' ${font.opticalSize}")
            .build()
        val hindi = android.graphics.fonts.Font.Builder(file(context, R.font.noto_sans_devanagari_variable))
            .setWeight(weight)
            .setFontVariationSettings("'wght' $weight")
            .build()
        return Typeface.CustomFallbackBuilder(android.graphics.fonts.FontFamily.Builder(latin).build())
            .addCustomFallback(android.graphics.fonts.FontFamily.Builder(hindi).build())
            .setStyle(android.graphics.fonts.FontStyle(weight, android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT))
            .build()
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface = loadBlocking(context, font)

    @Synchronized
    fun preload(context: Context) {
        file(context, R.font.inter_variable)
        file(context, R.font.noto_sans_devanagari_variable)
    }

    private fun file(context: Context, resId: Int): ByteBuffer = files.getOrPut(resId) {
        val bytes = context.resources.openRawResource(resId).use { it.readBytes() }
        ByteBuffer.allocateDirect(bytes.size).put(bytes).apply { flip() }
    }.duplicate()
}

// Line heights ≈ 1.4× so Devanagari vowel signs above and below never touch the next line.
// No letter spacing anywhere: tracking breaks the Devanagari headline bar.
private fun style(family: FontFamily, size: TextUnit, lineHeight: TextUnit, weight: FontWeight) =
    TextStyle(fontFamily = family, fontSize = size, lineHeight = lineHeight, fontWeight = weight, letterSpacing = 0.sp)

val PillionTypography = Typography(
    displayLarge = style(PillionDisplayFont, 57.sp, 64.sp, FontWeight.Bold),
    displayMedium = style(PillionDisplayFont, 45.sp, 52.sp, FontWeight.Bold),
    displaySmall = style(PillionDisplayFont, 36.sp, 46.sp, FontWeight.SemiBold),
    headlineLarge = style(PillionDisplayFont, 32.sp, 42.sp, FontWeight.Bold),
    headlineMedium = style(PillionDisplayFont, 28.sp, 38.sp, FontWeight.SemiBold),
    headlineSmall = style(PillionDisplayFont, 24.sp, 34.sp, FontWeight.SemiBold),
    titleLarge = style(PillionFont, 22.sp, 30.sp, FontWeight.SemiBold),
    titleMedium = style(PillionFont, 18.sp, 26.sp, FontWeight.SemiBold),
    titleSmall = style(PillionFont, 16.sp, 24.sp, FontWeight.SemiBold),
    bodyLarge = style(PillionFont, 18.sp, 28.sp, FontWeight.Normal),
    bodyMedium = style(PillionFont, 16.sp, 24.sp, FontWeight.Normal),
    bodySmall = style(PillionFont, 14.sp, 20.sp, FontWeight.Normal),
    labelLarge = style(PillionFont, 16.sp, 22.sp, FontWeight.SemiBold),
    labelMedium = style(PillionFont, 14.sp, 20.sp, FontWeight.Medium),
    labelSmall = style(PillionFont, 12.sp, 16.sp, FontWeight.Medium),
)

/** The ride screen, read at arm's length on a moving bike. */
object RideType {
    /** The newest line of the conversation. */
    val latest = style(PillionDisplayFont, 34.sp, 48.sp, FontWeight.SemiBold)
    /** Older lines and the English subtitle of the newest. */
    val secondary = style(PillionFont, 24.sp, 34.sp, FontWeight.Normal)
    /** Ride controls' labels. */
    val control = style(PillionFont, 22.sp, 28.sp, FontWeight.SemiBold)
    /** Money, times and counts line up. */
    val tabular = TextStyle(fontFeatureSettings = "tnum")
}
