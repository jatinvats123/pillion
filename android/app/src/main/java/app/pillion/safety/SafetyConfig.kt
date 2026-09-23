package app.pillion.safety

/** Timings of the crash check, SOS and fatigue reminder. Detection thresholds are in [CrashConfig]. */
data class SafetyConfig(
    /** "Are you OK?" countdown after a detected crash. */
    val crashCountdownMs: Long = 20_000,
    /** The prompt is repeated when this much of the crash countdown is left. */
    val repeatPromptAtLeftMs: Long = 10_000,
    /** Cancel window of a manual SOS (voice or button). */
    val manualCountdownMs: Long = 5_000,
    /** Location updates by SMS after an SOS, until the rider taps "I'm OK now". */
    val followUpIntervalMs: Long = 2 * 60_000L,
    val followUpCount: Int = 5,
    /** Call the first emergency contact after the SOS SMS. */
    val autoCallFirstContact: Boolean = false,
    val fatigueAfterMs: Long = 120 * 60_000L,
    val fatigueRepeatMs: Long = 30 * 60_000L,
    val fatigueResetAfterStopMs: Long = 10 * 60_000L,
) {
    companion object {
        /**
         * Debug builds: follow-ups every 30 s, twice (one test = 3 SMS per contact, not 6), and the
         * fatigue reminder after 2 minutes when that debug switch is on.
         */
        fun forBuild(debug: Boolean, fatigueInTwoMinutes: Boolean) = if (debug) {
            SafetyConfig(
                followUpIntervalMs = 30_000,
                followUpCount = 2,
                fatigueAfterMs = if (fatigueInTwoMinutes) 2 * 60_000L else 120 * 60_000L,
            )
        } else {
            SafetyConfig()
        }
    }
}
