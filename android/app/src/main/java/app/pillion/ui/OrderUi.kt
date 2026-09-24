package app.pillion.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.pillion.R
import app.pillion.data.DropLocation
import app.pillion.data.Order
import app.pillion.order.Confidence
import app.pillion.order.Field
import app.pillion.order.LoadedImage
import app.pillion.order.NumberRole
import app.pillion.order.OrderDraft
import app.pillion.order.ScanFailure
import app.pillion.order.ScanSource
import app.pillion.order.ScanState

/** The order Pillion acts on (ETA, SMS, call), and the ways to scan a new one. */
@Composable
fun ActiveOrderCard(
    order: Order,
    locatingDrop: Boolean,
    onScanScreenshot: () -> Unit,
    onScanCamera: () -> Unit,
    onUseDemo: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = when {
                    order.isDemo -> stringResource(R.string.order_demo)
                    order.orderId.isNotBlank() -> "${stringResource(R.string.order_current)} · ${stringResource(R.string.order_id, order.orderId)}"
                    else -> stringResource(R.string.order_current)
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = listOf(order.customerName, order.dropArea).filter { it.isNotBlank() }.joinToString(" · "),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = when {
                    order.customerPhone.isNotBlank() -> order.customerPhone
                    order.phoneMasked -> stringResource(R.string.order_number_masked)
                    else -> stringResource(R.string.order_no_number)
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (!order.isDemo) {
                Text(
                    text = stringResource(
                        when {
                            locatingDrop -> R.string.order_drop_locating
                            order.dropAddress.isBlank() -> R.string.order_drop_no_address
                            else -> when (val drop = order.drop) {
                                is DropLocation.Found -> if (drop.approximate) R.string.order_drop_approximate else R.string.order_drop_found
                                DropLocation.NotFound -> R.string.order_drop_not_found
                                DropLocation.Unchecked -> R.string.order_drop_unchecked
                            }
                        }
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (!locatingDrop && order.drop == DropLocation.NotFound) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = onScanScreenshot, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.order_scan_gallery)) }
                OutlinedButton(onClick = onScanCamera, modifier = Modifier.height(48.dp)) { Text(stringResource(R.string.order_scan_camera)) }
                if (!order.isDemo) TextButton(onClick = onUseDemo) { Text(stringResource(R.string.order_use_demo)) }
            }
            Text(
                stringResource(R.string.order_share_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Replaces the transcript while an order is read or checked. */
@Composable
fun OrderScanPanel(
    state: ScanState,
    onConfirm: (OrderDraft) -> Unit,
    onTypeIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state) {
            ScanState.Idle -> Unit
            ScanState.Reading -> Row(
                modifier = Modifier.padding(vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Column {
                    Text(stringResource(R.string.order_reading), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.order_reading_note), style = MaterialTheme.typography.bodyMedium)
                }
            }
            is ScanState.Failed -> {
                Text(
                    stringResource(if (state.reason == ScanFailure.Unreadable) R.string.order_unreadable else R.string.order_nothing_found),
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.close)) }
                    Button(onClick = onTypeIn, modifier = Modifier.weight(1f).height(56.dp)) { Text(stringResource(R.string.order_type_in)) }
                }
            }
            is ScanState.Review -> OrderReviewForm(state, onConfirm, onDismiss)
        }
    }
}

/**
 * What OCR read, editable. Sure values are filled in, unsure ones marked "Check this", missing or
 * low-confidence ones left empty for the rider; several numbers → the rider picks the customer's.
 */
