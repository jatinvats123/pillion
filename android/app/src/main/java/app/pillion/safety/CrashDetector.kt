package app.pillion.safety

import java.util.Locale
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One sensor reading. Every sensor uses the same clock: milliseconds since boot (elapsedRealtime). */
sealed interface SensorSample {
    val tMs: Long
}

/** Accelerometer, gravity included, m/s² on the phone's axes. */
data class Accel(override val tMs: Long, val x: Float, val y: Float, val z: Float) : SensorSample

/** Gyroscope, rad/s on the phone's axes. */
data class Gyro(override val tMs: Long, val x: Float, val y: Float, val z: Float) : SensorSample

/** GPS ground speed. */
data class Speed(override val tMs: Long, val kmh: Float) : SensorSample

sealed interface DetectorEvent {
    val atMs: Long

    /** A large enough impact that didn't start a detection (e.g. the phone dropped while parked). */
    data class ImpactIgnored(override val atMs: Long, val peakG: Float, val reason: String) : DetectorEvent

    /** A detection that started with an impact and was then ruled out. */
    data class Rejected(
        override val atMs: Long,
        val impactAtMs: Long,
        val peakG: Float,
        val tumbleDeg: Float,
        val reason: String,
    ) : DetectorEvent

    data class Crash(
        override val atMs: Long,
        val impactAtMs: Long,
        val peakG: Float,
        val tumbleDeg: Float,
        /** Highest GPS speed just before the impact; null when GPS was lost. */
        val speedBeforeKmh: Float?,
        val gpsLost: Boolean,
        /** "still" (rider not moving) or "lying" (phone tipped over, e.g. a fallen bike's mount). */
        val down: String,
    ) : DetectorEvent {
        fun describe(): String = buildString {
            append("impact ${String.format(Locale.US, "%.1f", peakG)} g")
            append(if (gpsLost) " (no GPS)" else speedBeforeKmh?.let { " at ${it.roundToInt()} km/h" }.orEmpty())
            append(", tipped ${tumbleDeg.roundToInt()}°, then $down (confirmed ${(atMs - impactAtMs) / 1000} s after)")
        }
    }
}

/**
 * Phone-based crash detection for a two-wheeler rider: plain Kotlin, no Android, fed one sample at
 * a time (see [SensorSample]) so it can be tested with synthetic or recorded traces.
 *
 * A crash is four things in order ([CrashConfig] holds every threshold):
 * 1. **Moving** — GPS showed riding speed just before the impact. Without GPS for a while, this
 *    is dropped and a stronger impact is required instead.
 * 2. **Impact** — a spike in acceleration magnitude.
 * 3. **Tumble** — how far the phone tipped over around the impact: the gyroscope integrated into
 *    one rotation (vibration cancels out), measured against the pre-impact "down" so that turning
 *    a corner doesn't count. Without a gyroscope, the change of orientation stands in for it.
 * 4. **Down** — then several consecutive seconds of being stopped and either still, or lying tipped
 *    over and not handled. Riding on, or never settling within the time limit, rules it out.
 *
 * Samples may arrive in batches per sensor (Android batches while the CPU sleeps); each sensor's
 * own samples must be in time order.
 */
class CrashDetector(private val config: CrashConfig = CrashConfig()) {

    private val recentAccel = ArrayDeque<Accel>()
    private val recentGyro = ArrayDeque<Gyro>()
    private val recentSpeed = ArrayDeque<Speed>()
    private var firstSampleMs: Long? = null
    private var lastSpeedMs: Long? = null
    private var lastAccelMs = Long.MIN_VALUE
    private var lastGyroMs = Long.MIN_VALUE
    private var gyroSeen = false
    private var lastIgnoredMs: Long? = null
    private var cooldownUntilMs = Long.MIN_VALUE
    private var candidate: Candidate? = null

    /** Feeds one reading. Returns an event when something was decided, else null. */
    fun onSample(sample: SensorSample): DetectorEvent? {
        if (firstSampleMs == null) firstSampleMs = sample.tMs
        return when (sample) {
            is Accel -> onAccel(sample)
            is Gyro -> onGyro(sample)
            is Speed -> onSpeed(sample)
        }
    }

