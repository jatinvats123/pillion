package app.pillion.safety

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash detector against synthetic traces (see [TraceBuilder]): what must never trigger, what
 * must, and the known limits, written down as tests so they stay true. Each scenario runs with
 * several random seeds (different noise).
 */
class CrashDetectorTest {

    // ---- Must NOT trigger: normal riding in Delhi.

    @Test
    fun `normal city riding with bumps and signals`() = seeds { seed ->
        val trace = TraceBuilder(seed).apply {
            repeat(10) { lap ->
                ride(20_000, kmh = 30.0 + lap % 3 * 5)
                impact(2.0 + lap % 4 * 0.5, joltRadS = 2.0) // 2.0–3.5 g bumps
                ride(15_000, kmh = 40.0)
                brake(3_000, toKmh = 0.0)
                idle(20_000)
                brake(4_000, toKmh = 30.0)
            }
        }.build()
        val events = run("normal riding (${trace.minutes()} min)", trace)
        assertTrue(events.crashes().isEmpty())
    }

    @Test
    fun `speed breaker taken slowly`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 30.0).brake(2_000, toKmh = 15.0)
            .impact(2.5, joltRadS = 3.0).ride(300, kmh = 15.0).impact(2.8, joltRadS = 3.0)
            .ride(20_000, kmh = 20.0)
            .build()
        assertTrue(run("speed breaker, slow", trace).crashes().isEmpty())
    }

    @Test
    fun `speed breaker taken fast`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 30.0)
            .impact(4.5, joltRadS = 5.0).ride(250, kmh = 30.0).impact(5.0, joltRadS = 5.0)
            .ride(30_000, kmh = 28.0)
            .build()
        val events = run("speed breaker, fast (4.5–5 g)", trace)
        assertTrue(events.crashes().isEmpty())
    }

    @Test
    fun `sharp pothole on a handlebar mount`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 35.0)
            .impact(6.5, joltRadS = 6.0)
            .ride(30_000, kmh = 30.0, vibrationG = 0.35)
            .build()
        val events = run("pothole on mount (6.5 g)", trace)
        assertTrue(events.crashes().isEmpty())
        assertTrue(events.rejections().all { it.reason.startsWith("no tumble") })
    }

    @Test
    fun `pothole then stopping at a signal`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 35.0)
            .impact(5.0, joltRadS = 4.0)
            .brake(3_000, toKmh = 0.0)
            .idle(40_000)
            .build()
        assertTrue(run("pothole then signal", trace).crashes().isEmpty())
    }

    @Test
    fun `pothole while turning into a lane, then stopping at the customer's gate`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 25.0)
            .rotate(25.0, 500, Axis.Z) // lean in (about the forward axis)
            .turn(45.0, 1_500)
            .impact(5.0, joltRadS = 4.0)
            .turn(45.0, 1_500)
            .rotate(-25.0, 500, Axis.Z) // upright again
            .brake(1_500, toKmh = 0.0)
            .still(20_000) // engine off, phone in pocket
            .build()
        val events = run("pothole mid-turn, then stop", trace)
        assertTrue(events.crashes().isEmpty())
    }

    @Test
    fun `hard braking to a stop`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 45.0)
            .brake(1_500, toKmh = 0.0) // ~0.85 g
            .idle(30_000)
            .build()
        val events = run("hard braking", trace)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `hard braking with a jolt`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 45.0)
            .brake(1_200, toKmh = 0.0)
            .impact(4.5, joltRadS = 3.0)
            .idle(30_000)
            .build()
        assertTrue(run("hard braking + 4.5 g jolt", trace).crashes().isEmpty())
    }

    @Test
    fun `phone picked up at a signal`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 30.0).brake(3_000, toKmh = 0.0).idle(5_000)
            .rotate(80.0, 800, Axis.X, noiseG = 0.3)
            .handle(15_000)
            .rotate(-80.0, 800, Axis.X, noiseG = 0.3)
            .idle(10_000)
            .build()
        assertTrue(run("phone pickup", trace).isEmpty())
    }

    @Test
    fun `phone dropped at a signal`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 30.0).brake(3_000, toKmh = 0.0).idle(12_000)
            .handle(3_000).freeFall(250).impact(8.0).rotate(150.0, 400, Axis.X).still(30_000)
            .build()
        val events = run("phone dropped at a signal", trace)
        assertTrue(events.crashes().isEmpty())
        assertTrue(events.ignored().any { it.reason.startsWith("not moving") })
    }

    @Test
    fun `phone dropped while parked`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .still(40_000).handle(5_000).freeFall(300).impact(9.0).rotate(180.0, 500, Axis.X).still(30_000)
            .build()
        val events = run("parked drop (9 g)", trace)
        assertTrue(events.crashes().isEmpty())
        assertTrue(events.ignored().isNotEmpty())
    }

    // ---- Must trigger.

    @Test
    fun `crash with the phone in a pocket`() = seeds { seed ->
        val events = run("crash, pocket", SyntheticTraces.crash(seed))
        val crash = events.crashes().single()
        assertEquals("still", crash.down)
        assertTrue("confirmed ${crash.atMs - crash.impactAtMs} ms after the impact", crash.atMs - crash.impactAtMs < 12_000)
    }

    @Test
    fun `crash with the phone on the mount of a bike whose engine keeps running`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(12_000, kmh = 30.0)
            .impact(5.0).speed(8.0)
            .rotate(90.0, 700, Axis.Z, noiseG = 0.4) // the bike falls on its side
            .speed(0.0)
            .still(30_000, noiseG = 0.15, gyroNoise = 0.15) // engine vibration, phone lying sideways
            .build()
        val crash = run("crash, mount, engine running", trace).crashes().single()
        assertEquals("lying", crash.down)
    }

    @Test
    fun `pothole, then a crash two seconds later`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(12_000, kmh = 35.0)
            .impact(5.0, joltRadS = 4.0)
            .ride(2_000, kmh = 30.0)
            .impact(7.0).speed(10.0).rotate(110.0, 800, Axis.X, noiseG = 0.5).speed(0.0)
            .still(20_000)
            .build()
        assertEquals(1, run("pothole then crash", trace).crashes().size)
    }

    @Test
    fun `crash under a flyover, GPS gone for 15 s`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 40.0)
            .noGps().ride(15_000, kmh = 40.0)
            .impact(5.0).rotate(100.0, 800, Axis.X, noiseG = 0.5).speed(0.0)
            .still(20_000)
            .build()
        val crash = run("crash, GPS gone 15 s", trace).crashes().single()
        assertTrue(!crash.gpsLost)
    }

    @Test
    fun `crash with GPS lost for over 30 s needs 6 g`() = seeds { seed ->
        fun trace(peakG: Double) = TraceBuilder(seed)
            .noGps().ride(60_000, kmh = 30.0)
            .impact(peakG).rotate(120.0, 900, Axis.X, noiseG = 0.5).speed(0.0)
            .still(20_000)
            .build()
        val strong = run("crash, no GPS, 7 g", trace(7.0)).crashes().single()
        assertTrue(strong.gpsLost)
        val weaker = run("crash, no GPS, 5 g", trace(5.0))
        assertTrue(weaker.crashes().isEmpty())
        assertTrue(weaker.ignored().any { it.reason.startsWith("below 6.0 g without GPS") })
    }

    @Test
    fun `potholes with GPS lost don't trigger`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .noGps().ride(60_000, kmh = 30.0)
            .impact(7.0, joltRadS = 6.0)
            .ride(30_000, kmh = 30.0, vibrationG = 0.35)
            .build()
        assertTrue(run("pothole, no GPS, 7 g", trace).crashes().isEmpty())
    }

    @Test
    fun `sensors delivered in one-second batches per sensor`() = seeds { seed ->
        // Android batches sensor events while the CPU sleeps: a second of accelerometer, then a
        // second of gyroscope, then GPS.
        val batched = SyntheticTraces.crash(seed)
            .groupBy { it.tMs / 1_000 }.toSortedMap().values
            .flatMap { second -> second.filterIsInstance<Accel>() + second.filterIsInstance<Gyro>() + second.filterIsInstance<Speed>() }
        assertEquals(1, run("crash, batched delivery", batched).crashes().size)
    }

    @Test
    fun `phone without a gyroscope`() = seeds { seed ->
        val noGyro = SyntheticTraces.crash(seed).filter { it !is Gyro }
        assertEquals(1, run("crash, no gyroscope", noGyro).crashes().size)
    }

    @Test
    fun `demo mode detects a throw onto a mattress while standing still`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .still(20_000, noiseG = 0.05)
            .freeFall(300).impact(3.0, ms = 100).rotate(90.0, 400, Axis.X).still(20_000)
            .build()
        assertTrue(run("mattress throw, default config", trace).crashes().isEmpty())
        assertEquals(1, run("mattress throw, demo mode", trace, CrashConfig.DEMO).crashes().size)
    }

    @Test
    fun `picking the phone up and dropping it again after a crash gives no second alert`() = seeds { seed ->
        val crash = SyntheticTraces.crash(seed)
        val after = TraceBuilder(seed, startMs = crash.last().tMs + 20)
            .handle(4_000).freeFall(250).impact(8.0).rotate(150.0, 400, Axis.X).still(20_000)
            .build()
        assertEquals(1, run("crash, then phone dropped again", crash + after).crashes().size)
    }

    @Test
    fun `impact thresholds stay reachable on a low-range accelerometer`() {
        val config = CrashConfig().forSensorRange(maxRangeG = 4f)
        assertEquals(3.6f, config.impactG, 0.01f)
        assertEquals(3.6f, config.impactNoGpsG, 0.01f)
        assertEquals(4f, CrashConfig().forSensorRange(16f).impactG, 0.01f)
    }

    // ---- Known limits: the detector's actual behaviour, stated for the README.

    @Test
    fun `LIMIT - hit while stopped at a signal is missed (speed gate)`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 30.0).brake(3_000, toKmh = 0.0).idle(15_000)
            .impact(6.0).rotate(100.0, 800, Axis.X, noiseG = 0.5).still(20_000)
            .build()
        assertTrue(run("LIMIT rear-ended at a signal", trace).crashes().isEmpty())
    }

    @Test
    fun `LIMIT - rider who gets up and moves around is not alerted`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(12_000, kmh = 35.0)
            .impact(7.0).rotate(120.0, 900, Axis.X, noiseG = 0.5).speed(0.0)
            .still(3_000).handle(30_000)
            .build()
        val events = run("LIMIT rider gets up", trace)
        assertTrue(events.crashes().isEmpty())
        assertTrue(events.rejections().any { it.reason.startsWith("not down") })
    }

    @Test
    fun `LIMIT - phone falling off while riding raises an alert`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(12_000, kmh = 35.0)
            .freeFall(400).impact(9.0).rotate(200.0, 600, Axis.X, noiseG = 0.5).speed(0.0)
            .still(20_000)
            .build()
        assertEquals(1, run("LIMIT phone falls off while riding", trace).crashes().size)
    }

    @Test
    fun `LIMIT - phone dropped within seconds of stopping raises an alert`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .ride(10_000, kmh = 30.0).brake(2_000, toKmh = 0.0).idle(2_000)
            .freeFall(250).impact(8.0).rotate(150.0, 400, Axis.X).still(20_000)
            .build()
        assertEquals(1, run("LIMIT dropped 2 s after stopping", trace).crashes().size)
    }

    @Test
    fun `LIMIT - phone dropped while parked with GPS lost raises an alert`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .noGps().still(40_000).handle(5_000).freeFall(300).impact(9.0).rotate(180.0, 500, Axis.X).still(20_000)
            .build()
        assertEquals(1, run("LIMIT parked drop, no GPS", trace).crashes().size)
    }

    @Test
    fun `LIMIT - no detection in the first 30 s of a ride before any GPS fix`() = seeds { seed ->
        val trace = TraceBuilder(seed)
            .noGps().ride(10_000, kmh = 30.0)
            .impact(7.0).rotate(120.0, 900, Axis.X, noiseG = 0.5).speed(0.0).still(15_000)
            .build()
        val events = run("LIMIT crash before first GPS fix", trace)
        assertTrue(events.crashes().isEmpty())
        assertTrue(events.ignored().any { it.reason == "no GPS fix yet" })
    }

    // ---- Helpers.

    private fun seeds(test: (Long) -> Unit) = (1L..5L).forEach(test)

    private fun run(name: String, samples: List<SensorSample>, config: CrashConfig = CrashConfig()): List<DetectorEvent> {
        val detector = CrashDetector(config)
        val events = samples.mapNotNull(detector::onSample)
        println("[$name] ${if (events.isEmpty()) "no events" else events.joinToString("; ") { it.summary() }}")
        return events
    }

    private fun DetectorEvent.summary(): String = when (this) {
        is DetectorEvent.Crash -> "CRASH ${describe()}"
        is DetectorEvent.Rejected -> "rejected: $reason (peak ${"%.1f".format(peakG)} g)"
        is DetectorEvent.ImpactIgnored -> "ignored ${"%.1f".format(peakG)} g: $reason"
    }

    private fun List<DetectorEvent>.crashes() = filterIsInstance<DetectorEvent.Crash>()
    private fun List<DetectorEvent>.rejections() = filterIsInstance<DetectorEvent.Rejected>()
    private fun List<DetectorEvent>.ignored() = filterIsInstance<DetectorEvent.ImpactIgnored>()
    private fun List<SensorSample>.minutes() = (last().tMs - first().tMs) / 60_000
}
