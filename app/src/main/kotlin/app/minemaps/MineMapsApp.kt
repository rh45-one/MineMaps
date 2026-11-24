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
import app.minemaps.nav.MirroredAlertNotice
import app.minemaps.nav.NavigationController
import app.minemaps.search.GooglePlacesClient
import app.minemaps.search.PhotonClient
import app.minemaps.update.UpdateManager
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

internal class RoadAlertsFeed(
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val mapState: MapStateRepository,
    private val location: LocationRepository,
    private val roadAlertsClient: RoadAlertsClient,
    private val roadAlerts: MutableStateFlow<List<RoadAlert>>,
    private val online: () -> Boolean,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val alertNotices: Flow<MirroredAlertNotice?> = GoogleMapsMirror.latestAlertNotice,
    private val idleDelayMs: Long = 2_000L,
    private val pollDelayMs: Long = 4_000L,
    private val failureDelayMs: Long = 8_000L,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val alertsLock = Any()
    private var alertsEnabled: Boolean? = null
    private var dbAlerts: List<RoadAlert> = emptyList()
    private val gmapsPins = LinkedHashMap<String, Pair<RoadAlert, Long>>()
    private val settingsReady = CompletableDeferred<Unit>()
    private var lastLat = Double.NaN
    private var lastLon = Double.NaN
    private var lastFetchMs = 0L

    private fun pruneExpiredLocked(now: Long): Boolean {
        if (gmapsPins.isEmpty()) return false
        return gmapsPins.entries.removeAll { now - it.value.second >= PIN_TTL_MS }
    }

    private fun publishLocked() {
        roadAlerts.value = if (alertsEnabled == true) {
            (dbAlerts + gmapsPins.values.map { it.first }).distinctBy { it.id }
        } else {
            emptyList()
        }
    }

    internal fun start() {
        scope.launch {
            try {
                settings.settings
                    .map { it.showRoadAlerts }
                    .distinctUntilChanged()
                    .collect { enabled -> onSettingsChanged(enabled) }
            } finally {
                settingsReady.complete(Unit)
            }
        }
        scope.launch {
            settingsReady.await()
            alertNotices.collect { notice -> onAlertNotice(notice) }
        }
        scope.launch(ioDispatcher) {
            settingsReady.await()
            while (isActive) {
                delay(pollStep())
            }
        }
    }

    internal fun onSettingsChanged(enabled: Boolean) {
        val now = clockMs()
        synchronized(alertsLock) {
            alertsEnabled = enabled
            pruneExpiredLocked(now)
            publishLocked()
        }
        settingsReady.complete(Unit)
    }

    internal fun onAlertNotice(notice: MirroredAlertNotice?) {
        if (notice == null) return
        val initialNow = clockMs()
        val enabled = synchronized(alertsLock) {
            val pruned = pruneExpiredLocked(initialNow)
            if (alertsEnabled != true) {
                if (pruned || roadAlerts.value.isNotEmpty()) publishLocked()
                return
            }
            true
        }
        if (!enabled) return
        val loc = location.lastKnown()
        val cam = mapState.current
        val lat = loc?.latitude ?: cam?.lat
        val lon = loc?.longitude ?: cam?.lon
        synchronized(alertsLock) {
            val now = clockMs()
            val pruned = pruneExpiredLocked(now)
            if (alertsEnabled != true) {
                if (pruned || roadAlerts.value.isNotEmpty()) publishLocked()
                return
            }
            if (lat == null || lon == null) {
                if (pruned) publishLocked()
                return
            }
            val id = "gmaps_${notice.kind.name}_${(lat * 200).toInt()}_${(lon * 200).toInt()}"
            gmapsPins[id] = RoadAlert(id, lat, lon, notice.kind, notice.label, notice.detail) to notice.reportedAtMs
            pruneExpiredLocked(now)
            publishLocked()
        }
    }

    internal suspend fun pollStep(): Long {
        val enabled = synchronized(alertsLock) { alertsEnabled }
            ?: (settings.settings.firstOrNull()?.showRoadAlerts?.also { onSettingsChanged(it) } ?: false)
        val c = mapState.current ?: location.lastKnown()?.let { Camera(it.latitude, it.longitude, 16.0) }
        val now = clockMs()
        if (!enabled || c == null || c.zoom < 10.5 || !online()) {
            synchronized(alertsLock) {
                val pruned = pruneExpiredLocked(now)
                if (pruned || (!enabled && roadAlerts.value.isNotEmpty())) {
                    publishLocked()
                }
            }
            return idleDelayMs
        }
        val movedM = if (lastLat.isFinite() && lastLon.isFinite()) {
            RouteTracker.metersBetween(lastLat, lastLon, c.lat, c.lon)
        } else {
            Double.MAX_VALUE
        }
        if (movedM <= 5_000.0 && now - lastFetchMs <= 5 * 60_000L) {
            synchronized(alertsLock) {
                val pruned = pruneExpiredLocked(now)
                if (pruned || (alertsEnabled != true && roadAlerts.value.isNotEmpty())) {
                    publishLocked()
                }
            }
            return pollDelayMs
        }
        return try {
            val fetched = roadAlertsClient.fetchNearby(c.lat, c.lon)
            lastLat = c.lat
            lastLon = c.lon
            lastFetchMs = now
            val publishNow = clockMs()
            synchronized(alertsLock) {
                dbAlerts = fetched
                pruneExpiredLocked(publishNow)
                publishLocked()
            }
            pollDelayMs
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            val failNow = clockMs()
            synchronized(alertsLock) {
                val pruned = pruneExpiredLocked(failNow)
                if (pruned || (alertsEnabled != true && roadAlerts.value.isNotEmpty())) {
                    publishLocked()
                }
            }
            failureDelayMs
        }
    }

    private companion object {
        const val PIN_TTL_MS = 30 * 60_000L
    }
}

