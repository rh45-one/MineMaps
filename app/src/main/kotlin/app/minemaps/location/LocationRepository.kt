package app.minemaps.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.view.Surface
import android.view.WindowManager
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlin.math.abs

enum class LocationStatus { NO_PERMISSION, DISABLED, SEARCHING, OK }

data class LocationState(val location: Location?, val status: LocationStatus)

@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepository(private val context: Context, appScope: CoroutineScope) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val trigger = MutableStateFlow(0)

    val state: SharedFlow<LocationState> = trigger.flatMapLatest { locationFlow() }
        .shareIn(appScope, SharingStarted.WhileSubscribed(5000), replay = 1)

    /** Live compass azimuth in degrees [0, 360) from the device's rotation-vector sensor, smoothed with a circular low-pass filter. */
    val headingDeg: StateFlow<Float?> = headingFlow()
        .stateIn(appScope, SharingStarted.WhileSubscribed(2000), null)

    @Suppress("DEPRECATION")
    private fun headingFlow() = callbackFlow<Float?> {
        val sm = sensorManager
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
            ?: sm?.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        if (sm == null || sensor == null) {
            trySend(null)
            awaitClose { }
            return@callbackFlow
        }
        val rotMat = FloatArray(9)
        val remapped = FloatArray(9)
        val orientation = FloatArray(3)
        var smoothed: Float? = null
        var lastEmitNs = 0L

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.values.size < 3) return
                try {
                    SensorManager.getRotationMatrixFromVector(rotMat, event.values)
                } catch (_: IllegalArgumentException) {
                    return
                }
                val wm = context.getSystemService(WindowManager::class.java)
                val rotation = runCatching { wm?.defaultDisplay?.rotation ?: Surface.ROTATION_0 }.getOrDefault(Surface.ROTATION_0)
                val (axisX, axisY) = when (rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotMat, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                val rawDeg = ((Math.toDegrees(orientation[0].toDouble()).toFloat() % 360f) + 360f) % 360f
                val prev = smoothed
                val next = if (prev == null) {
                    rawDeg
                } else {
                    var delta = (rawDeg - prev) % 360f
                    if (delta > 180f) delta -= 360f else if (delta <= -180f) delta += 360f
                    (((prev + delta * 0.25f) % 360f) + 360f) % 360f
                }
                smoothed = next
                // Emit at ~30–50 fps whenever the smoothed angle moves by >= 0.35 degrees
                if (prev == null || (event.timestamp - lastEmitNs >= 18_000_000L && abs(shortestAngleDelta(prev, next)) >= 0.35f)) {
                    lastEmitNs = event.timestamp
                    trySend(next)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
        awaitClose { sm.unregisterListener(listener) }
    }

    companion object {
        fun shortestAngleDelta(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f else if (d <= -180f) d += 360f
            return d
        }
    }

    fun refresh() {
        trigger.value++
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(): List<String> {
        val all = manager.allProviders
        if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in all) {
            return listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER).filter { it in all }
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
    }

    @Suppress("MissingPermission")
    fun lastKnown(): Location? {
        if (!hasPermission()) return null
        return try {
            providers().mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) {
            null
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun locationFlow() = callbackFlow {
        if (!hasPermission()) {
            trySend(LocationState(null, LocationStatus.NO_PERMISSION))
            awaitClose { }
            return@callbackFlow
        }
        var last: Location? = lastKnown()

        fun current(): LocationState {
            val status = when {
                !LocationManagerCompat.isLocationEnabled(manager) -> LocationStatus.DISABLED
                last == null -> LocationStatus.SEARCHING
                else -> LocationStatus.OK
            }
            return LocationState(last, status)
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                val prev = last
                if (prev != null && location.elapsedRealtimeNanos < prev.elapsedRealtimeNanos) return
                last = location
                trySend(current())
            }

            override fun onProviderEnabled(provider: String) {
                trySend(current())
            }

            override fun onProviderDisabled(provider: String) {
                trySend(current())
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                trySend(current())
            }
        }
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            providers().forEach { manager.requestLocationUpdates(it, 1000L, 0f, listener, Looper.getMainLooper()) }
        } catch (e: SecurityException) {
            trySend(LocationState(null, LocationStatus.NO_PERMISSION))
        }
        trySend(current())
        awaitClose {
            manager.removeUpdates(listener)
            context.unregisterReceiver(receiver)
        }
    }
}