    /** Forgets everything, as if newly created. */
    fun reset() {
        recentAccel.clear()
        recentGyro.clear()
        recentSpeed.clear()
        firstSampleMs = null
        lastSpeedMs = null
        lastAccelMs = Long.MIN_VALUE
        lastGyroMs = Long.MIN_VALUE
        gyroSeen = false
        lastIgnoredMs = null
        cooldownUntilMs = Long.MIN_VALUE
        candidate = null
    }

    private fun onAccel(sample: Accel): DetectorEvent? {
        lastAccelMs = sample.tMs
        recentAccel.addLast(sample)
        while (recentAccel.first().tMs < sample.tMs - SENSOR_HISTORY_MS) recentAccel.removeFirst()

        val g = magnitude(sample.x, sample.y, sample.z) / G
        val current = candidate
        if (current == null) {
            return if (g >= min(config.impactG, config.impactNoGpsG)) onImpact(sample, g) else null
        }
        if (sample.tMs - current.impactMs < config.reImpactGapMs) {
            current.peakG = max(current.peakG, g)
        } else if (g >= min(config.impactG, config.impactNoGpsG)) {
            // A second, separate impact (a pothole, then the crash; or hitting something, then
            // landing): time the tumble and down stages from the new one.
            val gate = speedGate(sample.tMs)
            if (g >= impactNeeded(gate) && gate.passes()) {
                startCandidate(sample, g, gate, previous = current)
                return null
            }
        }
        current.addAccel(sample)
        return evaluate(current)
    }

    private fun onGyro(sample: Gyro): DetectorEvent? {
        lastGyroMs = sample.tMs
        gyroSeen = true
        recentGyro.addLast(sample)
        while (recentGyro.first().tMs < sample.tMs - SENSOR_HISTORY_MS) recentGyro.removeFirst()
        val current = candidate ?: return null
        current.addGyro(sample)
        return evaluate(current)
    }

    private fun onSpeed(sample: Speed): DetectorEvent? {
        lastSpeedMs = sample.tMs
        recentSpeed.addLast(sample)
        while (recentSpeed.first().tMs < sample.tMs - SPEED_HISTORY_MS) recentSpeed.removeFirst()
        val current = candidate ?: return null
        if (sample.tMs - current.impactMs >= config.rodeOnAfterMs && sample.kmh >= config.rodeOnSpeedKmh) {
            return reject(current, sample.tMs, "kept riding (${sample.kmh.roundToInt()} km/h)")
        }
        current.addSpeed(sample)
        return evaluate(current)
    }

    private fun onImpact(sample: Accel, g: Float): DetectorEvent? {
        if (sample.tMs < cooldownUntilMs) return null
        val gate = speedGate(sample.tMs)
        val reason = when {
            g < impactNeeded(gate) -> "below ${impactNeeded(gate)} g without GPS"
            !gate.passes() -> gate.why
            else -> null
        }
        if (reason == null) {
            startCandidate(sample, g, gate)
            return null
        }
        // One report per second: a single knock is several samples above the threshold.
        lastIgnoredMs?.let { if (sample.tMs - it < config.reImpactGapMs) return null }
        lastIgnoredMs = sample.tMs
        return DetectorEvent.ImpactIgnored(sample.tMs, g, reason)
    }

    /** [previous]: the detection this impact continues, whose orientation, speed and rotation carry over. */
    private fun startCandidate(sample: Accel, g: Float, gate: SpeedGate, previous: Candidate? = null) {
        val next = Candidate(
            impactMs = sample.tMs,
            peakG = max(g, previous?.peakG ?: 0f),
            preGravity = if (previous != null) previous.preGravity else meanRecentGravity(before = sample.tMs),
            gpsLost = previous?.gpsLost ?: (gate is SpeedGate.GpsLost),
            speedBeforeKmh = if (previous != null) previous.speedBeforeKmh else (gate as? SpeedGate.Moving)?.kmh,
            carriedRotationDeg = previous?.rotationDeg() ?: 0f,
        )
        // The tumble can start just before the peak.
        recentGyro.forEach { next.addGyro(it) }
        next.addAccel(sample)
        candidate = next
    }

