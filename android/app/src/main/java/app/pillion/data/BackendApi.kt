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
        )
    }

    suspend fun stopAgent(agentId: String) {
        post("/agent/stop", JSONObject().put("agentId", agentId))
    }

    suspend fun reportLatency(line: String) {
        post("/debug/latency", JSONObject().put("line", line))
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
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
