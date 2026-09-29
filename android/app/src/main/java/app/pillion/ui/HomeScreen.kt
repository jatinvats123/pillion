package app.pillion.ui

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pillion.R
import app.pillion.data.EarningsDb
import app.pillion.data.Order
import app.pillion.order.maskPhone
import app.pillion.order.maskedPhoneDigits
import app.pillion.ui.components.cssLinearGradient
import app.pillion.ui.components.cssShadows
import app.pillion.ui.components.drawBackgroundLayers
import app.pillion.ui.components.insetTopHighlight
import app.pillion.ui.theme.CssShadow
import app.pillion.ui.theme.Home
import app.pillion.ui.theme.HomeColors
import app.pillion.ui.theme.HomeStartGradient
import app.pillion.ui.theme.HomeType
import app.pillion.ui.theme.Pillion
import java.util.Locale

/**
 * The Ride tab before a ride (design/pillion-home-handoff.html: C11 light, C13 dark): header with
 * logo, theme / debug / settings and SOS; the greeting with today's line and the safety fixes pill;
 * the glass order card; Start ride over a fade into the tab bar. Only the look is new: every
 * control calls what it called before.
 */
@Composable
fun HomeScreen(
    riderName: String,
    today: EarningsDb.DayTotal?,
    /** Safety setup items still to fix (the pill opens them); 0 hides the pill. */
    setupIssueCount: Int,
    onOpenSetup: () -> Unit,
    /** A running safety alert's banner, above the order card. */
    alertBanner: (@Composable () -> Unit)?,
    notices: List<@Composable () -> Unit>,
    orderCard: @Composable () -> Unit,
    sosEnabled: Boolean,
    onSos: () -> Unit,
    onStartRide: () -> Unit,
    onToggleTheme: () -> Unit,
    onOpenDebug: (() -> Unit)?,
    onOpenSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        HomeBar(sosEnabled, onSos, onToggleTheme, onOpenDebug, onOpenSettings)
        // .scroll: padding 8 20 24, gap 16.
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Greeting(riderName, today, setupIssueCount, onOpenSetup)
            alertBanner?.invoke()
            notices.forEach { it() }
            orderCard()
        }
        HomeActionSheet(R.drawable.ic_home_bike, stringResource(R.string.start_ride), onStartRide)
    }
}

/**
 * `.bar`: logo and wordmark; the icon capsule and the SOS pill. Its colour runs up under the status
 * bar. Shared by the home and Ride done screens.
 */
@Composable
internal fun HomeBar(sosEnabled: Boolean, onSos: () -> Unit, onToggleTheme: () -> Unit, onOpenDebug: (() -> Unit)?, onOpenSettings: () -> Unit) {
    val c = Home.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.bar)
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .semantics(mergeDescendants = true) { heading() },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // .polish .brand svg: 9 px corners, 0 2px 6px rgba(21,19,28,.18).
            Image(
                painterResource(R.drawable.ic_pillion_logo),
                contentDescription = null,
                modifier = Modifier
                    .size(30.dp)
                    .cssShadows(RoundedCornerShape(9.dp), listOf(CssShadow(Color(0x2E15131C), 2f, 6f)))
                    .clip(RoundedCornerShape(9.dp)),
            )
            // 27 sp as designed (390 dp wide); steps down only where a narrow phone can't fit the row.
            BasicText(
                stringResource(R.string.app_name),
                style = HomeType.brand.copy(color = c.ink),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 20.sp, maxFontSize = 27.sp, stepSize = 1.sp),
            )
        }
        // .capsule (.polish: padding 2, gap 0) of 44 dp .capbtn, 20 dp icons.
        Row(
            Modifier
                .cssShadows(CircleShape, c.capsuleShadows)
                .background(c.capsuleFill, CircleShape)
                .border(1.dp, c.capsuleBorder, CircleShape)
                .padding(2.dp),
        ) {
            CapsuleButton(
                if (c.isDark) R.drawable.ic_home_sun else R.drawable.ic_home_moon,
                stringResource(if (c.isDark) R.string.theme_to_light else R.string.theme_to_dark),
                onToggleTheme,
            )
            if (onOpenDebug != null) CapsuleButton(R.drawable.ic_home_bug, stringResource(R.string.debug_tools), onOpenDebug)
            CapsuleButton(R.drawable.ic_home_settings, stringResource(R.string.settings), onOpenSettings)
        }
        SosPill(sosEnabled, onSos)
    }
}

