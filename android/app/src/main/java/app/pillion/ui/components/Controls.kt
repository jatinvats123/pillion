package app.pillion.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.pillion.R
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.Space

/**
 * Small round button with one icon (settings, back, order, debug): a glass circle like the ride
 * screen's, or a solid [container] where one is given.
 */
@Composable
fun RoundIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    container: Color? = null,
    content: Color = Pillion.colors.ink,
    enabled: Boolean = true,
) {
    val colors = Pillion.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .then(if (container == null) Modifier.glass(colors, CircleShape) else Modifier.background(container, CircleShape))
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Icon(
            painterResource(icon),
            contentDescription = null,
            tint = if (enabled) content else content.copy(alpha = 0.38f),
            modifier = Modifier.size(if (size >= 72.dp) 32.dp else 22.dp),
        )
    }
}

/** Fully round button with an optional leading icon; grows with the font scale instead of clipping. */
@Composable
fun PillButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    container: Color = Pillion.colors.ink,
    content: Color = Pillion.colors.background,
    minHeight: Dp = 56.dp,
    enabled: Boolean = true,
    style: TextStyle = MaterialTheme.typography.labelLarge,
    border: BorderStroke? = null,
    horizontalPadding: Dp = Space.l,
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) container else container.copy(alpha = 0.38f),
        contentColor = if (enabled) content else content.copy(alpha = 0.7f),
        border = border,
        modifier = modifier.heightIn(min = minHeight),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = horizontalPadding, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(if (minHeight >= 72.dp) 28.dp else 22.dp))
            Text(text, style = style, textAlign = TextAlign.Center)
        }
    }
}

/** A screen's title row: round back button (pushed screens), a heading, and settings (tabs). */
@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, onOpenSettings: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) Space.m else Space.gutter, end = if (onOpenSettings != null) Space.m else Space.gutter, top = Space.s, bottom = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (onBack != null) RoundIconButton(R.drawable.ic_arrow_back, stringResource(R.string.back), onBack)
        Text(
            title,
            style = MaterialTheme.typography.headlineMedium,
            color = Pillion.colors.ink,
            modifier = Modifier
                .weight(1f)
                .semantics { heading() },
        )
        if (onOpenSettings != null) RoundIconButton(R.drawable.ic_settings, stringResource(R.string.settings), onOpenSettings)
    }
}

/** A small heading above a group of settings or cards. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = Pillion.colors.inkSecondary,
        modifier = modifier.semantics { heading() },
    )
}

/** The one pill chip (the ride screen's glass pill): a coloured dot and a word. TalkBack reads each change. */
@Composable
fun StatusPill(label: String, dot: Color, modifier: Modifier = Modifier) {
    val colors = Pillion.colors
    Row(
        modifier = modifier
            .heightIn(min = 36.dp)
            .glass(colors, RoundedCornerShape(18.dp))
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp), color = colors.ink)
    }
}

/** A quiet label chip ("Demo order"). */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = Pillion.colors.ink,
        modifier = modifier
            .background(Pillion.colors.surfaceHigh, CircleShape)
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}

/**
 * Cards: the design's glass panel (translucent fill, 1 dp edge, 24 dp corners; a soft shadow in
 * the light theme). With [onClick] the whole card is the target, and its press feedback follows
 * the rounded shape.
 */
@Composable
fun PillionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = Pillion.colors
    val shape = CardShape
    Column(
        modifier
            .fillMaxWidth()
            .glass(colors, shape)
            .clip(shape)
            .then(if (onClick != null) Modifier.clickable(onClickLabel = onClickLabel, onClick = onClick) else Modifier)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(Space.s),
        content = content,
    )
}

/** The design's panel corners (`.drop`: 24 px). */
val CardShape = RoundedCornerShape(24.dp)

/**
 * Something the rider should know or fix: an icon, a title, a line of detail, and actions. Red
 * icons only for what affects safety; everything else stays neutral.
 */
@Composable
fun NoticeCard(
    title: String,
    modifier: Modifier = Modifier,
    body: String? = null,
    @DrawableRes icon: Int = R.drawable.ic_info,
    safety: Boolean = false,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
    onDismiss: (() -> Unit)? = null,
) {
    val colors = Pillion.colors
    PillionCard(modifier) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = if (safety) colors.danger else colors.ink,
                modifier = Modifier.padding(top = 2.dp).size(24.dp),
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text(title, style = MaterialTheme.typography.titleMedium, color = colors.ink)
                body?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary) }
            }
        }
        if (onDismiss != null || actionLabel != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                onDismiss?.let { TextButton(onClick = it) { Text(stringResource(R.string.dismiss), color = colors.inkSecondary) } }
                if (actionLabel != null) {
                    PillButton(actionLabel, onAction, minHeight = 48.dp, container = colors.surfaceHigh, content = colors.ink)
                }
            }
        }
    }
}
