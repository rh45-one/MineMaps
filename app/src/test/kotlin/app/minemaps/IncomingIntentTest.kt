package app.minemaps

import android.app.ActivityManager
import android.content.Intent
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.net.toUri
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.test.core.app.ApplicationProvider
import app.minemaps.data.Camera
import app.minemaps.data.MapStateRepository
import app.minemaps.data.SavedPlace
import app.minemaps.engine.layout.RenderProfile
import app.minemaps.map.MapRenderer
import app.minemaps.map.MapView
import app.minemaps.search.SearchState
import app.minemaps.ui.InfoCard
import app.minemaps.ui.MainViewModel
import app.minemaps.ui.MapController
import app.minemaps.ui.Screen
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IncomingIntentTest {

    private lateinit var app: MineMapsApp
    private val extraScopes = mutableListOf<CoroutineScope>()

    private class GatedDataStore(
        private val loadGate: CompletableDeferred<Preferences>,
        private val onLoadStarted: CompletableDeferred<Unit> = CompletableDeferred(),
    ) : DataStore<Preferences> {
        private val editMutex = Mutex()
        private val state = MutableStateFlow(emptyPreferences())

        override val data: Flow<Preferences> = flow {
            onLoadStarted.complete(Unit)
            val prefs = loadGate.await()
            state.value = prefs
            emit(prefs)
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
        Shadows.shadowOf(app.getSystemService(ActivityManager::class.java)).setMemoryClass(256)
    }

    @After
    fun tearDown() {
        app.container.appScope.cancel()
        extraScopes.forEach { it.cancel() }
        extraScopes.clear()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun tempStore(name: String): DataStore<Preferences> {
        val dir = Files.createTempDirectory("mm-intent-$name")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        extraScopes += scope
        return PreferenceDataStoreFactory.create(scope = scope) { dir.resolve("$name.preferences_pb").toFile() }
    }

    private fun offlineClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { throw IOException("offline test client") })
        .build()

    private fun emptySearchClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (req.url.host == "photon.komoot.io") {
                Response.Builder()
                    .request(req)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body("""{"features":[]}""".toResponseBody("application/json".toMediaType()))
                    .build()
            } else {
                throw IOException("offline test client for ${req.url.host}")
            }
        })
        .build()

    private fun installContainer(
        clientFactory: () -> OkHttpClient = ::offlineClient,
        cameraStore: DataStore<Preferences> = tempStore("camera"),
    ): AppContainer {
        app.container.appScope.cancel()
        val c = AppContainer(
            app = app,
            httpClientFactory = clientFactory,
            cameraStore = cameraStore,
            settingsStore = tempStore("settings"),
            savedPlacesStore = tempStore("saved"),
        )
        app.container = c
        return c
    }

    private fun findMapView(root: View): MapView? {
        if (root is MapView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findMapView(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun pumpUntil(timeoutMs: Long = 8_000L, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(25, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(condition(), "Timed out after ${timeoutMs}ms waiting for condition")
    }

    private fun advanceMainLooper(ms: Long) {
        var remaining = ms
        while (remaining > 0) {
            val step = minOf(25L, remaining)
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(step, TimeUnit.MILLISECONDS)
            remaining -= step
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun assertCameraNear(expected: Camera, actual: Camera?, message: String? = null) {
        val cam = assertNotNull(actual, message)
        assertEquals(expected.lat, cam.lat, 1e-6, message)
        assertEquals(expected.lon, cam.lon, 1e-6, message)
        assertEquals(expected.zoom, cam.zoom, 1e-3, message)
        assertEquals(expected.bearing, cam.bearing, 1e-3, message)
    }

    @Test
    fun mapStateLoadCompletingAfterUpdateOrPersistDoesNotClobberCurrent() = runBlocking<Unit> {
        val staleCamera = Camera(51.5007, -0.1246, 14.0)
        val incomingCamera = Camera(35.6812, 139.7671, 17.0)
        val stalePrefs = preferencesOf(
            doublePreferencesKey("lat") to staleCamera.lat,
            doublePreferencesKey("lon") to staleCamera.lon,
            doublePreferencesKey("zoom") to staleCamera.zoom,
            doublePreferencesKey("bearing") to staleCamera.bearing,
        )

        // Variant A: concurrent update() while load() is suspended
        val gateA = CompletableDeferred<Preferences>()
        val startedA = CompletableDeferred<Unit>()
        val repoA = MapStateRepository(GatedDataStore(gateA, startedA))
        val loadJobA = async(Dispatchers.IO) { repoA.load() }
        startedA.await()
        repoA.update(incomingCamera)
        assertEquals(incomingCamera, repoA.current)
        gateA.complete(stalePrefs)
        assertEquals(staleCamera, loadJobA.await())
        assertEquals(incomingCamera, repoA.current, "async load() must not clobber concurrent update()")

        // Variant B: concurrent persist() while load() is suspended
        val gateB = CompletableDeferred<Preferences>()
        val startedB = CompletableDeferred<Unit>()
        val repoB = MapStateRepository(GatedDataStore(gateB, startedB))
        val loadJobB = async(Dispatchers.IO) { repoB.load() }
        startedB.await()
        repoB.persist(incomingCamera)
        assertEquals(incomingCamera, repoB.current)
        gateB.complete(stalePrefs)
        assertEquals(staleCamera, loadJobB.await())
        assertEquals(incomingCamera, repoB.current, "async load() must not clobber concurrent persist()")
    }

    @Test
    fun coldStartGeoQueryPopulatesInitialCameraMountsMapAndSearchesOfflineAndWithPersistedCamera() {
        // Case 1: Fresh empty cameraStore + offline client + geo:0,0?q=pizza
        val container1 = installContainer(clientFactory = ::offlineClient)
        val queryIntent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=pizza".toUri())
        val controller1 = Robolectric.buildActivity(MainActivity::class.java, queryIntent).setup()
        val activity1 = controller1.get()
        val vm1 = ViewModelProvider(activity1)[MainViewModel::class.java]

        pumpUntil { vm1.initialCamera.value != null && findMapView(activity1.window.decorView) != null }

        assertEquals(Camera.WORLD, vm1.initialCamera.value)
        assertNotNull(vm1.camera)
        assertNotNull(container1.mapState.current)
        assertNotNull(findMapView(activity1.window.decorView), "MineMapsRoot must mount MapView, not stay on blank Box")
        assertEquals("pizza", vm1.text.value)
        assertTrue(vm1.resultsVisible.value)

        // Advance past Photon debounce (250ms) and verify offline fallback settles cleanly without crashing
        advanceMainLooper(350)
        pumpUntil { vm1.searchState.value is SearchState.Error }
        assertNotNull(findMapView(activity1.window.decorView))
        controller1.pause().stop().destroy()

        // Case 2: Pre-populated cameraStore + shared Google Maps URL + empty Photon results
        val savedCam = Camera(51.5007, -0.1246, 14.0)
        val cameraStore2 = tempStore("persisted_query")
        runBlocking {
            cameraStore2.edit {
                it[doublePreferencesKey("lat")] = savedCam.lat
                it[doublePreferencesKey("lon")] = savedCam.lon
                it[doublePreferencesKey("zoom")] = savedCam.zoom
                it[doublePreferencesKey("bearing")] = savedCam.bearing
            }
        }
        val container2 = installContainer(clientFactory = ::emptySearchClient, cameraStore = cameraStore2)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "https://maps.google.com/?q=Barnes+%26+Noble")
        }
        val controller2 = Robolectric.buildActivity(MainActivity::class.java, shareIntent).setup()
        val activity2 = controller2.get()
        val vm2 = ViewModelProvider(activity2)[MainViewModel::class.java]

        pumpUntil { vm2.initialCamera.value != null && findMapView(activity2.window.decorView) != null }

        assertEquals(savedCam, vm2.initialCamera.value)
        assertCameraNear(savedCam, container2.mapState.current)
        assertEquals("Barnes & Noble", vm2.text.value)
        assertTrue(vm2.resultsVisible.value)

        advanceMainLooper(350)
        pumpUntil {
            val s = vm2.searchState.value
            s is SearchState.Results && !s.updating
        }
        val res = vm2.searchState.value as SearchState.Results
        assertTrue(res.items.isEmpty())
        assertNotNull(findMapView(activity2.window.decorView))
        controller2.pause().stop().destroy()
    }

    @Test
    fun coldStartGeoPlaceIsNotClobberedBySlowPersistedMapStateOrInit() {
        val persistedCam = Camera(51.5007, -0.1246, 14.0)
        val tokyoCam = Camera(35.6812, 139.7671, 17.0)
        val loadGate = CompletableDeferred<Preferences>()
        val loadStarted = CompletableDeferred<Unit>()
        val gatedStore = GatedDataStore(loadGate, loadStarted)
        val container = installContainer(clientFactory = ::offlineClient, cameraStore = gatedStore)

        val placeIntent = Intent(Intent.ACTION_VIEW, "geo:35.6812,139.7671?q=35.6812,139.7671(Tokyo)".toUri())
        val controller = Robolectric.buildActivity(MainActivity::class.java, placeIntent).setup()
        val activity = controller.get()
        val vm = ViewModelProvider(activity)[MainViewModel::class.java]

        runBlocking { loadStarted.await() }
        assertCameraNear(tokyoCam, container.mapState.current)
        assertEquals(tokyoCam, vm.initialCamera.value)
        assertFalse(vm.following.value)
        assertFalse(vm.resultsVisible.value)

        // Now complete the slow disk load with stale London camera and let IO + UI settle
        loadGate.complete(
            preferencesOf(
                doublePreferencesKey("lat") to persistedCam.lat,
                doublePreferencesKey("lon") to persistedCam.lon,
                doublePreferencesKey("zoom") to persistedCam.zoom,
                doublePreferencesKey("bearing") to persistedCam.bearing,
            ),
        )

        pumpUntil { findMapView(activity.window.decorView) != null && vm.pendingFlyTarget.value == null }
        advanceMainLooper(800)

        assertCameraNear(tokyoCam, container.mapState.current, "persisted load() must not clobber incoming Place")
        assertCameraNear(tokyoCam, vm.camera, "MainViewModel.init must not overwrite incoming Place camera")
        assertEquals(tokyoCam, vm.initialCamera.value)
        assertFalse(vm.following.value)
        assertFalse(vm.resultsVisible.value)

        val mapView = assertNotNull(findMapView(activity.window.decorView))
        val actual = mapView.camera()
        assertEquals(tokyoCam.lat, actual.lat, 1e-3)
        assertEquals(tokyoCam.lon, actual.lon, 1e-3)
        assertEquals(tokyoCam.zoom, actual.zoom, 1e-2)
        controller.pause().stop().destroy()
    }

    @Test
    fun flyTargetEmittedWhileMapControllerViewIsNullExecutesAndConsumesUponAttachment() {
        val container = installContainer(clientFactory = ::offlineClient)
        val activityController = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = activityController.get()
        val vm = MainViewModel(container)
        val mapController = MapController()
        val engine = runBlocking { container.engine.await() }
        val renderer = MapRenderer(engine, RenderProfile.PHONE, 1f, null) {}

        try {
            activity.setContent {
                val flyTarget by vm.pendingFlyTarget.collectAsStateWithLifecycle()
                val mapView = mapController.view
                LaunchedEffect(flyTarget, mapView) {
                    val t = flyTarget ?: return@LaunchedEffect
                    val view = mapView ?: return@LaunchedEffect
                    vm.consumeFlyTarget(t.seq)
                    view.animateTo(t.camera.lat, t.camera.lon, t.camera.zoom)
                }
            }
            advanceMainLooper(50)

            val target = Camera(48.8584, 2.2945, 17.0)
            vm.requestFlyTo(target)
            advanceMainLooper(100)

            // View is still null: flyTarget must remain pending and not be dropped
            assertNotNull(vm.pendingFlyTarget.value)
            assertEquals(target, vm.pendingFlyTarget.value?.camera)

            val attachedView = MapView(activity, renderer).apply {
                setCamera(Camera(0.0, 0.0, 3.0))
            }
            mapController.view = attachedView

            pumpUntil { vm.pendingFlyTarget.value == null }
            advanceMainLooper(800)

            val cam = attachedView.camera()
            assertEquals(target.lat, cam.lat, 1e-3)
            assertEquals(target.lon, cam.lon, 1e-3)
            assertEquals(target.zoom, cam.zoom, 1e-2)
        } finally {
            renderer.close()
            activityController.pause().stop().destroy()
        }
    }

    @Test
    fun identicalPlaceIntentsAndRequestFlyToCallsAreNotConflated() {
        installContainer(clientFactory = ::offlineClient)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val vm = ViewModelProvider(activity)[MainViewModel::class.java]

        pumpUntil { findMapView(activity.window.decorView) != null }
        val mapView = assertNotNull(findMapView(activity.window.decorView))

        val eiffel = Camera(48.8584, 2.2945, 17.0)
        vm.requestFlyTo(eiffel)
        val firstSeq = assertNotNull(vm.pendingFlyTarget.value).seq
        vm.requestFlyTo(eiffel)
        val secondSeq = assertNotNull(vm.pendingFlyTarget.value).seq
        assertTrue(secondSeq > firstSeq, "identical requestFlyTo calls must allocate distinct sequence numbers")
        pumpUntil { vm.pendingFlyTarget.value == null }
        advanceMainLooper(800)

        // First identical onNewIntent
        val sameIntent = Intent(Intent.ACTION_VIEW, "geo:48.8584,2.2945?q=48.8584,2.2945(Eiffel)".toUri())
        controller.newIntent(sameIntent)
        pumpUntil { vm.pendingFlyTarget.value == null }
        advanceMainLooper(800)
        assertEquals(eiffel.lat, mapView.camera().lat, 1e-3)
        assertEquals(eiffel.lon, mapView.camera().lon, 1e-3)

        // Pan away and deliver exact same Place intent again
        mapView.setCamera(Camera(10.0, 20.0, 12.0))
        advanceMainLooper(50)
        assertTrue(abs(mapView.camera().lat - 10.0) < 0.01)

        controller.newIntent(sameIntent)
        pumpUntil { vm.pendingFlyTarget.value == null }
        advanceMainLooper(800)

        val finalCam = mapView.camera()
        assertEquals(eiffel.lat, finalCam.lat, 1e-3)
        assertEquals(eiffel.lon, finalCam.lon, 1e-3)
        assertEquals(eiffel.zoom, finalCam.zoom, 1e-2)
        controller.pause().stop().destroy()
    }

    @Test
    fun incomingPlaceAndShowSavedClearStaleResultsVisible() {
        installContainer(clientFactory = ::offlineClient)
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val vm = ViewModelProvider(activity)[MainViewModel::class.java]

        pumpUntil { findMapView(activity.window.decorView) != null }
        val mapView = assertNotNull(findMapView(activity.window.decorView))

        // Activate search results panel
        vm.onQueryChange("pizza")
        advanceMainLooper(50)
        assertTrue(vm.resultsVisible.value)

        // Incoming Place intent must clear resultsVisible so InfoCard.Inspect isn't occluded
        controller.newIntent(Intent(Intent.ACTION_VIEW, "geo:48.8584,2.2945?q=48.8584,2.2945(Tower)".toUri()))
        pumpUntil { vm.pendingFlyTarget.value == null }
        advanceMainLooper(800)

        assertFalse(vm.resultsVisible.value)
        assertEquals(InfoCard.Inspect("Tower", "Shared location", 48.8584, 2.2945), vm.card.value)
        assertEquals(48.8584, mapView.camera().lat, 1e-3)
        assertEquals(2.2945, mapView.camera().lon, 1e-3)

        // Also verify SavedPlacesScreen / showSaved clears stale resultsVisible
        vm.onQueryChange("bakery")
        advanceMainLooper(50)
        assertTrue(vm.resultsVisible.value)
        val saved = SavedPlace("id-1", "Bakery", 48.86, 2.35, 1L, "pin")
        vm.showSaved(saved)
        assertFalse(vm.resultsVisible.value)
        assertEquals(InfoCard.Saved(saved), vm.card.value)

        controller.pause().stop().destroy()
    }

    @Test
    fun onNewIntentFromSettingsSwitchesToMapAndActivityRecreatePreservesStateWithoutReplay() {
        installContainer(clientFactory = ::offlineClient)
        val launchIntent = Intent(Intent.ACTION_VIEW, "geo:0,0?q=pizza".toUri())
        val controller = Robolectric.buildActivity(MainActivity::class.java, launchIntent).setup()
        val activity = controller.get()
        val vm = ViewModelProvider(activity)[MainViewModel::class.java]

        pumpUntil { findMapView(activity.window.decorView) != null }

        // Navigate into Settings
        vm.screen.value = Screen.SETTINGS
        advanceMainLooper(50)
        assertEquals(Screen.SETTINGS, vm.screen.value)

        // Deliver Place via onNewIntent while on Settings
        val tokyoIntent = Intent(Intent.ACTION_VIEW, "geo:35.6812,139.7671?q=35.6812,139.7671(Tokyo)".toUri())
        controller.newIntent(tokyoIntent)
        pumpUntil { vm.pendingFlyTarget.value == null }
        advanceMainLooper(800)

        assertEquals(Screen.MAP, vm.screen.value)
        assertEquals(tokyoIntent.dataString, activity.intent.dataString)
        assertEquals(InfoCard.Inspect("Tokyo", "Shared location", 35.6812, 139.7671), vm.card.value)
        val mapViewBefore = assertNotNull(findMapView(activity.window.decorView))
        assertEquals(35.6812, mapViewBefore.camera().lat, 1e-3)
        assertEquals(139.7671, mapViewBefore.camera().lon, 1e-3)

        // Pan away and dismiss inspect card, then simulate configuration recreation
        mapViewBefore.setCamera(Camera(40.4168, -3.7038, 11.0))
        vm.dismissCard()
        advanceMainLooper(50)

        controller.recreate()
        val recreated = controller.get()
        val recreatedVm = ViewModelProvider(recreated)[MainViewModel::class.java]
        pumpUntil { findMapView(recreated.window.decorView) != null }
        advanceMainLooper(800)

        val mapViewAfter = assertNotNull(findMapView(recreated.window.decorView))
        assertNull(recreatedVm.card.value, "activity recreation must not replay stale incoming Place intent")
        assertNull(recreatedVm.pendingFlyTarget.value)
        assertEquals(40.4168, mapViewAfter.camera().lat, 1e-2)
        assertEquals(-3.7038, mapViewAfter.camera().lon, 1e-2)

        controller.pause().stop().destroy()
    }
}
