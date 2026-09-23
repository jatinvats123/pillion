package app.pillion.safety

/**
 * Time-based fatigue reminder: after [remindAfterMs] of continuous riding, suggest a break; again
 * at most every [repeatMs]. Continuous = moving, with stops shorter than [resetAfterStopMs] (signals,
 * handing over an order) not breaking it; a longer stop starts afresh. Plain Kotlin, times in ms.
 */
class FatigueTracker(
    private val remindAfterMs: Long,
    private val repeatMs: Long = 30 * 60_000L,
    private val resetAfterStopMs: Long = 10 * 60_000L,
    private val movingKmh: Float = 8f,
    /** Without a GPS speed this long, the ride itself counts as riding (no way to see stops). */
    private val gpsLostAfterMs: Long = 60_000L,
) {
    private var ridingSince: Long? = null
    private var lastMovingMs: Long? = null
    private var lastSpeedMs: Long? = null
    private var lastReminderMs: Long? = null

    fun onSpeed(tMs: Long, kmh: Float) {
        lastSpeedMs = tMs
        if (kmh >= movingKmh) moving(tMs)
    }

    /** Call now and then (e.g. every 30 s). Returns the minutes ridden when a reminder is due. */
    fun check(nowMs: Long): Long? {
        val speedAt = lastSpeedMs
        if (speedAt == null || nowMs - speedAt >= gpsLostAfterMs) moving(nowMs)
        // On a long stop (a real break) right now: nothing to remind, and the count starts afresh.
        lastMovingMs?.let { if (nowMs - it >= resetAfterStopMs) ridingSince = null }
        val since = ridingSince ?: return null
        if (nowMs - since < remindAfterMs) return null
        lastReminderMs?.let { if (nowMs - it < repeatMs) return null }
        lastReminderMs = nowMs
        return (nowMs - since) / 60_000
    }

    private fun moving(tMs: Long) {
        val last = lastMovingMs
        if (ridingSince == null || (last != null && tMs - last >= resetAfterStopMs)) {
            ridingSince = tMs
            lastReminderMs = null
        }
        lastMovingMs = tMs
    }
}
