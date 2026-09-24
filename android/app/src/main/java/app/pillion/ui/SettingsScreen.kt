package app.pillion.ui

import android.os.PowerManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pillion.BuildConfig
import app.pillion.R
import app.pillion.data.ThemeMode
import app.pillion.pillion
import app.pillion.ui.components.PillButton
import app.pillion.ui.components.PillionCard
import app.pillion.ui.components.ScreenHeader
import app.pillion.ui.components.SectionTitle
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.Space

@Composable
fun SettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val prefs = context.pillion.uiPrefs
    val contacts = context.pillion.contacts
    val themeMode by prefs.themeMode.collectAsStateWithLifecycle()
    val subtitles by prefs.subtitles.collectAsStateWithLifecycle()
    val savedName by contacts.riderName.collectAsStateWithLifecycle()
    var name by rememberSaveable(savedName) { mutableStateOf(savedName) }
    // Battery settings change outside the app; re-check on every return.
    var resumes by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumes++ }
    val batteryOk = remember(resumes) {
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
    }
    val colors = Pillion.colors
    BackHandler(onBack = onBack)

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.settings), onBack)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = Space.gutter, end = Space.gutter, top = Space.s, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            SectionTitle(stringResource(R.string.settings_name))
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_name_field)) },
                    supportingText = { Text(stringResource(R.string.first_run_name_note)) },
                    singleLine = true,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.weight(1f),
                )
                PillButton(
                    stringResource(R.string.save),
                    onClick = { contacts.setRiderName(name) },
                    enabled = name.trim() != savedName,
                    minHeight = 56.dp,
                    // Level with the field's outline, which starts below its floating label.
                    modifier = Modifier.padding(top = Space.s),
                )
            }

            SectionTitle(stringResource(R.string.settings_theme), Modifier.padding(top = Space.m))
            ThemeChoice(themeMode, prefs::setThemeMode)
            Text(stringResource(R.string.settings_theme_note), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)

            SectionTitle(stringResource(R.string.settings_subtitles), Modifier.padding(top = Space.m))
            PillionCard {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .toggleable(value = subtitles, role = Role.Switch, onValueChange = prefs::setSubtitles),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.m),
                ) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.settings_subtitles_switch), style = MaterialTheme.typography.titleSmall, color = colors.ink)
                        Text(stringResource(R.string.settings_subtitles_note), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    }
                    Switch(checked = subtitles, onCheckedChange = null)
                }
            }

            SectionTitle(stringResource(R.string.settings_battery), Modifier.padding(top = Space.m))
            PillionCard {
                Text(
                    stringResource(if (batteryOk) R.string.settings_battery_ok else R.string.setup_battery),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.ink,
                )
                if (isColorOsFamily()) {
                    Text(stringResource(R.string.setup_battery_oem), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                }
                if (!batteryOk) {
                    PillButton(
                        stringResource(R.string.fix),
                        onClick = { context.askToIgnoreBatteryOptimization() },
                        container = colors.surfaceHigh,
                        content = colors.ink,
                        minHeight = 48.dp,
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_about), Modifier.padding(top = Space.m))
            PillionCard {
                Text(
                    stringResource(R.string.settings_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.ink,
                )
                Text(stringResource(R.string.tagline), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                Text(stringResource(R.string.settings_about_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                Text(stringResource(R.string.settings_licences), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            }

            if (BuildConfig.DEBUG) {
                SectionTitle(stringResource(R.string.settings_debug), Modifier.padding(top = Space.m))
                PillionCard {
                    Text(stringResource(R.string.settings_debug_backend, BuildConfig.BACKEND_URL), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
                    PillButton(
                        stringResource(R.string.settings_debug_first_run),
                        onClick = { prefs.setFirstRunDone(false) },
                        container = colors.surfaceHigh,
                        content = colors.ink,
                        minHeight = 48.dp,
                    )
                }
            }
        }
    }
}

/** System / Light / Dark as three round choices; the chosen one is filled. */
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
