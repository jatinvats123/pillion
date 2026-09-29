package app.pillion.ui

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import app.pillion.R
import app.pillion.data.EarningsDb
import app.pillion.pillion
import app.pillion.safety.EmergencyContact
import app.pillion.safety.EmergencyContacts
import app.pillion.ui.components.PillButton
import app.pillion.ui.components.PillionCard
import app.pillion.ui.components.RoundIconButton
import app.pillion.ui.components.ScreenHeader
import app.pillion.ui.components.SectionTitle
import app.pillion.ui.components.Tag
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.Space
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The safety log (crash detected, SOS sent…), including the seeded past alerts. */
class SafetyViewModel(application: Application) : AndroidViewModel(application) {
    private val db = application.pillion.db
    private val _events = MutableStateFlow<List<EarningsDb.SafetyEvent>?>(null)
    val events: StateFlow<List<EarningsDb.SafetyEvent>?> = _events.asStateFlow()

    fun refresh() {
        viewModelScope.launch { _events.value = withContext(Dispatchers.IO) { db.safetyEvents(limit = 50) } }
    }
}

/** Emergency contacts (up to 3, via the system contact picker), then the safety log. */
@Composable
fun SafetyRoute(onBack: () -> Unit, onOpenSettings: () -> Unit, viewModel: SafetyViewModel = viewModel()) {
    val context = LocalContext.current
    val store = context.pillion.contacts
    val contacts by store.contacts.collectAsStateWithLifecycle()
    val events by viewModel.events.collectAsStateWithLifecycle()
    var message by rememberSaveable { mutableStateOf<Int?>(null) }
    val colors = Pillion.colors

    val picker = rememberContactPicker(store) { message = it }
    BackHandler(onBack = onBack)
    // A crash check or SOS may have been logged while this screen was away.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }

    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        ScreenHeader(stringResource(R.string.safety_title), onOpenSettings = onOpenSettings)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = Space.gutter, end = Space.gutter, bottom = Space.xl),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            SectionTitle(stringResource(R.string.safety_contacts_title))
            Text(stringResource(R.string.safety_contacts_body), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            if (contacts.isNotEmpty()) {
                PillionCard {
                    contacts.forEachIndexed { index, contact ->
                        if (index > 0) HorizontalDivider(color = colors.hairline)
                        ContactRow(
                            contact = contact,
                            first = index == 0,
                            canMoveUp = index > 0,
                            canMoveDown = index < contacts.lastIndex,
                            onMove = { by -> store.move(index, by) },
                            onRemove = { store.remove(index) },
                        )
                    }
                }
            }
            PillButton(
                text = stringResource(if (contacts.size < EmergencyContacts.MAX) R.string.safety_add_contact else R.string.safety_contacts_full),
                onClick = {
                    message = null
                    picker()
                },
                icon = R.drawable.ic_person_add,
                enabled = contacts.size < EmergencyContacts.MAX,
                minHeight = 56.dp,
                modifier = Modifier.fillMaxWidth(),
            )
            message?.let {
                Text(
                    stringResource(it),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.danger,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }

            SectionTitle(stringResource(R.string.safety_log_title), Modifier.padding(top = Space.m))
            val list = events
            when {
                list == null -> Unit
                list.isEmpty() -> Text(stringResource(R.string.safety_log_empty), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                else -> PillionCard {
                    list.forEachIndexed { index, event ->
                        if (index > 0) HorizontalDivider(color = colors.hairline, modifier = Modifier.padding(vertical = Space.xs))
                        SafetyEventRow(event)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactRow(
    contact: EmergencyContact,
    first: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMove: (Int) -> Unit,
    onRemove: () -> Unit,
) {
    val colors = Pillion.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(contact.name, style = MaterialTheme.typography.titleMedium, color = colors.ink)
            Text(contact.number, style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
            if (first) Tag(stringResource(R.string.safety_first_contact), Modifier.padding(top = Space.xs))
        }
        if (canMoveUp) RoundIconButton(R.drawable.ic_arrow_upward, stringResource(R.string.move_up_description, contact.name), { onMove(-1) })
        if (canMoveDown) RoundIconButton(R.drawable.ic_arrow_downward, stringResource(R.string.move_down_description, contact.name), { onMove(1) })
        RoundIconButton(R.drawable.ic_delete, stringResource(R.string.remove_description, contact.name), onRemove, content = colors.danger)
    }
}

/**
 * Opens the system contact picker (phone numbers only) and adds the pick to [store]; [onMessage]
 * gets why it wasn't added, or null. Returns the launcher.
 */
@Composable
fun rememberContactPicker(store: EmergencyContacts, onMessage: (Int?) -> Unit): () -> Unit {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(PickPhoneNumber()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val before = store.contacts.value.size
        val contact = context.readPickedContact(uri)
        onMessage(
            when {
                contact == null -> R.string.safety_contact_no_number
                store.add(contact) -> null
                before >= EmergencyContacts.MAX -> R.string.safety_contacts_full
                else -> R.string.safety_contact_exists
            }
        )
    }
    return { launcher.launch(Unit) }
}

/**
 * The system contact picker, limited to phone numbers. The result URI comes with a one-time read
 * grant for that row, so no READ_CONTACTS permission is needed.
 */
private class PickPhoneNumber : ActivityResultContract<Unit, Uri?>() {
    override fun createIntent(context: Context, input: Unit) =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        intent?.data?.takeIf { resultCode == Activity.RESULT_OK }
}

private fun Context.readPickedContact(uri: Uri): EmergencyContact? = runCatching {
    val columns = arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER)
    contentResolver.query(uri, columns, null, null, null)?.use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val number = cursor.getString(1)?.filter { it.isDigit() || it == '+' }.orEmpty()
        if (number.length < 7) return@use null
        EmergencyContact(name = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: number, number = number)
    }
}.getOrNull()
