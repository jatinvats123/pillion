package app.pillion.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.ContactsContract
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.pillion.R
import app.pillion.pillion
import app.pillion.safety.EmergencyContact
import app.pillion.safety.EmergencyContacts

/** Emergency contacts (up to 3, via the system contact picker) and the rider's name for the SOS text. */
@Composable
fun SafetySettingsRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = context.pillion.contacts
    val contacts by store.contacts.collectAsStateWithLifecycle()
    val savedName by store.riderName.collectAsStateWithLifecycle()
    var name by rememberSaveable(savedName) { mutableStateOf(savedName) }
    var message by rememberSaveable { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(PickPhoneNumber()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val contact = context.readPickedContact(uri)
        message = when {
            contact == null -> R.string.safety_contact_no_number
            store.add(contact) -> null
            contacts.size >= EmergencyContacts.MAX -> R.string.safety_contacts_full
            else -> R.string.safety_contact_exists
        }
    }
    BackHandler(onBack = onBack)

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("← " + stringResource(R.string.back)) }
                Text(
                    stringResource(R.string.safety_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.safety_name_label)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { store.setRiderName(name) }, enabled = name.trim() != savedName) {
                    Text(stringResource(R.string.save))
                }
            }

            Text(
                stringResource(R.string.safety_contacts_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(stringResource(R.string.safety_contacts_body), style = MaterialTheme.typography.bodyMedium)

            contacts.forEachIndexed { index, contact ->
                ContactRow(
                    contact = contact,
                    first = index == 0,
                    canMoveUp = index > 0,
                    canMoveDown = index < contacts.lastIndex,
                    onMove = { by -> store.move(index, by) },
                    onRemove = { store.remove(index) },
                )
            }

            Button(
                onClick = {
                    message = null
                    picker.launch(Unit)
                },
                enabled = contacts.size < EmergencyContacts.MAX,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (contacts.size < EmergencyContacts.MAX) R.string.safety_add_contact else R.string.safety_contacts_full))
            }
            message?.let {
                Text(
                    stringResource(it),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(contact.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(contact.number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (first) Text(stringResource(R.string.safety_first_contact), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            val up = stringResource(R.string.move_up_description, contact.name)
            val down = stringResource(R.string.move_down_description, contact.name)
            val remove = stringResource(R.string.remove_description, contact.name)
            TextButton(onClick = { onMove(-1) }, enabled = canMoveUp, modifier = Modifier.semantics { contentDescription = up }) {
                Text(stringResource(R.string.move_up))
            }
            TextButton(onClick = { onMove(1) }, enabled = canMoveDown, modifier = Modifier.semantics { contentDescription = down }) {
                Text(stringResource(R.string.move_down))
            }
            TextButton(onClick = onRemove, modifier = Modifier.semantics { contentDescription = remove }) {
                Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error)
            }
        }
    }
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
