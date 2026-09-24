package app.pillion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pillion.R
import app.pillion.pillion
import app.pillion.safety.EmergencyContacts
import app.pillion.ui.components.Orb
import app.pillion.ui.components.OrbMood
import app.pillion.ui.components.PillButton
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.RideType
import app.pillion.ui.theme.Space
import app.pillion.ui.theme.Targets

/**
 * The one welcome screen: the rider's name (for the SOS text and the greeting) and a shortcut to
 * add an emergency contact. Everything is optional; Skip goes straight to the ride screen.
 */
@Composable
fun FirstRunRoute(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = context.pillion.contacts
    val contacts by store.contacts.collectAsStateWithLifecycle()
    val savedName by store.riderName.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf(savedName) }
    var message by rememberSaveable { mutableStateOf<Int?>(null) }
    val picker = rememberContactPicker(store) { message = it }
    val colors = Pillion.colors
    val finish = {
        if (name.isNotBlank()) store.setRiderName(name)
        onDone()
    }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.gutter),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Orb(OrbMood.Dormant, level = { 0f }, description = stringResource(R.string.orb_dormant), modifier = Modifier.fillMaxWidth().height(180.dp))
            Text(
                stringResource(R.string.first_run_title),
                style = MaterialTheme.typography.headlineLarge,
                color = colors.ink,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.first_run_body), style = MaterialTheme.typography.bodyLarge, color = colors.inkSecondary)

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(stringResource(R.string.first_run_name)) },
                supportingText = { Text(stringResource(R.string.first_run_name_note)) },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth().padding(top = Space.s),
            )

            contacts.forEach { contact ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    Icon(painterResource(R.drawable.ic_check_circle), contentDescription = null, tint = colors.success, modifier = Modifier.size(22.dp))
                    Text(stringResource(R.string.first_run_contact_added, contact.name), style = MaterialTheme.typography.bodyLarge, color = colors.ink)
                }
            }
            if (contacts.size < EmergencyContacts.MAX) {
                PillButton(
                    text = stringResource(if (contacts.isEmpty()) R.string.first_run_add_contact else R.string.first_run_add_another),
                    onClick = {
                        message = null
                        picker()
                    },
                    icon = R.drawable.ic_person_add,
                    container = colors.surfaceHigh,
                    content = colors.ink,
                    minHeight = 56.dp,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text(stringResource(R.string.first_run_contact_note), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
            message?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.danger,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
        Column(
            Modifier.padding(start = Space.gutter, end = Space.gutter, top = Space.m, bottom = Space.l),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            PillButton(
                text = stringResource(R.string.first_run_continue),
                onClick = finish,
                container = colors.accent,
                content = colors.onAccent,
                minHeight = Targets.ride,
                style = RideType.control,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = onDone, modifier = Modifier.height(Targets.min)) {
                Text(stringResource(R.string.first_run_skip), color = colors.inkSecondary)
            }
        }
    }
}
