package app.pillion.device

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * True while any phone call is ringing or active — one Pillion placed or an incoming one — so the
 * ride can hand the mic and speaker to the call. Collect on the main thread (the pre-Android 12
 * listener needs a Looper). On Android 12+ it needs READ_PHONE_STATE; without it the flow is empty.
 */
fun phoneCallActive(context: Context): Flow<Boolean> = callbackFlow {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    if (telephony == null) {
        close()
        return@callbackFlow
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) {
            close()
            return@callbackFlow
        }
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                trySend(state != TelephonyManager.CALL_STATE_IDLE)
            }
        }
        telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback)
        awaitClose { telephony.unregisterTelephonyCallback(callback) }
    } else {
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) {
                trySend(state != TelephonyManager.CALL_STATE_IDLE)
            }
        }
        @Suppress("DEPRECATION")
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        @Suppress("DEPRECATION")
        awaitClose { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }
}.distinctUntilChanged()

/**
 * Starts a call through Telecom, which works with the screen locked (starting a dialer activity
 * doesn't). False if the phone has no Telecom service; throws SecurityException without CALL_PHONE.
 */
fun startPhoneCall(context: Context, number: String): Boolean {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
        throw SecurityException("CALL_PHONE not granted")
    }
    val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
    telecom.placeCall(Uri.fromParts("tel", number, null), Bundle())
    return true
}
