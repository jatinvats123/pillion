package app.pillion.voice

/** One reply's latency: Agora's `message.metrics` per stage plus client-side timing. */
data class TurnLatency(
    val turnId: Long,
    /** Rider stopped talking (local VAD, ±200 ms) → agent started speaking. What the rider feels. */
    val endToEndMs: Long?,
    /** Final user transcript arrived → agent started speaking (LLM + TTS + delivery). */
    val afterTranscriptMs: Long?,
    val asrMs: Int?,
    val llmFirstTokenMs: Int?,
    val llmFirstSentenceMs: Int?,
    val ttsFirstAudioMs: Int?,
) {
    override fun toString() =
        "turn=$turnId e2e=${endToEndMs.ms()} after_transcript=${afterTranscriptMs.ms()} " +
            "asr=${asrMs.ms()} llm_ttfb=${llmFirstTokenMs.ms()} llm_ttfs=${llmFirstSentenceMs.ms()} tts_ttfb=${ttsFirstAudioMs.ms()}"

    private fun Number?.ms() = this?.let { "${it}ms" } ?: "-"
}

/**
 * Assembles [TurnLatency] per turn. Reports once the agent is speaking and the TTS metric (which
 * arrives around the same moment) is in. Turns without a rider transcript (the greeting) are skipped.
 */
internal class LatencyTracker(private val onReport: (TurnLatency) -> Unit) {
    private var lastRiderVoiceMs = 0L
    private val riderStoppedMs = HashMap<Long, Long>()
    private val transcriptMs = HashMap<Long, Long>()
    private val speakingMs = HashMap<Long, Long>()
    private val metrics = HashMap<Long, MutableMap<String, Int>>()
    private val reported = HashSet<Long>()

    @Synchronized
    fun onRiderVoice(nowMs: Long) {
        lastRiderVoiceMs = nowMs
    }

    @Synchronized
    fun onUserFinalTranscript(turnId: Long, nowMs: Long) {
        if (lastRiderVoiceMs > 0) riderStoppedMs[turnId] = lastRiderVoiceMs
        transcriptMs[turnId] = nowMs
    }

    @Synchronized
    fun onMetric(turnId: Long, module: String, name: String, latencyMs: Int) {
        metrics.getOrPut(turnId) { mutableMapOf() }["$module.$name"] = latencyMs
        tryReport(turnId)
    }

    @Synchronized
    fun onAgentSpeaking(turnId: Long, nowMs: Long) {
        speakingMs.putIfAbsent(turnId, nowMs)
        tryReport(turnId)
    }

    @Synchronized
    fun reset() {
        lastRiderVoiceMs = 0L
        listOf(riderStoppedMs, transcriptMs, speakingMs, metrics).forEach { it.clear() }
        reported.clear()
    }

    private fun tryReport(turnId: Long) {
        val speaking = speakingMs[turnId] ?: return
        val transcript = transcriptMs[turnId] ?: return
        val turnMetrics = metrics[turnId].orEmpty()
        if ("tts.ttfb" !in turnMetrics || !reported.add(turnId)) return
        onReport(
            TurnLatency(
                turnId = turnId,
                endToEndMs = riderStoppedMs[turnId]?.let { speaking - it },
                afterTranscriptMs = speaking - transcript,
                asrMs = turnMetrics["asr.ttlw"],
                llmFirstTokenMs = turnMetrics["llm.ttfb"],
                llmFirstSentenceMs = turnMetrics["llm.ttfs"],
                ttsFirstAudioMs = turnMetrics["tts.ttfb"],
            )
        )
    }
}
