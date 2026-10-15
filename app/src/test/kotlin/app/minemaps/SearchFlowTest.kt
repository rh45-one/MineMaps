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

    private fun result(name: String, lat: Double = 0.0, lon: Double = 0.0, local: Boolean = false) = SearchResult(name, "", "", lat, lon, null, null, null, local)

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
}
