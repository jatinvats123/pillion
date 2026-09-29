package app.pillion.ui

import android.graphics.BitmapFactory
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
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
import app.pillion.order.maskPhone
import app.pillion.order.maskedPhoneDigits
import app.pillion.ui.components.PillButton
import app.pillion.ui.components.PillionCard
import app.pillion.ui.components.Tag
import app.pillion.ui.theme.Pillion
import app.pillion.ui.theme.Space

/** The order Pillion acts on (ETA, SMS, call), and the ways to scan a new one. */
@Composable
fun ActiveOrderCard(
    order: Order,
    locatingDrop: Boolean,
    onScanScreenshot: () -> Unit,
    onScanCamera: () -> Unit,
    onUseDemo: () -> Unit,
    setAside: Order?,
    onRestore: () -> Unit,
    onTrySample: () -> Unit,
) {
    val colors = Pillion.colors
    PillionCard {
        Tag(text = orderLabel(order), modifier = Modifier.semantics { heading() })
        if (order.customerName.isNotBlank()) {
            Text(order.customerName, style = MaterialTheme.typography.titleLarge, color = colors.ink)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            if (order.dropArea.isNotBlank()) OrderDetail(R.drawable.ic_location_on, order.dropArea)
            val phoneDescription = stringResource(R.string.order_number_description, maskedPhoneDigits(order.customerPhone))
            OrderDetail(
                R.drawable.ic_call,
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
            OrderDetail(
                icon = if (notFound) R.drawable.ic_error else R.drawable.ic_info,
                text = stringResource(dropNote(order, locatingDrop)),
                color = if (notFound) colors.danger else colors.inkSecondary,
            )
        }
        FlowRow(
            modifier = Modifier.padding(top = Space.xs),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            PillButton(stringResource(R.string.order_scan_gallery), onScanScreenshot, icon = R.drawable.ic_photo_library, container = colors.surfaceHigh, content = colors.ink, minHeight = 48.dp)
            PillButton(stringResource(R.string.order_scan_camera), onScanCamera, icon = R.drawable.ic_photo_camera, container = colors.surfaceHigh, content = colors.ink, minHeight = 48.dp)
            if (!order.isDemo) {
                TextButton(onClick = onUseDemo, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.order_use_demo)) }
            } else if (setAside != null) {
                TextButton(onClick = onRestore, modifier = Modifier.heightIn(min = 48.dp)) { Text(backToOrderLabel(setAside)) }
            }
            TextButton(onClick = onTrySample, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(R.string.order_try_sample)) }
        }
        Text(stringResource(R.string.order_share_hint), style = MaterialTheme.typography.bodySmall, color = colors.inkSecondary)
    }
}

/** "Current order", "Current order · #123" or "Demo order (sample)". */
@Composable
fun orderLabel(order: Order): String = when {
    order.isDemo -> stringResource(R.string.order_demo)
    order.isSample -> stringResource(R.string.order_sample)
    order.orderId.isNotBlank() -> "${stringResource(R.string.order_current)} · ${stringResource(R.string.order_id, order.orderId)}"
    else -> stringResource(R.string.order_current)
}

/** The demo card's way back to the scanned order: "Back to Kavita Rao's order". */
@Composable
fun backToOrderLabel(order: Order): String =
    if (order.customerName.isBlank()) stringResource(R.string.order_back_to_scanned) else stringResource(R.string.order_back_to, order.customerName)

/** What the rider should know about the drop's place on the map (a scanned order only). */
@StringRes
fun dropNote(order: Order, locatingDrop: Boolean): Int = when {
    locatingDrop -> R.string.order_drop_locating
    order.dropAddress.isBlank() -> R.string.order_drop_no_address
    else -> when (val drop = order.drop) {
        is DropLocation.Found -> if (drop.approximate) R.string.order_drop_approximate else R.string.order_drop_found
        DropLocation.NotFound -> R.string.order_drop_not_found
        DropLocation.Unchecked -> R.string.order_drop_unchecked
    }
}

/** The drop couldn't be placed at all: ETA won't work for this order. */
fun dropNotFound(order: Order, locatingDrop: Boolean): Boolean = !locatingDrop && order.drop == DropLocation.NotFound

@Composable
private fun OrderDetail(@DrawableRes icon: Int, text: String, color: Color = Pillion.colors.inkSecondary, description: String? = null) {
    Row(
        modifier = if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = color, modifier = Modifier.padding(top = 2.dp).size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = color)
    }
}

