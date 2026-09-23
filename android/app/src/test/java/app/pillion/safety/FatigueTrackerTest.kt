package app.pillion.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FatigueTrackerTest {

    private val minute = 60_000L

    /** Rides [minutes] at 30 km/h from [fromMin], one GPS fix a second, checking every 30 s. */
    private fun FatigueTracker.ride(fromMin: Long, minutes: Long, kmh: Float = 30f): List<Long> {
        val reminders = mutableListOf<Long>()
        var t = fromMin * minute
        while (t < (fromMin + minutes) * minute) {
            onSpeed(t, kmh)
            if (t % 30_000 == 0L) check(t)?.let { reminders += t / minute }
            t += 1_000
        }
        return reminders
    }

    @Test
    fun `reminds after two hours, then every 30 minutes`() {
        val tracker = FatigueTracker(remindAfterMs = 120 * minute)
        assertEquals(listOf(120L, 150L, 180L), tracker.ride(0, 181))
    }

    @Test
    fun `short stops don't reset the count, a long one does`() {
        val tracker = FatigueTracker(remindAfterMs = 120 * minute)
        tracker.ride(0, 60)
        tracker.ride(60, 5, kmh = 0f) // signal / handover: 5 min
        assertEquals(listOf(120L), tracker.ride(65, 56))

        val rested = FatigueTracker(remindAfterMs = 120 * minute)
        rested.ride(0, 100)
        rested.ride(100, 15, kmh = 0f) // a real break
        assertEquals(emptyList<Long>(), rested.ride(115, 100))
        assertEquals(listOf(235L), rested.ride(215, 21))
    }

    @Test
    fun `no reminder while on a break`() {
        val tracker = FatigueTracker(remindAfterMs = 120 * minute)
        tracker.ride(0, 115)
        tracker.ride(115, 20, kmh = 0f)
        assertNull(tracker.check(135 * minute))
    }

    @Test
    fun `without GPS the ride time counts`() {
        val tracker = FatigueTracker(remindAfterMs = 2 * minute)
        assertNull(tracker.check(0))
        assertNull(tracker.check(minute))
        assertEquals(2L, tracker.check(2 * minute))
    }
}
