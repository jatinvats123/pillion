package app.pillion.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import app.pillion.data.EarningsDb
import app.pillion.data.OrderSource
import app.pillion.safety.ManualSosResult
import app.pillion.safety.SafetyMonitor
import app.pillion.safety.SosTrigger
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * position, the active order or the earnings, send an SMS, place a call, start an SOS. Every answer
 * is real — from the location sensor, the local database, or Android's own "SMS sent" result.
 *
 * [onSmsDelivery] gets the network's delivery report (true = delivered) for a sent SMS. It comes
 * seconds to minutes after "sent", so it can't be part of the tool's answer.
 */
class DeviceActions(
    context: Context,
    private val orders: OrderSource,
    private val earnings: EarningsDb,
    private val safety: SafetyMonitor,
    private val onSmsDelivery: (customerName: String, delivered: Boolean) -> Unit,
) {
    private val appContext = context.applicationContext
    private val location = RiderLocation(appContext)
    private val sms = SmsSender(appContext)

    /** Handles one `pillion.request`; returns the body for POST /ride/device-result. */
    suspend fun handle(request: JSONObject): JSONObject {
        val reply = try {
            val data = when (request.optString("action")) {
                "location" -> location()
                "order" -> order()
                "earnings" -> withContext(Dispatchers.IO) { earnings.summary() }
                "sms" -> sendSms(request.optJSONObject("args")?.optString("message").orEmpty())
                "call" -> placeCall()
                "sos" -> startSos()
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
        if (!sms.hasPermission()) throw DeviceActionException("permission_denied", RidePermission.Sms)
        val order = orders.activeOrder() ?: throw DeviceActionException("no_active_order")
        val number = customerNumber(order.customerPhone)
        val sent = sms.send(number, message, SMS_TIMEOUT_MS) { delivered -> onSmsDelivery(order.customerName, delivered) }
        if (!sent) throw DeviceActionException("sms_failed")
        return JSONObject().put("status", "sent").put("customer_name", order.customerName)
    }

    /**
     * The rider asked for help by voice: a 5-second cancel window on the phone, then the SOS. The
     * phone speaks the result itself; the LLM only says a short line after this answer.
     */
    private fun startSos(): JSONObject = when (val result = safety.startManualSos(SosTrigger.Voice)) {
        is ManualSosResult.Started -> JSONObject()
            .put("status", "countdown_started")
            .put("seconds_to_cancel", result.seconds)
            .put("emergency_contacts", result.contacts)
        ManualSosResult.AlreadyActive -> JSONObject().put("status", "sos_already_in_progress")
        ManualSosResult.NoContacts -> throw DeviceActionException("no_emergency_contacts")
        ManualSosResult.NoSmsPermission -> throw DeviceActionException("permission_denied", RidePermission.Sms)
    }

    private fun placeCall(): JSONObject {
        if (!granted(Manifest.permission.CALL_PHONE)) throw DeviceActionException("permission_denied", RidePermission.Call)
        val order = orders.activeOrder() ?: throw DeviceActionException("no_active_order")
        val number = customerNumber(order.customerPhone)
        val placed = try {
            startPhoneCall(appContext, number)
        } catch (_: SecurityException) {
            throw DeviceActionException("permission_denied", RidePermission.Call)
        }
        if (!placed) throw DeviceActionException("call_failed")
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
    }
}
