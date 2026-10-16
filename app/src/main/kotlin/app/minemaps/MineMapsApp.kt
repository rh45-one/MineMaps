package app.minemaps

import android.app.Application
import android.os.StrictMode
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.minemaps.data.MapStateRepository
import app.minemaps.data.SavedPlacesRepository
import app.minemaps.data.SettingsRepository
import app.minemaps.data.cameraStore
import app.minemaps.data.savedPlacesStore
import app.minemaps.data.settingsStore
import app.minemaps.location.LocationRepository
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.search.PlaceIndex
import app.minemaps.engine.search.PlacePrefetcher
import app.minemaps.map.MapEngine
import app.minemaps.map.TexturePackManager
import app.minemaps.search.PhotonClient
import app.minemaps.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

class AppContainer(app: Application) {
    private val renderers = java.util.Collections.newSetFromMap(java.util.WeakHashMap<app.minemaps.map.MapRenderer, Boolean>())

    fun register(renderer: app.minemaps.map.MapRenderer) {
        synchronized(renderers) { renderers.add(renderer) }
    }

    /** Clears the caches of every live renderer (called on low-memory callbacks). */
    fun trimMemory() {
        val live = synchronized(renderers) { renderers.toList() }
        live.forEach { it.trimMemory() }
    }

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val httpClient: Deferred<OkHttpClient> = appScope.async(Dispatchers.IO) { OkHttpClient() }
    val placeIndex = PlaceIndex()
    val engine: Deferred<MapEngine> = appScope.async(Dispatchers.IO) { MapEngine(app, httpClient.await(), placeIndex) }
    val location = LocationRepository(app, appScope)
    val savedPlaces = SavedPlacesRepository(app.savedPlacesStore)
    val settings = SettingsRepository(app.settingsStore)
    val mapState = MapStateRepository(app.cameraStore)
    val updates = UpdateManager(app, appScope, settings, client = { httpClient.await() })
    val textures = TexturePackManager(app, appScope, settings, engine = { engine.await() }, client = { httpClient.await() })
    val photon = PhotonClient({ httpClient.await() }, BuildConfig.PHOTON_URL, BuildConfig.USER_AGENT)

    init {
        appScope.launch(Dispatchers.IO.limitedParallelism(1)) { feedPlaceIndex(app) }
    }

    private suspend fun feedPlaceIndex(app: Application) {
        val prefetcher = PlacePrefetcher(engine.await().repository)
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        fun online() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        var warmed = false
        while (true) {
            val c = mapState.current
            if (c == null) {
                delay(3_000)
                continue
            }
            placeIndex.setCenter(Mercator.lonToX(c.lon), Mercator.latToY(c.lat))
            if (!warmed) {
                warmed = true
                prefetcher.warmFromCache(c.lat, c.lon)
            }
            if (prefetcher.needsPrefetch(c.lat, c.lon) && online()) prefetcher.prefetch(c.lat, c.lon, ::online)
            delay(30_000)
        }
    }
}

class MineMapsApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) container.trimMemory()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        container.trimMemory()
    }

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.DEBUG) {
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectAll().penaltyLog().build())
        }
        DebugTools.apply(false)
        container = AppContainer(this)
        container.textures.start()
        container.appScope.launch {
            container.settings.settings.map { it.debugMode }.distinctUntilChanged().collect { DebugTools.apply(it) }
        }
    }
}
