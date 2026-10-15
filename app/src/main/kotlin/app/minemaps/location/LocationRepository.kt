package app.minemaps.location

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.shareIn

enum class LocationStatus { NO_PERMISSION, DISABLED, SEARCHING, OK }

data class LocationState(val location: Location?, val status: LocationStatus)

@OptIn(ExperimentalCoroutinesApi::class)
class LocationRepository(private val context: Context, appScope: CoroutineScope) {
    private val manager = context.getSystemService(LocationManager::class.java)
    private val trigger = MutableStateFlow(0)

    val state: SharedFlow<LocationState> = trigger.flatMapLatest { locationFlow() }
        .shareIn(appScope, SharingStarted.WhileSubscribed(5000), replay = 1)

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
