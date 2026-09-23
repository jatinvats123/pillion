package app.pillion.ride

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.pillion.MainActivity
import app.pillion.R

/**
 * Microphone foreground service for the length of a ride. Without it Android silences the mic
 * as soon as the screen locks or the app leaves the foreground — i.e. whenever the rider rides.
 * The voice session itself lives in the ViewModel; this only keeps the app allowed to listen.
 */
class RideService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_ride)
            .setContentTitle(getString(R.string.notification_ride_title))
            .setContentText(getString(R.string.notification_ride_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, serviceTypes())
        } catch (error: Exception) {
            // E.g. mic permission revoked; the ride still works while the app is in the foreground.
            Log.w(TAG, "Could not start ride foreground service", error)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    // Location only when granted: Android 14+ refuses a location-type service without the permission.
    // With it, tools can read GPS while the phone is locked.
    private fun serviceTypes(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val hasLocation = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            .any { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE or
            (if (hasLocation) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_ride),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "RideService"
        private const val CHANNEL_ID = "ride"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, RideService::class.java)) }
                .onFailure { Log.w(TAG, "Could not start ride service", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RideService::class.java))
        }
    }
}
