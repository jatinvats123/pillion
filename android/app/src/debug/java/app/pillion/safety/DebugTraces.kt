package app.pillion.safety

/** DEBUG BUILDS ONLY — the trace behind "Simulate crash": synthetic, not a recording. */
object DebugTraces {
    fun crash(): List<SensorSample> = SyntheticTraces.crash()
}
