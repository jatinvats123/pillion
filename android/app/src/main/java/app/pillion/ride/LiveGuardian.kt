package app.pillion.ride

import android.util.Base64
import android.util.Log
import app.pillion.BuildConfig
import app.pillion.data.BackendApi
import app.pillion.data.RideCredentials
import app.pillion.device.RiderLocation
import app.pillion.safety.LiveGuardianLink
import java.security.SecureRandom
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Live Guardian for one ride's voice session: each SOS gets a link that lets family hear the
 * rider, talk into their earphones and follow their location. The token is made here, so the SMS
 * goes at once; the backend learns it in the background, then gets the rider's fix every 5 s
 * until I'M OK NOW, the voice ends, or the backend says no link is live (2 h).
 */
class LiveGuardian(
    private val ride: RideCredentials,
    private val baseUrl: String,
    private val api: BackendApi,
    private val location: RiderLocation,
    private val onLink: (String) -> Unit,
) : LiveGuardianLink {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var sharing: Job? = null
    @Volatile
    private var riderIsOk = false

    override fun newLink(riderName: String): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        val token = Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        val link = "$baseUrl/g/$token"
        if (BuildConfig.DEBUG) Log.i(TAG, "SOS live link: $link")
        onLink(link)
        scope.launch {
            if (register(token, riderName)) startSharing()
        }
        return link
    }

    override fun riderOk() {
        riderIsOk = true
        sharing?.cancel()
        scope.launch {
            runCatching { api.guardianRiderOk(ride.rideToken) }.onFailure { Log.w(TAG, "Rider OK not sent", it) }
        }
    }

    override fun close() {
        scope.cancel()
    }

    private suspend fun register(token: String, riderName: String): Boolean {
        for (wait in RETRY_WAITS_MS) {
            delay(wait)
            try {
                api.registerGuardianLink(ride.rideToken, token, riderName)
                return true
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w(TAG, "Live link not registered yet", error)
            }
        }
        return false
    }

    private fun startSharing() {
        if (riderIsOk || sharing?.isActive == true) return
        sharing = scope.launch {
            while (isActive) {
                val fix = location.newestKnown()
                val live = try {
                    api.postGuardianLocation(ride.rideToken, fix, fix?.let(location::ageMs) ?: 0)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    true // offline for now: the page shows the last fix and its age
                }
                if (!live) break
                delay(SHARE_INTERVAL_MS)
            }
        }
    }

    private companion object {
        const val TAG = "LiveGuardian"
        const val TOKEN_BYTES = 16 // 128 bits → 22 characters, all in the GSM-7 alphabet
        const val SHARE_INTERVAL_MS = 5_000L
        val RETRY_WAITS_MS = longArrayOf(0, 2_000, 5_000, 10_000)
        val random = SecureRandom()
    }
}
