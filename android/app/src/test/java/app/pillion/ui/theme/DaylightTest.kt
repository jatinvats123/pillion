package app.pillion.ui.theme

import java.time.ZonedDateTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DaylightTest {

    private fun at(iso: String) = ZonedDateTime.parse(iso).toInstant().toEpochMilli()

    // Delhi on 24 Sep: sunrise ≈ 06:10, sunset ≈ 18:15 IST.
    @Test
    fun delhiEveningIsNight() = assertTrue(isNight(at("2026-09-24T19:10+05:30"), DEFAULT_LAT, DEFAULT_LNG))

    @Test
    fun delhiAfternoonIsDay() = assertFalse(isNight(at("2026-09-24T15:00+05:30"), DEFAULT_LAT, DEFAULT_LNG))

    @Test
    fun delhiJustBeforeSunriseIsNight() = assertTrue(isNight(at("2026-09-24T05:50+05:30"), DEFAULT_LAT, DEFAULT_LNG))

    @Test
    fun delhiJustAfterSunriseIsDay() = assertFalse(isNight(at("2026-09-24T06:30+05:30"), DEFAULT_LAT, DEFAULT_LNG))

    // Summer evenings stay light for longer: 19:00 in June is still day in Delhi (sunset ≈ 19:20).
    @Test
    fun delhiJuneSevenPmIsDay() = assertFalse(isNight(at("2026-06-21T19:00+05:30"), DEFAULT_LAT, DEFAULT_LNG))

    // West of Greenwich, sunset falls after midnight UTC (the wrapped case).
    @Test
    fun mountainViewEveningIsNight() = assertTrue(isNight(at("2026-09-24T21:00-07:00"), 37.42, -122.08))

    @Test
    fun mountainViewNoonIsDay() = assertFalse(isNight(at("2026-09-24T12:00-07:00"), 37.42, -122.08))
}
