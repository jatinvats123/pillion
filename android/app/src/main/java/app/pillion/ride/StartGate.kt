package app.pillion.ride

/**
 * A ride (and its Agora agent, which bills by the minute) starts only right after the rider asked
 * for one: a tap on Start Ride, or on "Allow microphone" in the card that tap brings up. Each
 * request lets exactly one start through, within [maxAgeMs] (the permission prompts in between).
 * Anything else — a share, an activity re-creation, a re-delivered permission result — is refused.
 * Not saved across process death, on purpose. Main thread only.
 */
class StartGate(private val maxAgeMs: Long = 120_000) {

    private var requestedAtMs: Long? = null

    fun request(nowMs: Long) {
        requestedAtMs = nowMs
    }

    /** How long ago the rider asked (ms) if a start may go ahead now, else null. Uses up the request. */
    fun consume(nowMs: Long): Long? {
        val at = requestedAtMs ?: return null
        requestedAtMs = null
        return (nowMs - at).takeIf { it in 0..maxAgeMs }
    }
}
