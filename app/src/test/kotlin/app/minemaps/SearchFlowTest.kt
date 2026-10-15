package app.minemaps

import app.minemaps.search.ResponseCache
import app.minemaps.search.SearchResult
import app.minemaps.search.SearchState
import app.minemaps.search.mergeResults
import app.minemaps.search.searchStates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchFlowTest {
    private suspend fun TestScope.settle() {
        advanceTimeBy(5000)
        runCurrent()
    }

    private fun result(name: String, lat: Double = 0.0, lon: Double = 0.0, local: Boolean = false) = SearchResult(name, "", "", lat, lon, null, null, null, local, landmark = local)

    private class Harness(scope: TestScope, networkDelay: Long = 0, val netFails: Boolean = false, val cache: ResponseCache<List<SearchResult>>? = null) {
        val queries = MutableStateFlow("")
        val states = mutableListOf<SearchState>()
        val netCalls = mutableListOf<String>()
        val cancelled = mutableListOf<String>()
        val localCalls = mutableListOf<String>()
        var localFor: (String) -> List<SearchResult> = { emptyList() }

        init {
            searchStates(
                queries,
                local = { q -> localCalls += q; localFor(q) },
                cached = { q -> cache?.get(q) },
            ) { q ->
                netCalls += q
                try {
                    delay(networkDelay)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    cancelled += q
                    throw e
                }
                if (netFails) throw IOException("offline")
                listOf(SearchResult("net:$q", "", "", 1.0, 1.0, null, null, null)).also { cache?.put(q, it) }
            }.onEach { states += it }.launchIn(scope.backgroundScope)
        }

        val last get() = states.last()
    }

    @Test
    fun localResultsAreImmediateFromTwoCharsAndNetworkIsDebounced() = runTest {
        val h = Harness(this)
        h.localFor = { q -> listOf(result("local:$q", local = true)) }
        runCurrent()
        h.queries.value = "p"
        runCurrent()
        assertEquals(SearchState.Idle, h.last)
        h.queries.value = "pa"
        runCurrent()
        assertEquals(listOf("pa"), h.localCalls)
        val r = h.last as SearchState.Results
        assertEquals(listOf("local:pa"), r.items.map { it.name })
        assertTrue(!r.updating || h.netCalls.isEmpty(), "no network below 3 chars")
        assertTrue(h.netCalls.isEmpty())
        h.queries.value = "par"
        runCurrent()
        assertEquals(listOf("pa", "par"), h.localCalls)
        assertEquals(true, (h.last as SearchState.Results).updating)
        advanceTimeBy(249)
        assertTrue(h.netCalls.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("par"), h.netCalls)
        val done = h.last as SearchState.Results
        assertEquals(false, done.updating)
        assertEquals(listOf("local:par", "net:par"), done.items.map { it.name })
    }

    @Test
    fun fastTypingSendsOneRequestAfterThePause() = runTest {
        val h = Harness(this)
        runCurrent()
        for (q in listOf("lau", "laus", "lausa", "lausan")) {
            h.queries.value = q
            advanceTimeBy(100)
        }
        advanceTimeBy(300)
        runCurrent()
        assertEquals(listOf("lausan"), h.netCalls)
    }

    @Test
    fun inFlightRequestIsKeptWhenTheNewQueryExtendsItAndItsResultsStayVisible() = runTest {
        val h = Harness(this, networkDelay = 1000)
        runCurrent()
        h.queries.value = "par"
        advanceTimeBy(300)
        assertEquals(listOf("par"), h.netCalls)
        h.queries.value = "pari"
        advanceTimeBy(300)
        assertTrue(h.cancelled.isEmpty(), "extending query must not cancel")
        advanceTimeBy(700)
        runCurrent()
        val mid = h.last as SearchState.Results
        assertEquals(listOf("net:par"), mid.items.map { it.name })
        assertEquals(true, mid.updating)
        assertEquals(listOf("par", "pari"), h.netCalls)
        settle()
        val end = h.last as SearchState.Results
        assertEquals(listOf("net:pari"), end.items.map { it.name })
        assertEquals(false, end.updating)
    }

    @Test
    fun inFlightRequestIsCancelledWhenTheNewQueryDoesNotExtendIt() = runTest {
        val h = Harness(this, networkDelay = 1000)
        runCurrent()
        h.queries.value = "paris"
        advanceTimeBy(400)
        h.queries.value = "lyon"
        advanceTimeBy(100)
        assertEquals(listOf("paris"), h.cancelled)
        settle()
        assertEquals(listOf("paris", "lyon"), h.netCalls)
        assertEquals(listOf("net:lyon"), (h.last as SearchState.Results).items.map { it.name })
    }

    @Test
    fun errorsShowOnlyWhenBothLocalAndNetworkAreEmpty() = runTest {
        val h = Harness(this, netFails = true)
        runCurrent()
        h.queries.value = "xyz"
        settle()
        assertIs<SearchState.Error>(h.last)
        h.localFor = { listOf(result("near", local = true)) }
        h.queries.value = "xyzz"
        settle()
        val r = h.last as SearchState.Results
        assertEquals(listOf("near"), r.items.map { it.name })
        assertEquals(false, r.updating)
    }

    @Test
    fun cachedQueriesSkipTheNetwork() = runTest {
        val cache = ResponseCache<List<SearchResult>>()
        cache.put("rome", listOf(result("cached rome")))
        val h = Harness(this, cache = cache)
        runCurrent()
        h.queries.value = "rome"
        settle()
        assertTrue(h.netCalls.isEmpty())
        assertEquals(listOf("cached rome"), (h.last as SearchState.Results).items.map { it.name })
    }

    @Test
    fun clearingTheQueryReturnsToIdleAndCancels() = runTest {
        val h = Harness(this, networkDelay = 1000)
        runCurrent()
        h.queries.value = "paris"
        advanceTimeBy(400)
        h.queries.value = ""
        runCurrent()
        assertEquals(SearchState.Idle, h.last)
        assertEquals(listOf("paris"), h.cancelled)
    }

    @Test
    fun mergeKeepsTopThreeLocalThenNetworkThenRestAndReplacesSameNameNearby() {
        val local = listOf("a", "b", "c", "d", "e").map { result(it, 48.0, 2.0, local = true) }
        val net = listOf(result("x", 10.0, 10.0), result("y", 11.0, 11.0))
        assertEquals(listOf("a", "b", "c", "x", "y", "d", "e"), mergeResults(local, net).map { it.name })
        val dup = listOf(result("Rue Foo", 48.0, 2.0, local = true), result("Rue Bar", 48.0, 2.0, local = true))
        val netDup = listOf(result("rue foo", 48.0005, 2.0), result("Rue Bar", 49.0, 2.0))
        val merged = mergeResults(dup, netDup)
        assertEquals(listOf("Rue Bar", "rue foo", "Rue Bar"), merged.map { it.name })
        assertEquals(listOf(true, false, false), merged.map { it.local })
        assertEquals(local, mergeResults(local, emptyList()))
    }

    @Test
    fun responseCacheExpiresAndEvictsLeastRecentlyUsed() {
        var now = 0L
        val c = ResponseCache<String>(capacity = 2, ttlMs = 1000, clock = { now })
        c.put("a", "1")
        c.put("b", "2")
        assertEquals("1", c.get("a"))
        c.put("c", "3")
        assertEquals(null, c.get("b"))
        assertEquals("1", c.get("a"))
        now = 1500
        assertEquals(null, c.get("a"))
        assertEquals(ResponseCache.key("Paris ", 48.861, 2.349, "en"), ResponseCache.key("paris", 48.849, 2.351, "en"))
        assertTrue(ResponseCache.key("paris", 48.0, 2.0, "en") != ResponseCache.key("paris", 48.0, 2.0, "fr"))
    }

    private fun osm(name: String, lat: Double, lon: Double, key: String, value: String, local: Boolean = false) =
        SearchResult(name, "", "", lat, lon, null, value, null, local, key)

    @Test
    fun photonOrderIsKeptAndDuplicateTransitStopsCollapseToTheNearest() {
        val net = listOf(
            osm("Eiffel Tower", 48.8584, 2.2945, "man_made", "tower"),
            osm("Eiffel", 48.8950, 2.2870, "highway", "bus_stop"),
            osm("Eiffel", 48.8700, 2.3000, "office", "company"),
            osm("Eiffel", 48.9500, 2.2000, "highway", "bus_stop"),
            osm("Eiffel", 49.2000, 2.5000, "highway", "bus_stop"),
            osm("Eiffel", 48.7000, 2.1000, "highway", "bus_stop"),
            osm("Eiffel", 48.6000, 2.0000, "highway", "bus_stop"),
            osm("Eiffel XV", 48.8450, 2.2900, "tourism", "hotel"),
        )
        val out = mergeResults(emptyList(), net, "eiffel", 48.8584, 2.2945)
        assertEquals("Eiffel Tower", out.first().name)
        val stops = out.filter { it.osmValue == "bus_stop" }
        assertEquals(1, stops.size)
        assertEquals(48.8950, stops.single().lat, 1e-9)
        assertEquals(listOf("Eiffel Tower", "Eiffel", "Eiffel XV", "Eiffel"), out.map { it.name })
        assertEquals("office", out[1].osmKey)
        assertEquals("bus_stop", out.last().osmValue)
    }

    @Test
    fun nearbyLocalLandmarkAllTokenMatchPrecedesNetworkPartialMatches() {
        val local = listOf(result("Madame Brasserie", 48.8590, 2.2950, local = true), result("Brasserie Lune", 48.8590, 2.2950, local = true), result("Madame Brasserie Far", 49.5, 2.9, local = true))
        val net = listOf("Madame", "Madame Arthur", "Madame Z").map { result(it, 48.9, 2.3) }
        val ranked = mergeResults(local, net, "Madame Brasserie", 48.8584, 2.2945).map { it.name }
        assertEquals(listOf("Madame Brasserie", "Madame", "Madame Arthur", "Madame Z", "Brasserie Lune", "Madame Brasserie Far"), ranked)
    }

    @Test
    fun nonLandmarkLocalHitLeadsWhenTheNetworkOffersOnlyPartialMatches() {
        val restaurant = SearchResult("Madame Brasserie", "", "", 48.8590, 2.2950, null, null, null, true)
        val net = listOf("Madame", "Madame Arthur").map { result(it, 48.9, 2.3) }
        assertEquals(listOf("Madame Brasserie", "Madame", "Madame Arthur"), mergeResults(listOf(restaurant), net, "Madame Brasserie", 48.8584, 2.2945).map { it.name })
    }

    @Test
    fun nonLandmarkLocalHitFollowsTheNetworkWhenTheNetworkHasAnAllTokenMatch() {
        val restaurant = SearchResult("Madame Brasserie Annexe", "", "", 48.8590, 2.2950, null, null, null, true)
        val net = listOf(result("Madame", 48.9, 2.3), result("Madame Brasserie", 48.86, 2.3))
        assertEquals(listOf("Madame", "Madame Brasserie", "Madame Brasserie Annexe"), mergeResults(listOf(restaurant), net, "Madame Brasserie", 48.8584, 2.2945).map { it.name })
    }

    @Test
    fun onlyNearbyLandmarksLeadTheEiffelList() {
        fun poi(name: String, kind: String, landmark: Boolean) = SearchResult(name, "", "$kind \u00b7 nearby", 48.8584, 2.2945, "house", null, null, true, landmark = landmark)
        val local = listOf(poi("Eiffel techno", "Gift", false), poi("Tour Eiffel 2e \u00e9tage", "Viewpoint", true), poi("Le Carrousel de la Tour Eiffel", "Attraction", true))
        val net = listOf(
            osm("Eiffel Tower", 48.8584, 2.2945, "man_made", "tower"),
            osm("Eiffel", 48.8950, 2.2870, "highway", "bus_stop"),
            osm("Eiffel XV", 48.8450, 2.2900, "tourism", "hotel"),
        )
        val out = mergeResults(local, net, "eiffel", 48.8584, 2.2945).map { it.name }
        assertEquals(listOf("Tour Eiffel 2e \u00e9tage", "Le Carrousel de la Tour Eiffel", "Eiffel Tower", "Eiffel XV", "Eiffel", "Eiffel techno"), out)
    }

    @Test
    fun localDuplicatesOfNetworkHitsAreDropped() {
        val local = listOf(result("Eiffel Tower", 48.8584, 2.2945, local = true), result("Eiffel Shop", 48.8590, 2.2950, local = true))
        val net = listOf(result("eiffel tower", 48.8585, 2.2946))
        val out = mergeResults(local, net, "eiffel", 48.8584, 2.2945)
        assertEquals(listOf("Eiffel Shop", "eiffel tower"), out.map { it.name })
        assertEquals(listOf(true, false), out.map { it.local })
    }
}
