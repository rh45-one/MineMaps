package app.minemaps

import android.app.Application
import android.os.StrictMode
import app.minemaps.data.MapStateRepository
import app.minemaps.data.SavedPlacesRepository
import app.minemaps.data.SettingsRepository
import app.minemaps.data.cameraStore
import app.minemaps.data.savedPlacesStore
import app.minemaps.data.settingsStore
import app.minemaps.location.LocationRepository
import app.minemaps.map.MapEngine
import app.minemaps.search.PhotonClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import okhttp3.OkHttpClient

class AppContainer(app: Application) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val httpClient: Deferred<OkHttpClient> = appScope.async(Dispatchers.IO) { OkHttpClient() }
    val engine: Deferred<MapEngine> = appScope.async(Dispatchers.IO) { MapEngine(app, httpClient.await()) }
    val location = LocationRepository(app, appScope)
    val savedPlaces = SavedPlacesRepository(app.savedPlacesStore)
    val settings = SettingsRepository(app.settingsStore)
    val mapState = MapStateRepository(app.cameraStore)
    val photon = PhotonClient({ httpClient.await() }, BuildConfig.PHOTON_URL, BuildConfig.USER_AGENT)
}

class MineMapsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
        container = AppContainer(this)
    }
}
