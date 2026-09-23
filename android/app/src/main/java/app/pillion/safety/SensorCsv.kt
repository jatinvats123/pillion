package app.pillion.safety

/**
 * Sensor traces as CSV — what the debug sensor recorder writes and the detector tests replay:
 * `t_ms,type,x,y,z` with type `a` (accelerometer, m/s²), `g` (gyroscope, rad/s) or `s` (GPS
 * speed in km/h, in the x column). Rows are in the order the phone received them.
 */
object SensorCsv {
    const val HEADER = "t_ms,type,x,y,z"

    fun line(sample: SensorSample): String = when (sample) {
        is Accel -> "${sample.tMs},a,${sample.x},${sample.y},${sample.z}"
        is Gyro -> "${sample.tMs},g,${sample.x},${sample.y},${sample.z}"
        is Speed -> "${sample.tMs},s,${sample.kmh},,"
    }

    /** Null for the header, blank or malformed lines. */
    fun parse(line: String): SensorSample? {
        val cols = line.trim().split(',')
        if (cols.size < 3) return null
        val t = cols[0].toLongOrNull() ?: return null
        val x = cols[2].toFloatOrNull() ?: return null
        if (cols[1] == "s") return Speed(t, x)
        val y = cols.getOrNull(3)?.toFloatOrNull() ?: return null
        val z = cols.getOrNull(4)?.toFloatOrNull() ?: return null
        return when (cols[1]) {
            "a" -> Accel(t, x, y, z)
            "g" -> Gyro(t, x, y, z)
            else -> null
        }
    }
}
