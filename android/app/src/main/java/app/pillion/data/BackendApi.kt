package app.pillion.data

import android.content.Context
import android.location.Location
import androidx.core.content.edit
import app.pillion.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What the backend hands back when it has started an agent for this ride. */
data class RideCredentials(
    val appId: String,
    val channel: String,
    /** Combined RTC + RTM token for [uid]. */
    val token: String,
    val uid: Int,
    val agentUid: Int,
    val agentId: String,
    /** Authenticates this ride's own calls to the backend (transcripts, device results). */
    val rideToken: String,
    /** Live Guardian: base of the SOS link (`<base>/g/<token>`); null when the feature is off. */
    val guardianBaseUrl: String? = null,
    /** The public demo server stops the voice this many minutes in; 0 = no limit. */
    val maxRideMinutes: Int = 0,
)

/** [code]: the server's error code (`demo_limit_reached`, `slow_down`…); [message] is fit to show. */
class BackendException(message: String, val code: String = "") : IOException(message)

/**
 * The public server's refusals, as the rider reads them. The voice-offline panel adds that crash
 * detection and SOS still work (they never depend on the server).
 */
private fun refusalMessage(code: String): String? = when (code) {
    "demo_limit_reached" -> "Demo limit reached for today. Try again later."
    "rides_paused" -> "Pillion's voice is paused right now."
    "server_busy" -> "Pillion is busy with other riders. Try again in a minute."
    "slow_down" -> "Too many tries. Wait a minute, then tap Retry."
    "app_key_required" -> "This version of Pillion can't use the server. Please update the app."
    else -> null
}

/** A random id for this install, not tied to the phone or the rider: the server's per-device rate limit. */
fun installId(context: Context): String {
    val prefs = context.getSharedPreferences("install", Context.MODE_PRIVATE)
    return prefs.getString("id", null) ?: UUID.randomUUID().toString().also { prefs.edit { putString("id", it) } }
}

/**
 * Talks to the Pillion Node backend. All secrets stay on the backend.
 *
 * [baseUrls]: where the backend may be, most direct first (`BACKEND_URL` is a comma-separated
 * list; scripts/phone.ps1 puts in the laptop's Wi-Fi address, adb reverse and the tunnel). Each
 * ride starts on the first one whose /health answers, so losing one route — wireless adb drops
 * whenever the phone sleeps — doesn't cut the phone off from the backend.
 */
