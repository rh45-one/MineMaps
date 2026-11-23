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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

import java.util.concurrent.atomic.AtomicLong

class TileRepository(
    private val source: TileSource,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val memoryBudgetBytes: Long = defaultMemoryBudget(),
    maxConcurrentDecodes: Int = 2,
) {
    private class Inflight(val result: CompletableDeferred<VectorTile>) {
        var job: Job? = null
        var waiters = 0
    }

    private val lock = Any()
    private val decodePermits = Semaphore(maxConcurrentDecodes)
    private val memory = LinkedHashMap<TileKey, VectorTile>(64, 0.75f, true)
    private var memoryBytes = 0L
    private val inflight = HashMap<TileKey, Inflight>()
    private val _networkSuccessCount = AtomicLong(0L)

    init {
        if (source.reportsNetworkSuccess) {
            val prev = source.onNetworkSuccess
            source.onNetworkSuccess = {
                _networkSuccessCount.incrementAndGet()
                prev?.invoke()
            }
        }
    }

    /** Monotonic count of live network responses observed by the underlying [TileSource]. */
    fun networkSuccessCount(): Long = _networkSuccessCount.get()

    val maxZoom: Int get() = source.maxZoom

    @Volatile
    var onDecoded: ((TileKey, VectorTile) -> Unit)? = null

    fun cachedKeys(z: Int): List<TileKey> = source.cachedKeys(z)

    /** Drops every decoded tile held in memory (tiles stay in the disk cache). */
    fun clearMemory() = synchronized(lock) {
        memory.clear()
        memoryBytes = 0
    }

    fun memoryBytes(): Long = synchronized(lock) { memoryBytes }

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
                if (!source.reportsNetworkSuccess) _networkSuccessCount.incrementAndGet()
                val tile = if (bytes == null || bytes.isEmpty()) VectorTile.EMPTY else decodePermits.withPermit { MvtDecoder.decode(bytes) }
                synchronized(lock) {
                    memory.put(key, tile)?.let { memoryBytes -= it.estimatedBytes }
                    memoryBytes += tile.estimatedBytes
                    val it = memory.entries.iterator()
                    while (memoryBytes > memoryBudgetBytes && memory.size > 1 && it.hasNext()) {
                        val e = it.next()
                        if (e.value !== tile) {
                            memoryBytes -= e.value.estimatedBytes
                            it.remove()
                        }
                    }
                    inflight.remove(key)
                }
                runCatching { onDecoded?.invoke(key, tile) }
                entry.result.complete(tile)
            } catch (e: Throwable) {
                synchronized(lock) { if (inflight[key] === entry) inflight.remove(key) }
                entry.result.completeExceptionally(e)
            }
        }
    }
}

/** About a sixth of the heap limit, between 16 and 48 MB: decoded tiles are large (a dense z14 tile is ~4 MB). */
fun defaultMemoryBudget(): Long = (Runtime.getRuntime().maxMemory() / 6).coerceIn(16L * 1024 * 1024, 48L * 1024 * 1024)