    /** The phone's orientation before an impact: mean acceleration over a span ending just before it. */
    private fun meanRecentGravity(before: Long): Vec? {
        val pre = recentAccel.filter { it.tMs >= before - PRE_IMPACT_MS && it.tMs <= before - PRE_IMPACT_GAP_MS }
        if (pre.isEmpty()) return null
        return Vec(
            pre.sumOf { it.x.toDouble() } / pre.size,
            pre.sumOf { it.y.toDouble() } / pre.size,
            pre.sumOf { it.z.toDouble() } / pre.size,
        )
    }

    private fun evaluate(c: Candidate): DetectorEvent? {
        val latest = max(lastAccelMs, lastGyroMs)
        if (!c.tumbleDone) {
            val end = c.impactMs + config.tumbleToMs
            if ((gyroSeen && lastGyroMs >= end) || lastAccelMs >= end + LATE_SENSOR_MS) {
                c.tumbleDone = true
                if (gyroSeen && c.rotationDeg() < config.tumbleDeg) {
                    return reject(c, latest, "no tumble (${c.rotationDeg().roundToInt()}°)")
                }
            }
        }
        while (windowClosed(c, c.nextWindow)) {
            val down = downKind(c, c.windows.remove(c.nextWindow))
            c.nextWindow++
            c.downStreak = if (down != null) c.downStreak + 1 else 0
            if (down != null) c.lastDown = down
            if (c.downStreak >= config.downSeconds && c.tumbleDone && tumbleDeg(c) >= config.tumbleDeg) {
                return confirm(c, latest)
            }
        }
        if (latest - c.impactMs > config.downWithinMs) {
            val reason = if (tumbleDeg(c) < config.tumbleDeg) "no tumble (${tumbleDeg(c).roundToInt()}°)" else "not down within ${config.downWithinMs / 1000} s"
            return reject(c, latest, reason)
        }
        return null
    }

    // A window can close once both sensors have moved past it; a late or missing gyroscope doesn't block it.
    private fun windowClosed(c: Candidate, index: Int): Boolean {
        val end = c.windowStart(index) + WINDOW_MS
        return (lastAccelMs >= end && (!gyroSeen || lastGyroMs >= end)) || lastAccelMs >= end + LATE_SENSOR_MS
    }

    /** "still", "lying", or null if this second doesn't look like someone (or something) down. */
    private fun downKind(c: Candidate, window: Window?): String? {
        if (window == null || window.accelCount < MIN_WINDOW_SAMPLES) return null
        if ((window.maxSpeedKmh ?: 0f) >= config.stoppedSpeedKmh) return null
        val gyroMean = if (window.gyroCount > 0) window.gyroSum / window.gyroCount else 0.0
        val tilt = c.preGravity?.let { angleDeg(it, window.meanGravity()) }
        if (tilt != null) c.maxTiltDeg = max(c.maxTiltDeg, tilt)
        return when {
            window.magnitudeStdG() <= config.stillAccelStdG && gyroMean <= config.stillGyroRadS -> "still"
            tilt != null && tilt >= config.lyingTiltDeg && gyroMean <= config.lyingGyroRadS -> "lying"
            else -> null
        }
    }

    // Without a gyroscope, how far the phone ended up turned stands in for the tumble.
    private fun tumbleDeg(c: Candidate): Float = if (gyroSeen) c.rotationDeg() else c.maxTiltDeg

    private fun confirm(c: Candidate, atMs: Long): DetectorEvent {
        candidate = null
        cooldownUntilMs = atMs + config.cooldownMs
        return DetectorEvent.Crash(
            atMs = atMs,
            impactAtMs = c.impactMs,
            peakG = c.peakG,
            tumbleDeg = tumbleDeg(c),
            speedBeforeKmh = c.speedBeforeKmh,
            gpsLost = c.gpsLost,
            down = c.lastDown ?: "still",
        )
    }

