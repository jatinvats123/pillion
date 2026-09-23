package app.pillion.data

import android.content.Context
import androidx.core.content.edit
import app.pillion.BuildConfig

/** The delivery the rider is on right now. */
data class Order(
    val customerName: String,
    /** Stays on the phone: SMS and calls are placed here, the backend never sees the number. */
    val customerPhone: String,
    val dropAddress: String,
    val dropArea: String,
    val payoutRupees: Int,
)

/** Where the active order comes from. Phase 4 adds a source that reads the delivery app's screen (OCR). */
interface OrderSource {
    fun activeOrder(): Order?
}

/**
 * SEEDED DEMO ORDER — delivery apps offer no API, so until OCR lands the active order is this fixed
 * sample in East Delhi. It has no customer number of its own (SMS/call fail with
 * `no_customer_number`), so nothing can reach a stranger: debug builds use the developer's test
 * phone from local.properties, or one typed into the debug card.
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
        )
    }
}
