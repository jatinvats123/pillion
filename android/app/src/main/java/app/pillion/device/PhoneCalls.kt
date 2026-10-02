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

/** The phone's call state, as Android reports it. */
sealed interface CallState {
    data object Idle : CallState

    /** An incoming call. [number]: the caller's number, or null if Android doesn't give it. */
    data class Ringing(val number: String?) : CallState

    /** A call is on: answered, or one this phone placed (outgoing calls never ring). */
    data object Offhook : CallState
}

/**
 * The phone's call state, so the ride can hand the mic and speaker to a call. Collect on the main
 * thread (the listener needs a Looper). On Android 12+ it needs READ_PHONE_STATE; without it the
 * flow is empty.
 *
 * The caller's number comes only with READ_CALL_LOG, and only through the old PhoneStateListener
 * (Android 12's TelephonyCallback never passes it), so with [withNumber] and both permissions that
 * listener is used on every version.
 */
fun callState(context: Context, withNumber: Boolean): Flow<CallState> = callbackFlow {
    val telephony = context.getSystemService(TelephonyManager::class.java)
    if (telephony == null) {
        close()
        return@callbackFlow
    }
    fun granted(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
    fun send(state: Int, number: String?) {
        trySend(
            when (state) {
                TelephonyManager.CALL_STATE_RINGING -> CallState.Ringing(number?.takeIf { it.isNotBlank() })
                TelephonyManager.CALL_STATE_OFFHOOK -> CallState.Offhook
                else -> CallState.Idle
            },
        )
    }
    val listenerForNumber = withNumber && granted(Manifest.permission.READ_CALL_LOG) && granted(Manifest.permission.READ_PHONE_STATE)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !listenerForNumber) {
        if (!granted(Manifest.permission.READ_PHONE_STATE)) {
            close()
            return@callbackFlow
        }
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) = send(state, null)
        }
        telephony.registerTelephonyCallback(ContextCompat.getMainExecutor(context), callback)
        awaitClose { telephony.unregisterTelephonyCallback(callback) }
    } else {
        @Suppress("DEPRECATION")
        val listener = object : PhoneStateListener() {
            @Deprecated("Deprecated in Java")
            override fun onCallStateChanged(state: Int, phoneNumber: String?) = send(state, phoneNumber)
        }
        @Suppress("DEPRECATION")
        telephony.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        @Suppress("DEPRECATION")
        awaitClose { telephony.listen(listener, PhoneStateListener.LISTEN_NONE) }
    }
}.distinctUntilChanged()

/**
 * Answers ([answer]) or rejects the ringing call through Telecom. Needs ANSWER_PHONE_CALLS (the
 * caller checks it). Both calls are deprecated since Android 10 in favour of being the dialer or a
 * call screening app, but still work for other apps. False if the phone has no Telecom service or
 * nothing was rejected; declining needs Android 9 (`endCall`), so it is false on Android 8.
 */
@Suppress("DEPRECATION")
@androidx.annotation.RequiresPermission(Manifest.permission.ANSWER_PHONE_CALLS)
fun controlRingingCall(context: Context, answer: Boolean): Boolean {
    val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
    if (!answer) return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && telecom.endCall()
    telecom.acceptRingingCall()
    return true
}

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
