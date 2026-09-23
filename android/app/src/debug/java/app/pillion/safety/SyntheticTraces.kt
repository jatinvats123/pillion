package app.pillion.safety

import java.util.Random
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * DEBUG BUILDS ONLY — synthetic sensor traces for the crash detector's unit tests and the debug
 * "Simulate crash" button. They are NOT recordings: each event is modelled from simple physics
 * (gravity turning with the phone, impact pulses, road vibration, 1 Hz GPS that lags) plus noise.
 * They test the detector's logic; real-world accuracy needs recorded rides.
 */
object SyntheticTraces {

    /**
     * Riding at 35 km/h, then a crash: a 7 g impact, the phone (in a pocket) tumbles 120° while
     * the bike slides to a stop, a 3.5 g landing, then the rider lies still.
     */
    fun crash(seed: Long = 7, startMs: Long = 0): List<SensorSample> = TraceBuilder(seed, startMs = startMs)
        .ride(12_000, kmh = 35.0)
        .impact(7.0)
        .speed(12.0)
        .rotate(120.0, 900, Axis.X, noiseG = 0.5, gyroNoise = 0.6)
        .impact(3.5)
        .speed(0.0)
        .rotate(-15.0, 400, Axis.Z, noiseG = 0.2, gyroNoise = 0.3)
        .still(14_000)
        .build()
}

enum class Axis(val x: Double, val y: Double, val z: Double) {
    X(1.0, 0.0, 0.0),
    Y(0.0, 1.0, 0.0),
    Z(0.0, 0.0, 1.0),
}

/**
 * Builds a 50 Hz accelerometer + gyroscope trace with 1 Hz GPS speed. The phone starts upright
 * (gravity on +y, as in a trouser pocket while seated, or a portrait mount); its forward axis is z.
 */
