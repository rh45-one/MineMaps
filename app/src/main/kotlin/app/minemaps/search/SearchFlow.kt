package app.minemaps.search

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

sealed interface SearchState {
    data object Idle : SearchState
    data object Loading : SearchState
    data class Results(val items: List<SearchResult>) : SearchState
    data class Error(val message: String) : SearchState
}

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
fun searchStates(
    queries: Flow<String>,
    debounceMs: Long = 450,
    minChars: Int = 3,
    search: suspend (String) -> List<SearchResult>,
): Flow<SearchState> = queries
    .map { it.trim() }
    .debounce(debounceMs)
    .distinctUntilChanged()
    .flatMapLatest { q ->
        if (q.length < minChars) {
            flowOf<SearchState>(SearchState.Idle)
        } else {
            flow<SearchState> {
                emit(SearchState.Loading)
                emit(
                    try {
                        SearchState.Results(search(q))
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SearchState.Error(e.message?.takeIf { it.isNotBlank() } ?: "Search failed")
                    },
                )
            }
        }
    }
