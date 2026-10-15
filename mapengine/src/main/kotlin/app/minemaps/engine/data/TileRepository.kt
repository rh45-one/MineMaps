package app.minemaps.engine.data

import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.mvt.MvtDecoder
import app.minemaps.engine.mvt.VectorTile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class TileRepository(
    private val source: TileSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val memoryCapacity: Int = 48,
) {
    private class Inflight(val result: CompletableDeferred<VectorTile>) {
        var job: Job? = null
        var waiters = 0
    }

    private val lock = Any()
    private val memory = object : LinkedHashMap<TileKey, VectorTile>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, VectorTile>?): Boolean = size > memoryCapacity
    }
    private val inflight = HashMap<TileKey, Inflight>()

    val maxZoom: Int get() = source.maxZoom

    fun peek(key: TileKey): VectorTile? = synchronized(lock) { memory[key] }

    suspend fun get(key: TileKey): VectorTile {
        val entry: Inflight
        synchronized(lock) {
            memory[key]?.let { return it }
            entry = inflight.getOrPut(key) { Inflight(CompletableDeferred()).also { start(key, it) } }
            entry.waiters++
        }
        try {
            return entry.result.await()
        } finally {
            synchronized(lock) {
                entry.waiters--
                if (entry.waiters == 0 && !entry.result.isCompleted) {
                    entry.job?.cancel()
                    if (inflight[key] === entry) inflight.remove(key)
                }
            }
        }
    }

    private fun start(key: TileKey, entry: Inflight) {
        entry.job = scope.launch {
            try {
                val bytes = source.fetch(key)
                val tile = if (bytes == null || bytes.isEmpty()) VectorTile.EMPTY else MvtDecoder.decode(bytes)
                synchronized(lock) {
                    memory[key] = tile
                    inflight.remove(key)
                }
                entry.result.complete(tile)
            } catch (e: Throwable) {
                synchronized(lock) { if (inflight[key] === entry) inflight.remove(key) }
                entry.result.completeExceptionally(e)
            }
        }
    }
}
