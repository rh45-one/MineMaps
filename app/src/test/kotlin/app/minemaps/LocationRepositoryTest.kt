package app.minemaps

import android.Manifest
import android.app.Application
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.LocationRequest
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import app.minemaps.location.LocationRepository
import app.minemaps.location.LocationState
import app.minemaps.location.LocationStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLocationManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocationRepositoryTest {

    private lateinit var app: Application
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        scope.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
    }

    private fun loc(
        provider: String,
        lat: Double = 48.8566,
        lon: Double = 2.3522,
        accuracy: Float? = null,
        elapsedNs: Long = 10_000_000_000L,
        timeMs: Long = 1_700_000_000_000L,
    ): Location = Location(provider).apply {
        latitude = lat
        longitude = lon
        if (accuracy != null) this.accuracy = accuracy
        elapsedRealtimeNanos = elapsedNs
        time = timeMs
    }

    @Test
    fun isBetterLocationRejectsMissingOrInvalidAccuracyAgainstValidFix() {
        val gps = loc(LocationManager.GPS_PROVIDER, accuracy = 4f, elapsedNs = 10_000_000_000L)

        val netNoAcc = loc(LocationManager.NETWORK_PROVIDER, accuracy = null, elapsedNs = 11_000_000_000L)
        val passiveNoAcc = loc(LocationManager.PASSIVE_PROVIDER, accuracy = null, elapsedNs = 12_000_000_000L)
        val netZero = loc(LocationManager.NETWORK_PROVIDER, accuracy = 0f, elapsedNs = 11_000_000_000L)
        val netNeg = loc(LocationManager.NETWORK_PROVIDER, accuracy = -5f, elapsedNs = 11_000_000_000L)
        val netNaN = loc(LocationManager.NETWORK_PROVIDER, accuracy = Float.NaN, elapsedNs = 11_000_000_000L)
        val netInf = loc(LocationManager.NETWORK_PROVIDER, accuracy = Float.POSITIVE_INFINITY, elapsedNs = 11_000_000_000L)

        assertFalse(LocationRepository.isBetterLocation(netNoAcc, gps))
        assertFalse(LocationRepository.isBetterLocation(passiveNoAcc, gps))
        assertFalse(LocationRepository.isBetterLocation(netZero, gps))
        assertFalse(LocationRepository.isBetterLocation(netNeg, gps))
        assertFalse(LocationRepository.isBetterLocation(netNaN, gps))
        assertFalse(LocationRepository.isBetterLocation(netInf, gps))

        // Valid accuracy beats missing accuracy within 15s
        val gpsNewer = loc(LocationManager.GPS_PROVIDER, accuracy = 4f, elapsedNs = 12_000_000_000L)
        assertTrue(LocationRepository.isBetterLocation(gpsNewer, netNoAcc))
    }

    @Test
    fun isBetterLocationRejectsCoarseCrossProviderFixesAndRanksEqualAccuracy() {
        val gps4 = loc(LocationManager.GPS_PROVIDER, accuracy = 4f, elapsedNs = 10_000_000_000L)
        for (coarseAcc in listOf(5f, 20f, 50f, 54f)) {
            val net1s = loc(LocationManager.NETWORK_PROVIDER, accuracy = coarseAcc, elapsedNs = 11_000_000_000L)
            val net0s = loc(LocationManager.NETWORK_PROVIDER, accuracy = coarseAcc, elapsedNs = 10_000_000_000L)
            assertFalse(LocationRepository.isBetterLocation(net1s, gps4), "NETWORK ${coarseAcc}m +1s must not overwrite GPS 4m")
            assertFalse(LocationRepository.isBetterLocation(net0s, gps4), "NETWORK ${coarseAcc}m +0s must not overwrite GPS 4m")
        }

        val gps30 = loc(LocationManager.GPS_PROVIDER, accuracy = 30f, elapsedNs = 10_000_000_000L)
        val net60 = loc(LocationManager.NETWORK_PROVIDER, accuracy = 60f, elapsedNs = 11_000_000_000L)
        assertFalse(LocationRepository.isBetterLocation(net60, gps30))

        // Better accuracy GPS beats coarse NETWORK
        val net50 = loc(LocationManager.NETWORK_PROVIDER, accuracy = 50f, elapsedNs = 10_000_000_000L)
        val gps4Newer = loc(LocationManager.GPS_PROVIDER, accuracy = 4f, elapsedNs = 11_000_000_000L)
        assertTrue(LocationRepository.isBetterLocation(gps4Newer, net50))

        // Equal accuracy & equal timestamp prefers higher-rank provider (GPS > NETWORK)
        val net4Same = loc(LocationManager.NETWORK_PROVIDER, accuracy = 4f, elapsedNs = 10_000_000_000L)
        assertTrue(LocationRepository.isBetterLocation(gps4, net4Same))
        assertFalse(LocationRepository.isBetterLocation(net4Same, gps4))

        // Same provider at identical timestamp requires strict accuracy improvement
        val gps20Same = loc(LocationManager.GPS_PROVIDER, accuracy = 20f, elapsedNs = 10_000_000_000L)
        val gps2Same = loc(LocationManager.GPS_PROVIDER, accuracy = 2f, elapsedNs = 10_000_000_000L)
        assertFalse(LocationRepository.isBetterLocation(gps20Same, gps4))
        assertTrue(LocationRepository.isBetterLocation(gps2Same, gps4))
    }

    @Test
    fun isBetterLocationFallsBackToWallTimeWhenElapsedRealtimeNanosIsZero_andRejectsInvalidCoords() {
        val baseTime = 1_700_000_000_000L
        val currentWithElapsed = loc(
            LocationManager.GPS_PROVIDER,
            accuracy = 4f,
            elapsedNs = 10_000_000_000L,
            timeMs = baseTime,
        )

        // Mock/legacy fix with elapsedRealtimeNanos == 0L and +20s wall time must promote
        val staleMock = loc(
            LocationManager.NETWORK_PROVIDER,
            accuracy = 500f,
            elapsedNs = 0L,
            timeMs = baseTime + 20_000L,
        )
        assertTrue(LocationRepository.isBetterLocation(staleMock, currentWithElapsed))

        // Mock GPS 4f (+1s wall time) beats coarse NETWORK 500f
        val netCurrent = loc(
            LocationManager.NETWORK_PROVIDER,
            accuracy = 500f,
            elapsedNs = 10_000_000_000L,
            timeMs = baseTime,
        )
        val gpsMock = loc(
            LocationManager.GPS_PROVIDER,
            accuracy = 4f,
            elapsedNs = 0L,
            timeMs = baseTime + 1_000L,
        )
        assertTrue(LocationRepository.isBetterLocation(gpsMock, netCurrent))

        // Older wall time (-5s) with elapsedRealtimeNanos == 0L is rejected
        val olderMock = loc(
            LocationManager.GPS_PROVIDER,
            accuracy = 4f,
            elapsedNs = 0L,
            timeMs = baseTime - 5_000L,
        )
        assertFalse(LocationRepository.isBetterLocation(olderMock, currentWithElapsed))

        // Both elapsedRealtimeNanos == 0L: +20s promoted, +1s coarse rejected, -1s rejected
        val baseZero = loc(LocationManager.GPS_PROVIDER, accuracy = 4f, elapsedNs = 0L, timeMs = baseTime)
        val plus20Zero = loc(LocationManager.NETWORK_PROVIDER, accuracy = 500f, elapsedNs = 0L, timeMs = baseTime + 20_000L)
        val plus1CoarseZero = loc(LocationManager.NETWORK_PROVIDER, accuracy = 50f, elapsedNs = 0L, timeMs = baseTime + 1_000L)
        val minus1Zero = loc(LocationManager.GPS_PROVIDER, accuracy = 2f, elapsedNs = 0L, timeMs = baseTime - 1_000L)
        assertTrue(LocationRepository.isBetterLocation(plus20Zero, baseZero))
        assertFalse(LocationRepository.isBetterLocation(plus1CoarseZero, baseZero))
        assertFalse(LocationRepository.isBetterLocation(minus1Zero, baseZero))

        // Completely untimestamped fix is rejected against timestamped fix
        val untimestamped = loc(LocationManager.GPS_PROVIDER, accuracy = 1f, elapsedNs = 0L, timeMs = 0L)
        assertFalse(LocationRepository.isBetterLocation(untimestamped, currentWithElapsed))

        // Non-finite or out-of-range coordinates are always rejected
        assertFalse(LocationRepository.isBetterLocation(loc(LocationManager.GPS_PROVIDER, lat = Double.NaN, accuracy = 1f), null))
        assertFalse(LocationRepository.isBetterLocation(loc(LocationManager.GPS_PROVIDER, lon = Double.POSITIVE_INFINITY, accuracy = 1f), null))
        assertFalse(LocationRepository.isBetterLocation(loc(LocationManager.GPS_PROVIDER, lat = 95.0, accuracy = 1f), null))
        assertFalse(LocationRepository.isBetterLocation(loc(LocationManager.GPS_PROVIDER, lon = 185.0, accuracy = 1f), null))
    }

    @Test
    fun lastKnownSelectsAccurateGpsOverNewerCoarseNetwork() {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(LocationManager::class.java)!!
        val shadowLm = Shadows.shadowOf(lm)

        val gps = loc(
            LocationManager.GPS_PROVIDER,
            lat = 48.8566,
            lon = 2.3522,
            accuracy = 4f,
            elapsedNs = 10_000_000_000L,
            timeMs = 10_000L,
        )
        val net = loc(
            LocationManager.NETWORK_PROVIDER,
            lat = 48.8700,
            lon = 2.3700,
            accuracy = 500f,
            elapsedNs = 11_000_000_000L,
            timeMs = 11_000L,
        )
        @Suppress("DEPRECATION")
        run {
            shadowLm.setLastKnownLocation(LocationManager.GPS_PROVIDER, gps)
            shadowLm.setLastKnownLocation(LocationManager.NETWORK_PROVIDER, net)
        }

        val repo = LocationRepository(app, scope)
        val picked = assertNotNull(repo.lastKnown())
        assertEquals(LocationManager.GPS_PROVIDER, picked.provider)
        assertEquals(4f, picked.accuracy)
    }

    @Implements(LocationManager::class)
    class SecurityThrowingLocationManagerShadow : ShadowLocationManager() {
        @Implementation
        override fun requestLocationUpdates(
            provider: String,
            locationRequest: LocationRequest,
            executor: java.util.concurrent.Executor,
            listener: LocationListener,
        ) {
            throw SecurityException("simulated missing permission")
        }

        @Implementation
        override fun requestLocationUpdates(
            provider: String,
            minTimeMs: Long,
            minDistanceM: Float,
            listener: LocationListener,
            looper: Looper?,
        ) {
            throw SecurityException("simulated missing permission")
        }
    }

    @Test
    @Config(shadows = [SecurityThrowingLocationManagerShadow::class])
    fun securityExceptionDuringRequestLocationUpdatesPreservesNoPermission() = runBlocking<Unit> {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        val repo = LocationRepository(app, scope)
        val emitted = repo.state.first()
        assertEquals(LocationStatus.NO_PERMISSION, emitted.status)
        assertEquals(null, emitted.location)
    }

    @Suppress("DEPRECATION")
    @Test
    fun locationFlowFiltersCoarseAndConcurrentUpdatesThreadSafely() = runBlocking<Unit> {
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val lm = app.getSystemService(LocationManager::class.java)!!
        val shadowLm = Shadows.shadowOf(lm)
        shadowLm.setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        shadowLm.setProviderEnabled(LocationManager.NETWORK_PROVIDER, true)

        val repo = LocationRepository(app, scope)
        val observed = CopyOnWriteArrayList<LocationState>()
        val firstOkLatch = CountDownLatch(1)
        val promotedLatch = CountDownLatch(1)

        val collectJob = scope.launch {
            repo.state.collect { st ->
                observed += st
                val loc = st.location
                if (st.status == LocationStatus.OK && loc?.accuracy == 4f) {
                    firstOkLatch.countDown()
                }
                if (loc != null && loc.provider == LocationManager.NETWORK_PROVIDER && loc.elapsedRealtimeNanos == 35_000_000_000L) {
                    promotedLatch.countDown()
                }
            }
        }

        // Wait until LocationListener is registered by locationFlow()
        val deadline = System.currentTimeMillis() + 5_000L
        while (shadowLm.requestLocationUpdateListeners.isEmpty() && System.currentTimeMillis() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(10, TimeUnit.MILLISECONDS)
            Thread.sleep(10)
        }
        assertTrue(shadowLm.requestLocationUpdateListeners.isNotEmpty(), "LocationListener should be registered")

        val gps1 = loc(LocationManager.GPS_PROVIDER, lat = 48.8566, lon = 2.3522, accuracy = 4f, elapsedNs = 10_000_000_000L, timeMs = 10_000L)
        shadowLm.simulateLocation(gps1)
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(firstOkLatch.await(3, TimeUnit.SECONDS))

        // Send coarse 50f and !hasAccuracy NETWORK fixes within 15s; neither may appear in state
        shadowLm.simulateLocation(loc(LocationManager.NETWORK_PROVIDER, lat = 48.90, lon = 2.40, accuracy = 50f, elapsedNs = 12_000_000_000L, timeMs = 12_000L))
        shadowLm.simulateLocation(loc(LocationManager.NETWORK_PROVIDER, lat = 48.91, lon = 2.41, accuracy = null, elapsedNs = 13_000_000_000L, timeMs = 13_000L))
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        Thread.sleep(50)

        assertTrue(
            observed.none { st -> st.location?.let { it.accuracy == 50f || !it.hasAccuracy() } == true },
            "Coarse 50f and !hasAccuracy() fixes must be filtered out: $observed",
        )

        // Concurrent multi-threaded listener hammer: monotonic GPS fixes interleaved with stale/coarse/no-acc/older fixes
        val listeners = shadowLm.requestLocationUpdateListeners.toList()
        val startGate = CountDownLatch(1)
        val threads = (0 until 4).map { tid ->
            Thread {
                startGate.await()
                for (i in 1..40) {
                    val l = listeners.first()
                    when ((tid + i) % 4) {
                        0 -> l.onLocationChanged(
                            loc(
                                LocationManager.GPS_PROVIDER,
                                lat = 48.8566 + i * 0.0001,
                                lon = 2.3522,
                                accuracy = 4f,
                                elapsedNs = 10_000_000_000L + i * 100_000_000L,
                                timeMs = 10_000L + i * 100L,
                            ),
                        )
                        1 -> l.onLocationChanged(
                            loc(
                                LocationManager.NETWORK_PROVIDER,
                                lat = 49.0,
                                lon = 3.0,
                                accuracy = 50f,
                                elapsedNs = 10_000_000_000L + i * 100_000_000L,
                                timeMs = 10_000L + i * 100L,
                            ),
                        )
                        2 -> l.onLocationChanged(
                            loc(
                                LocationManager.NETWORK_PROVIDER,
                                lat = 49.5,
                                lon = 3.5,
                                accuracy = null,
                                elapsedNs = 10_000_000_000L + i * 100_000_000L,
                                timeMs = 10_000L + i * 100L,
                            ),
                        )
                        else -> l.onProviderEnabled(LocationManager.GPS_PROVIDER)
                    }
                }
            }.apply { start() }
        }
        startGate.countDown()
        threads.forEach { it.join(5_000) }

        // No torn LocationState or leaked coarse/no-accuracy fix across concurrent threads
        assertTrue(observed.none { it.location != null && it.status == LocationStatus.SEARCHING })
        assertTrue(observed.none { st -> st.location?.let { it.accuracy == 50f || !it.hasAccuracy() } == true })

        // Verify stale (>15s) NETWORK fix is still promoted after the stress burst
        shadowLm.simulateLocation(
            loc(
                LocationManager.NETWORK_PROVIDER,
                lat = 48.88,
                lon = 2.38,
                accuracy = 500f,
                elapsedNs = 35_000_000_000L,
                timeMs = 35_000L,
            ),
        )
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        assertTrue(promotedLatch.await(3, TimeUnit.SECONDS))

        collectJob.cancel()
    }
}
