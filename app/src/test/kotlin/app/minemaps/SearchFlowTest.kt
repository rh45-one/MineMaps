package app.minemaps

import app.minemaps.search.SearchResult
import app.minemaps.search.SearchState
import app.minemaps.search.searchStates
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SearchFlowTest {
    private fun result(name: String) = SearchResult(name, "", "", 0.0, 0.0, null, null, null)

    @Test
    fun debouncesAndSearchesOnlyLastQuery() = runTest {
        val queries = MutableStateFlow("")
        val calls = mutableListOf<String>()
        val states = mutableListOf<SearchState>()
        searchStates(queries) { q -> calls += q; listOf(result(q)) }.onEach { states += it }.launchIn(backgroundScope)
        runCurrent()
        queries.value = "lau"
        advanceTimeBy(100)
        queries.value = "laus"
        advanceTimeBy(100)
        queries.value = "lausa"
        advanceTimeBy(449)
        assertTrue(calls.isEmpty())
        advanceTimeBy(2)
        runCurrent()
        assertEquals(listOf("lausa"), calls)
        assertIs<SearchState.Results>(states.last())
    }

    @Test
    fun shortQueriesDoNotSearch() = runTest {
        val queries = MutableStateFlow("")
        var calls = 0
        val states = mutableListOf<SearchState>()
        searchStates(queries) { calls++; emptyList() }.onEach { states += it }.launchIn(backgroundScope)
        queries.value = "la"
        advanceTimeBy(1000)
        runCurrent()
        assertEquals(0, calls)
        assertEquals(SearchState.Idle, states.last())
    }

    @Test
    fun staleRequestIsCancelled() = runTest {
        val queries = MutableStateFlow("")
        val completed = mutableListOf<String>()
        val states = mutableListOf<SearchState>()
        searchStates(queries) { q ->
            delay(if (q == "slow") 5000 else 10)
            completed += q
            listOf(result(q))
        }.onEach { states += it }.launchIn(backgroundScope)
        queries.value = "slow"
        advanceTimeBy(460)
        queries.value = "fast"
        advanceTimeBy(460)
        advanceTimeBy(6000)
        runCurrent()
        assertEquals(listOf("fast"), completed)
        val last = states.last()
        assertIs<SearchState.Results>(last)
        assertEquals("fast", last.items.single().name)
    }

    @Test
    fun errorsBecomeStateWithoutRetry() = runTest {
        val queries = MutableStateFlow("")
        var calls = 0
        val states = mutableListOf<SearchState>()
        searchStates(queries) { calls++; throw IOException("offline") }.onEach { states += it }.launchIn(backgroundScope)
        queries.value = "paris"
        advanceTimeBy(500)
        runCurrent()
        assertEquals(1, calls)
        assertEquals(SearchState.Error("offline"), states.last())
    }
}
