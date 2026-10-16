package app.minemaps.car

import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.Screen
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.MapController
import androidx.car.app.navigation.model.MapWithContentTemplate
import app.minemaps.search.SearchResult

/** Results of a navigation request with a text query (`geo:0,0?q=...`); picking one shows it on the map. */
class SearchResultsScreen(
    carContext: CarContext,
    private val results: List<SearchResult>,
    private val actions: CarMapActions,
) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val limit = carContext.getCarService(ConstraintManager::class.java).getContentLimit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
        val list = ItemList.Builder()
        for (r in results.take(limit)) {
            val row = Row.Builder().setTitle(r.name)
            if (r.subtitle.isNotBlank()) row.addText(r.subtitle)
            row.setOnClickListener {
                actions.showPlace(r.lat, r.lon, r.name)
                CarToast.makeText(carContext, NO_DIRECTIONS, CarToast.LENGTH_LONG).show()
                screenManager.pop()
            }
            list.addItem(row.build())
        }
        list.setNoItemsMessage("No results")
        return MapWithContentTemplate.Builder()
            .setMapController(MapController.Builder().setMapActionStrip(CarStrips.mapActionStrip(carContext, actions, actions.following.value)).build())
            .setContentTemplate(ListTemplate.Builder().setSingleList(list.build()).setTitle("Results").setHeaderAction(Action.BACK).build())
            .build()
    }

    companion object {
        const val NO_DIRECTIONS = "MineMaps shows the place but doesn't give directions"
    }
}
