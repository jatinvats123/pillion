package app.pillion.order

import app.pillion.data.DropLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OrderDraftTest {

    @Test
    fun typedNumbersBecomeDialable() {
        assertEquals("+919811022334", OrderDraft.dialable("98110 22334"))
        assertEquals("+919811022334", OrderDraft.dialable("098110-22334"))
        assertEquals("+919811022334", OrderDraft.dialable("+91 98110 22334"))
        assertEquals("01145678901", OrderDraft.dialable("011 4567 8901"))
        assertEquals("", OrderDraft.dialable("  "))
    }

    @Test
    fun lowConfidenceValuesAreNotPrefilled() {
        val parsed = OrderParser.parse("Anjali Mehta\n98100 11223\n12, Preet Vihar, Delhi 110092")
        val draft = OrderDraft.from(parsed)
        assertEquals("+919810011223", draft.phone) // Medium: filled, marked "check this"
        assertEquals("", draft.address) // Low: left for the rider
        assertEquals("", draft.name) // not found
    }

    @Test
    fun draftToOrder() {
        val order = OrderDraft("Rahul Verma", "98110 22334", "Flat 12, Laxmi Nagar, Delhi 110092", "52", "NK-1", null).toOrder()
        assertEquals("+919811022334", order.customerPhone)
        assertEquals("Laxmi Nagar", order.dropArea)
        assertEquals(52, order.payoutRupees)
        assertEquals(DropLocation.Unchecked, order.drop)
        assertFalse(order.isDemo)
        assertFalse(order.phoneMasked)
    }

    @Test
    fun maskedNumberLeftEmptyAndNoEarningRecordsZero() {
        val order = OrderDraft("Amit Kumar", "", "", "", "", maskedPhone = "98XXX XX123").toOrder()
        assertTrue(order.phoneMasked)
        assertEquals(0, order.payoutRupees)
        assertEquals(DropLocation.NotFound, order.drop) // no address, no ETA
    }

    @Test
    fun spokenConfirmation() {
        val order = OrderDraft("Rahul Verma", "", "Flat 12, Laxmi Nagar, Delhi 110092", "", "", null).toOrder()
            .copy(drop = DropLocation.Found(28.63, 77.28, approximate = false))
        assertEquals("Rahul का order set हो गया, drop Laxmi Nagar में।", OrderLines.orderSet(order, hindi = true))
        assertEquals("Rahul's order is set, drop in Laxmi Nagar.", OrderLines.orderSet(order, hindi = false))
        val notOnMap = order.copy(drop = DropLocation.NotFound)
        assertTrue(OrderLines.orderSet(notOnMap, hindi = true).endsWith("ETA नहीं बता पाऊँगी।"))
    }
}
