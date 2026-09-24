package app.pillion.ui.theme

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** New Delhi, when the phone has no position yet. */
const val DEFAULT_LAT = 28.61
const val DEFAULT_LNG = 77.21

/**
 * True between sunset and sunrise at this place (NOAA's approximate solar equations, ±2 min).
 * The ride screen goes dark then: light text on dark avoids glare and after-images at night.
 */
fun isNight(nowMs: Long, lat: Double, lng: Double): Boolean {
    val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = nowMs }
    val minutes = utc.get(Calendar.HOUR_OF_DAY) * 60 + utc.get(Calendar.MINUTE)
    val gamma = 2 * PI / 365 * (utc.get(Calendar.DAY_OF_YEAR) - 1 + (minutes / 60.0 - 12) / 24)
    val equationOfTime = 229.18 * (0.000075 + 0.001868 * cos(gamma) - 0.032077 * sin(gamma) -
        0.014615 * cos(2 * gamma) - 0.040849 * sin(2 * gamma))
    val declination = 0.006918 - 0.399912 * cos(gamma) + 0.070257 * sin(gamma) - 0.006758 * cos(2 * gamma) +
        0.000907 * sin(2 * gamma) - 0.002697 * cos(3 * gamma) + 0.00148 * sin(3 * gamma)
    val latRad = Math.toRadians(lat)
    val cosHourAngle = cos(Math.toRadians(90.833)) / (cos(latRad) * cos(declination)) - tan(latRad) * tan(declination)
    if (cosHourAngle >= 1) return true // polar night
    if (cosHourAngle <= -1) return false // midnight sun
    val hourAngle = Math.toDegrees(acos(cosHourAngle))
    val sunrise = (720 - 4 * (lng + hourAngle) - equationOfTime).mod(1440.0)
    val sunset = (720 - 4 * (lng - hourAngle) - equationOfTime).mod(1440.0)
    // In UTC minutes of the day: India's sunrise ≈ 00:40, sunset ≈ 12:40. Far west, sunset can wrap past midnight.
    return if (sunrise < sunset) minutes < sunrise || minutes >= sunset else minutes in sunset.toInt()..<sunrise.toInt()
}
