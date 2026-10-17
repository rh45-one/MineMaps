package app.minemaps

import android.app.Application
import android.os.StrictMode
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.minemaps.data.Camera
import app.minemaps.data.MapStateRepository
import app.minemaps.data.SavedPlacesRepository
import app.minemaps.data.SettingsRepository
import app.minemaps.data.cameraStore
import app.minemaps.data.savedPlacesStore
import app.minemaps.data.settingsStore
import app.minemaps.location.LocationRepository
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.routing.RoadAlert
import app.minemaps.engine.routing.RoadAlertsClient
import app.minemaps.engine.routing.RouteTracker
import app.minemaps.engine.routing.RoutingClient
import app.minemaps.engine.search.PlaceIndex
import app.minemaps.engine.search.PlacePrefetcher
import app.minemaps.map.MapEngine
import app.minemaps.map.TexturePackManager
import app.minemaps.nav.GoogleMapsMirror
import app.minemaps.nav.NavigationController
import app.minemaps.search.GooglePlacesClient
import app.minemaps.search.PhotonClient
import app.minemaps.update.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
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
    val googlePlaces = GooglePlacesClient({ httpClient.await() }, BuildConfig.USER_AGENT)
    val routing = RoutingClient({ httpClient.await() }, BuildConfig.USER_AGENT)
    val roadAlertsClient = RoadAlertsClient({ httpClient.await() }, BuildConfig.USER_AGENT)
    val roadAlerts = kotlinx.coroutines.flow.MutableStateFlow<List<RoadAlert>>(emptyList())
    val navigation = NavigationController(
        appScope,
        settings,
        location,
        routing,
        engine = { engine.await() },
        fallbackOrigin = { mapState.current?.let { it.lat to it.lon } },
    )

    init {
        appScope.launch(Dispatchers.IO.limitedParallelism(1)) { feedPlaceIndex(app) }
        appScope.launch(Dispatchers.IO) { feedRoadAlerts(app) }
    }

    private suspend fun feedRoadAlerts(app: Application) {
        val connectivity = app.getSystemService(ConnectivityManager::class.java)
        fun online() = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
        var lastLat = Double.NaN
        var lastLon = Double.NaN
        var lastFetchMs = 0L
        var dbAlerts: List<RoadAlert> = emptyList()
        val gmapsPins = LinkedHashMap<String, Pair<RoadAlert, Long>>()
        appScope.launch {
            GoogleMapsMirror.latestAlertNotice.collect { notice ->
                if (notice == null) return@collect
                val loc = location.lastKnown()
                val lat = loc?.latitude ?: mapState.current?.lat ?: return@collect
                val lon = loc?.longitude ?: mapState.current?.lon ?: return@collect
                val id = "gmaps_${notice.kind.name}_${(lat * 200).toInt()}_${(lon * 200).toInt()}"
                synchronized(gmapsPins) {
                    gmapsPins[id] = RoadAlert(id, lat, lon, notice.kind, notice.label, notice.detail) to notice.reportedAtMs
                }
                val now = System.currentTimeMillis()
                val activeGmaps = synchronized(gmapsPins) {
                    gmapsPins.values.filter { now - it.second < 30 * 60_000L }.map { it.first }
                }
                roadAlerts.value = (dbAlerts + activeGmaps).distinctBy { it.id }
            }
        }
        while (true) {
            val s = settings.settings.firstOrNull()
            val c = mapState.current ?: location.lastKnown()?.let { Camera(it.latitude, it.longitude, 16.0) }
            if (s == null || !s.showRoadAlerts || c == null || c.zoom < 10.5 || !online()) {
                if (s != null && !s.showRoadAlerts && roadAlerts.value.isNotEmpty()) roadAlerts.value = emptyList()
                delay(2_000)
                continue
            }
            val now = System.currentTimeMillis()
            val movedM = if (lastLat.isFinite() && lastLon.isFinite()) RouteTracker.metersBetween(lastLat, lastLon, c.lat, c.lon) else Double.MAX_VALUE
            if (movedM > 5_000.0 || now - lastFetchMs > 5 * 60_000L) {
                runCatching { roadAlertsClient.fetchNearby(c.lat, c.lon) }
                    .onSuccess { fetched ->
                        lastLat = c.lat
                        lastLon = c.lon
                        lastFetchMs = now
                        dbAlerts = fetched
                        val activeGmaps = synchronized(gmapsPins) {
                            gmapsPins.values.filter { now - it.second < 30 * 60_000L }.map { it.first }
                        }
                        roadAlerts.value = (dbAlerts + activeGmaps).distinctBy { it.id }
                    }
                    .onFailure {
                        delay(8_000)
                    }
            }
            delay(4_000)
        }
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