@Composable
private fun CapsuleButton(@DrawableRes icon: Int, description: String, onClick: () -> Unit) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = Home.colors.capsuleIcon, modifier = Modifier.size(20.dp))
    }
}

/**
 * `.polish .sos.pill`: 50 dp, clearly red, not shouting. Works with or without a ride, voice or
 * internet: a 5-second cancel window, then SMS.
 */
@Composable
private fun SosPill(enabled: Boolean, onClick: () -> Unit) {
    val c = Home.colors
    val haptics = LocalHapticFeedback.current
    val description = stringResource(R.string.sos_button_description)
    val shape = RoundedCornerShape(25.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .height(50.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .cssShadows(shape, c.sosShadows)
            .background(c.sosFill, shape)
            .border(1.dp, c.sosBorder, shape)
            .clip(shape)
            .clickable(enabled = enabled, role = Role.Button) {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                onClick()
            }
            .semantics { contentDescription = description }
            .padding(horizontal = 17.dp),
    ) {
        Text(stringResource(R.string.sos_button), style = HomeType.sos, color = Color.White, modifier = Modifier.clearAndSetSemantics {})
    }
}

/** `.hello` and `.subrow`: the greeting, today's line and, if safety isn't fully set up, the fixes pill. */
@Composable
private fun Greeting(name: String, today: EarningsDb.DayTotal?, setupIssueCount: Int, onOpenSetup: () -> Unit) {
    val c = Home.colors
    // .refined .hello margin-top 2; .subrow margin-top -10 under the 16 dp gap = 6.
    Column(Modifier.padding(top = 2.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = if (name.isBlank()) stringResource(R.string.greeting_no_name) else stringResource(R.string.greeting, name.trim().substringBefore(' ')),
            style = HomeType.hello,
            color = c.ink,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val line = if (today == null || today.trips == 0) {
                buildAnnotatedString { append(stringResource(R.string.today_none)) }
            } else {
                val trips = pluralStringResource(R.plurals.trips_count, today.trips, today.trips)
                val todayWord = stringResource(R.string.today_word)
                buildAnnotatedString {
                    withStyle(SpanStyle(color = c.ink, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")) { append(rupees(today.rupees)) }
                    append(" $todayWord · $trips")
                }
            }
            // .refined .sub's margin-top -10 in the centred row puts the line at the row's top, the pill below it.
            Text(line, style = HomeType.sub, color = c.sub, modifier = Modifier.weight(1f).align(Alignment.Top))
            if (setupIssueCount > 0) FixPill(setupIssueCount, onOpenSetup)
        }
    }
}

/** `.polish .fixchip`: 28 dp tall; its touch area reaches 48 dp (the CSS ::after, Compose's touch-target rule). */
@Composable
private fun FixPill(count: Int, onClick: () -> Unit) {
    val c = Home.colors
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .height(28.dp)
            .background(c.fixFill, shape)
            .border(1.dp, c.fixBorder, shape)
            .clip(shape)
            .clickable(onClickLabel = stringResource(R.string.setup_open), role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        Icon(painterResource(R.drawable.ic_home_shield), contentDescription = null, tint = c.fixInk, modifier = Modifier.size(14.dp))
        Text(pluralStringResource(R.plurals.setup_count, count, count), style = HomeType.fix, color = c.fixInk, maxLines = 1)
    }
}

/**
 * The order card (`.card.order.glasscard`, C8's content): translucent 140° gradient, 1 dp white
 * edge, a top highlight and soft shadows, over the violet and blue glows of `.orderglass`. On
 * Android 12+ what's behind it is blurred and saturated as in CSS (`backdrop-filter: blur(26px)
 * saturate(180%)`); on older versions the fill and edge alone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeOrderCard(
    order: Order,
    locatingDrop: Boolean,
    onScanScreenshot: () -> Unit,
    onScanCamera: () -> Unit,
    onUseDemo: () -> Unit,
    setAside: Order?,
    onRestore: () -> Unit,
) {
    val c = Home.colors
    HomeGlassCard(
        modifier = Modifier.drawBehind { drawCardGlows(c) },
        behind = { root, card ->
            drawBackgroundLayers(c.page, root)
            translate(card.x, card.y) { drawCardGlows(c) }
        },
    ) {
        Text(orderLabel(order).uppercase(Locale.ROOT), style = HomeType.label, color = c.label, modifier = Modifier.semantics { heading() })
        if (order.customerName.isNotBlank()) {
            // margin-top -2
            Text(order.customerName, style = HomeType.name, color = c.name, modifier = Modifier.pullUp(2.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (order.dropArea.isNotBlank()) Meta(R.drawable.ic_home_pin, order.dropArea)
            val phoneDescription = stringResource(R.string.order_number_description, maskedPhoneDigits(order.customerPhone))
            Meta(
                R.drawable.ic_home_phone,
                when {
                    // Masked on screen; SMS and calls still use the full number.
                    order.customerPhone.isNotBlank() -> maskPhone(order.customerPhone)
                    order.phoneMasked -> stringResource(R.string.order_number_masked)
                    else -> stringResource(R.string.order_no_number)
                },
                description = phoneDescription.takeIf { order.customerPhone.isNotBlank() },
            )
        }
        if (!order.isDemo) {
            val notFound = dropNotFound(order, locatingDrop)
            val noteShape = RoundedCornerShape(12.dp)
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(c.noteFill, noteShape)
                    .border(1.dp, c.noteBorder, noteShape)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val ink = if (notFound) Pillion.colors.danger else c.noteInk
                Icon(
                    painterResource(R.drawable.ic_home_info),
                    contentDescription = null,
                    tint = if (notFound) Pillion.colors.danger else c.metaIcon,
                    modifier = Modifier.padding(top = 1.dp).size(18.dp),
                )
                Text(stringResource(dropNote(order, locatingDrop)), style = HomeType.note, color = ink)
            }
        }
        // .acts.two: margin-top 4, gap 12.
        Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SoftButton(R.drawable.ic_home_gallery, stringResource(R.string.order_scan_gallery), onScanScreenshot, Modifier.weight(1f))
            SoftButton(R.drawable.ic_home_camera, stringResource(R.string.order_scan_camera), onScanCamera, Modifier.weight(1f))
        }
        // "Use demo order" on a scanned order; on the demo, the way back to the scanned one.
        val link = when {
            !order.isDemo -> stringResource(R.string.order_use_demo) to onUseDemo
            setAside != null -> backToOrderLabel(setAside) to onRestore
            else -> null
        }
        if (link != null) {
            Box(
                contentAlignment = Alignment.CenterStart,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(role = Role.Button, onClick = link.second)
                    .padding(horizontal = 4.dp),
            ) {
                Text(
                    link.first,
                    style = HomeType.button.copy(textDecoration = TextDecoration.Underline),
                    color = c.link,
                )
            }
        }
        // margin-top -4
        Text(stringResource(R.string.order_share_hint), style = HomeType.hint, color = c.hint, modifier = Modifier.pullUp(4.dp))
    }
}

/**
 * A glass card (`.card.glasscard` under `.refined` / `.darkmode`): 140 degree translucent gradient,
 * 1 dp white edge, the inset top highlight and soft shadows, 24 dp corners. On Android 12+ what's
 * behind it is blurred and saturated as in CSS (`backdrop-filter: blur(26px) saturate(180%)`):
 * [behind] draws that, in root coordinates (the root's size and this card's place in it). Older
 * versions keep the fill, edge and highlight.
 */
@Composable
internal fun HomeGlassCard(
    modifier: Modifier = Modifier,
    behind: DrawScope.(root: Size, card: Offset) -> Unit,
    padding: PaddingValues = PaddingValues(20.dp),
    spacing: Dp = 12.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Home.colors
    val shape = RoundedCornerShape(24.dp)
    var inRoot by remember { mutableStateOf(Offset.Zero) }
    var rootSize by remember { mutableStateOf(IntSize.Zero) }
    Box(
        modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                inRoot = coordinates.positionInRoot()
                rootSize = coordinates.findRootCoordinates().size
            }
            .cssShadows(shape, c.cardShadows),
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Box(
                Modifier
                    .matchParentSize()
                    .clip(shape)
                    .graphicsLayer { renderEffect = backdropEffect(26.dp.toPx(), 1.8f) }
                    .drawBehind {
                        translate(-inRoot.x, -inRoot.y) {
                            behind(Size(rootSize.width.toFloat(), rootSize.height.toFloat()), inRoot)
                        }
                    },
            )
        }
        Column(
            Modifier
                .fillMaxWidth()
                .drawBehind {
                    drawRoundRect(
                        cssLinearGradient(140f, size, listOf(0f to c.cardTop, 1f to c.cardBottom)),
                        cornerRadius = CornerRadius(24.dp.toPx()),
                    )
                }
                .border(1.dp, c.cardBorder, shape)
                .insetTopHighlight(shape, c.cardHighlight)
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

@Composable
private fun Meta(@DrawableRes icon: Int, text: String, description: String? = null) {
    val c = Home.colors
    Row(
        modifier = if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = c.metaIcon, modifier = Modifier.size(18.dp))
        Text(text, style = HomeType.meta.copy(fontFeatureSettings = "tnum"), color = c.meta)
    }
}

/** `.soft` in the glass card: 56 dp, translucent white with a top highlight, 20 dp icon. */
@Composable
private fun SoftButton(@DrawableRes icon: Int, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Home.colors
    Row(
        modifier
            .height(56.dp)
            .cssShadows(CircleShape, c.softShadows)
            .background(c.softFill, CircleShape)
            .border(1.dp, c.softBorder, CircleShape)
            .insetTopHighlight(CircleShape, c.softHighlight)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = c.softInk, modifier = Modifier.size(20.dp))
        Text(text, style = HomeType.button, color = c.softInk, maxLines = 1)
    }
}

/**
 * `.sheet.clear` with `.start.big` (Start ride; Ride done's Done): full width, over a fade that
 * starts 28 dp up over the list (the sheet's negative margin) and runs on into the tab bar below.
 */
@Composable
internal fun HomeActionSheet(@DrawableRes icon: Int, text: String, onClick: () -> Unit) {
    val c = Home.colors
    val haptics = LocalHapticFeedback.current
    // Ride done's Done and home's Start ride sit in the same place: a quick second tap on Done (or
    // one tap registered twice) must not start a ride, so taps in the button's first moments are ignored.
    val shownAt = remember { SystemClock.uptimeMillis() }
    Box(
        Modifier
            .fillMaxWidth()
            .drawBehind {
                val overlap = SHEET_OVERLAP.toPx()
                val total = SHEET_HEIGHT.toPx()
                val bottom = (overlap + size.height) / total
                drawRect(
                    Brush.verticalGradient(
                        0f to c.sheetAt(0f),
                        (SHEET_MID * total / (overlap + size.height)).coerceAtMost(1f) to c.sheetAt(SHEET_MID),
                        1f to c.sheetAt(bottom),
                        startY = -overlap,
                        endY = size.height,
                    ),
                    topLeft = Offset(0f, -overlap),
                    size = Size(size.width, size.height + overlap),
                )
            }
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        val shape = RoundedCornerShape(34.dp)
        Row(
            Modifier
                .fillMaxWidth()
                .height(68.dp)
                .cssShadows(shape, c.startShadows)
                .drawBehind { drawRoundRect(cssLinearGradient(100f, size, HomeStartGradient), cornerRadius = CornerRadius(size.height / 2)) }
                .clip(shape)
                .clickable(role = Role.Button) {
                    if (SystemClock.uptimeMillis() - shownAt < TAP_GUARD_MS) return@clickable
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    onClick()
                },
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            Text(text, style = HomeType.start, color = Color.White)
        }
    }
}

/**
 * The `.sheet.clear` fade at [fraction] of the sheet's height: transparent → `sheetMid` at 26 % →
 * `sheetEnd`. The tab bar's own background continues it below the Start button.
 */
fun HomeColors.sheetAt(fraction: Float): Color = if (fraction <= SHEET_MID) {
    lerp(sheetMid.copy(alpha = 0f), sheetMid, fraction / SHEET_MID)
} else {
    lerp(sheetMid, sheetEnd, ((fraction - SHEET_MID) / (1f - SHEET_MID)).coerceIn(0f, 1f))
}

/** Where the Start button's part of the sheet ends, as a fraction of it (the tab bar takes the rest). */
val HOME_SHEET_SPLIT: Float get() = (SHEET_OVERLAP.value + 68f + 12f) / SHEET_HEIGHT.value

private const val TAP_GUARD_MS = 600L

/** 28 (overlap) + 68 (Start) + 12 (gap) + 70 (tabs) + 22 (bottom). */
private val SHEET_HEIGHT = 200.dp
private val SHEET_OVERLAP = 28.dp
private const val SHEET_MID = 0.26f

/**
 * `.orderglass::before` / `::after`, placed where they sit relative to the first card in the design:
 * a violet glow (230 px) at the card's right and a blue one (250 px) low on its left. [below]: how
 * much lower they sit relative to a first card placed higher (Ride done's summary: 10 dp).
 */
internal fun DrawScope.drawCardGlows(c: HomeColors, below: Dp = 0.dp) {
    val violet = Offset(size.width - 55.dp.toPx(), (159.dp + below).toPx())
    val violetRadius = 115.dp.toPx()
    drawCircle(Brush.radialGradient(listOf(c.glowViolet, c.glowViolet.copy(alpha = 0f)), violet, violetRadius), violetRadius, violet)
    val blue = Offset(35.dp.toPx(), (299.dp + below).toPx())
    val blueRadius = 125.dp.toPx()
    drawCircle(Brush.radialGradient(listOf(c.glowBlue, c.glowBlue.copy(alpha = 0f)), blue, blueRadius), blueRadius, blue)
}

/** CSS `blur(σ) saturate(s)` as one Android 12+ render effect (blur first, as CSS applies them). */
@androidx.annotation.RequiresApi(Build.VERSION_CODES.S)
private fun backdropEffect(sigma: Float, saturation: Float): androidx.compose.ui.graphics.RenderEffect {
    // RenderEffect takes a radius; Android turns it into σ = 0.57735·r + 0.5.
    val radius = ((sigma - 0.5f) / 0.57735f).coerceAtLeast(1f)
    val blur = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP)
    val saturate = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }))
    return RenderEffect.createChainEffect(saturate, blur).asComposeRenderEffect()
}

/** A negative CSS margin-top: the element is drawn [amount] higher and takes that much less room. */
internal fun Modifier.pullUp(amount: Dp): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints)
    val shift = amount.roundToPx()
    layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) { placeable.place(0, -shift) }
}
