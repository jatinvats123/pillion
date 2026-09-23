package app.pillion.safety

import kotlin.math.min

/**
 * Every threshold of the crash detector, in one place for tuning. A crash is:
 * moving (speed gate) → impact → tumble → down, in that order. See [CrashDetector].
 *
 * The defaults come from phone-based crash detection practice (WreckWatch: ≥ 4 g plus a GPS
 * speed filter against dropped phones) and are meant to be re-checked against recorded rides.
 */
data class CrashConfig(
    // ---- Speed gate: the rider was riding just before the impact (filters parked drops, pick-ups).
    /** GPS speed that counts as riding: above walking/pushing speed and stationary GPS jitter. */
    val movingSpeedKmh: Float = 15f,
    /** Look back this far from the impact for a riding-speed fix (covers GPS lag and 1 Hz fixes). */
    val speedLookbackMs: Long = 8_000,
    /** Off in demo mode (debug builds): a drop while standing still can trigger a real detection. */
    val requireSpeedGate: Boolean = true,
    /**
     * No GPS speed for this long (flyovers, narrow lanes, location off): the speed gate is dropped
     * and the stronger [impactNoGpsG] is required instead. A false alarm can be cancelled; a missed
     * crash can't.
     */
    val gpsLostAfterMs: Long = 30_000,

    // ---- Impact.
    /** Peak acceleration magnitude, gravity included. Riding bumps in a pocket stay mostly below 3 g. */
    val impactG: Float = 4f,
    /** The impact needed while GPS is lost (no speed gate). */
    val impactNoGpsG: Float = 6f,
    /** A new impact at least this long after the current one restarts detection from it. */
    val reImpactGapMs: Long = 1_000,

    // ---- Tumble: how far the phone tipped relative to gravity around the impact (gyroscope; turning
    // a corner doesn't count). A bike going down rolls ~90°; potholes and speed breakers tip the
    // phone a few degrees and back; a cornering lean stays around 20–35°.
    val tumbleDeg: Float = 45f,
    val tumbleFromMs: Long = -500,
    val tumbleToMs: Long = 2_500,

    // ---- Down: the rider (or the bike) stays down. Checked in 1 s windows after the impact.
    /** First window starts this long after the impact (the tumble is still settling before). */
    val downStartMs: Long = 1_000,
    /** Consecutive "down" seconds needed. */
    val downSeconds: Int = 6,
    /** Give up if the rider isn't down this long after the impact (got up, kept moving). */
    val downWithinMs: Long = 30_000,
    /** A window with a GPS fix at or above this speed isn't down. */
    val stoppedSpeedKmh: Float = 8f,
    /** Still: spread (std dev) of acceleration magnitude and mean rotation rate in the window. */
    val stillAccelStdG: Float = 0.10f,
    val stillGyroRadS: Float = 0.35f,
    /**
     * Lying: phone at least this far from its pre-impact orientation and not turning (engine
     * vibration shakes a phone but barely rotates it; a hand holding it does). Covers a handlebar
     * mount on a fallen bike whose engine keeps running.
     */
    val lyingTiltDeg: Float = 60f,
    val lyingGyroRadS: Float = 0.5f,

    // ---- Rode on: a riding-speed fix this long after the impact means the rider carried on.
    val rodeOnSpeedKmh: Float = 15f,
    val rodeOnAfterMs: Long = 4_000,

    /** No new detection this long after one (the same crash, the rider picking the phone up). */
    val cooldownMs: Long = 30_000,
) {
    /**
     * Keeps the impact thresholds reachable on phones whose accelerometer saturates early
     * (Android reports the range as `Sensor.maximumRange`).
     */
    fun forSensorRange(maxRangeG: Float): CrashConfig = copy(
        impactG = min(impactG, maxRangeG * 0.9f),
        impactNoGpsG = min(impactNoGpsG, maxRangeG * 0.9f),
    )

    companion object {
        /**
         * DEBUG BUILDS ONLY — demo mode: no speed gate and a 2.5 g impact, so dropping the phone
         * onto a mattress while standing still runs a real detection (a mattress absorbs most of
         * the hit). Tumble and down stages are unchanged.
         */
        val DEMO = CrashConfig(requireSpeedGate = false, impactG = 2.5f, impactNoGpsG = 2.5f)
    }
}
