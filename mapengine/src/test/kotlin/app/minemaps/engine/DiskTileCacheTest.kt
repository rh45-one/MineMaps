package app.minemaps.engine

import app.minemaps.engine.data.DiskTileCache
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
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

    @Test
    fun corruptGetDoesNotDeleteConcurrentValidPutOrDriftTotalBytes() {
        val dir = Files.createTempDirectory("tilecache-corrupt-race").toFile()
        try {
            val cache = DiskTileCache(dir, maxBytes = 10_000, clock = { 1_000_000L })
            val tileFile = File(dir, "k.tile")
            tileFile.writeBytes(byteArrayOf(1, 2, 3))
            assertEquals(3L, cache.sizeBytes())

            val readCorrupt = CountDownLatch(1)
            val putDone = CountDownLatch(1)
            cache.beforeCorruptLock = {
                readCorrupt.countDown()
                assertTrue(putDone.await(5, TimeUnit.SECONDS))
            }

            var readerResult: DiskTileCache.Entry? = DiskTileCache.Entry(ByteArray(0), 0L)
            var readerError: Throwable? = null
            val reader = Thread {
                try {
                    readerResult = cache.get("k")
                } catch (t: Throwable) {
                    readerError = t
                }
            }.apply { start() }

            assertTrue(readCorrupt.await(5, TimeUnit.SECONDS))
            val validPayload = ByteArray(50) { 42 }
            cache.put("k", validPayload)
            putDone.countDown()
            reader.join(5000)
            assertFalse(reader.isAlive)
            assertNull(readerError)
            assertContentEquals(validPayload, assertNotNull(readerResult).bytes)
            assertContentEquals(validPayload, assertNotNull(cache.get("k")).bytes)
            assertEquals(62L, cache.sizeBytes())
            val actualOnDisk = dir.listFiles { f -> f.name.endsWith(".tile") }?.sumOf { it.length() } ?: 0L
            assertEquals(62L, actualOnDisk)

            // Standalone corrupt & truncated-header files are still deleted with accurate totalBytes
            cache.clear()
            val shortFile = File(dir, "short.tile")
            shortFile.writeBytes(byteArrayOf(9, 8, 7))
            val cache2 = DiskTileCache(dir, maxBytes = 10_000, clock = { 1_000_000L })
            assertEquals(3L, cache2.sizeBytes())
            assertNull(cache2.get("short"))
            assertFalse(shortFile.exists())
            assertEquals(0L, cache2.sizeBytes())

            val mismatchFile = File(dir, "mismatch.tile")
            mismatchFile.writeBytes(ByteBuffer.allocate(16).putLong(1_000L).putInt(100).putInt(0).array())
            val cache3 = DiskTileCache(dir, maxBytes = 10_000, clock = { 1_000_000L })
            assertEquals(16L, cache3.sizeBytes())
            assertNull(cache3.get("mismatch"))
            assertFalse(mismatchFile.exists())
            assertEquals(0L, cache3.sizeBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun evictPreservesValidEntryBetweenTargetAndMaxBytesEvenOnEqualTimestamps() {
        val dir = Files.createTempDirectory("tilecache-hysteresis").toFile()
        try {
            val cache = DiskTileCache(dir, maxBytes = 1000, clock = { 1_000_000L })
            cache.put("a", ByteArray(88) { 1 }) // 100 B framed
            cache.put("b", ByteArray(938) { 2 }) // 950 B framed (> 900 target, <= 1000 maxBytes)
            assertNull(cache.get("a"))
            assertContentEquals(ByteArray(938) { 2 }, assertNotNull(cache.get("b")).bytes)
            assertEquals(950L, cache.sizeBytes())

            // Multiple small entries + 950 B entry at equal timestamps
            cache.clear()
            cache.put("a0", ByteArray(38) { 3 }) // 50 B
            cache.put("a1", ByteArray(38) { 4 }) // 50 B
            cache.put("b", ByteArray(938) { 5 }) // 950 B
            assertNull(cache.get("a0"))
            assertNull(cache.get("a1"))
            assertContentEquals(ByteArray(938) { 5 }, assertNotNull(cache.get("b")).bytes)
            assertEquals(950L, cache.sizeBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun putAppliesClockMtimeForLruEviction() {
        val dir = Files.createTempDirectory("tilecache-clock-mtime").toFile()
        try {
            var now = 1_000_000L
            val cache = DiskTileCache(dir, maxBytes = 1000, clock = { now })
            cache.put("a", ByteArray(400) { 1 }) // 412 B at t=1_000_000
            now = 1_010_000L
            cache.put("b", ByteArray(400) { 2 }) // 412 B at t=1_010_000
            now = 1_020_000L
            assertNotNull(cache.get("a")) // touches "a" to t=1_020_000
            now = 1_030_000L
            cache.put("c", ByteArray(400) { 3 }) // 412 B at t=1_030_000; triggers eviction
            assertNull(cache.get("b"))
            assertNotNull(cache.get("a"))
            assertNotNull(cache.get("c"))
            assertEquals(824L, cache.sizeBytes())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun getCacheHitDoesNotBlockOnPutLock() {
        val dir = Files.createTempDirectory("tilecache-lockfree-get").toFile()
        try {
            val writerInLock = CountDownLatch(1)
            val readerDone = CountDownLatch(1)
            val writerTimedOut = AtomicBoolean(false)
            val cache = DiskTileCache(dir, maxBytes = 10_000, clock = {
                if (Thread.currentThread().name == "writer") {
                    writerInLock.countDown()
                    writerTimedOut.set(!readerDone.await(5, TimeUnit.SECONDS))
                }
                1_000_000L
            })
            cache.put("a", ByteArray(32) { 5 })

            var writerError: Throwable? = null
            val writer = Thread({
                try {
                    cache.put("b", ByteArray(32) { 6 })
                } catch (t: Throwable) {
                    writerError = t
                }
            }, "writer").apply { start() }

            assertTrue(writerInLock.await(5, TimeUnit.SECONDS))
            var hit: DiskTileCache.Entry? = null
            var readerError: Throwable? = null
            val reader = Thread({
                try {
                    hit = cache.get("a")
                } catch (t: Throwable) {
                    readerError = t
                } finally {
                    readerDone.countDown()
                }
            }, "reader").apply { start() }

            reader.join(5000)
            writer.join(5000)
            assertFalse(reader.isAlive)
            assertFalse(writer.isAlive)
            assertNull(readerError)
            assertNull(writerError)
            assertFalse(writerTimedOut.get(), "writer timed out waiting for reader; get() blocked on put() lock")
            assertContentEquals(ByteArray(32) { 5 }, assertNotNull(hit).bytes)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oversizedPutAndZeroMaxBytesDoNotWipeExistingEntries() {
        val dir = Files.createTempDirectory("tilecache-oversized").toFile()
        try {
            val cache = DiskTileCache(dir, maxBytes = 1000, clock = { 1_000_000L })
            cache.put("a", ByteArray(100) { 1 }) // 112 B
            cache.put("huge", ByteArray(1000) { 2 }) // 1012 B > 1000
            assertContentEquals(ByteArray(100) { 1 }, assertNotNull(cache.get("a")).bytes)
            assertNull(cache.get("huge"))
            assertEquals(112L, cache.sizeBytes())

            // Overwriting existing key with oversized entry removes stale key without wiping others
            cache.put("b", ByteArray(50) { 4 }) // 62 B
            cache.put("a", ByteArray(1000) { 3 })
            assertNull(cache.get("a"))
            assertContentEquals(ByteArray(50) { 4 }, assertNotNull(cache.get("b")).bytes)
            assertEquals(62L, cache.sizeBytes())

            val zeroDir = Files.createTempDirectory("tilecache-zero").toFile()
            try {
                val zeroCache = DiskTileCache(zeroDir, maxBytes = 0, clock = { 1_000_000L })
                zeroCache.put("x", ByteArray(10))
                assertNull(zeroCache.get("x"))
                assertEquals(0L, zeroCache.sizeBytes())
                assertEquals(0, zeroDir.listFiles { f -> f.name.endsWith(".tile") }?.size ?: 0)
            } finally {
                zeroDir.deleteRecursively()
            }
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
