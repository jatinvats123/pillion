package app.pillion.device

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import app.pillion.data.EarningsDb
import app.pillion.data.OrderSource
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** Runtime permissions a ride action can need; `wireName` is what the backend and LLM see. */
enum class RidePermission(val wireName: String, val manifestNames: Array<String>) {
    Location("location", arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)),
    Sms("sms", arrayOf(Manifest.permission.SEND_SMS)),
    Call("call", arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)),
}

private class DeviceActionException(val code: String, val permission: RidePermission? = null) : Exception(code)

/**
 * Does what the backend asks of the phone during a ride (the request arrives over RTM): read the
 * position, the active order or the earnings, send an SMS, place a call. Every answer is real —
 * from the location sensor, the local database, or Android's own "SMS sent" result.
 *
 * [onSmsDelivery] gets the network's delivery report (true = delivered) for a sent SMS. It comes
 * seconds to minutes after "sent", so it can't be part of the tool's answer.
 */
class DeviceActions(
    context: Context,
    private val orders: OrderSource,
    private val earnings: EarningsDb,
    private val onSmsDelivery: (customerName: String, delivered: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val location = RiderLocation(appContext)

    /** Handles one `pillion.request`; returns the body for POST /ride/device-result. */
    suspend fun handle(request: JSONObject): JSONObject {
        val reply = try {
            val data = when (request.optString("action")) {
                "location" -> location()
                "order" -> order()
                "earnings" -> withContext(Dispatchers.IO) { earnings.summary() }
                "sms" -> sendSms(request.optJSONObject("args")?.optString("message").orEmpty())
                "call" -> placeCall()
                else -> throw DeviceActionException("unknown_action")
            }
            JSONObject().put("ok", true).put("data", data)
        } catch (error: DeviceActionException) {
            JSONObject().put("ok", false).put("error", error.code).apply {
                error.permission?.let { put("permission", it.wireName) }
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.w(TAG, "Device action ${request.optString("action")} failed", error)
            JSONObject().put("ok", false).put("error", "device_error")
        }
        return reply.put("id", request.optString("id"))
    }

    private suspend fun location(): JSONObject {
        if (!location.hasPermission()) throw DeviceActionException("permission_denied", RidePermission.Location)
        if (!location.isEnabled()) throw DeviceActionException("location_off")
        val fix = location.current() ?: throw DeviceActionException("location_unavailable")
        return JSONObject()
            .put("lat", fix.latitude)
            .put("lng", fix.longitude)
            .put("accuracy_m", fix.accuracy.roundToInt())
            .put("age_s", location.ageMs(fix) / 1000)
    }

    // Name and address only: the customer's number never leaves the phone.
    private fun order(): JSONObject {
        val order = orders.activeOrder() ?: throw DeviceActionException("no_active_order")
        return JSONObject().put("customer_name", order.customerName).put("drop_address", order.dropAddress)
    }

    private suspend fun sendSms(message: String): JSONObject {
        if (message.isBlank()) throw DeviceActionException("message_missing")
        if (!granted(Manifest.permission.SEND_SMS)) throw DeviceActionException("permission_denied", RidePermission.Sms)
        val order = orders.activeOrder() ?: throw DeviceActionException("no_active_order")
        val number = customerNumber(order.customerPhone)
        val sent = withTimeoutOrNull(SMS_TIMEOUT_MS) { sendAndAwaitResult(number, message, order.customerName) }
            ?: throw DeviceActionException("sms_timeout")
        if (!sent) throw DeviceActionException("sms_failed")
        return JSONObject().put("status", "sent").put("customer_name", order.customerName)
    }

    /**
     * Sends the SMS and suspends until Android reports it sent (true) or failed (false). "Sent" means
     * the carrier accepted it; delivery is reported later through [onSmsDelivery].
     */
    private suspend fun sendAndAwaitResult(number: String, message: String, customerName: String): Boolean =
        suspendCancellableCoroutine { continuation ->
            val action = "${appContext.packageName}.SMS_SENT.${UUID.randomUUID()}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    runCatching { appContext.unregisterReceiver(this) }
                    Log.i(TAG, "SMS to …${number.takeLast(4)}: sent result $resultCode (OK = ${Activity.RESULT_OK})")
                    // A long message arrives in parts; the first result decides.
                    if (continuation.isActive) continuation.resume(resultCode == Activity.RESULT_OK)
                }
            }
            ContextCompat.registerReceiver(appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
            continuation.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

            val sentIntent = PendingIntent.getBroadcast(
                appContext, 0, Intent(action).setPackage(appContext.packageName), PendingIntent.FLAG_IMMUTABLE,
            )
            try {
                val sms = smsManager()
                val parts = sms.divideMessage(message)
                val deliveryIntent = watchDelivery(customerName)
                if (parts.size == 1) {
                    sms.sendTextMessage(number, null, message, sentIntent, deliveryIntent)
                } else {
                    sms.sendMultipartTextMessage(
                        number, null, parts, ArrayList(parts.map { sentIntent }), ArrayList(parts.map { deliveryIntent }),
                    )
                }
            } catch (error: Exception) {
                // No SIM, SMS disabled, invalid number…
                Log.w(TAG, "SMS to …${number.takeLast(4)} not sent", error)
                runCatching { appContext.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(false)
            }
        }

    /** Listens for the carrier's delivery report of one SMS and passes the outcome to [onSmsDelivery]. */
    private fun watchDelivery(customerName: String): PendingIntent {
        val action = "${appContext.packageName}.SMS_DELIVERED.${UUID.randomUUID()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = deliveryStatus(intent)
                Log.i(TAG, "SMS delivery report: status=$status")
                // 0x00–0x1F delivered, 0x20–0x3F carrier still trying, 0x40+ failed (3GPP TS 23.040).
                if (status == null || status in 0x20..0x3F) return
                runCatching { appContext.unregisterReceiver(this) }
                onSmsDelivery(customerName, status < 0x20)
            }
        }
        ContextCompat.registerReceiver(appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        // Some carriers never send a report; stop listening after a while.
        Handler(Looper.getMainLooper()).postDelayed(
            { runCatching { appContext.unregisterReceiver(receiver) } },
            DELIVERY_WAIT_MS,
        )
        // Mutable: Android puts the report PDU into this intent. The package keeps it explicit.
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(appContext, 0, Intent(action).setPackage(appContext.packageName), flags)
    }

    private fun deliveryStatus(intent: Intent): Int? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val format = intent.getStringExtra("format") ?: return null
        return runCatching { SmsMessage.createFromPdu(pdu, format)?.status }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appContext.getSystemService(SmsManager::class.java)
        else SmsManager.getDefault()

    /** Starts the call through Telecom, which works with the screen locked (starting a dialer activity doesn't). */
    private fun placeCall(): JSONObject {
        if (!granted(Manifest.permission.CALL_PHONE)) throw DeviceActionException("permission_denied", RidePermission.Call)
        val order = orders.activeOrder() ?: throw DeviceActionException("no_active_order")
        val number = customerNumber(order.customerPhone)
        val telecom = appContext.getSystemService(TelecomManager::class.java)
            ?: throw DeviceActionException("call_failed")
        try {
            telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
        } catch (_: SecurityException) {
            throw DeviceActionException("permission_denied", RidePermission.Call)
        }
        return JSONObject().put("status", "calling").put("customer_name", order.customerName)
    }

    private fun customerNumber(raw: String): String =
        raw.filter { it.isDigit() || it == '+' }.takeIf { it.length >= 7 }
            ?: throw DeviceActionException("no_customer_number")

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    private companion object {
        const val TAG = "DeviceActions"
        const val SMS_TIMEOUT_MS = 12_000L
        const val DELIVERY_WAIT_MS = 5 * 60_000L
    }
}
