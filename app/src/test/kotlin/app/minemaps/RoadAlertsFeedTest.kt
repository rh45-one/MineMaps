package app.minemaps

import android.app.Application
import android.os.Looper
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.test.core.app.ApplicationProvider
import app.minemaps.data.Camera
import app.minemaps.data.MapStateRepository
import app.minemaps.data.SettingsRepository
import app.minemaps.engine.routing.RoadAlert
import app.minemaps.engine.routing.RoadAlertKind
import app.minemaps.engine.routing.RoadAlertsClient
import app.minemaps.location.LocationRepository
import app.minemaps.nav.GoogleMapsMirror
import app.minemaps.nav.MirroredAlertNotice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoadAlertsFeedTest {

    private lateinit var app: Application
    private val extraScopes = mutableListOf<CoroutineScope>()
    private val containers = mutableListOf<AppContainer>()

    private class GatedDataStore(
        private val initialGate: CompletableDeferred<Preferences>,
        private val onLoadStarted: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : DataStore<Preferences> {
        private val editMutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())
        private val gateUsed = AtomicBoolean(false)

        override val data: Flow<Preferences> = flow {
            onLoadStarted.complete(Unit)
            if (gateUsed.compareAndSet(false, true)) {
                val prefs = initialGate.await()
                state.value = prefs
            }
            state.collect { emit(it) }
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            editMutex.withLock {
                val updated = transform(state.value)
                state.value = updated
                updated
            }
    }

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        GoogleMapsMirror.latestAlertNotice.value = null
    }

    @After
    fun tearDown() {
        GoogleMapsMirror.latestAlertNotice.value = null
        containers.forEach { it.appScope.cancel() }
        containers.clear()
        extraScopes.forEach { it.cancel() }
        extraScopes.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tempStore(name: String): DataStore<Preferences> {
        val dir = Files.createTempDirectory("mm-alerts-$name")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        extraScopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("$name.preferences_pb").toFile() }
    }

    private fun jsonResponse(chain: Interceptor.Chain, json: String, code: Int = 200): Response =
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "ERR")
            .body(json.toResponseBody("application/json".toMediaType()))
            .build()

    private fun fakeAlertsClient(
        fetchCount: AtomicInteger = AtomicInteger(0),
        failNext: AtomicBoolean = AtomicBoolean(false),
        gate: CompletableDeferred<Unit>? = null,
        onPostPassHit: (() -> Unit)? = null,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor { chain ->
            val host = chain.request().url.host
            when (host) {
                "postpass.geofabrik.de" -> {
                    fetchCount.incrementAndGet()
                    onPostPassHit?.invoke()
                    if (gate != null) runBlocking { gate.await() }
                    if (failNext.get()) throw IOException("simulated failure")
                    jsonResponse(
                        chain,
                        """
                        {
                          "features": [
                            {
                              "properties": {
                                "osm_id": "101",
                                "tags": { "highway": "speed_camera", "maxspeed": "50" }
                              },
                              "geometry": { "coordinates": [2.3522, 48.8566] }
                            }
                          ]
                        }
                        """.trimIndent(),
                    )
                }
                "nominatim.openstreetmap.org" -> {
                    if (failNext.get()) throw IOException("simulated failure")
                    jsonResponse(chain, "[]")
                }
                else -> throw IOException("simulated failure for $host")
            }
        }
        .build()

    private fun pumpUntil(timeoutMs: Long = 5_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(15, java.util.concurrent.TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(condition(), "Timed out after ${timeoutMs}ms waiting for condition")
    }

    @Test
    fun latestAlertNoticeIsIgnoredWhileShowRoadAlertsFalseIncludingSlowSettingsLoad() = runBlocking<Unit> {
        // Case A: GatedDataStore emits showRoadAlerts = false AFTER alert notice is already populated
        val gate = CompletableDeferred<Preferences>()
        val loadStarted = CompletableDeferred<Unit>()
        val gatedStore = GatedDataStore(gate, loadStarted)
        val settingsRepo = SettingsRepository(gatedStore)
        val mapState = MapStateRepository(tempStore("cam-a"))
        mapState.update(Camera(48.8566, 2.3522, 15.0))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        extraScopes += scope
        val location = LocationRepository(app, scope)
        val alertsFlow = MutableStateFlow<List<RoadAlert>>(emptyList())
        val notices = MutableStateFlow<MirroredAlertNotice?>(
            MirroredAlertNotice(RoadAlertKind.SPEED_CAMERA, "Camera", "Ahead", reportedAtMs = 1_000L),
        )
        val client = RoadAlertsClient({ fakeAlertsClient() }, "TestAgent")
        val feed = RoadAlertsFeed(
            scope = scope,
            settings = settingsRepo,
            mapState = mapState,
            location = location,
            roadAlertsClient = client,
            roadAlerts = alertsFlow,
            online = { false },
            clockMs = { 1_000L },
            alertNotices = notices,
            idleDelayMs = 20L,
            pollDelayMs = 20L,
            failureDelayMs = 20L,
        )
        feed.start()
        loadStarted.await()
        // Notice is already emitted, settings haven't loaded yet -> must not publish prematurely
        Thread.sleep(30)
        assertEquals(emptyList(), alertsFlow.value)

        gate.complete(preferencesOf(booleanPreferencesKey("show_road_alerts") to false))
        Thread.sleep(60)
        assertEquals(emptyList(), alertsFlow.value, "Alert notice emitted while showRoadAlerts=false must be ignored")

        // Case B: Toggle true -> false -> true; notice emitted while disabled must never leak
        settingsRepo.setShowRoadAlerts(true)
        pumpUntil {
            // Confirm settings switched to true by pushing a fresh notice
            notices.value = MirroredAlertNotice(RoadAlertKind.HAZARD, "Hazard 1", "Lane closed", reportedAtMs = 2_000L)
            alertsFlow.value.any { it.label == "Hazard 1" }
        }
        settingsRepo.setShowRoadAlerts(false)
        pumpUntil { alertsFlow.value.isEmpty() }

        notices.value = MirroredAlertNotice(RoadAlertKind.SPEED_CAMERA, "Disabled Camera", "Should ignore", reportedAtMs = 3_000L)
        Thread.sleep(50)
        assertEquals(emptyList(), alertsFlow.value)

        settingsRepo.setShowRoadAlerts(true)
        Thread.sleep(50)
        assertTrue(alertsFlow.value.none { it.label == "Disabled Camera" }, "Disabled notice must not resurrect on re-enable")
    }

    @Test
    fun expiredGmapsPinsArePrunedOnOfflineZoomedOutSkippedFetchFailureAndDisabledTicks() = runBlocking<Unit> {
        val ttl = 30 * 60_000L
        val t0 = 1_000L

        suspend fun setupFeedWithSeed(
            nowMs: AtomicLong,
            online: AtomicBoolean,
            failFetch: AtomicBoolean = AtomicBoolean(false),
            fetchCount: AtomicInteger = AtomicInteger(0),
        ): Triple<RoadAlertsFeed, MutableStateFlow<List<RoadAlert>>, MapStateRepository> {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            extraScopes += scope
            val settingsRepo = SettingsRepository(tempStore("set-${System.nanoTime()}"))
            val mapState = MapStateRepository(tempStore("cam-${System.nanoTime()}"))
            mapState.update(Camera(48.8566, 2.3522, 15.0))
            val location = LocationRepository(app, scope)
            val alertsFlow = MutableStateFlow<List<RoadAlert>>(emptyList())
            val http = fakeAlertsClient(fetchCount = fetchCount, failNext = failFetch)
            val client = RoadAlertsClient({ http }, "TestAgent")
            val feed = RoadAlertsFeed(
                scope = scope,
                settings = settingsRepo,
                mapState = mapState,
                location = location,
                roadAlertsClient = client,
                roadAlerts = alertsFlow,
                online = { online.get() },
                clockMs = { nowMs.get() },
            )
            feed.onSettingsChanged(true)
            feed.pollStep() // seeds dbAlerts = [cam_101], lastFetchMs = t0
            feed.onAlertNotice(MirroredAlertNotice(RoadAlertKind.HAZARD, "Mirror Hazard", "Debris", reportedAtMs = t0))
            assertEquals(2, alertsFlow.value.size)

            // Just before 30m boundary: both dbAlert and gmapsPin must be retained
            nowMs.set(t0 + ttl - 1L)
            return Triple(feed, alertsFlow, mapState)
        }

        // 1. Offline pruning: retains before 30m, prunes gmapsPin at 30m while keeping dbAlert
        run {
            val now = AtomicLong(t0)
            val online = AtomicBoolean(true)
            val (feed, alerts, _) = setupFeedWithSeed(now, online)
            online.set(false)
            feed.pollStep()
            assertEquals(2, alerts.value.size, "Non-expired gmapsPin must survive offline tick before 30m")

            now.set(t0 + ttl)
            feed.pollStep()
            assertEquals(listOf("cam_101"), alerts.value.map { it.id }, "Expired gmapsPin must be pruned offline")
        }

        // 2. Zoomed out (< 10.5) pruning
        run {
            val now = AtomicLong(t0)
            val online = AtomicBoolean(true)
            val (feed, alerts, mapState) = setupFeedWithSeed(now, online)
            mapState.update(Camera(48.8566, 2.3522, 9.0))
            feed.pollStep()
            assertEquals(2, alerts.value.size)

            now.set(t0 + ttl)
            feed.pollStep()
            assertEquals(listOf("cam_101"), alerts.value.map { it.id }, "Expired gmapsPin must be pruned when zoomed out")
        }

        // 3. Skipped fetch pruning (fetch at t+29m, tick at t+31m without moving)
        run {
            val now = AtomicLong(t0)
            val online = AtomicBoolean(true)
            val fetchCount = AtomicInteger(0)
            val (feed, alerts, _) = setupFeedWithSeed(now, online, fetchCount = fetchCount)
            assertEquals(1, fetchCount.get())

            now.set(t0 + 29 * 60_000L)
            feed.pollStep()
            assertEquals(2, fetchCount.get(), "29m > 5m interval triggers second fetch")
            assertEquals(2, alerts.value.size, "gmapsPin is 29m old, still valid")

            now.set(t0 + 31 * 60_000L)
            feed.pollStep()
            assertEquals(2, fetchCount.get(), "2m since last fetch must skip network fetch")
            assertEquals(listOf("cam_101"), alerts.value.map { it.id }, "Expired gmapsPin must be pruned on skipped-fetch tick")
        }

        // 4. Fetch failure pruning
        run {
            val now = AtomicLong(t0)
            val online = AtomicBoolean(true)
            val fail = AtomicBoolean(false)
            val (feed, alerts, _) = setupFeedWithSeed(now, online, failFetch = fail)
            fail.set(true)
            now.set(t0 + ttl)
            feed.pollStep()
            assertEquals(listOf("cam_101"), alerts.value.map { it.id }, "Expired gmapsPin must be pruned on fetch failure")
        }

        // 5. Disabled for 30m then re-enabled: expired gmapsPin must not reappear
        run {
            val now = AtomicLong(t0)
            val online = AtomicBoolean(true)
            val (feed, alerts, _) = setupFeedWithSeed(now, online)
            feed.onSettingsChanged(false)
            assertEquals(emptyList(), alerts.value)

            now.set(t0 + ttl)
            feed.pollStep()
            assertEquals(emptyList(), alerts.value)

            feed.onSettingsChanged(true)
            assertEquals(listOf("cam_101"), alerts.value.map { it.id }, "Expired gmapsPin must not reappear on re-enable")
        }
    }

    @Test
    fun concurrentFetchAndAlertNoticeAndDisableDoNotDropOrLeakAlerts() = runBlocking<Unit> {
        // Part A: Concurrent fetchNearby + alertNotices emission must keep both dbAlerts and gmapsPins
        repeat(5) { iter ->
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            extraScopes += scope
            val settingsRepo = SettingsRepository(tempStore("conc-set-$iter"))
            val mapState = MapStateRepository(tempStore("conc-cam-$iter"))
            mapState.update(Camera(48.8566, 2.3522, 15.0))
            val location = LocationRepository(app, scope)
            val alertsFlow = MutableStateFlow<List<RoadAlert>>(emptyList())
            val notices = MutableStateFlow<MirroredAlertNotice?>(null)
            val fetchStarted = CompletableDeferred<Unit>()
            val fetchGate = CompletableDeferred<Unit>()
            val http = fakeAlertsClient(
                gate = fetchGate,
                onPostPassHit = { fetchStarted.complete(Unit) },
            )
            val feed = RoadAlertsFeed(
                scope = scope,
                settings = settingsRepo,
                mapState = mapState,
                location = location,
                roadAlertsClient = RoadAlertsClient({ http }, "TestAgent"),
                roadAlerts = alertsFlow,
                online = { true },
                clockMs = { 10_000L },
                alertNotices = notices,
                idleDelayMs = 10L,
                pollDelayMs = 5_000L,
                failureDelayMs = 10L,
            )
            feed.start()
            fetchStarted.await()

            // Emit alert notice on Default while IO fetchNearby is about to complete
            notices.value = MirroredAlertNotice(RoadAlertKind.HAZARD, "Concurrent Pin $iter", "Detail", reportedAtMs = 10_000L)
            fetchGate.complete(Unit)

            pumpUntil {
                val ids = alertsFlow.value.map { it.id }
                "cam_101" in ids && ids.any { it.startsWith("gmaps_HAZARD_") }
            }
            scope.cancel()
        }

        // Part B: Disabling showRoadAlerts while fetchNearby is in-flight must leave roadAlerts empty after HTTP 200
        val scopeB = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        extraScopes += scopeB
        val settingsRepoB = SettingsRepository(tempStore("conc-disable-set"))
        val mapStateB = MapStateRepository(tempStore("conc-disable-cam"))
        mapStateB.update(Camera(48.8566, 2.3522, 15.0))
        val locationB = LocationRepository(app, scopeB)
        val alertsFlowB = MutableStateFlow<List<RoadAlert>>(emptyList())
        val fetchStartedB = CompletableDeferred<Unit>()
        val fetchGateB = CompletableDeferred<Unit>()
        val httpB = fakeAlertsClient(
            gate = fetchGateB,
            onPostPassHit = { fetchStartedB.complete(Unit) },
        )
        val feedB = RoadAlertsFeed(
            scope = scopeB,
            settings = settingsRepoB,
            mapState = mapStateB,
            location = locationB,
            roadAlertsClient = RoadAlertsClient({ httpB }, "TestAgent"),
            roadAlerts = alertsFlowB,
            online = { true },
            clockMs = { 10_000L },
            alertNotices = MutableStateFlow(null),
            idleDelayMs = 10L,
            pollDelayMs = 5_000L,
            failureDelayMs = 10L,
        )
        feedB.start()
        fetchStartedB.await()

        settingsRepoB.setShowRoadAlerts(false)
        pumpUntil { alertsFlowB.value.isEmpty() }
        fetchGateB.complete(Unit)
        Thread.sleep(80)
        assertEquals(emptyList(), alertsFlowB.value, "In-flight fetch completing after disable must not publish alerts")
    }

    @Test
    fun appContainerEndToEndWiringWithGoogleMapsMirrorAndFakeClock() = runBlocking<Unit> {
        val clock = AtomicLong(1_000L)
        val online = AtomicBoolean(true)
        val container = AppContainer(
            app = app,
            httpClientFactory = { fakeAlertsClient() },
            cameraStore = tempStore("e2e-cam"),
            settingsStore = tempStore("e2e-set"),
            savedPlacesStore = tempStore("e2e-saved"),
            clockMs = { clock.get() },
            isOnline = { online.get() },
            startFeeders = true,
        )
        containers += container
        container.mapState.update(Camera(48.8566, 2.3522, 15.0))

        pumpUntil { container.roadAlerts.value.any { it.id == "cam_101" } }

        GoogleMapsMirror.latestAlertNotice.value = MirroredAlertNotice(
            kind = RoadAlertKind.SPEED_CAMERA,
            label = "Mobile Speed Camera (Google Maps)",
            detail = "Radar ahead",
            reportedAtMs = 1_000L,
        )
        pumpUntil {
            val ids = container.roadAlerts.value.map { it.id }
            "cam_101" in ids && ids.any { it.startsWith("gmaps_SPEED_CAMERA_") }
        }

        // Disable via SettingsRepository -> AppContainer.roadAlerts clears immediately
        container.settings.setShowRoadAlerts(false)
        pumpUntil { container.roadAlerts.value.isEmpty() }
    }
}
