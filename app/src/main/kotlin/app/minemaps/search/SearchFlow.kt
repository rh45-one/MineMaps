package app.minemaps.search

import app.minemaps.engine.search.PlaceIndex
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.hypot

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val items: List<SearchResult>, val updating: Boolean = false) : SearchState
    data class Error(val message: String) : SearchState
}

private val TRANSIT_VALUES = setOf("bus_stop", "platform", "stop", "stop_position", "tram_stop", "subway_entrance", "station")

private fun isTransit(r: SearchResult) = r.osmValue in TRANSIT_VALUES

private fun isLowValue(r: SearchResult) = isTransit(r) || r.osmKey == "office"

private fun matchesAll(r: SearchResult, tokens: List<String>): Boolean {
    val nameTokens = PlaceIndex.normalize(r.name).split(' ')
    return tokens.all { t -> nameTokens.any { it.startsWith(t) } }
}

private fun metersTo(r: SearchResult, lat: Double, lon: Double): Double {
    val dx = (r.lon - lon) * 111_320.0 * cos(Math.toRadians((r.lat + lat) / 2))
    return hypot(dx, (r.lat - lat) * 110_540.0)
}

/**
 * Photon's order is the backbone: duplicate transit/office hits keep only the one nearest the map centre, and transit stops sink below
 * the other all-token matches. Up to 3 local hits that match every query token within 5 km go first (unless a network hit has the
 * same name within 250 m); the other local hits follow the network list.
 */
fun mergeResults(local: List<SearchResult>, network: List<SearchResult>, query: String = "", centerLat: Double? = null, centerLon: Double? = null): List<SearchResult> {
    if (network.isEmpty()) return local
    val tokens = PlaceIndex.normalize(query).split(' ').filter { it.isNotEmpty() }
    val best = HashMap<String, SearchResult>()
    for (r in network) {
        if (!isLowValue(r)) continue
        val key = PlaceIndex.normalize(r.name) + "|" + (if (r.osmKey == "office") "office" else r.osmValue)
        val cur = best[key]
        if (cur == null || (centerLat != null && centerLon != null && metersTo(r, centerLat, centerLon) < metersTo(cur, centerLat, centerLon))) best[key] = r
    }
    val collapsed = network.filter { !isLowValue(it) || best[PlaceIndex.normalize(it.name) + "|" + (if (it.osmKey == "office") "office" else it.osmValue)] === it }
    val lastMatch = collapsed.indexOfLast { !isTransit(it) && matchesAll(it, tokens) }
    val net = if (lastMatch < 0) collapsed else {
        val transit = collapsed.take(lastMatch + 1).filter { isTransit(it) }
        collapsed.take(lastMatch + 1).filter { !isTransit(it) } + transit + collapsed.drop(lastMatch + 1)
    }
    val fresh = local.filter { l ->
        val n = PlaceIndex.normalize(l.name)
        net.none { PlaceIndex.normalize(it.name) == n && meters(it, l) < 250.0 }
    }
    val first = fresh.filter { l ->
        matchesAll(l, tokens) && (centerLat == null || centerLon == null || metersTo(l, centerLat, centerLon) <= 5_000.0)
    }.take(3)
    return first + net + fresh.filter { it !in first }
}

private fun meters(a: SearchResult, b: SearchResult): Double {
    val dx = (a.lon - b.lon) * 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
    return hypot(dx, (a.lat - b.lat) * 110_540.0)
}

/**
 * Local results are emitted on every keystroke; the network search starts after [debounceMs]. An in-flight request is only
 * cancelled when the new query no longer extends it; otherwise it finishes and its results stay visible while the newer query loads.
 * Runs on the collector's (single-threaded) context.
 */
fun searchStates(
    queries: Flow<String>,
    center: () -> Pair<Double, Double>? = { null },
    local: suspend (String) -> List<SearchResult> = { emptyList() },
    cached: (String) -> List<SearchResult>? = { null },
    debounceMs: Long = 250,
    localMinChars: Int = 2,
    netMinChars: Int = 3,
    network: suspend (String) -> List<SearchResult>,
): Flow<SearchState> = channelFlow {
    var latest = ""
    var localRes = emptyList<SearchResult>()
    var netRes = emptyList<SearchResult>()
    var updating = false
    var error: String? = null
    var inflight: Job? = null
    var inflightQuery = ""
    var pending: Job? = null

    suspend fun ProducerScope<SearchState>.publish() {
        val merged = mergeResults(localRes, netRes, latest, center()?.first, center()?.second)
        val err = error
        send(if (merged.isEmpty() && err != null && !updating) SearchState.Error(err) else SearchState.Results(merged, updating))
    }

    fun startNetwork(q: String) {
        if (inflight?.isActive == true) return
        inflightQuery = q
        inflight = launch {
            try {
                netRes = network(q)
                error = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message?.takeIf { it.isNotBlank() } ?: "Search failed"
            }
            inflight = null
            if (latest != q && latest.length >= netMinChars && cached(latest) == null) {
                startNetwork(latest)
            } else {
                updating = false
            }
            publish()
        }
    }

    queries.map { it.trim() }.distinctUntilChanged().collect { q ->
        latest = q
        pending?.cancel()
        if (q.length < localMinChars) {
            inflight?.cancel()
            inflight = null
            localRes = emptyList()
            netRes = emptyList()
            updating = false
            error = null
            send(SearchState.Idle)
            return@collect
        }
        localRes = local(q)
        if (q.length < netMinChars) {
            inflight?.cancel()
            inflight = null
            netRes = emptyList()
            updating = false
            error = null
        } else {
            val hit = cached(q)
            if (hit != null) {
                inflight?.cancel()
                inflight = null
                netRes = hit
                updating = false
                error = null
            } else {
                updating = true
                if (inflight?.isActive == true && !q.startsWith(inflightQuery)) {
                    inflight?.cancel()
                    inflight = null
                }
                pending = launch {
                    delay(debounceMs)
                    startNetwork(q)
                }
            }
        }
        publish()
    }
}

class ResponseCache<V : Any>(private val capacity: Int = 64, private val ttlMs: Long = 10 * 60_000L, private val clock: () -> Long = System::currentTimeMillis) {
    private class Item<V>(val value: V, val at: Long)

    private val map = object : LinkedHashMap<String, Item<V>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Item<V>>?): Boolean = size > capacity
    }

    @Synchronized
    fun get(key: String): V? {
        val item = map[key] ?: return null
        if (clock() - item.at > ttlMs) {
            map.remove(key)
            return null
        }
        return item.value
    }

    @Synchronized
    fun put(key: String, value: V) {
        map[key] = Item(value, clock())
    }

    companion object {
        fun key(query: String, lat: Double?, lon: Double?, lang: String?): String =
            "${lang.orEmpty()}|${lat?.let { Math.round(it / 0.05) }}|${lon?.let { Math.round(it / 0.05) }}|${query.trim().lowercase()}"
    }
}
