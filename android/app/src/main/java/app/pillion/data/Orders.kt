package app.pillion.data

import android.content.Context
import androidx.core.content.edit
import app.pillion.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Where the drop is on the map, for ETA. */
sealed interface DropLocation {
    /** Not looked up yet (the server was out of reach): the server looks it up when ETA is asked. */
    data object Unchecked : DropLocation

    /** [approximate]: only the drop's locality was found, not the building. */
    data class Found(val lat: Double, val lng: Double, val approximate: Boolean) : DropLocation

    /** The map search found nothing, or several far-apart places: no ETA rather than a wrong one. */
    data object NotFound : DropLocation
}

/** The delivery the rider is on right now. */
data class Order(
    val customerName: String,
    /** Stays on the phone: SMS and calls are placed here, the backend never sees the number. */
    val customerPhone: String,
    val dropAddress: String,
    val dropArea: String,
    val payoutRupees: Int,
    val orderId: String = "",
    /** The delivery app masks the customer's number and the rider didn't type it in. */
    val phoneMasked: Boolean = false,
    val drop: DropLocation = DropLocation.Unchecked,
    /** The seeded sample order, not one the rider scanned. */
    val isDemo: Boolean = false,
)

/** Where the active order comes from. */
interface OrderSource {
    fun activeOrder(): Order?
}

/**
 * SEEDED DEMO ORDER — delivery apps offer no API, so until the rider scans an order the active
 * order is this fixed sample in East Delhi. It has no customer number of its own (SMS/call fail
 * with `no_customer_number`), so nothing can reach a stranger: debug builds use the developer's
 * test phone from local.properties, or one typed into the debug card.
 */
class SeededOrderSource(context: Context) : OrderSource {

    private val prefs = context.applicationContext.getSharedPreferences("seeded_order", Context.MODE_PRIVATE)

    override fun activeOrder(): Order =
        SEEDED_ORDER.copy(customerPhone = prefs.getString(KEY_PHONE, null) ?: BuildConfig.TEST_CUSTOMER_PHONE)

    /** Debug builds: point the seeded customer at a test number. */
    fun setCustomerPhone(number: String) {
        prefs.edit { putString(KEY_PHONE, number.trim()) }
    }

    private companion object {
        const val KEY_PHONE = "customer_phone"

        val SEEDED_ORDER = Order(
            customerName = "Rahul Verma",
            customerPhone = "",
            // Worded so free geocoders resolve it exactly (adding "Vikas Marg, 110092" lands ~2 km off).
            dropAddress = "Laxmi Nagar Metro Station, Delhi",
            dropArea = "Laxmi Nagar",
            payoutRupees = 48,
            isDemo = true,
        )
    }
}

/**
 * The order the rider scanned from their delivery app (kept on the phone only, until replaced or
 * cleared), else the seeded demo order.
 */
class ActiveOrderSource(context: Context) : OrderSource {

    private val prefs = context.applicationContext.getSharedPreferences("active_order", Context.MODE_PRIVATE)
    private val seeded = SeededOrderSource(context)
    private val _order = MutableStateFlow(load() ?: seeded.activeOrder())
    val order: StateFlow<Order> = _order.asStateFlow()

    override fun activeOrder(): Order = _order.value

    fun set(order: Order) {
        prefs.edit { putString(KEY_ORDER, order.toJson().toString()) }
        _order.value = order
    }

    /** The drop's map lookup finished; ignored if the rider has set another order since. */
    fun updateDrop(of: Order, drop: DropLocation, area: String) {
        if (_order.value != of) return
        set(of.copy(drop = drop, dropArea = area))
    }

    /** Back to the demo order. */
    fun clear() {
        prefs.edit { remove(KEY_ORDER) }
        _order.value = seeded.activeOrder()
    }

    /** Debug builds: the demo order's customer number for SMS/call tests. */
    fun setDemoCustomerPhone(number: String) {
        seeded.setCustomerPhone(number)
        if (_order.value.isDemo) _order.value = seeded.activeOrder()
    }

    private fun load(): Order? = prefs.getString(KEY_ORDER, null)?.let { runCatching { orderFrom(JSONObject(it)) }.getOrNull() }

    private fun Order.toJson() = JSONObject()
        .put("name", customerName)
        .put("phone", customerPhone)
        .put("address", dropAddress)
        .put("area", dropArea)
        .put("payout", payoutRupees)
        .put("orderId", orderId)
        .put("phoneMasked", phoneMasked)
        .apply {
            when (drop) {
                is DropLocation.Found -> put("drop", "found").put("lat", drop.lat).put("lng", drop.lng).put("approximate", drop.approximate)
                DropLocation.NotFound -> put("drop", "not_found")
                DropLocation.Unchecked -> put("drop", "unchecked")
            }
        }

    private fun orderFrom(json: JSONObject) = Order(
        customerName = json.getString("name"),
        customerPhone = json.getString("phone"),
        dropAddress = json.getString("address"),
        dropArea = json.getString("area"),
        payoutRupees = json.getInt("payout"),
        orderId = json.optString("orderId"),
        phoneMasked = json.optBoolean("phoneMasked"),
        drop = when (json.optString("drop")) {
            "found" -> DropLocation.Found(json.getDouble("lat"), json.getDouble("lng"), json.optBoolean("approximate"))
            "not_found" -> DropLocation.NotFound
            else -> DropLocation.Unchecked
        },
    )

    private companion object {
        const val KEY_ORDER = "order"
    }
}
