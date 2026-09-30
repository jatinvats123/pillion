package app.pillion.safety

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.ExecutorCompat
import app.pillion.BuildConfig
import app.pillion.device.RiderLocation
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * The ride's crash-detection sensors, owned by the ride service: accelerometer and gyroscope at
 * 50 Hz, delivered in batches of up to a second (the sensor hub buffers while the CPU sleeps, so
 * no wake lock is needed), GPS once a second for speed, and the network location every 10 s (works
 * indoors; for the SOS and Live Guardian only, never the detector). Everything runs on one background
 * thread with the [CrashDetector]; results go to [SafetyMonitor] on the main thread.
 */
class SafetySensors(context: Context, private val safety: SafetyMonitor) {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var detector: CrashDetector? = null
    private var clockOffsetMs: Long? = null
    private var recorder: BufferedWriter? = null

    fun start() {
        if (thread != null) return
        val worker = HandlerThread("pillion-safety").apply { start() }
        val workerHandler = Handler(worker.looper)
        thread = worker
        handler = workerHandler

        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val base = if (safety.debug.demoMode.value) CrashConfig.DEMO else CrashConfig()
        val config = accel?.let { base.forSensorRange(it.maximumRange / SensorManager.GRAVITY_EARTH) } ?: base
        detector = CrashDetector(config)
        Log.i(TAG, "Crash detection: accel=${accel.describe()} gyro=${gyro.describe()} demo=${safety.debug.demoMode.value} impact=${config.impactG} g (no GPS ${config.impactNoGpsG} g)")
        if (accel == null) Log.w(TAG, "No accelerometer: crash detection off (SOS button still works)")
        accel?.let { sensorManager.registerListener(sensorListener, it, SAMPLING_US, MAX_BATCH_US, workerHandler) }
        gyro?.let { sensorManager.registerListener(sensorListener, it, SAMPLING_US, MAX_BATCH_US, workerHandler) }
        startGps(workerHandler)
        safety.sensors = this
    }

    // Removing a listener needs no permission; lint flags it anyway.
    @SuppressLint("MissingPermission")
    fun stop() {
        sensorManager.unregisterListener(sensorListener)
        runCatching { LocationManagerCompat.removeUpdates(locationManager, locationListener) }
        runCatching { LocationManagerCompat.removeUpdates(locationManager, networkListener) }
        handler?.post { closeRecorder() }
        thread?.quitSafely()
        thread = null
        handler = null
        if (safety.sensors === this) safety.sensors = null
    }

    /** Debug: runs a trace through the detector as if it had just happened. False if not riding. */
    fun replay(samples: List<SensorSample>): Boolean {
        val workerHandler = handler ?: return false
        if (samples.isEmpty()) return false
        workerHandler.post {
            val crashDetector = detector ?: return@post
            val shift = SystemClock.elapsedRealtime() - samples.last().tMs
            Log.i(TAG, "Replaying ${samples.size} samples (synthetic trace)")
            crashDetector.reset()
            samples.forEach { sample -> crashDetector.onSample(sample.shiftedBy(shift))?.let(::report) }
            // Live readings start again from a clean slate (their timestamps overlap the replay).
            crashDetector.reset()
        }
        return true
    }

