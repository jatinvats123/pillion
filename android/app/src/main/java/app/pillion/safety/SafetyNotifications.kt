package app.pillion.safety

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.pillion.R
import app.pillion.pillion
import app.pillion.ui.SafetyAlertActivity

/**
 * The alert as a high-priority notification. Its full-screen intent is what puts the alert screen
 * over the lock screen (Android doesn't let a background app start an activity); on an unlocked
 * phone in use it shows as a heads-up with an "I'M OK" button. No sound of its own: the alarm plays.
 */
class SafetyNotifications(context: Context) {

    private val appContext = context.applicationContext
    private val manager = NotificationManagerCompat.from(appContext)

    fun show(state: SafetyState) {
        if (state == SafetyState.Idle) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "Notifications not allowed: no lock-screen alert")
            return
        }
        createChannel()
        val (title, text, okLabel) = when (state) {
            is SafetyState.Countdown -> if (state.trigger == SosTrigger.Crash) {
                Triple(R.string.alert_crash_title, R.string.alert_notification_crash_text, R.string.alert_im_ok)
            } else {
                Triple(R.string.alert_manual_title, R.string.alert_notification_manual_text, R.string.alert_cancel_sos)
            }
            is SafetyState.Sos -> if (state.problem == null) {
                Triple(R.string.alert_sos_sent_title, R.string.alert_notification_sent_text, R.string.alert_im_ok_now)
            } else {
                Triple(R.string.alert_sos_not_sent_title, R.string.alert_notification_not_sent_text, R.string.alert_im_ok_now)
            }
            SafetyState.Idle -> return
        }
        val open = PendingIntent.getActivity(
            appContext, 0, SafetyAlertActivity.intent(appContext), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val ok = PendingIntent.getBroadcast(
            appContext, 1, Intent(appContext, SafetyActionReceiver::class.java).setAction(ACTION_OK), PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(appContext.getString(title))
            .setContentText(appContext.getString(text))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .addAction(0, appContext.getString(okLabel), ok)
            .build()
        try {
            manager.notify(NOTIFICATION_ID, notification)
        } catch (error: SecurityException) {
            Log.w(TAG, "Alert notification refused", error)
        }
    }

    /** Android 14+ only grants full-screen alerts to some apps by default; the rider can allow it. */
    fun canShowOverLockScreen(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE ||
            appContext.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    private fun createChannel() {
        val channel = NotificationChannel(CHANNEL_ID, appContext.getString(R.string.notification_channel_safety), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        }
        appContext.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "SafetyNotifications"
        private const val CHANNEL_ID = "safety_alerts"
        private const val NOTIFICATION_ID = 2
        const val ACTION_OK = "app.pillion.safety.RIDER_OK"
    }
}

/** The notification's "I'M OK" button. */
class SafetyActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == SafetyNotifications.ACTION_OK) context.pillion.safety.riderIsOk()
    }
}
