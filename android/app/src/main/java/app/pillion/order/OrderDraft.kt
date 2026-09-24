package app.pillion.order

import app.pillion.data.DropLocation
import app.pillion.data.Order

/** The confirm card's fields as the rider left them. */
data class OrderDraft(
    val name: String,
    val phone: String,
    val address: String,
    /** Optional: the rider types what the order pays, so the trip log doesn't record ₹0. */
    val earning: String,
    val orderId: String,
    /** The masked number the screen showed, if any. */
    val maskedPhone: String?,
) {
    val isBlank: Boolean get() = name.isBlank() && phone.isBlank() && address.isBlank()

    fun toOrder(): Order {
        val dial = dialable(phone)
        return Order(
            customerName = name.trim(),
            customerPhone = dial,
            dropAddress = address.trim(),
            dropArea = OrderParser.areaOf(address.trim()).orEmpty(),
            payoutRupees = earning.filter(Char::isDigit).take(6).toIntOrNull() ?: 0,
            orderId = orderId,
            phoneMasked = dial.isEmpty() && maskedPhone != null,
            drop = if (address.isBlank()) DropLocation.NotFound else DropLocation.Unchecked,
        )
    }

    companion object {
        fun from(parsed: ParsedOrder) = OrderDraft(
            name = parsed.customerName.prefill(),
            phone = parsed.customerPhone.prefill(),
            address = parsed.dropAddress.prefill(),
            earning = "",
            orderId = parsed.orderId.prefill(),
            maskedPhone = parsed.maskedPhone,
        )

        /** Low-confidence values are left for the rider to fill, never pre-filled. */
        private fun Field?.prefill() = this?.takeIf { it.confidence != Confidence.Low }?.value.orEmpty()

        /** What the rider typed or picked, as a number the phone can dial: Indian mobiles get +91. */
        fun dialable(input: String): String {
            val plus = input.trim().startsWith("+")
            val digits = input.filter(Char::isDigit)
            return when {
                digits.isEmpty() -> ""
                !plus && digits.length == 10 && digits[0] in '6'..'9' -> "+91$digits"
                !plus && digits.length == 11 && digits[0] == '0' && digits[1] in '6'..'9' -> "+91${digits.drop(1)}"
                plus -> "+$digits"
                else -> digits
            }
        }
    }
}

/** What Pillion says when the rider sets an order during a ride (Agora speak API). */
object OrderLines {
    fun orderSet(order: Order, hindi: Boolean): String {
        val first = order.customerName.substringBefore(' ').takeIf { it.isNotBlank() }
        val area = order.dropArea.takeIf { it.isNotBlank() }
        val noMap = order.drop == DropLocation.NotFound && order.dropAddress.isNotBlank()
        return if (hindi) {
            buildString {
                append(if (first != null) "$first का order set हो गया" else "नया order set हो गया")
                if (area != null) append(", drop $area में")
                append("।")
                if (noMap) append(" पर drop का पता map पर नहीं मिला, इसलिए ETA नहीं बता पाऊँगी।")
            }
        } else {
            buildString {
                append(if (first != null) "$first's order is set" else "New order set")
                if (area != null) append(", drop in $area")
                append(".")
                if (noMap) append(" I couldn't find the drop on the map, so I can't give an ETA.")
            }
        }
    }

    fun scanWhenStopped(hindi: Boolean): String =
        if (hindi) "Bike रोककर order scan कीजिए।" else "Scan the order once you've stopped."
}