    private fun reject(c: Candidate, atMs: Long, reason: String): DetectorEvent {
        candidate = null
        return DetectorEvent.Rejected(atMs, c.impactMs, c.peakG, tumbleDeg(c), reason)
    }

    private fun impactNeeded(gate: SpeedGate): Float =
        if (gate is SpeedGate.GpsLost) config.impactNoGpsG else config.impactG

    private fun speedGate(atMs: Long): SpeedGate {
        val lastFix = lastSpeedMs
        if (atMs - (lastFix ?: firstSampleMs ?: atMs) >= config.gpsLostAfterMs) return SpeedGate.GpsLost
        if (!config.requireSpeedGate) return SpeedGate.Off
        if (lastFix == null) return SpeedGate.NotMoving("no GPS fix yet")
        val recent = recentSpeed.filter { it.tMs >= atMs - config.speedLookbackMs && it.tMs <= atMs }
        // No fix in the look-back (e.g. under a flyover): the last fix before it decides.
        val kmh = recent.maxOfOrNull { it.kmh } ?: recentSpeed.last().kmh
        return if (kmh >= config.movingSpeedKmh) SpeedGate.Moving(kmh) else SpeedGate.NotMoving("not moving (${kmh.roundToInt()} km/h)")
    }

    private sealed interface SpeedGate {
        val why: String
        fun passes() = this !is NotMoving

        data class Moving(val kmh: Float) : SpeedGate {
            override val why = "moving"
        }

        data class NotMoving(override val why: String) : SpeedGate
        data object GpsLost : SpeedGate {
            override val why = "GPS lost"
        }

        data object Off : SpeedGate {
            override val why = "speed gate off"
        }
    }

    private inner class Candidate(
        val impactMs: Long,
        var peakG: Float,
        val preGravity: Vec?,
        val gpsLost: Boolean,
        val speedBeforeKmh: Float?,
        private val carriedRotationDeg: Float,
    ) {
        private val rotation = RotationTracker(preGravity)
        var tumbleDone = false
        val windows = HashMap<Int, Window>()
        var nextWindow = 0
        var downStreak = 0
        var lastDown: String? = null
        var maxTiltDeg = 0f

        fun rotationDeg() = max(carriedRotationDeg, rotation.maxDeg)

        fun windowStart(index: Int) = impactMs + config.downStartMs + index * WINDOW_MS

        private fun window(tMs: Long): Window? {
            if (tMs < windowStart(0)) return null
            val index = ((tMs - windowStart(0)) / WINDOW_MS).toInt()
            return if (index < nextWindow) null else windows.getOrPut(index) { Window() }
        }

        fun addAccel(s: Accel) {
            window(s.tMs)?.addAccel(s)
        }

        fun addGyro(s: Gyro) {
            if (s.tMs >= impactMs + config.tumbleFromMs && s.tMs <= impactMs + config.tumbleToMs) rotation.add(s)
            window(s.tMs)?.addGyro(s)
        }

        fun addSpeed(s: Speed) {
            window(s.tMs)?.addSpeed(s)
        }
    }

    /** One second of readings after the impact. */
    private class Window {
        var accelCount = 0
        private var sum = 0.0
        private var sumSq = 0.0
        private var gx = 0.0
        private var gy = 0.0
        private var gz = 0.0
        var gyroCount = 0
        var gyroSum = 0.0
        var maxSpeedKmh: Float? = null

        fun addAccel(s: Accel) {
            val m = magnitude(s.x, s.y, s.z) / G
            accelCount++
            sum += m
            sumSq += m * m
            gx += s.x
            gy += s.y
            gz += s.z
        }

        fun addGyro(s: Gyro) {
            gyroCount++
            gyroSum += magnitude(s.x, s.y, s.z)
        }

        fun addSpeed(s: Speed) {
            maxSpeedKmh = max(maxSpeedKmh ?: 0f, s.kmh)
        }

        fun magnitudeStdG(): Float {
            val mean = sum / accelCount
            return sqrt(max(0.0, sumSq / accelCount - mean * mean)).toFloat()
        }

