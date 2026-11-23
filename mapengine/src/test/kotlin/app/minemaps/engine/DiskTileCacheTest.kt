package app.minemaps.engine

import app.minemaps.engine.data.DiskTileCache
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiskTileCacheTest {
    @Test
    fun roundTripFreshnessAndEviction() {
        val dir = Files.createTempDirectory("tilecache").toFile()
        var now = 1_000_000L
        val cache = DiskTileCache(dir, maxBytes = 1000, ttlMillis = 100, clock = { now })
        assertNull(cache.get("a"))
        cache.put("a", ByteArray(400) { 1 })
        val e = assertNotNull(cache.get("a"))
        assertContentEquals(ByteArray(400) { 1 }, e.bytes)
        assertTrue(cache.isFresh(e))
        now += 500
        assertFalse(cache.isFresh(assertNotNull(cache.get("a"))))
        assertEquals(0, assertNotNull(run { cache.put("empty", ByteArray(0)); cache.get("empty") }).bytes.size)

        File2.touch(dir, 1)
        cache.put("b", ByteArray(400))
        cache.put("c", ByteArray(400))
        assertTrue(cache.sizeBytes() <= 1000)
        assertNotNull(cache.get("c"))
        dir.deleteRecursively()
    }

    @Test
    fun concurrentGetAndEvictEnforcesMaxBytesWithoutTimSortFailure() {
        val dir = Files.createTempDirectory("tilecache-race").toFile()
        try {
            var tick = 1_000_000L
            val cache = DiskTileCache(dir, maxBytes = 4_000, ttlMillis = 60_000, clock = { tick += 1000; tick })
            val payload = ByteArray(150) { 7 }
            for (i in 0 until 20) cache.put("k$i", payload)
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val readers = List(4) { r ->
                Thread {
                    var i = r
                    while (!stop.get()) {
                        cache.get("k${i % 60}")
                        i++
                    }
                }.apply { start() }
            }
            for (i in 20 until 120) {
                cache.put("k$i", payload)
            }
            stop.set(true)
            readers.forEach { it.join(5000) }
            assertTrue(cache.sizeBytes() <= 4_000, "cache exceeded maxBytes: ${cache.sizeBytes()}")
            val actualOnDisk = dir.listFiles { f -> f.name.endsWith(".tile") }?.sumOf { it.length() } ?: 0L
            assertTrue(actualOnDisk <= 4_000, "actual bytes on disk exceeded maxBytes: $actualOnDisk")
        } finally {
            dir.deleteRecursively()
        }
    }

    private object File2 {
        fun touch(dir: java.io.File, @Suppress("UNUSED_PARAMETER") n: Int) {
            dir.listFiles()?.forEachIndexed { i, f -> f.setLastModified(1000L + i) }
        }
    }
}
