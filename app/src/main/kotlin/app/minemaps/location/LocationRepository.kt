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
import android.hardware.display.DisplayManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.view.Display
import android.view.Surface
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
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
    private val displayManager = context.getSystemService(DisplayManager::class.java)
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
                val rotation = runCatching { displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0 }.getOrDefault(Surface.ROTATION_0)
                val (axisX, axisY) = when (rotation) {
                    Surface.ROTATION_90 -> SensorManager.AXIS_Y to SensorManager.AXIS_MINUS_X
                    Surface.ROTATION_180 -> SensorManager.AXIS_MINUS_X to SensorManager.AXIS_MINUS_Y
                    Surface.ROTATION_270 -> SensorManager.AXIS_MINUS_Y to SensorManager.AXIS_X
                    else -> SensorManager.AXIS_X to SensorManager.AXIS_Y
                }
                SensorManager.remapCoordinateSystem(rotMat, axisX, axisY, remapped)
                SensorManager.getOrientation(remapped, orientation)
                if (!orientation[0].isFinite()) return
                val rawDeg = ((Math.toDegrees(orientation[0].toDouble()).toFloat() % 360f) + 360f) % 360f
                if (!rawDeg.isFinite()) return
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
        private const val STALE_LOCATION_NS = 15_000_000_000L
        private const val FUSED_PROVIDER_NAME = "fused"
        private const val MAX_SAFE_MS_DELTA = Long.MAX_VALUE / 1_000_000L

        fun shortestAngleDelta(from: Float, to: Float): Float {
            var d = (to - from) % 360f
            if (d > 180f) d -= 360f else if (d <= -180f) d += 360f
            return d
        }

        private fun hasValidCoords(loc: Location): Boolean =
            loc.latitude.isFinite() && loc.longitude.isFinite() &&
                loc.latitude in -90.0..90.0 && loc.longitude in -180.0..180.0

        private fun hasValidAccuracy(loc: Location): Boolean =
            loc.hasAccuracy() && loc.accuracy.isFinite() && loc.accuracy > 0f

        private fun providerRank(provider: String?): Int = when (provider) {
            LocationManager.GPS_PROVIDER -> 4
            FUSED_PROVIDER_NAME -> 3
            LocationManager.NETWORK_PROVIDER -> 2
            LocationManager.PASSIVE_PROVIDER -> 1
            else -> 0
        }

        private fun computeTimeDeltaNs(candidate: Location, current: Location): Long {
            val candElapsed = candidate.elapsedRealtimeNanos
            val currElapsed = current.elapsedRealtimeNanos
            if (candElapsed > 0L && currElapsed > 0L) {
                return candElapsed - currElapsed
            }
            val candTime = candidate.time
            val currTime = current.time
            if (candTime > 0L && currTime > 0L) {
                val diffMs = candTime - currTime
                return when {
                    diffMs > MAX_SAFE_MS_DELTA -> Long.MAX_VALUE
                    diffMs < -MAX_SAFE_MS_DELTA -> Long.MIN_VALUE
                    else -> diffMs * 1_000_000L
                }
            }
            val candHasTs = candElapsed > 0L || candTime > 0L
            val currHasTs = currElapsed > 0L || currTime > 0L
            return when {
                candHasTs && !currHasTs -> 1L
                !candHasTs && currHasTs -> -1L
                else -> 0L
            }
        }

        internal fun isBetterLocation(candidate: Location, current: Location?): Boolean {
            if (!hasValidCoords(candidate)) return false
            if (current == null || !hasValidCoords(current)) return true
            val timeDeltaNs = computeTimeDeltaNs(candidate, current)
            if (timeDeltaNs < 0L) return false
            if (timeDeltaNs > STALE_LOCATION_NS) return true

            val sameProvider = candidate.provider != null && candidate.provider == current.provider
            if (sameProvider) {
                if (timeDeltaNs > 0L) return true
                return hasValidAccuracy(candidate) && (!hasValidAccuracy(current) || candidate.accuracy < current.accuracy)
            }

            val candAccValid = hasValidAccuracy(candidate)
            val currAccValid = hasValidAccuracy(current)
            if (currAccValid) {
                if (!candAccValid) return false
                if (candidate.accuracy > current.accuracy) return false
                if (candidate.accuracy < current.accuracy) return true
                return timeDeltaNs > 0L || providerRank(candidate.provider) > providerRank(current.provider)
            }
            return candAccValid || timeDeltaNs > 0L || providerRank(candidate.provider) > providerRank(current.provider)
        }
    }

    fun refresh() {
        trigger.value++
    }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun providers(): List<String> {
        val mgr = manager ?: return emptyList()
        val all = mgr.allProviders
        if (Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in all) {
            return listOf(LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
        }
        return listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).filter { it in all }
    }

    @Suppress("MissingPermission")
    fun lastKnown(): Location? {
        val mgr = manager ?: return null
        if (!hasPermission()) return null
        return try {
            val all = mgr.allProviders
            val candidates = (providers() + listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER))
                .distinct()
                .filter { it in all }
            val fixes = candidates
                .mapNotNull { runCatching { mgr.getLastKnownLocation(it) }.getOrNull() }
                .filter { hasValidCoords(it) }
            if (fixes.isEmpty()) return null
            val useElapsed = fixes.all { it.elapsedRealtimeNanos > 0L }
            val sorted = fixes.sortedWith(
                compareBy<Location> { if (useElapsed) it.elapsedRealtimeNanos else it.time }
                    .thenByDescending { if (hasValidAccuracy(it)) it.accuracy else Float.MAX_VALUE },
            )
            var best: Location? = null
            for (loc in sorted) {
                if (isBetterLocation(loc, best)) best = loc
            }
            best
        } catch (_: SecurityException) {
            null
        }
    }

    @Suppress("MissingPermission", "DEPRECATION")
    private fun locationFlow() = callbackFlow {
        val mgr = manager
        if (mgr == null) {
            trySend(LocationState(null, LocationStatus.DISABLED))
            awaitClose { }
            return@callbackFlow
        }
        if (!hasPermission()) {
            trySend(LocationState(null, LocationStatus.NO_PERMISSION))
            awaitClose { }
            return@callbackFlow
        }
        val lock = Any()
        var last: Location? = lastKnown()

        fun emitCurrent() {
            val fallback = if (synchronized(lock) { last == null }) lastKnown() else null
            synchronized(lock) {
                if (last == null && fallback != null) last = fallback
                val status = when {
                    !LocationManagerCompat.isLocationEnabled(mgr) -> LocationStatus.DISABLED
                    last == null -> LocationStatus.SEARCHING
                    else -> LocationStatus.OK
                }
                trySend(LocationState(last, status))
            }
        }

        val listener = object : LocationListenerCompat {
            override fun onLocationChanged(location: Location) {
                synchronized(lock) {
                    if (!isBetterLocation(location, last)) return
                    last = location
                    val status = if (LocationManagerCompat.isLocationEnabled(mgr)) LocationStatus.OK else LocationStatus.DISABLED
                    trySend(LocationState(location, status))
                }
            }

            override fun onProviderEnabled(provider: String) {
                emitCurrent()
            }

            override fun onProviderDisabled(provider: String) {
                emitCurrent()
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                emitCurrent()
            }
        }
        val filter = IntentFilter().apply {
            addAction(LocationManager.PROVIDERS_CHANGED_ACTION)
            addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val req = LocationRequestCompat.Builder(1000L)
                .setMinUpdateIntervalMillis(500L)
                .setMinUpdateDistanceMeters(0f)
                .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
                .build()
            providers().forEach { provider ->
                LocationManagerCompat.requestLocationUpdates(mgr, provider, req, listener, Looper.getMainLooper())
            }
        } catch (_: SecurityException) {
            trySend(LocationState(null, LocationStatus.NO_PERMISSION))
            awaitClose {
                runCatching { LocationManagerCompat.removeUpdates(mgr, listener) }
                runCatching { context.unregisterReceiver(receiver) }
            }
            return@callbackFlow
        }
        emitCurrent()
        awaitClose {
            runCatching { LocationManagerCompat.removeUpdates(mgr, listener) }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    internal fun isBetterLocation(candidate: Location, current: Location?): Boolean =
        Companion.isBetterLocation(candidate, current)
}