class AppContainer(
    app: Application,
    private val httpClientFactory: () -> OkHttpClient = { OkHttpClient() },
    cameraStore: DataStore<Preferences> = app.cameraStore,
    settingsStore: DataStore<Preferences> = app.settingsStore,
    savedPlacesStore: DataStore<Preferences> = app.savedPlacesStore,
    private val clockMs: () -> Long = { System.currentTimeMillis() },
    private val isOnline: (() -> Boolean)? = null,
    private val startFeeders: Boolean = true,
) {
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
    val httpClient: Deferred<OkHttpClient> = appScope.async(Dispatchers.IO) { httpClientFactory() }
    val placeIndex = PlaceIndex()
    val engine: Deferred<MapEngine> = appScope.async(Dispatchers.IO) { MapEngine(app, httpClient.await(), placeIndex) }
    val location = LocationRepository(app, appScope)
    val savedPlaces = SavedPlacesRepository(savedPlacesStore)
    val settings = SettingsRepository(settingsStore)
    val mapState = MapStateRepository(cameraStore)
    val updates = UpdateManager(app, appScope, settings, client = { httpClient.await() })
    val textures = TexturePackManager(app, appScope, settings, engine = { engine.await() }, client = { httpClient.await() })
    val photon = PhotonClient({ httpClient.await() }, BuildConfig.PHOTON_URL, BuildConfig.USER_AGENT)
    val googlePlaces = GooglePlacesClient({ httpClient.await() }, BuildConfig.USER_AGENT)
    val routing = RoutingClient({ httpClient.await() }, BuildConfig.USER_AGENT)
    val roadAlertsClient = RoadAlertsClient({ httpClient.await() }, BuildConfig.USER_AGENT)
    val roadAlerts = MutableStateFlow<List<RoadAlert>>(emptyList())
    val navigation = NavigationController(
        appScope,
        settings,
        location,
        routing,
        engine = { engine.await() },
        fallbackOrigin = { mapState.current?.let { it.lat to it.lon } },
    )

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val onlineCheck: () -> Boolean = isOnline ?: {
        connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    internal val roadAlertsFeed = RoadAlertsFeed(
        scope = appScope,
        settings = settings,
        mapState = mapState,
        location = location,
        roadAlertsClient = roadAlertsClient,
        roadAlerts = roadAlerts,
        online = onlineCheck,
        clockMs = clockMs,
    )

    init {
        if (startFeeders) {
            appScope.launch(Dispatchers.IO.limitedParallelism(1)) { feedPlaceIndex() }
            roadAlertsFeed.start()
        }
    }

    private suspend fun feedPlaceIndex() {
        val prefetcher = PlacePrefetcher(engine.await().repository)
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
            if (prefetcher.needsPrefetch(c.lat, c.lon) && onlineCheck()) prefetcher.prefetch(c.lat, c.lon, onlineCheck)
            delay(30_000)
        }
    }
}

class MineMapsApp : Application() {
    lateinit var container: AppContainer
        internal set

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