@Composable
private fun OrderReviewForm(review: ScanState.Review, onConfirm: (OrderDraft) -> Unit, onCancel: () -> Unit) {
    val parsed = review.parsed
    val initial = remember(review) { OrderDraft.from(parsed) }
    var name by rememberSaveable(review) { mutableStateOf(initial.name) }
    var phone by rememberSaveable(review) { mutableStateOf(initial.phone) }
    var address by rememberSaveable(review) { mutableStateOf(initial.address) }
    var earning by rememberSaveable(review) { mutableStateOf("") }

    Text(
        stringResource(R.string.order_review_title),
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { heading() },
    )
    Text(
        stringResource(
            when (review.source) {
                ScanSource.Share -> R.string.order_review_share
                ScanSource.Camera -> R.string.order_review_camera
                ScanSource.Manual -> R.string.order_review_manual
                ScanSource.Gallery, ScanSource.TestImage -> R.string.order_review_gallery
            }
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    parsed.orderId?.takeIf { it.confidence != Confidence.Low }?.let {
        Text(stringResource(R.string.order_id, it.value), style = MaterialTheme.typography.bodyMedium)
    }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.order_field_name)) },
        supportingText = hint(parsed.customerName, edited = name != initial.name),
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = phone,
        onValueChange = { phone = it },
        label = { Text(stringResource(R.string.order_field_phone)) },
        supportingText = when {
            parsed.maskedPhone != null && phone.isBlank() -> note(stringResource(R.string.order_masked_note, parsed.maskedPhone))
            parsed.callButtonOnly && phone.isBlank() -> note(stringResource(R.string.order_call_button_note))
            else -> hint(parsed.customerPhone, edited = phone != initial.phone, quietWhenMissing = parsed.phoneNumbers.isNotEmpty())
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    // The rider picks when the parser couldn't tell which number is the customer's.
    if (parsed.customerPhone?.confidence != Confidence.High && parsed.phoneNumbers.isNotEmpty()) {
        Text(stringResource(R.string.order_pick_number), style = MaterialTheme.typography.bodyMedium)
        parsed.phoneNumbers.forEach { number ->
            val role = stringResource(
                when (number.role) {
                    NumberRole.Customer -> R.string.order_role_customer
                    NumberRole.Store -> R.string.order_role_store
                    NumberRole.Support -> R.string.order_role_support
                    NumberRole.Other -> R.string.order_role_other
                    NumberRole.Unknown -> R.string.order_role_unknown
                }
            )
            FilterChip(
                selected = OrderDraft.dialable(phone) == number.dial,
                onClick = { phone = number.shown },
                label = {
                    Text(stringResource(if (number.ocrFixed) R.string.order_number_chip_fixed else R.string.order_number_chip, number.shown, role))
                },
            )
        }
    }

    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text(stringResource(R.string.order_field_address)) },
        supportingText = hint(parsed.dropAddress, edited = address != initial.address),
        minLines = 2,
        maxLines = 4,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = earning,
        onValueChange = { value -> earning = value.filter(Char::isDigit).take(5) },
        label = { Text(stringResource(R.string.order_field_earning)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    val draft = OrderDraft(name, phone, address, earning, initial.orderId, parsed.maskedPhone)
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f).height(64.dp)) {
            Text(stringResource(R.string.cancel), style = MaterialTheme.typography.titleMedium)
        }
        Button(onClick = { onConfirm(draft) }, enabled = !draft.isBlank, modifier = Modifier.weight(2f).height(64.dp)) {
            Text(stringResource(R.string.order_set), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
    }
}

/** "Check this" on unsure values, "type it in" on missing ones; nothing once the rider has edited. */
@Composable
private fun hint(field: Field?, edited: Boolean, quietWhenMissing: Boolean = false): (@Composable () -> Unit)? = when {
    edited -> null
    field == null || field.confidence == Confidence.Low -> if (quietWhenMissing) null else note(stringResource(R.string.order_type_missing))
    field.confidence == Confidence.Medium -> note(stringResource(R.string.order_check_this), MaterialTheme.colorScheme.tertiary)
    else -> null
}

private fun note(text: String, color: Color = Color.Unspecified): @Composable () -> Unit = { Text(text, color = color) }

/** DEBUG BUILDS ONLY — mock order screens (src/debug/assets) through the real OCR and parser. */
@Composable
fun DebugScanCard(onScan: (ScanSource, suspend () -> LoadedImage) -> Unit) {
    val context = LocalContext.current
    val samples = remember { context.assets.list(SAMPLES).orEmpty().sorted() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.debug_scan_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (samples.isEmpty()) Text(stringResource(R.string.debug_scan_none), style = MaterialTheme.typography.bodyMedium)
            samples.forEach { file ->
                TextButton(onClick = {
                    onScan(ScanSource.TestImage) {
                        val bitmap = context.assets.open("$SAMPLES/$file").use(BitmapFactory::decodeStream)
                        LoadedImage(bitmap, 0)
                    }
                }) { Text(file) }
            }
        }
    }
}

private const val SAMPLES = "order_samples"
