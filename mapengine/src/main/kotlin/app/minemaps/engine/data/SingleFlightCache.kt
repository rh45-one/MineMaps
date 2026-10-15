package app.minemaps.engine.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class SingleFlightCache<K : Any, V : Any>(
    private val scope: CoroutineScope,
    private val capacity: Int,
    private val load: suspend (K) -> V,
) {
    private class Inflight<V>(val result: CompletableDeferred<V>) {
        var job: Job? = null
        var waiters = 0
    }

    private val lock = Any()
    private val cache = object : LinkedHashMap<K, V>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    }
    private val inflight = HashMap<K, Inflight<V>>()

    fun clear() = synchronized(lock) { cache.clear() }

    fun peek(key: K): V? = synchronized(lock) { cache[key] }

    suspend fun get(key: K): V {
        val entry: Inflight<V>
        synchronized(lock) {
            cache[key]?.let { return it }
            entry = inflight.getOrPut(key) { Inflight(CompletableDeferred<V>()).also { start(key, it) } }
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

    private fun start(key: K, entry: Inflight<V>) {
        entry.job = scope.launch {
            try {
                val v = load(key)
                synchronized(lock) {
                    cache[key] = v
                    inflight.remove(key)
                }
                entry.result.complete(v)
            } catch (e: Throwable) {
                synchronized(lock) { if (inflight[key] === entry) inflight.remove(key) }
                entry.result.completeExceptionally(e)
            }
        }
    }
}
