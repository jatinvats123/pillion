package app.pillion.ride

import android.content.Context
import app.pillion.data.BackendApi
import app.pillion.data.RideCredentials
import app.pillion.voice.VoiceSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Starts and ends a ride: the backend owns the agent, [voice] owns the realtime connection. */
class RideRepository(
    context: Context,
    private val api: BackendApi = BackendApi(),
) {
    val voice = VoiceSession(context)

    suspend fun start(): RideCredentials {
        val ride = api.startAgent()
        try {
            voice.join(ride)
        } catch (error: Throwable) {
            // Never leave an agent running (and billing) if we couldn't connect to it.
            withContext(NonCancellable) {
                voice.leave()
                runCatching { api.stopAgent(ride.agentId) }
            }
            throw error
        }
        return ride
    }

    /** Leaves the channel, then stops the agent. The agent also self-stops 30 s after we leave. */
    suspend fun end(agentId: String) = withContext(NonCancellable) {
        voice.leave()
        api.stopAgent(agentId)
    }

    /** Debug only: mirror a per-turn latency line into the backend log. */
    suspend fun reportLatency(line: String) = api.reportLatency(line)
}
