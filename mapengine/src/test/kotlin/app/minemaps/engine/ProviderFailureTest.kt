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
}
