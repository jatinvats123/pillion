package app.pillion.device

import app.pillion.data.Order
import app.pillion.safety.EmergencyContact
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IncomingCallsTest {

    // Invented numbers.
    private val order = Order(
        customerName = "Rahul Verma",
        customerPhone = "+91 98765 43210",
        dropAddress = "Laxmi Nagar Metro Station, Delhi",
        dropArea = "Laxmi Nagar",
        payoutRupees = 48,
    )
    private val contacts = listOf(EmergencyContact("Mummy", "098111 22233"), EmergencyContact("Bhaiya", "+91-99999-00011"))

    @Test
    fun `the order's customer, whatever the number format`() {
        for (number in listOf("+919876543210", "919876543210", "09876543210", "9876543210", "98765-43210", "+91 98765 43210")) {
            assertEquals(number, Caller(CallerKind.Customer, "Rahul"), identifyCaller(number, order, contacts))
        }
    }

    @Test
    fun `an emergency contact by name`() {
        assertEquals(Caller(CallerKind.EmergencyContact, "Mummy"), identifyCaller("+919811122233", order, contacts))
        assertEquals(Caller(CallerKind.EmergencyContact, "Bhaiya"), identifyCaller("9999900011", order, contacts))
    }

    @Test
    fun `the customer wins when a number is both`() {
        val both = contacts + EmergencyContact("Test phone", "9876543210")
        assertEquals(CallerKind.Customer, identifyCaller("9876543210", order, both).kind)
    }

    @Test
    fun `unknown, hidden or short numbers are unknown callers`() {
        assertEquals(Caller(CallerKind.Unknown), identifyCaller("+917000000001", order, contacts))
        assertEquals(Caller(CallerKind.Unknown), identifyCaller(null, order, contacts))
        assertEquals(Caller(CallerKind.Unknown), identifyCaller("", order, contacts))
        assertEquals(Caller(CallerKind.Unknown), identifyCaller("121", order, contacts))
    }

    @Test
    fun `a sample order or one without a number never matches`() {
        assertEquals(CallerKind.Unknown, identifyCaller("9876543210", order.copy(isSample = true), emptyList()).kind)
        assertEquals(CallerKind.Unknown, identifyCaller("9876543210", order.copy(customerPhone = ""), emptyList()).kind)
        assertEquals(CallerKind.Unknown, identifyCaller("9876543210", null, emptyList()).kind)
    }

    @Test
    fun `a blank customer name is left out`() {
        assertEquals(Caller(CallerKind.Customer, null), identifyCaller("9876543210", order.copy(customerName = " "), emptyList()))
    }

    private fun assertReply(expected: CallReply, vararg transcripts: String) = transcripts.forEach {
        assertEquals("\"$it\"", expected, CallReplies.classify(it))
    }

    @Test
    fun `answer it`() = assertReply(
        CallReply.Answer,
        // Heard on the Realme while the phone rang.
        "हां उठाओ।", "उठाओ।", "उठा को उठा",
        "हाँ", "हाँ जी", "उठा लो", "ठीक है उठा दो", "haan utha lo", "Haan ji", "uthao", "Yes", "Yes, answer it", "pick it up", "okay",
    )

    @Test
    fun `decline it`() = assertReply(
        CallReply.Decline,
        "नहीं", "बाद में", "काट दो", "मत उठाओ", "अभी नहीं", "nahi", "baad mein", "cut karo", "mat uthao", "No", "not now", "later",
        "haan, cut kar do", "nahi nahi, baad mein",
    )

    @Test
    fun `anything else is unclear`() = assertReply(
        CallReply.Unclear,
        "", "कौन है?", "kiska call hai", "who is it", "petrol pump kahan hai", "hello",
    )

    @Test
    fun `the phone's own question is never read as the rider's answer`() {
        val callers = listOf(
            Caller(CallerKind.Customer, "Rahul"), Caller(CallerKind.Customer), Caller(CallerKind.EmergencyContact, "Mummy"),
            Caller(CallerKind.Unknown),
        )
        for (caller in callers) for (orderActive in listOf(true, false)) {
            val line = callQuestion(caller, orderActive)
            assertReply(CallReply.Unclear, line.hindi, line.english)
        }
    }

    @Test
    fun `the question names the caller`() {
        assertEquals("Customer Rahul का call आ रहा है। उठाऊँ?", callQuestion(Caller(CallerKind.Customer, "Rahul"), true).hindi)
        assertEquals("Mummy is calling. Take it?", callQuestion(Caller(CallerKind.EmergencyContact, "Mummy"), true).english)
        assertEquals("Unknown number से call है। उठाऊँ?", callQuestion(Caller(CallerKind.Unknown), false).hindi)
    }

    @Test
    fun `Pillion steps aside for any call unless it may ask while ringing`() {
        // Feature off (today's rule): ringing and answered calls both pause Pillion.
        assertTrue(pausesPillion(CallState.Ringing("9876543210"), askWhileRinging = false))
        assertTrue(pausesPillion(CallState.Offhook, askWhileRinging = false))
        assertFalse(pausesPillion(CallState.Idle, askWhileRinging = false))
        // Feature live: Pillion stays on while it rings, steps aside once the call is on.
        assertFalse(pausesPillion(CallState.Ringing(null), askWhileRinging = true))
        assertTrue(pausesPillion(CallState.Offhook, askWhileRinging = true))
        assertFalse(pausesPillion(CallState.Idle, askWhileRinging = true))
    }
}