        fun meanGravity() = Vec(gx / accelCount, gy / accelCount, gz / accelCount)
    }

    /**
     * Integrates gyroscope readings into one rotation (a quaternion) and tracks how far it has
     * tipped the phone relative to [gravity], the pre-impact "down". Shaking back and forth cancels
     * out, and so does turning a corner (rotation about the vertical leaves "down" where it was);
     * falling over doesn't. A full roll still shows up, since the largest angle on the way is kept.
     * Without a known [gravity], the whole rotation angle counts.
     */
    private class RotationTracker(gravity: Vec?) {
        private val down = gravity?.let { g ->
            val norm = sqrt(g.x * g.x + g.y * g.y + g.z * g.z)
            if (norm < 1e-9) null else Vec(g.x / norm, g.y / norm, g.z / norm)
        }
        private var w = 1.0
        private var x = 0.0
        private var y = 0.0
        private var z = 0.0
        private var lastMs: Long? = null
        var maxDeg = 0f
            private set

        fun add(s: Gyro) {
            val previous = lastMs
            if (previous != null && s.tMs <= previous) return
            lastMs = s.tMs
            if (previous == null) return
            val dt = min(s.tMs - previous, MAX_GYRO_GAP_MS) / 1000.0
            val rate = magnitude(s.x, s.y, s.z).toDouble()
            val half = rate * dt / 2
            if (half < 1e-9) return
            val c = cos(half)
            val k = sin(half) / rate
            val bx = s.x * k
            val by = s.y * k
            val bz = s.z * k
            val nw = w * c - x * bx - y * by - z * bz
            val nx = w * bx + x * c + y * bz - z * by
            val ny = w * by - x * bz + y * c + z * bx
            val nz = w * bz + x * by - y * bx + z * c
            val norm = sqrt(nw * nw + nx * nx + ny * ny + nz * nz)
            w = nw / norm
            x = nx / norm
            y = ny / norm
            z = nz / norm
            maxDeg = max(maxDeg, Math.toDegrees(tiltRad()).toFloat())
        }

        // A rotation by θ about axis n moves a unit vector v by φ, where
        // cos φ = cos θ + (1 − cos θ)(n·v)². Axis along "down" (turning a corner): φ = 0.
        private fun tiltRad(): Double {
            val theta = 2 * acos(min(1.0, abs(w)))
            val axisNorm = sqrt(x * x + y * y + z * z)
            if (down == null || axisNorm < 1e-12) return theta
            val along = (x * down.x + y * down.y + z * down.z) / axisNorm
            val cosTheta = cos(theta)
            return acos((cosTheta + (1 - cosTheta) * along * along).coerceIn(-1.0, 1.0))
        }
    }

    private class Vec(val x: Double, val y: Double, val z: Double)

    private companion object {
        const val G = 9.80665f
        const val WINDOW_MS = 1_000L
        const val MIN_WINDOW_SAMPLES = 5
        /** How long past a window's end to wait for a sensor whose batch arrives late. */
        const val LATE_SENSOR_MS = 1_000L
        const val SENSOR_HISTORY_MS = 3_000L
        const val SPEED_HISTORY_MS = 40_000L
        /** Pre-impact orientation: the mean acceleration over this span, ending just before the impact. */
        const val PRE_IMPACT_MS = 2_000L
        const val PRE_IMPACT_GAP_MS = 200L
        const val MAX_GYRO_GAP_MS = 100L

        fun magnitude(x: Float, y: Float, z: Float) = sqrt(x * x + y * y + z * z)

        fun angleDeg(a: Vec, b: Vec): Float {
            val dot = a.x * b.x + a.y * b.y + a.z * b.z
            val norms = sqrt(a.x * a.x + a.y * a.y + a.z * a.z) * sqrt(b.x * b.x + b.y * b.y + b.z * b.z)
            if (norms < 1e-9) return 0f
            return Math.toDegrees(acos((dot / norms).coerceIn(-1.0, 1.0))).toFloat()
        }
    }
}
