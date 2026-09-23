package app.pillion.data

import app.pillion.BuildConfig
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
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
)

class BackendException(message: String) : IOException(message)

/** Talks to the Pillion Node backend. All secrets stay on the backend. */
class BackendApi(private val baseUrl: String = BuildConfig.BACKEND_URL) {

    suspend fun startAgent(): RideCredentials {
        val json = post("/agent/start", JSONObject())
        return RideCredentials(
            appId = json.getString("appId"),
            channel = json.getString("channel"),
            token = json.getString("token"),
            uid = json.getInt("uid"),
            agentUid = json.getInt("agentUid"),
            agentId = json.getString("agentId"),
            rideToken = json.getString("rideToken"),
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

    private suspend fun post(
        path: String,
        body: JSONObject,
        rideToken: String? = null,
        connectTimeoutMs: Int = 10_000,
        readTimeoutMs: Int = 30_000,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = connectTimeoutMs
            readTimeout = readTimeoutMs
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            rideToken?.let { setRequestProperty("Authorization", "Bearer $it") }
        }
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                val message = runCatching { JSONObject(text).optString("error") }.getOrNull().orEmpty()
                throw BackendException(message.ifBlank { "Pillion server error ($code)." })
            }
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
