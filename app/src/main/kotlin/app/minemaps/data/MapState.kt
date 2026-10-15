package app.minemaps.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.first

data class Camera(val lat: Double, val lon: Double, val zoom: Double) {
    companion object {
        val WORLD = Camera(30.0, 0.0, 3.0)
    }
}

class MapStateRepository(private val store: DataStore<Preferences>) {
    private val latKey = doublePreferencesKey("lat")
    private val lonKey = doublePreferencesKey("lon")
    private val zoomKey = doublePreferencesKey("zoom")

    @Volatile
    var current: Camera? = null
        private set

    suspend fun load(): Camera? {
        val p = store.data.first()
        val lat = p[latKey] ?: return null
        val lon = p[lonKey] ?: return null
        val zoom = p[zoomKey] ?: return null
        return Camera(lat, lon, zoom).also { current = it }
    }

    fun update(camera: Camera) {
        current = camera
    }

    suspend fun persist(camera: Camera) {
        current = camera
        store.edit {
            it[latKey] = camera.lat
            it[lonKey] = camera.lon
            it[zoomKey] = camera.zoom
        }
    }
}
