package app.minemaps.engine

import app.minemaps.engine.data.MapTileProvider
import app.minemaps.engine.data.NetState
import app.minemaps.engine.data.TileRepository
import app.minemaps.engine.data.TileSource
import app.minemaps.engine.geo.TileKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProviderFailureTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private class FailingSource(val failure: () -> Throwable) : TileSource {
        val calls = AtomicInteger()

        override suspend fun fetch(key: TileKey): ByteArray? {
            calls.incrementAndGet()
            throw failure()
        }
    }

    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        Thread.sleep(50)
    }

    private fun provider(source: TileSource, clock: () -> Long) =
        MapTileProvider(TileRepository(source, scope), TestSupport.atlas, scope, clock, 0) { _, _, _ -> }

    @Test
    fun nonIoErrorIsNotRetriedWithinSixtySeconds() {
        val source = FailingSource { IllegalStateException("boom") }
        var now = 1_000_000L
        val p = provider(source) { now }
        val key = TileKey(14, 8296, 5636)
        p.request(key, false)
        waitFor { source.calls.get() >= 1 && p.pendingCount() == 0 }
        assertEquals(1, source.calls.get())
        repeat(50) { p.request(key, false) }
        Thread.sleep(200)
        assertEquals(1, source.calls.get())
        now += 59_000
        p.request(key, false)
        Thread.sleep(200)
        assertEquals(1, source.calls.get())
        now += 2_000
        p.request(key, false)
        waitFor { source.calls.get() >= 2 }
        assertEquals(2, source.calls.get())
    }

    @Test
    fun ioErrorAfterBackoffExhaustionIsNotRetriedWithinFifteenSeconds() {
        val source = FailingSource { IOException("offline") }
        var now = 5_000L
        val p = provider(source) { now }
        val key = TileKey(14, 8296, 5636)
        p.request(key, false)
        waitFor { source.calls.get() >= 1 && p.pendingCount() == 0 }
        val first = source.calls.get()
        repeat(50) { p.request(key, false) }
        now += 14_000
        repeat(10) { p.request(key, false) }
        Thread.sleep(200)
        assertEquals(first, source.calls.get())
        now += 2_000
        p.request(key, false)
        waitFor { source.calls.get() > first }
        assertEquals(first + 1, source.calls.get())
    }

    @Test
    fun labelsAreNotRelaunchedEitherWhileFailed() {
        val source = FailingSource { IllegalStateException("boom") }
        var now = 0L
        val p = provider(source) { now }
        val key = TileKey(14, 8296, 5636)
        p.labels(key)
        waitFor { source.calls.get() >= 1 }
        repeat(50) { p.labels(key) }
        Thread.sleep(200)
        assertEquals(1, source.calls.get())
        now += 61_000
        p.labels(key)
        waitFor { source.calls.get() >= 2 }
        assertEquals(2, source.calls.get())
    }

    @Test
    fun outOfMemoryTrimsCachesAndIsNegativeCachedForFifteenSeconds() {
        val source = FailingSource { OutOfMemoryError("test") }
        var now = 10_000L
        val low = AtomicInteger()
        val p = provider(source) { now }
        p.onLowMemory = { low.incrementAndGet() }
        val key = TileKey(14, 8296, 5636)
        p.request(key, false)
        waitFor { low.get() >= 1 && p.pendingCount() == 0 }
        assertEquals(1, low.get())
        val calls = source.calls.get()
        repeat(30) { p.request(key, false) }
        now += 14_000
        repeat(5) { p.request(key, false) }
        Thread.sleep(200)
        assertEquals(calls, source.calls.get())
        now += 2_000
        p.request(key, false)
        waitFor { source.calls.get() > calls }
        assertEquals(calls + 1, source.calls.get())
    }

    @Test
    fun cachedHitDoesNotClearOfflineStateUntilLiveNetworkSuccess() {
        val tileOk = TileKey(14, 1, 1)
        val tileFail = TileKey(14, 2, 2)
        val tileOnline = TileKey(14, 3, 3)
        val source = object : TileSource {
            override val reportsNetworkSuccess: Boolean = true
            override var onNetworkSuccess: (() -> Unit)? = null
            override suspend fun fetch(key: TileKey): ByteArray? = when (key) {
                tileOk -> ByteArray(0) // simulates disk cache hit (no onNetworkSuccess)
                tileFail -> throw IOException("offline")
                tileOnline -> {
                    onNetworkSuccess?.invoke()
                    ByteArray(0)
                }
                else -> null
            }
        }
        val p = provider(source) { 0L }
        p.request(tileFail, false)
        waitFor { p.networkState.value == app.minemaps.engine.data.NetState.OFFLINE && p.pendingCount() == 0 }
        assertEquals(app.minemaps.engine.data.NetState.OFFLINE, p.networkState.value)

        // Cached tile hit must NOT clear OFFLINE
        p.request(tileOk, false)
        waitFor { p.pendingCount() == 0 }
        assertEquals(app.minemaps.engine.data.NetState.OFFLINE, p.networkState.value)

        // Live network success clears OFFLINE
        p.request(tileOnline, false)
        waitFor { p.networkState.value == app.minemaps.engine.data.NetState.OK }
        assertEquals(app.minemaps.engine.data.NetState.OK, p.networkState.value)
    }

    @Test
    fun retainOnlyCancelsInFlightLabelRetryJobs() {
        val source = FailingSource { IOException("offline") }
        val p = MapTileProvider(TileRepository(source, scope), TestSupport.atlas, scope, { 0L }, maxAttempts = 8) { _, _, _ -> }
        val k1 = TileKey(14, 10, 10)
        val k2 = TileKey(14, 20, 20)
        p.labels(k1)
        p.labels(k2)
        waitFor { p.pendingLabelCount() == 2 }
        p.retainOnly(listOf(k1))
        waitFor { p.pendingLabelCount() == 1 }
        assertEquals(1, p.pendingLabelCount())
        p.retainOnly(emptyList())
        waitFor { p.pendingLabelCount() == 0 }
        assertEquals(0, p.pendingLabelCount())
    }

    private fun awaitCondition(cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            assertTrue(System.nanoTime() < deadline, "Timed out waiting for condition")
            LockSupport.parkNanos(500_000L)
        }
    }

    @Test
    fun liveNetworkSuccessWithConcurrentIoFailureDoesNotStickOffline() {
        val tileFail = TileKey(14, 10, 10)
        val tileSuccess = TileKey(14, 11, 11)
        val failFetchEntered = CountDownLatch(1)
        val allowFailToThrow = CountDownLatch(1)
        val composeReached = CountDownLatch(1)
        val allowSuccessToFinish = CountDownLatch(1)

        val source = object : TileSource {
            override val reportsNetworkSuccess: Boolean = true
            override var onNetworkSuccess: (() -> Unit)? = null
            override suspend fun fetch(key: TileKey): ByteArray? = when (key) {
                tileFail -> {
                    failFetchEntered.countDown()
                    assertTrue(allowFailToThrow.await(5, TimeUnit.SECONDS))
                    throw IOException("offline")
                }
                tileSuccess -> {
                    onNetworkSuccess?.invoke()
                    ByteArray(0)
                }
                else -> null
            }
        }

        val p = provider(source) { 0L }
        p.timings.compose.listener = {
            composeReached.countDown()
            assertTrue(allowSuccessToFinish.await(5, TimeUnit.SECONDS))
        }

        p.request(tileFail, false)
        assertTrue(failFetchEntered.await(5, TimeUnit.SECONDS))

        p.request(tileSuccess, false)
        assertTrue(composeReached.await(5, TimeUnit.SECONDS))

        allowFailToThrow.countDown()
        awaitCondition { p.pendingCount() == 1 }
        assertEquals(NetState.OK, p.networkState.value)

        allowSuccessToFinish.countDown()
        awaitCondition { p.pendingCount() == 0 }
        assertEquals(NetState.OK, p.networkState.value)
    }

    @Test
    fun newerIoFailureAfterNetworkSuccessKeepsOfflineState() {
        val tileFail1 = TileKey(14, 20, 20)
        val tileSuccess = TileKey(14, 21, 21)
        val tileFail2 = TileKey(14, 22, 22)
        val composeReached = CountDownLatch(1)
        val allowSuccessToFinish = CountDownLatch(1)

        val source = object : TileSource {
            override val reportsNetworkSuccess: Boolean = true
            override var onNetworkSuccess: (() -> Unit)? = null
            override suspend fun fetch(key: TileKey): ByteArray? = when (key) {
                tileFail1, tileFail2 -> throw IOException("offline")
                tileSuccess -> {
                    onNetworkSuccess?.invoke()
                    ByteArray(0)
                }
                else -> null
            }
        }

        val p = provider(source) { 0L }
        p.request(tileFail1, false)
        awaitCondition { p.pendingCount() == 0 && p.networkState.value == NetState.OFFLINE }
        assertEquals(NetState.OFFLINE, p.networkState.value)

        p.timings.compose.listener = {
            composeReached.countDown()
            assertTrue(allowSuccessToFinish.await(5, TimeUnit.SECONDS))
        }
        p.request(tileSuccess, false)
        assertTrue(composeReached.await(5, TimeUnit.SECONDS))

        p.request(tileFail2, false)
        awaitCondition { p.pendingCount() == 1 }

        allowSuccessToFinish.countDown()
        awaitCondition { p.pendingCount() == 0 }
        assertEquals(NetState.OFFLINE, p.networkState.value)
    }

    @Test
    fun concurrentCacheHitAndIoFailureNeverClearsOffline() {
        // Part A: deterministic latched interleaving
        val tileFail = TileKey(14, 30, 30)
        val tileCacheHit = TileKey(14, 31, 31)
        val failFetchEntered = CountDownLatch(1)
        val allowFailToThrow = CountDownLatch(1)
        val composeReached = CountDownLatch(1)
        val allowCacheHitToFinish = CountDownLatch(1)

        val latchedSource = object : TileSource {
            override val reportsNetworkSuccess: Boolean = true
            override var onNetworkSuccess: (() -> Unit)? = null
            override suspend fun fetch(key: TileKey): ByteArray? = when (key) {
                tileFail -> {
                    failFetchEntered.countDown()
                    assertTrue(allowFailToThrow.await(5, TimeUnit.SECONDS))
                    throw IOException("offline")
                }
                tileCacheHit -> ByteArray(0) // cache hit: no onNetworkSuccess
                else -> null
            }
        }

        val p = provider(latchedSource) { 0L }
        p.timings.compose.listener = {
            composeReached.countDown()
            assertTrue(allowCacheHitToFinish.await(5, TimeUnit.SECONDS))
        }

        p.request(tileFail, false)
        assertTrue(failFetchEntered.await(5, TimeUnit.SECONDS))

        p.request(tileCacheHit, false)
        assertTrue(composeReached.await(5, TimeUnit.SECONDS))

        allowFailToThrow.countDown()
        awaitCondition { p.pendingCount() == 1 }
        assertEquals(NetState.OFFLINE, p.networkState.value)

        allowCacheHitToFinish.countDown()
        awaitCondition { p.pendingCount() == 0 }
        assertEquals(NetState.OFFLINE, p.networkState.value)

        // Part B: 100 concurrent iterations with zero network successes
        val tileCacheLabel = TileKey(14, 32, 32)
        val stressSource = object : TileSource {
            override val reportsNetworkSuccess: Boolean = true
            override var onNetworkSuccess: (() -> Unit)? = null
            override suspend fun fetch(key: TileKey): ByteArray? = when (key) {
                tileFail -> throw IOException("offline")
                tileCacheHit, tileCacheLabel -> ByteArray(0)
                else -> null
            }
        }

        repeat(100) {
            val sp = provider(stressSource) { 0L }
            sp.request(tileCacheHit, false)
            sp.labels(tileCacheLabel)
            sp.request(tileFail, false)
            awaitCondition { sp.pendingCount() == 0 && sp.pendingLabelCount() == 0 }
            assertEquals(NetState.OFFLINE, sp.networkState.value)
        }
    }

    @Test
    fun labelsLiveNetworkSuccessWithConcurrentFailureDoesNotStickOffline() {
        val tileFail = TileKey(14, 40, 40)
        val tileSuccess = TileKey(14, 41, 41)
        val tileFail2 = TileKey(14, 42, 42)
        val tileCacheHit = TileKey(14, 43, 43)
        val tileSuccess2 = TileKey(14, 44, 44)
        val failFetchEntered = CountDownLatch(1)
        val allowFailToThrow = CountDownLatch(1)

        val source = object : TileSource {
            override val reportsNetworkSuccess: Boolean = true
            override var onNetworkSuccess: (() -> Unit)? = null
            override suspend fun fetch(key: TileKey): ByteArray? = when (key) {
                tileFail -> {
                    failFetchEntered.countDown()
                    assertTrue(allowFailToThrow.await(5, TimeUnit.SECONDS))
                    throw IOException("offline")
                }
                tileFail2 -> throw IOException("offline")
                tileSuccess, tileSuccess2 -> {
                    onNetworkSuccess?.invoke()
                    ByteArray(0)
                }
                tileCacheHit -> ByteArray(0)
                else -> null
            }
        }

        val p = provider(source) { 0L }
        val labelsDone = CountDownLatch(1)
        p.onLabelsReady = { if (it == tileSuccess) labelsDone.countDown() }

        p.request(tileFail, false)
        assertTrue(failFetchEntered.await(5, TimeUnit.SECONDS))

        p.labels(tileSuccess)
        assertTrue(labelsDone.await(5, TimeUnit.SECONDS))

        allowFailToThrow.countDown()
        awaitCondition { p.pendingCount() == 0 && p.pendingLabelCount() == 0 }
        assertEquals(NetState.OK, p.networkState.value)

        // Transition offline via labels(), confirm cache-hit labels preserve OFFLINE, then recover via live labels()
        p.labels(tileFail2)
        awaitCondition { p.pendingLabelCount() == 0 }
        assertEquals(NetState.OFFLINE, p.networkState.value)

        val cacheHitDone = CountDownLatch(1)
        p.onLabelsReady = { if (it == tileCacheHit) cacheHitDone.countDown() }
        p.labels(tileCacheHit)
        assertTrue(cacheHitDone.await(5, TimeUnit.SECONDS))
        awaitCondition { p.pendingLabelCount() == 0 }
        assertEquals(NetState.OFFLINE, p.networkState.value)

        val success2Done = CountDownLatch(1)
        p.onLabelsReady = { if (it == tileSuccess2) success2Done.countDown() }
        p.labels(tileSuccess2)
        assertTrue(success2Done.await(5, TimeUnit.SECONDS))
        awaitCondition { p.pendingLabelCount() == 0 }
        assertEquals(NetState.OK, p.networkState.value)
    }
}
