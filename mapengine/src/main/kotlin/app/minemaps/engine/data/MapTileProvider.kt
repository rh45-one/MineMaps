package app.minemaps.engine.data

import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.inspect.FeatureInspector
import app.minemaps.engine.inspect.InspectResult
import app.minemaps.engine.labels.LabelExtractor
import app.minemaps.engine.labels.TileLabels
import app.minemaps.engine.raster.BlockGrid
import app.minemaps.engine.raster.GridRasterizer
import app.minemaps.engine.render.TileCompositor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.plus
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.math.floor

enum class NetState { OK, OFFLINE }

class Timings {
    class Stat {
        private var count = 0L
        private var totalNs = 0L
        private var maxNs = 0L

        @Volatile
        var listener: ((Double) -> Unit)? = null

        fun record(ns: Long) {
            listener?.invoke(ns / 1e6)
            recordInternal(ns)
        }

        @Synchronized
        private fun recordInternal(ns: Long) {
            count++
            totalNs += ns
            if (ns > maxNs) maxNs = ns
        }

        @Synchronized
        fun count() = count

        @Synchronized
        fun meanMs() = if (count == 0L) 0.0 else totalNs / count / 1e6

        @Synchronized
        fun maxMs() = maxNs / 1e6
    }

    val rasterize = Stat()
    val compose = Stat()

    fun summary(): String =
        "rasterize: n=%d mean=%.2f ms max=%.2f ms | compose: n=%d mean=%.2f ms max=%.2f ms"
            .format(rasterize.count(), rasterize.meanMs(), rasterize.maxMs(), compose.count(), compose.meanMs(), compose.maxMs())
}

