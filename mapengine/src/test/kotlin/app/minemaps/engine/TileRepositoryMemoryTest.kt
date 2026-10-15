package app.minemaps.engine

import app.minemaps.engine.data.TileRepository
import app.minemaps.engine.data.TileSource
import app.minemaps.engine.geo.TileKey
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TileRepositoryMemoryTest {
    private val source = object : TileSource {
        override val maxZoom = 14
        override suspend fun fetch(key: TileKey): ByteArray? = TestSupport.fixtureBytes()
    }

    @Test
    fun decodedTileCacheIsBoundedByBytesNotCount() = runBlocking<Unit> {
        val budget = 10L * 1024 * 1024
        val repo = TileRepository(source, memoryBudgetBytes = budget)
        val keys = (0 until 20).map { TileKey(14, 8000 + it, 5000) }
        var biggest = 0L
        for (k in keys) {
            val tile = repo.get(k)
            biggest = maxOf(biggest, tile.estimatedBytes)
            assertTrue(repo.memoryBytes() <= budget + biggest, "cache holds ${repo.memoryBytes()} bytes after ${k.x}")
        }
        assertTrue(repo.memoryBytes() <= budget + biggest)
        assertNotNull(repo.peek(keys.last()))
        assertNull(repo.peek(keys.first()))
        repo.clearMemory()
        assertEquals(0L, repo.memoryBytes())
        assertNull(repo.peek(keys.last()))
    }
}