/** Replaces the ride screen's main area while an order is read or checked. */
@Composable
fun OrderScanPanel(
    state: ScanState,
    onConfirm: (OrderDraft) -> Unit,
    onTypeIn: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = Pillion.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(bottom = Space.m),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        when (state) {
            ScanState.Idle -> Unit
            ScanState.Reading -> PillionCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = colors.ink, strokeWidth = 3.dp)
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.order_reading), style = MaterialTheme.typography.titleMedium, color = colors.ink)
                        Text(stringResource(R.string.order_reading_note), style = MaterialTheme.typography.bodyMedium, color = colors.inkSecondary)
                    }
                }
            }
            is ScanState.Failed -> PillionCard {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Icon(painterResource(R.drawable.ic_info), contentDescription = null, tint = colors.ink, modifier = Modifier.padding(top = 2.dp).size(24.dp))
                    Text(
                        stringResource(if (state.reason == ScanFailure.Unreadable) R.string.order_unreadable else R.string.order_nothing_found),
                        style = MaterialTheme.typography.titleMedium,
                        color = colors.ink,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.m), modifier = Modifier.padding(top = Space.s)) {
                    PillButton(stringResource(R.string.close), onDismiss, container = colors.surfaceHigh, content = colors.ink, modifier = Modifier.weight(1f))
                    PillButton(stringResource(R.string.order_type_in), onTypeIn, modifier = Modifier.weight(1f))
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
    val colors = Pillion.colors
    val parsed = review.parsed
    val initial = remember(review) { OrderDraft.from(parsed) }
    var name by rememberSaveable(review) { mutableStateOf(initial.name) }
    var phone by rememberSaveable(review) { mutableStateOf(initial.phone) }
    var address by rememberSaveable(review) { mutableStateOf(initial.address) }
    var earning by rememberSaveable(review) { mutableStateOf("") }
    val fieldShape = MaterialTheme.shapes.medium

    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Text(
            stringResource(R.string.order_review_title),
            style = MaterialTheme.typography.headlineMedium,
            color = colors.ink,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(
                when (review.source) {
                    ScanSource.Share -> R.string.order_review_share
                    ScanSource.Camera -> R.string.order_review_camera
                    ScanSource.Manual -> R.string.order_review_manual
                    ScanSource.Sample -> R.string.order_review_sample
                    ScanSource.Gallery, ScanSource.TestImage -> R.string.order_review_gallery
                }
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = colors.inkSecondary,
        )
    }
    parsed.orderId?.takeIf { it.confidence != Confidence.Low }?.let { Tag(stringResource(R.string.order_id, it.value)) }

    OutlinedTextField(
        value = name,
        onValueChange = { name = it },
        label = { Text(stringResource(R.string.order_field_name)) },
        supportingText = hint(parsed.customerName, edited = name != initial.name),
        singleLine = true,
        shape = fieldShape,
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
        shape = fieldShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        modifier = Modifier.fillMaxWidth(),
    )
    // The rider picks when the parser couldn't tell which number is the customer's. The chips show
    // numbers masked; the field above shows the picked one in full to check.
    if (parsed.customerPhone?.confidence != Confidence.High && parsed.phoneNumbers.isNotEmpty()) {
        Text(stringResource(R.string.order_pick_number), style = MaterialTheme.typography.bodyMedium, color = colors.ink)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
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
                        Text(stringResource(if (number.ocrFixed) R.string.order_number_chip_fixed else R.string.order_number_chip, maskPhone(number.shown), role))
                    },
                    shape = CircleShape,
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = colors.ink,
                        selectedLabelColor = colors.background,
                        selectedLeadingIconColor = colors.background,
                    ),
                )
            }
        }
    }

    OutlinedTextField(
        value = address,
        onValueChange = { address = it },
        label = { Text(stringResource(R.string.order_field_address)) },
        supportingText = hint(parsed.dropAddress, edited = address != initial.address),
        minLines = 2,
        maxLines = 4,
        shape = fieldShape,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = earning,
        onValueChange = { value -> earning = value.filter(Char::isDigit).take(5) },
        label = { Text(stringResource(R.string.order_field_earning)) },
        singleLine = true,
        shape = fieldShape,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    val draft = OrderDraft(name, phone, address, earning, initial.orderId, parsed.maskedPhone)
    Row(horizontalArrangement = Arrangement.spacedBy(Space.m), modifier = Modifier.padding(top = Space.s, bottom = Space.s)) {
        PillButton(
            stringResource(R.string.cancel),
            onCancel,
            container = colors.surfaceHigh,
            content = colors.ink,
            minHeight = 64.dp,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        PillButton(
            stringResource(R.string.order_set),
            { onConfirm(draft) },
            enabled = !draft.isBlank,
            container = colors.accent,
            content = colors.onAccent,
            minHeight = 64.dp,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(2f),
        )
    }
}

/** "Check this" on unsure values, "type it in" on missing ones; nothing once the rider has edited. */
@Composable
private fun hint(field: Field?, edited: Boolean, quietWhenMissing: Boolean = false): (@Composable () -> Unit)? = when {
    edited -> null
    field == null || field.confidence == Confidence.Low -> if (quietWhenMissing) null else note(stringResource(R.string.order_type_missing))
    field.confidence == Confidence.Medium -> note(stringResource(R.string.order_check_this), Pillion.colors.caution)
    else -> null
}

private fun note(text: String, color: Color = Color.Unspecified): @Composable () -> Unit = { Text(text, color = color) }

/** DEBUG BUILDS ONLY — mock order screens (src/debug/assets) through the real OCR and parser. */
@Composable
fun DebugScanCard(onScan: (ScanSource, suspend () -> LoadedImage) -> Unit) {
    val context = LocalContext.current
    val samples = remember { context.assets.list(SAMPLES).orEmpty().sorted() }
    PillionCard {
        Text(stringResource(R.string.debug_scan_title), style = MaterialTheme.typography.titleMedium)
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

private const val SAMPLES = "order_samples"