@OptIn(ExperimentalCoroutinesApi::class)
class MapTileProvider(
    private val repo: TileRepository,
    atlas: TextureAtlas,
    parentScope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAttempts: Int = MAX_ATTEMPTS,
    private val onTileReady: (key: TileKey, night: Boolean, argb: IntArray) -> Unit,
) {
    private data class RenderKey(val tile: TileKey, val night: Boolean)

    private val scope: CoroutineScope = parentScope + CoroutineExceptionHandler { _, e ->
        if (!loggedUncaught) {
            loggedUncaught = true
            System.err.println("MapTileProvider: swallowed uncaught ${e.javaClass.simpleName}")
        }
    }

    @Volatile
    private var loggedUncaught = false

    var onLabelsReady: ((TileKey) -> Unit)? = null

    /** Called (once per occurrence) when an OutOfMemoryError was caught; platform code should trim its own caches. */
    @Volatile
    var onLowMemory: (() -> Unit)? = null

    /** Drops the provider's internal caches (block grids and label results). */
    fun trimMemory() {
        repo.clearMemory()
        grids.clear()
        labelCache.clear()
    }

    private fun handleOutOfMemory() {
        trimMemory()
        onLowMemory?.invoke()
    }

    val timings = Timings()

    @Volatile
    private var labelExtractor = LabelExtractor()
    private val compositor = TileCompositor(atlas)
    private val cpu = Dispatchers.Default.limitedParallelism(maxOf(2, Runtime.getRuntime().availableProcessors() - 1))
    private val _networkState = MutableStateFlow(NetState.OK)
    val networkState: StateFlow<NetState> = _networkState

    private val lock = Any()
    private val pending = HashMap<RenderKey, Job>()
    private val pendingLabels = HashSet<TileKey>()
    private val failedTiles = HashMap<RenderKey, Long>()
    private val failedLabels = HashMap<TileKey, Long>()
    private val labelCache = LruMap<TileKey, TileLabels>(LABEL_CAPACITY)

    private val grids = SingleFlightCache<TileKey, BlockGrid>(scope, GRID_CAPACITY) { gridKey ->
        val vtKey = gridKey.ancestor(minOf(gridKey.z, repo.maxZoom))
        val tile = repo.get(vtKey)
        withContext(cpu) {
            val t0 = System.nanoTime()
            val g = GridRasterizer.rasterize(tile, vtKey, gridKey)
            timings.rasterize.record(System.nanoTime() - t0)
            g
        }
    }

    fun vectorTileKey(key: TileKey): TileKey = key.ancestor(minOf(key.z, GRID_ZOOM, repo.maxZoom))

    fun request(key: TileKey, night: Boolean) {
        val rk = RenderKey(key, night)
        val job: Job
        synchronized(lock) {
            if (pending.containsKey(rk)) return
            failedTiles[rk]?.let { until -> if (clock() < until) return else failedTiles.remove(rk) }
            job = scope.launch(start = CoroutineStart.LAZY) { render(rk) }
            pending[rk] = job
            job.invokeOnCompletion { synchronized(lock) { if (pending[rk] === job) pending.remove(rk) } }
        }
        job.start()
    }

    fun retainOnly(keys: Collection<TileKey>) {
        val keep = keys.toHashSet()
        val cancel = synchronized(lock) { pending.filterKeys { it.tile !in keep }.values.toList() }
        cancel.forEach { it.cancel() }
    }

    fun setLabelNameField(field: String) {
        labelExtractor = LabelExtractor(field)
        labelCache.clear()
    }

    suspend fun inspect(wx: Double, wy: Double, zoom: Double, pixelRatio: Float = 1f): InspectResult? {
        val vz = minOf(floor(zoom).toInt(), repo.maxZoom).coerceIn(0, 14)
        val n = 1 shl vz
        val key = TileKey(vz, floor(wx * n).toInt().coerceIn(0, n - 1), floor(wy * n).toInt().coerceIn(0, n - 1))
        val tile = repo.get(key)
        return withContext(cpu) { FeatureInspector.inspect(tile, key, wx, wy, zoom, pixelRatio) }
    }

    fun pendingCount(): Int = synchronized(lock) { pending.size }

    fun labels(vtKey: TileKey): TileLabels? {
        labelCache.get(vtKey)?.let { return it }
        synchronized(lock) {
            failedLabels[vtKey]?.let { until -> if (clock() < until) return null else failedLabels.remove(vtKey) }
            if (!pendingLabels.add(vtKey)) return null
        }
        scope.launch {
            var attempt = 0
            try {
                while (true) {
                    try {
                        val tile = repo.get(vtKey)
                        val result = withContext(cpu) { labelExtractor.extract(tile, vtKey) }
                        labelCache.put(vtKey, result)
                        _networkState.value = NetState.OK
                        onLabelsReady?.invoke(vtKey)
                        return@launch
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: IOException) {
                        _networkState.value = NetState.OFFLINE
                        if (attempt >= maxAttempts) {
                            synchronized(lock) { failedLabels[vtKey] = clock() + IO_FAILURE_WINDOW_MS }
                            return@launch
                        }
                        delay(backoffMs(attempt++))
                    } catch (e: OutOfMemoryError) {
                        handleOutOfMemory()
                        synchronized(lock) { failedLabels[vtKey] = clock() + IO_FAILURE_WINDOW_MS }
                        return@launch
                    } catch (e: Throwable) {
                        synchronized(lock) { failedLabels[vtKey] = clock() + ERROR_FAILURE_WINDOW_MS }
                        return@launch
                    }
                }
            } finally {
                synchronized(lock) { pendingLabels.remove(vtKey) }
            }
        }
        return null
    }

    private suspend fun render(rk: RenderKey) {
        var attempt = 0
        while (true) {
            try {
                val grid = grids.get(rk.tile.ancestor(minOf(rk.tile.z, GRID_ZOOM)))
                val pixels = withContext(cpu) {
                    val t0 = System.nanoTime()
                    val p = compositor.compose(grid, rk.tile, rk.night)
                    timings.compose.record(System.nanoTime() - t0)
                    p
                }
                _networkState.value = NetState.OK
                onTileReady(rk.tile, rk.night, pixels)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                _networkState.value = NetState.OFFLINE
                if (attempt >= maxAttempts) {
                    synchronized(lock) { failedTiles[rk] = clock() + IO_FAILURE_WINDOW_MS }
                    return
                }
                delay(backoffMs(attempt++))
            } catch (e: OutOfMemoryError) {
                handleOutOfMemory()
                synchronized(lock) { failedTiles[rk] = clock() + IO_FAILURE_WINDOW_MS }
                return
            } catch (e: Throwable) {
                synchronized(lock) { failedTiles[rk] = clock() + ERROR_FAILURE_WINDOW_MS }
                return
            }
        }
    }

    private fun backoffMs(attempt: Int): Long = minOf(30_000L, 1000L shl attempt.coerceAtMost(5))

    private class LruMap<K : Any, V : Any>(private val capacity: Int) {
        private val map = object : LinkedHashMap<K, V>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
        }

        @Synchronized
        fun get(key: K): V? = map[key]

        @Synchronized
        fun clear() = map.clear()

        @Synchronized
        fun put(key: K, value: V) {
            map[key] = value
        }
    }

    private companion object {
        const val GRID_ZOOM = 16
        const val GRID_CAPACITY = 96
        const val LABEL_CAPACITY = 64
        const val MAX_ATTEMPTS = 8
        const val IO_FAILURE_WINDOW_MS = 15_000L
        const val ERROR_FAILURE_WINDOW_MS = 60_000L
    }
}