    @SuppressLint("MissingPermission") // checked just below with hasPermission()
    private fun startGps(workerHandler: Handler) {
        if (!RiderLocation(appContext).hasPermission()) {
            Log.w(TAG, "No location permission: crash detection runs without a speed gate (needs ${CrashConfig().impactNoGpsG} g)")
            return
        }
        val request = LocationRequestCompat.Builder(GPS_INTERVAL_MS).setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY).build()
        try {
            LocationManagerCompat.requestLocationUpdates(
                locationManager, LocationManager.GPS_PROVIDER, request, ExecutorCompat.create(workerHandler), locationListener,
            )
        } catch (error: Exception) {
            // SecurityException (permission just revoked) or no GPS hardware.
            Log.w(TAG, "GPS updates unavailable", error)
        }
        // Wi-Fi / cell position: indoors or under a flyover GPS has no fix, but the SOS and the family
        // page still need to know roughly where the rider is. Its speed is unreliable, so it's not used.
        if (!LocationManagerCompat.hasProvider(locationManager, LocationManager.NETWORK_PROVIDER)) return
        val coarse = LocationRequestCompat.Builder(NETWORK_INTERVAL_MS)
            .setQuality(LocationRequestCompat.QUALITY_BALANCED_POWER_ACCURACY).build()
        try {
            LocationManagerCompat.requestLocationUpdates(
                locationManager, LocationManager.NETWORK_PROVIDER, coarse, ExecutorCompat.create(workerHandler), networkListener,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Network location unavailable", error)
        }
    }

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val t = timeMs(event.timestamp)
            val v = event.values
            feed(if (event.sensor.type == Sensor.TYPE_GYROSCOPE) Gyro(t, v[0], v[1], v[2]) else Accel(t, v[0], v[1], v[2]))
        }

        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val locationListener = LocationListenerCompat { location: Location ->
        if (location.hasSpeed()) feed(Speed(location.elapsedRealtimeNanos / 1_000_000, location.speed * 3.6f))
        main.post { safety.onLocation(location) }
    }

    private val networkListener = LocationListenerCompat { location: Location ->
        main.post { safety.onNetworkLocation(location) }
    }

    private fun feed(sample: SensorSample) {
        if (BuildConfig.DEBUG) record(sample)
        detector?.onSample(sample)?.let(::report)
    }

    private fun report(event: DetectorEvent) {
        main.post { safety.onDetectorEvent(event) }
    }

    // Sensor timestamps should share elapsedRealtime's clock; a few devices use another base.
    private fun timeMs(eventNanos: Long): Long {
        val offset = clockOffsetMs ?: run {
            val diffMs = (SystemClock.elapsedRealtimeNanos() - eventNanos) / 1_000_000
            (if (abs(diffMs) > CLOCK_TOLERANCE_MS) diffMs else 0L).also {
                if (it != 0L) Log.w(TAG, "Sensor clock is ${it} ms off elapsedRealtime; correcting")
                clockOffsetMs = it
            }
        }
        return eventNanos / 1_000_000 + offset
    }

    // ---- Debug sensor recorder (CSV, see SensorCsv).

    private fun record(sample: SensorSample) {
        if (!safety.debug.recording.value) {
            if (recorder != null) closeRecorder()
            return
        }
        val writer = recorder ?: openRecorder() ?: return
        runCatching {
            writer.write(SensorCsv.line(sample))
            writer.newLine()
        }
    }

    private fun openRecorder(): BufferedWriter? = runCatching {
        val dir = appContext.getExternalFilesDir("traces") ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val file = File(dir, "trace_$stamp${if (safety.debug.demoMode.value) "_demo" else ""}.csv")
        Log.i(TAG, "Recording sensors to ${file.absolutePath}")
        main.post { safety.debugNote("● Recording sensors: ${file.name}") }
        file.bufferedWriter().apply {
            write(SensorCsv.HEADER)
            newLine()
        }
    }.onFailure { Log.w(TAG, "Can't record sensors", it) }.getOrNull()?.also { recorder = it }

    private fun closeRecorder() {
        val writer = recorder ?: return
        recorder = null
        runCatching { writer.close() }
        main.post { safety.debugNote("■ Sensor recording saved") }
    }

    private fun Sensor?.describe() =
        this?.let { "$name (range ${"%.0f".format(Locale.US, maximumRange / SensorManager.GRAVITY_EARTH)} g, fifo $fifoMaxEventCount, wakeup $isWakeUpSensor)" } ?: "none"

    private fun SensorSample.shiftedBy(ms: Long): SensorSample = when (this) {
        is Accel -> copy(tMs = tMs + ms)
        is Gyro -> copy(tMs = tMs + ms)
        is Speed -> copy(tMs = tMs + ms)
    }

    private companion object {
        const val TAG = "SafetySensors"
        const val SAMPLING_US = 20_000 // 50 Hz
        const val MAX_BATCH_US = 1_000_000 // up to 1 s buffered in the sensor hub
        const val GPS_INTERVAL_MS = 1_000L
        const val NETWORK_INTERVAL_MS = 10_000L
        const val CLOCK_TOLERANCE_MS = 5_000L
    }
}
