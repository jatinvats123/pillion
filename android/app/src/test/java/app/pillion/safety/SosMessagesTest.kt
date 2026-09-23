package app.pillion.safety

import java.util.Calendar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SosMessagesTest {

    private val at = Calendar.getInstance().apply { set(2026, Calendar.SEPTEMBER, 24, 0, 31) }.timeInMillis
    private val fix = SosFix(lat = 28.631204, lng = 77.280119, accuracyM = 15.4f, ageMs = 3_000)

    @Test
    fun `first SOS after a crash`() {
        assertEquals(
            "PILLION SOS: Jatin may have had a road accident at 12:31 AM. " +
                "Location: https://maps.google.com/?q=28.63120,77.28012 (accuracy 15 m). Please call now. " +
                "Jatin ka accident ho sakta hai, turant call karein.",
            SosMessages.first("Jatin", SosMessages.Reason.Crash, at, fix),
        )
    }

    @Test
    fun `messages fit two GSM-7 parts with a long name`() {
        val longName = "Jatin Kumar Vats"
        val messages = listOf(
            SosMessages.first(longName, SosMessages.Reason.Crash, at, fix.copy(ageMs = 7 * 60_000)),
            SosMessages.first(longName, SosMessages.Reason.RiderAsked, at, null),
            SosMessages.followUp(longName, at, fix),
            SosMessages.riderOk(longName, at),
        )
        messages.forEach { message ->
            assertTrue("GSM-7 only: $message", message.all { it in GSM7 })
            assertTrue("${message.length} chars: $message", message.length <= 2 * 153)
        }
    }

    @Test
    fun `old or missing fixes say so`() {
        assertTrue(SosMessages.location(fix.copy(ageMs = 2 * 60_000)).endsWith("(accuracy 15 m, from 2 min ago)"))
        assertTrue(SosMessages.location(fix.copy(ageMs = 3 * 3_600_000L)).endsWith("from 3 h ago)"))
        assertEquals("not available (no GPS fix)", SosMessages.location(null))
    }

    @Test
    fun `no name set`() {
        assertTrue(SosMessages.first(" ", SosMessages.Reason.Crash, at, fix).startsWith("PILLION SOS: Your contact may have"))
    }

    private companion object {
        // GSM 03.38 basic character set (the part that matters here).
        val GSM7 = (('A'..'Z') + ('a'..'z') + ('0'..'9')).toSet() + " .,:;?!'\"()/-+=&%@_#*<>\n".toSet()
    }
}