class TraceBuilder(
    seed: Long = 1,
    startMs: Long = 0,
    /** GPS speed trails the real speed by this much (receiver filtering). */
    private val gpsLagMs: Long = 1_000,
    hz: Int = 50,
) {
    private val random = Random(seed)
    private val samples = ArrayList<SensorSample>()
    private val stepMs = 1_000L / hz
    private var t = startMs
    private var nextFixMs = startMs
    private var gx = 0.0
    private var gy = G
    private var gz = 0.0
    private var kmh = 0.0
    private val speedHistory = ArrayDeque<Pair<Long, Double>>()
    private var gps = true

    val nowMs: Long get() = t

    fun build(): List<SensorSample> = samples.sortedBy { it.tMs }

    /** Riding at a steady speed: road and engine vibration on every axis. */
    fun ride(ms: Long, kmh: Double, vibrationG: Double = 0.25, gyroNoise: Double = 0.4) = apply {
        this.kmh = kmh
        repeat(steps(ms)) { tick(noiseG = vibrationG, gyroNoise = gyroNoise) }
    }

    /** Lying on the ground (or the phone on a table): sensor noise and breathing only. */
    fun still(ms: Long, noiseG: Double = 0.012, gyroNoise: Double = 0.02) = apply {
        kmh = 0.0
        repeat(steps(ms)) { tick(noiseG = noiseG, gyroNoise = gyroNoise) }
    }

    /** Stopped at a signal, engine idling: small vibration, same orientation. */
    fun idle(ms: Long) = still(ms, noiseG = 0.04, gyroNoise = 0.08)

    /** Changes speed evenly over [ms]; the phone feels the deceleration along its forward axis. */
    fun brake(ms: Long, toKmh: Double, vibrationG: Double = 0.2, gyroNoise: Double = 0.3) = apply {
        val from = kmh
        val n = steps(ms)
        val decel = (from - toKmh) / 3.6 / (ms / 1000.0)
        repeat(n) { i ->
            kmh = from + (toKmh - from) * (i + 1) / n
            tick(noiseG = vibrationG, gyroNoise = gyroNoise, lz = -decel)
        }
    }

    /** Sets the true speed from now on (GPS reports it after its lag). */
    fun speed(kmh: Double) = apply { this.kmh = kmh }

    fun noGps() = apply { gps = false }

    fun gpsBack() = apply { gps = true }

    /**
     * A short impact: a triangular pulse along gravity so the magnitude peaks at [peakG], with an
     * optional rotational jolt that swings [joltRadS] one way and back (net ≈ 0), as a pothole does.
     */
    fun impact(peakG: Double, ms: Long = 60, joltRadS: Double = 0.0, joltAxis: Axis = Axis.X) = apply {
        val n = max(2, steps(ms))
        repeat(n) { i ->
            val shape = 1.0 - kotlin.math.abs(2.0 * i / (n - 1) - 1.0)
            val extra = (peakG - 1.0) * G * shape
            val jolt = if (i < n / 2) joltRadS else -joltRadS
            tick(
                noiseG = 0.05, gyroNoise = 0.05,
                lx = gx / G * extra, ly = gy / G * extra, lz = gz / G * extra,
                wx = joltAxis.x * jolt, wy = joltAxis.y * jolt, wz = joltAxis.z * jolt,
            )
        }
    }

    /** Turns the phone [deg] about [axis] over [ms] at a constant rate. */
    fun rotate(deg: Double, ms: Long, axis: Axis, noiseG: Double = 0.2, gyroNoise: Double = 0.2) = apply {
        val rate = Math.toRadians(deg) / (ms / 1000.0)
        repeat(steps(ms)) {
            tick(noiseG = noiseG, gyroNoise = gyroNoise, wx = axis.x * rate, wy = axis.y * rate, wz = axis.z * rate)
        }
    }

    /** Turning a corner: [deg] about the world vertical (gravity), whatever the phone's lean. */
    fun turn(deg: Double, ms: Long, noiseG: Double = 0.25, gyroNoise: Double = 0.3) = apply {
        val rate = Math.toRadians(deg) / (ms / 1000.0)
        repeat(steps(ms)) {
            val norm = sqrt(gx * gx + gy * gy + gz * gz)
            tick(noiseG = noiseG, gyroNoise = gyroNoise, wx = gx / norm * rate, wy = gy / norm * rate, wz = gz / norm * rate)
        }
    }

    /** Falling: the accelerometer reads ~0 g. */
    fun freeFall(ms: Long) = apply {
        repeat(steps(ms)) { tick(noiseG = 0.02, gyroNoise = 0.5, lx = -gx, ly = -gy, lz = -gz) }
    }

    /** Held in a hand and looked at: moderate movement and turning, no impact. */
    fun handle(ms: Long) = apply {
        repeat(steps(ms)) { i ->
            val wobble = sin(i / 8.0) * 0.8
            tick(noiseG = 0.12, gyroNoise = 0.3, wx = wobble, wz = wobble / 2)
        }
    }

    private fun steps(ms: Long) = (ms / stepMs).toInt()

    /** One 20 ms step: accelerometer = gravity + linear [lx..lz] (m/s²) + noise; gyroscope = [wx..wz] + noise. */
    private fun tick(
        noiseG: Double,
        gyroNoise: Double,
        lx: Double = 0.0,
        ly: Double = 0.0,
        lz: Double = 0.0,
        wx: Double = 0.0,
        wy: Double = 0.0,
        wz: Double = 0.0,
    ) {
        val n = noiseG * G
        samples += Accel(
            t,
            (gx + lx + random.nextGaussian() * n).toFloat(),
            (gy + ly + random.nextGaussian() * n).toFloat(),
            (gz + lz + random.nextGaussian() * n).toFloat(),
        )
        samples += Gyro(
            t,
            (wx + random.nextGaussian() * gyroNoise).toFloat(),
            (wy + random.nextGaussian() * gyroNoise).toFloat(),
            (wz + random.nextGaussian() * gyroNoise).toFloat(),
        )
        turnGravity(wx, wy, wz, stepMs / 1000.0)

        speedHistory.addLast(t to kmh)
        while (speedHistory.size > 1 && speedHistory[1].first <= t - gpsLagMs) speedHistory.removeFirst()
        if (t >= nextFixMs) {
            if (gps) {
                val reported = speedHistory.first().second + random.nextGaussian() * 0.5
                samples += Speed(t, max(0.0, reported).toFloat())
            }
            nextFixMs = t + 1_000
        }
        t += stepMs
    }

    // The phone turning by ω turns the (fixed) world gravity by -ω on the phone's axes.
    private fun turnGravity(wx: Double, wy: Double, wz: Double, dt: Double) {
        val rate = sqrt(wx * wx + wy * wy + wz * wz)
        if (rate < 1e-9) return
        val angle = -rate * dt
        val kx = wx / rate
        val ky = wy / rate
        val kz = wz / rate
        val c = cos(angle)
        val s = sin(angle)
        val dot = kx * gx + ky * gy + kz * gz
        val nx = gx * c + (ky * gz - kz * gy) * s + kx * dot * (1 - c)
        val ny = gy * c + (kz * gx - kx * gz) * s + ky * dot * (1 - c)
        val nz = gz * c + (kx * gy - ky * gx) * s + kz * dot * (1 - c)
        gx = nx
        gy = ny
        gz = nz
    }

    private companion object {
        const val G = 9.80665
    }
}
