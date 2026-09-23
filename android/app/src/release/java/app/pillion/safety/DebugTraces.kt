package app.pillion.safety

/** Release builds have no simulated crash. */
object DebugTraces {
    fun crash(): List<SensorSample> = emptyList()
}
