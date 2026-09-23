package app.pillion.ride

import android.content.Context
import android.util.Log
import app.pillion.data.BackendApi
import app.pillion.data.EarningsDb
import app.pillion.data.RideCredentials
import app.pillion.data.SeededOrderSource
import app.pillion.device.DeviceActions
import app.pillion.device.RidePermission
import app.pillion.device.phoneCallActive
import app.pillion.voice.VoiceSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Starts and ends a ride: the backend owns the agent, [voice] owns the realtime connection. */
class RideRepository(
    context: Context,
    private val api: BackendApi = BackendApi(),
) {
    private val appContext = context.applicationContext
    val voice = VoiceSession(appContext)
    val orders = SeededOrderSource(appContext)
    private val earnings = EarningsDb(appContext)
    private val actions = DeviceActions(appContext, orders, earnings) { customer, delivered ->
        voice.showActionLine(
            if (delivered) "✓ Delivered to $customer" else "✗ SMS to $customer not delivered",
            failed = !delivered,
        )
    }

    private val _permissionNeeded = MutableSharedFlow<RidePermission>(
        extraBufferCapacity = 4,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** An action failed because the rider hasn't granted this permission. */
    val permissionNeeded: SharedFlow<RidePermission> = _permissionNeeded.asSharedFlow()

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

    /**
     * Everything a live ride does besides audio, until cancelled: forwards final transcripts to the
     * backend (Jev), answers the backend's device requests, and pauses Pillion during phone calls.
     * Collect from the main thread.
     */
    suspend fun serveRide(ride: RideCredentials): Nothing = coroutineScope {
        launch {
            voice.riderTurns.collect { turn ->
                launch {
                    runCatching { api.postTurn(ride.rideToken, turn.turnId, turn.text) }
                        .onFailure { Log.w(TAG, "Turn not posted", it) }
                }
            }
        }
        launch {
            voice.serverRequests.collect { request -> launch { answer(ride, request) } }
        }
        launch {
            phoneCallActive(appContext).collect { voice.setPhoneCallActive(it) }
        }
        awaitCancellation()
    }

    private suspend fun answer(ride: RideCredentials, request: JSONObject) {
        val reply = actions.handle(request)
        if (reply.optString("error") == "permission_denied") {
            RidePermission.entries.firstOrNull { it.wireName == reply.optString("permission") }
                ?.let { _permissionNeeded.tryEmit(it) }
        }
        runCatching { api.postDeviceResult(ride.rideToken, reply) }
            .onFailure { Log.w(TAG, "Device result for ${request.optString("action")} not delivered", it) }
    }

    /** Leaves the channel, then stops the agent. The agent also self-stops 30 s after we leave. */
    suspend fun end(agentId: String) = withContext(NonCancellable) {
        voice.leave()
        api.stopAgent(agentId)
    }

    /** Adds the finished ride to the trip history, paid at the active order's payout (seeded for now). */
    suspend fun recordTrip(startedAt: Long, endedAt: Long) = withContext(Dispatchers.IO) {
        val order = orders.activeOrder()
        earnings.addLiveTrip(startedAt, endedAt, order.dropArea, order.payoutRupees)
    }

    /** Debug only: mirror a per-turn latency line into the backend log. */
    suspend fun reportLatency(line: String) = api.reportLatency(line)

    private companion object {
        const val TAG = "RideRepository"
    }
}
