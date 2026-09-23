package app.pillion.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlin.coroutines.resume
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** One-shot rider position for tools: a very recent fix if there is one, else a fresh one. */
class RiderLocation(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)

    fun hasPermission(): Boolean = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        .any { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }

    fun isEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(manager)

    /** Callers check [hasPermission] first. Null if no provider produced a fix in time. */
    @SuppressLint("MissingPermission")
    suspend fun current(): Location? {
        val providers = enabledProviders()
        lastKnown(providers, maxAgeMs = RECENT_MS)?.let { return it }
        // Ask every provider at once and take the first fix: GPS is slow to start indoors.
        val fresh = withTimeoutOrNull(FRESH_TIMEOUT_MS) {
            channelFlow { providers.forEach { provider -> launch { send(currentFrom(provider)) } } }
                .filterNotNull()
                .firstOrNull()
        }
        return fresh ?: lastKnown(providers, maxAgeMs = STALE_OK_MS)
    }

    fun ageMs(location: Location): Long = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000

    private fun enabledProviders(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) add(LocationManager.FUSED_PROVIDER)
        add(LocationManager.GPS_PROVIDER)
        add(LocationManager.NETWORK_PROVIDER)
    }.filter { LocationManagerCompat.hasProvider(manager, it) && manager.isProviderEnabled(it) }

    @SuppressLint("MissingPermission")
    private fun lastKnown(providers: List<String>, maxAgeMs: Long): Location? = providers
        .mapNotNull { manager.getLastKnownLocation(it) }
        .filter { ageMs(it) <= maxAgeMs }
        .minByOrNull { it.accuracy }

    @SuppressLint("MissingPermission")
    private suspend fun currentFrom(provider: String): Location? = suspendCancellableCoroutine { continuation ->
        val signal = CancellationSignal()
        continuation.invokeOnCancellation { signal.cancel() }
        LocationManagerCompat.getCurrentLocation(manager, provider, signal, ContextCompat.getMainExecutor(appContext)) { location ->
            if (continuation.isActive) continuation.resume(location)
        }
    }

    private companion object {
        const val RECENT_MS = 20_000L
        const val FRESH_TIMEOUT_MS = 4_000L
        const val STALE_OK_MS = 5 * 60_000L
    }
}
