package app.pillion.ride

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StartGateTest {

    @Test
    fun nothingStartsWithoutARequest() {
        // A share, a re-created activity or a re-delivered permission result: no tap came first.
        assertNull(StartGate().consume(nowMs = 5_000))
    }

    @Test
    fun oneRequestLetsExactlyOneStartThrough() {
        val gate = StartGate()
        gate.request(nowMs = 1_000)
        assertEquals(2_500L, gate.consume(nowMs = 3_500))
        assertNull(gate.consume(nowMs = 3_600)) // a duplicate result doesn't start a second ride
    }

    @Test
    fun staleRequestIsRefused() {
        val gate = StartGate(maxAgeMs = 120_000)
        gate.request(nowMs = 0)
        assertNull(gate.consume(nowMs = 120_001))
    }

    @Test
    fun slowPermissionPromptsStillCount() {
        val gate = StartGate(maxAgeMs = 120_000)
        gate.request(nowMs = 0)
        assertEquals(90_000L, gate.consume(nowMs = 90_000))
    }
}
