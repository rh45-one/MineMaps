package app.minemaps.engine

import app.minemaps.engine.data.MapTileProvider
import app.minemaps.engine.data.TileRepository
import app.minemaps.engine.data.TileSource
import app.minemaps.engine.geo.TileKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
