package app.pillion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pillion.R
import app.pillion.data.ThemeMode
import app.pillion.pillion
import app.pillion.ui.components.PillButton
import app.pillion.ui.components.ScreenHeader
import app.pillion.ui.components.SectionTitle
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.Space

@Composable
fun SettingsRoute(onBack: () -> Unit) {
    val prefs = LocalContext.current.pillion.uiPrefs
    val themeMode by prefs.themeMode.collectAsStateWithLifecycle()
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.settings), onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter, vertical = Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            SectionTitle(stringResource(R.string.settings_theme))
            ThemeChoice(themeMode, prefs::setThemeMode)
        }
    }
}

/** System / Light / Dark as three round choices. */
@Composable
private fun ThemeChoice(selected: ThemeMode, onSelect: (ThemeMode) -> Unit) {
    val colors = Pillion.colors
    FlowRow(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        ThemeMode.entries.forEach { mode ->
            val (label, icon) = when (mode) {
                ThemeMode.System -> R.string.theme_system to R.drawable.ic_brightness_auto
                ThemeMode.Light -> R.string.theme_light to R.drawable.ic_light_mode
                ThemeMode.Dark -> R.string.theme_dark to R.drawable.ic_dark_mode
            }
            val on = mode == selected
            PillButton(
                text = stringResource(label),
                onClick = { onSelect(mode) },
                icon = icon,
                container = if (on) colors.ink else colors.surfaceHigh,
                content = if (on) colors.background else colors.ink,
                minHeight = 48.dp,
                modifier = Modifier.semantics { this.selected = on },
            )
        }
    }
}