class BackendApi(
    private val deviceId: String = "",
    private val baseUrls: List<String> = BuildConfig.BACKEND_URL.split(',').map { it.trim().trimEnd('/') }.filter { it.isNotEmpty() },
) {
    @Volatile
    private var baseUrl = baseUrls.first()

    private val _waking = MutableStateFlow(false)
    /** The server is slow to answer /health (a host starting up): shown instead of "Connecting". */
    val waking: StateFlow<Boolean> = _waking.asStateFlow()

    suspend fun startAgent(): RideCredentials {
        baseUrl = reachableBaseUrl()
        val json = post("/agent/start", JSONObject())
        return RideCredentials(
            appId = json.getString("appId"),
            channel = json.getString("channel"),
            token = json.getString("token"),
            uid = json.getInt("uid"),
            agentUid = json.getInt("agentUid"),
            agentId = json.getString("agentId"),
            rideToken = json.getString("rideToken"),
            guardianBaseUrl = json.optString("guardianBaseUrl").takeIf { it.startsWith("https://") },
            maxRideMinutes = json.optInt("maxRideMinutes", 0),
        )
    }

    suspend fun stopAgent(agentId: String) {
        post("/agent/stop", JSONObject().put("agentId", agentId))
    }

    suspend fun reportLatency(line: String) {
        post("/debug/latency", JSONObject().put("line", line))
    }

    /** A final rider transcript, for the backend's Jev intent router. */
    suspend fun postTurn(rideToken: String, turnId: Long, text: String) {
        post("/ride/turn", JSONObject().put("turnId", turnId).put("text", text), rideToken)
    }

    /** The phone's answer to a device request the backend sent over RTM. */
    suspend fun postDeviceResult(rideToken: String, result: JSONObject) {
        post("/ride/device-result", result, rideToken)
    }

    /**
     * Has the ride's agent speak [text] now through Agora's speak API (a safety alert). Short
     * timeouts: the phone speaks it itself if this doesn't work quickly.
     */
    suspend fun say(rideToken: String, text: String, interrupt: Boolean) {
        post("/ride/say", JSONObject().put("text", text).put("interrupt", interrupt), rideToken, connectTimeoutMs = 2_000, readTimeoutMs = 3_000)
    }

    /** Debug builds: the agent handles [text] as if the rider had said it (Agora think). */
    suspend fun debugThink(rideToken: String, text: String) {
        post("/debug/think", JSONObject().put("text", text), rideToken)
    }

    /** Live Guardian: this ride's SOS SMS carries `<base>/g/[token]` (made on the phone). */
    suspend fun registerGuardianLink(rideToken: String, token: String, riderName: String) {
        post("/ride/guardian/link", JSONObject().put("token", token).put("name", riderName), rideToken, connectTimeoutMs = 3_000, readTimeoutMs = 5_000)
    }

    /** The rider's latest fix for the family's page (null: no fix, still here). False once no link is live. */
    suspend fun postGuardianLocation(rideToken: String, fix: Location?, ageMs: Long): Boolean {
        val body = JSONObject()
        fix?.let {
            body.put("lat", it.latitude).put("lng", it.longitude).put("ageMs", ageMs)
            if (it.hasAccuracy()) body.put("accuracyM", it.accuracy.toDouble())
        }
        return post("/ride/guardian/location", body, rideToken, connectTimeoutMs = 3_000, readTimeoutMs = 5_000).optBoolean("live")
    }

    /** The rider tapped I'M OK NOW: the family's page says so and stops following the rider. */
    suspend fun guardianRiderOk(rideToken: String) {
        post("/ride/guardian/ok", JSONObject(), rideToken, connectTimeoutMs = 3_000, readTimeoutMs = 5_000)
    }

    /** Family joined (any) or left (all) the channel: the backend stops or brings back the agent. */
    suspend fun guardianPresence(rideToken: String, present: Boolean) {
        post("/ride/guardian/presence", JSONObject().put("present", present), rideToken, connectTimeoutMs = 3_000, readTimeoutMs = 5_000)
    }

    /** English for a Hindi transcript line (Sarvam, via the backend). Display-only, so short timeouts. */
    suspend fun translate(rideToken: String, text: String): String =
        post("/ride/translate", JSONObject().put("text", text), rideToken, connectTimeoutMs = 2_000, readTimeoutMs = 4_000)
            .getString("english")

    /**
     * Where a scanned drop address is on the map: `{ status: found | approximate | ambiguous |
     * not_found, lat, lng, area }`. Needs no ride (riders scan before starting); [near] is the
     * rider's position, if known, since deliveries are local.
     */
    suspend fun geocode(address: String, area: String, near: Location?): JSONObject {
        val body = JSONObject().put("address", address).put("area", area)
        near?.let { body.put("near", JSONObject().put("lat", it.latitude).put("lng", it.longitude)) }
        return post("/order/geocode", body, base = reachableBaseUrl(), readTimeoutMs = 15_000)
    }

    private suspend fun post(
        path: String,
        body: JSONObject,
        rideToken: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 30_000,
        base: String = baseUrl,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            rideToken?.let { setRequestProperty("Authorization", "Bearer $it") }
            // The public server's speed bump (it ships in the APK) and its per-install rate limit.
            if (BuildConfig.APP_KEY.isNotEmpty()) setRequestProperty("X-Pillion-Key", BuildConfig.APP_KEY)
            if (deviceId.isNotEmpty()) setRequestProperty("X-Pillion-Device", deviceId)
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val error = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw BackendException(refusalMessage(error) ?: error.ifBlank { "Pillion server error ($code)." }, error)
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * The first base URL whose /health answers. The last one gets longer (a hosted server may be
     * starting up), with [waking] set after a few seconds. If none answers, says so (with the
     * addresses, in debug builds).
     */
    private suspend fun reachableBaseUrl(): String = coroutineScope {
        val slow = launch {
            delay(WAKING_AFTER_MS)
            _waking.value = true
        }
        try {
            withContext(Dispatchers.IO) {
                baseUrls.withIndex().firstOrNull { (index, url) ->
                    answersHealth(url, if (index == baseUrls.lastIndex) LAST_HEALTH_TIMEOUT_MS else HEALTH_TIMEOUT_MS)
                }?.value
            }
        } finally {
            slow.cancel()
            _waking.value = false
        } ?: throw BackendException(
            if (BuildConfig.DEBUG) {
                "Can't reach the Pillion server (tried ${baseUrls.joinToString { it.substringAfter("://") }})."
            } else {
                "Can't reach the Pillion server."
            }
        )
    }

    private fun answersHealth(url: String, timeoutMs: Int): Boolean = runCatching {
        val connection = (URL("$url/health").openConnection() as HttpURLConnection).apply {
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
        }
        try {
            connection.responseCode == 200
        } finally {
            connection.disconnect()
        }
    }.getOrDefault(false)

    private companion object {
        const val HEALTH_TIMEOUT_MS = 2_000
        const val LAST_HEALTH_TIMEOUT_MS = 45_000
        const val WAKING_AFTER_MS = 3_000L
    }
}
