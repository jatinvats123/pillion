package app.pillion.device

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.SmsManager
import android.telephony.SmsMessage
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Sends SMS over the SIM (no internet) and reports Android's real results: "sent" (the carrier
 * accepted it) right away, "delivered" later if the carrier sends a delivery report. Used for the
 * customer SMS and the SOS.
 */
class SmsSender(context: Context) {

    private val appContext = context.applicationContext

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    /**
     * Sends [message] (split into parts if long) and returns true once Android reports it sent,
     * false if it failed or no result came within [timeoutMs]. [onDelivery] gets the delivery
     * report (true = delivered), seconds to minutes later, if the carrier sends one.
     */
    suspend fun send(number: String, message: String, timeoutMs: Long = 12_000, onDelivery: (Boolean) -> Unit = {}): Boolean =
        withTimeoutOrNull(timeoutMs) { sendAndAwaitResult(number, message, onDelivery) } ?: run {
            Log.w(TAG, "SMS to …${number.takeLast(4)}: no sent result in $timeoutMs ms")
            false
        }

    private suspend fun sendAndAwaitResult(number: String, message: String, onDelivery: (Boolean) -> Unit): Boolean =
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
                val deliveryIntent = watchDelivery(onDelivery)
                if (parts.size == 1) {
                    sms.sendTextMessage(number, null, message, sentIntent, deliveryIntent)
                } else {
                    sms.sendMultipartTextMessage(
                        number, null, parts, ArrayList(parts.map { sentIntent }), ArrayList(parts.map { deliveryIntent }),
                    )
                }
            } catch (error: Exception) {
                // No SIM, SMS disabled, invalid number, permission revoked…
                Log.w(TAG, "SMS to …${number.takeLast(4)} not sent", error)
                runCatching { appContext.unregisterReceiver(receiver) }
                if (continuation.isActive) continuation.resume(false)
            }
        }

    /** Listens for the carrier's delivery report of one SMS and passes the outcome on. */
    private fun watchDelivery(onDelivery: (Boolean) -> Unit): PendingIntent {
        val action = "${appContext.packageName}.SMS_DELIVERED.${UUID.randomUUID()}"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val status = deliveryStatus(intent)
                Log.i(TAG, "SMS delivery report: status=$status")
                // 0x00–0x1F delivered, 0x20–0x3F carrier still trying, 0x40+ failed (3GPP TS 23.040).
                if (status == null || status in 0x20..0x3F) return
                runCatching { appContext.unregisterReceiver(this) }
                onDelivery(status < 0x20)
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

    private companion object {
        const val TAG = "SmsSender"
        const val DELIVERY_WAIT_MS = 5 * 60_000L
    }
}
