package app.minemaps.car

import app.minemaps.search.SearchResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Handles `androidx.car.app.action.NAVIGATE` data: shows a place on the map, or searches and offers the results. */
class NavigateHandler(
    private val scope: CoroutineScope,
    private val actions: CarMapActions,
    private val search: suspend (String) -> List<SearchResult>,
    private val toast: (String) -> Unit,
    private val showResults: (List<SearchResult>) -> Unit,
) {
    fun handle(data: String?) {
        val target = GeoIntent.parse(data)
        android.util.Log.d("MineMapsCar", "navigate intent: " + (target?.let { it::class.simpleName } ?: "unparseable"))
        when (target) {
            is GeoTarget.Place -> {
                actions.showPlace(target.lat, target.lon, target.label ?: "Destination")
                toast(SearchResultsScreen.NO_DIRECTIONS)
            }
            is GeoTarget.Query -> scope.launch {
                val results = try {
                    search(target.text)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    toast("Search is unavailable")
                    return@launch
                }
                showResults(results)
            }
            null -> {}
        }
    }
}
