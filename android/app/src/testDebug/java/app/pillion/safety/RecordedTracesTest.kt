package app.pillion.safety

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays sensor recordings from the debug recorder (`adb pull /sdcard/Android/data/app.pillion/files/traces`)
 * through the detector. Put them in `src/testDebug/resources/traces/`:
 * - `ride_*.csv` — normal riding: must not trigger.
 * - `crash_*.csv` — a staged fall (e.g. the phone thrown onto a mattress in demo mode): must trigger
 *   with the demo config if the name contains `demo`, else with the default config.
 * - anything else is only reported.
 */
class RecordedTracesTest {

    @Test
    fun `csv round trip keeps a crash a crash`() {
        val csv = listOf(SensorCsv.HEADER) + SyntheticTraces.crash().map(SensorCsv::line)
        val parsed = csv.mapNotNull(SensorCsv::parse)
        assertEquals(csv.size - 1, parsed.size)
        val detector = CrashDetector()
        assertEquals(1, parsed.mapNotNull(detector::onSample).count { it is DetectorEvent.Crash })
    }

    @Test
    fun `recorded traces`() {
        val files = File("src/testDebug/resources/traces").listFiles { f -> f.extension == "csv" }.orEmpty().sortedBy { it.name }
        if (files.isEmpty()) {
            println("[recorded] no traces in src/testDebug/resources/traces — synthetic tests only")
            return
        }
        files.forEach { file ->
            val samples = file.readLines().mapNotNull(SensorCsv::parse)
            val config = if ("demo" in file.name) CrashConfig.DEMO else CrashConfig()
            val detector = CrashDetector(config)
            val events = samples.mapNotNull(detector::onSample)
            val minutes = if (samples.isEmpty()) 0 else (samples.last().tMs - samples.first().tMs) / 60_000.0
            println("[recorded ${file.name}] ${"%.1f".format(minutes)} min, ${samples.size} samples: " +
                events.joinToString("; ").ifEmpty { "no events" })
            val crashes = events.count { it is DetectorEvent.Crash }
            when {
                file.name.startsWith("ride_") -> assertEquals("${file.name} must not trigger", 0, crashes)
                file.name.startsWith("crash_") -> assertTrue("${file.name} must trigger", crashes >= 1)
            }
        }
    }
}
