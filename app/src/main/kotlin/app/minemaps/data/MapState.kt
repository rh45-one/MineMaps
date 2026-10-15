package app.minemaps.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

data class Camera(val lat: Double, val lon: Double, val zoom: Double, val bearing: Double = 0.0) {
    companion object {
        val WORLD = Camera(30.0, 0.0, 3.0)
        const val MAX_LAT = 85.05112878

        /** A usable camera (finite, lat clamped to the Mercator limit), or null when the values are garbage. */
        fun sanitized(lat: Double, lon: Double, zoom: Double, bearing: Double = 0.0): Camera? {
            if (!lat.isFinite() || !lon.isFinite() || !zoom.isFinite() || !bearing.isFinite()) return null
            if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0 || zoom < 0.0 || zoom > 25.0) return null
            return Camera(lat.coerceIn(-MAX_LAT, MAX_LAT), lon, zoom, ((bearing % 360.0) + 360.0) % 360.0)
        }
    }
}

class MapStateRepository(private val store: DataStore<Preferences>) {
    private val latKey = doublePreferencesKey("lat")
    private val lonKey = doublePreferencesKey("lon")
    private val zoomKey = doublePreferencesKey("zoom")
    private val bearingKey = doublePreferencesKey("bearing")

    @Volatile
    var current: Camera? = null
        private set

    suspend fun load(): Camera? {
        val p = store.safeData().first()
        val lat = p[latKey] ?: return null
        val lon = p[lonKey] ?: return null
        val zoom = p[zoomKey] ?: return null
        return Camera.sanitized(lat, lon, zoom, p[bearingKey] ?: 0.0)?.also { current = it }
    }

    fun update(camera: Camera) {
        if (Camera.sanitized(camera.lat, camera.lon, camera.zoom, camera.bearing) != null) current = camera
    }

    suspend fun persist(camera: Camera) {
        if (Camera.sanitized(camera.lat, camera.lon, camera.zoom, camera.bearing) == null) return
        current = camera
        store.edit {
            it[latKey] = camera.lat
            it[lonKey] = camera.lon
            it[zoomKey] = camera.zoom
            it[bearingKey] = camera.bearing
        }
    }
}
