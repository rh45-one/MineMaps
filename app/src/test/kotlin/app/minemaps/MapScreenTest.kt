package app.minemaps

import android.location.Location
import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.navigation.model.MapWithContentTemplate
import androidx.car.app.testing.ScreenController
import androidx.car.app.testing.TestCarContext
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ApplicationProvider
import app.minemaps.car.CarMapActions
import app.minemaps.car.InvalidateScheduler
import app.minemaps.car.MapScreen
import app.minemaps.data.SavedPlace
import app.minemaps.engine.data.NetState
import app.minemaps.location.LocationState
import app.minemaps.location.LocationStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapScreenTest {
    private class FakeActions : CarMapActions {
        override val following = MutableStateFlow(true)
        override val networkState = MutableStateFlow(NetState.OK)
        override val streetNames = MutableStateFlow(true)
        var toggled = 0
        var shown: Pair<Double, Double>? = null
        var recentered = 0
        var flewTo: Pair<Double, Double>? = null

        override fun zoomIn() {}

        override fun zoomOut() {}

        override fun recenter() {
            recentered++
        }

        override fun flyTo(lat: Double, lon: Double) {
            flewTo = lat to lon
        }

        override fun toggleStreetNames() {
            toggled++
            streetNames.value = !streetNames.value
        }

        override fun showPlace(lat: Double, lon: Double) {
            shown = lat to lon
        }
    }

    private fun place(i: Int) = SavedPlace("id$i", "Place $i", 48.85 + i * 0.01, 2.29, i.toLong())

    private fun fix(): Location = Location("test").apply {
        latitude = 48.8584
        longitude = 2.2945
    }

    private class Fixture(
        val actions: FakeActions,
        val places: MutableStateFlow<List<SavedPlace>>,
        val location: MutableStateFlow<LocationState>,
        val screen: MapScreen,
    )

    private fun fixture(
        permission: Boolean = true,
        places: List<SavedPlace> = emptyList(),
        status: LocationState = LocationState(fix(), LocationStatus.OK),
        limit: Int = 6,
        scheduler: InvalidateScheduler = InvalidateScheduler { _, _ -> },
    ): Fixture {
        val ctx = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        val actions = FakeActions()
        val placesFlow = MutableStateFlow(places)
        val locFlow = MutableStateFlow(status)
        val screen = MapScreen(ctx, actions, placesFlow, locFlow, { permission }, {}, { limit }, scheduler)
        ScreenController(screen).moveToState(Lifecycle.State.STARTED)
        return Fixture(actions, placesFlow, locFlow, screen)
    }

    private fun content(f: Fixture) = (f.screen.onGetTemplate() as MapWithContentTemplate).contentTemplate

    @Test
    fun returnsMapWithContentTemplateAndFourActionsIncludingPan() {
        val f = fixture()
        val t = f.screen.onGetTemplate()
        assertIs<MapWithContentTemplate>(t)
        val actions = t.mapController!!.mapActionStrip!!.actions
        assertEquals(4, actions.size)
        assertTrue(actions.contains(Action.PAN))
        assertIs<ListTemplate>(t.contentTemplate)
    }

    @Test
    fun deniedPermissionShowsMessageTemplate() {
        val f = fixture(permission = false)
        val c = content(f)
        assertIs<MessageTemplate>(c)
        assertEquals("Location permission is needed to show your position", c.message.toString())
        assertEquals("Grant on phone", c.actions[0].title.toString())
    }

    @Test
    fun listIsCappedAtContentLimit() {
        val f = fixture(places = (1..20).map { place(it) }, limit = 6)
        val c = content(f)
        assertIs<ListTemplate>(c)
        assertEquals(6, c.singleList!!.items.size)
    }

    @Test
    fun statusRowCountsTowardTheLimitAndOfflineShowsFirst() {
        val f = fixture(places = (1..20).map { place(it) }, limit = 6)
        f.actions.networkState.value = NetState.OFFLINE
        val c = content(f)
        assertIs<ListTemplate>(c)
        val items = c.singleList!!.items
        assertEquals(6, items.size)
        assertEquals("Offline \u2013 showing cached map", (items[0] as androidx.car.app.model.Row).title.toString())
    }

    @Test
    fun placesAreSortedByDistanceAndEmptyListHasMessage() {
        val f = fixture(places = listOf(place(30), place(1), place(10)))
        val items = (content(f) as ListTemplate).singleList!!.items
        assertEquals(listOf("Place 1", "Place 10", "Place 30"), items.map { (it as androidx.car.app.model.Row).title.toString() })
        val empty = fixture()
        val list = (content(empty) as ListTemplate).singleList!!
        assertEquals(0, list.items.size)
        assertEquals("Save places on your phone to see them here", list.noItemsMessage.toString())
    }

    @Test
    fun searchingStatusRowAppearsWithoutFix() {
        val f = fixture(places = listOf(place(1)), status = LocationState(null, LocationStatus.SEARCHING))
        val items = (content(f) as ListTemplate).singleList!!.items
        assertEquals("Finding your location\u2026", (items[0] as androidx.car.app.model.Row).title.toString())
    }

    @Test
    fun burstOfStateChangesYieldsAtMostTwoInvalidations() {
        val pending = ArrayList<() -> Unit>()
        var scheduled = 0
        val f = fixture(scheduler = InvalidateScheduler { _, action -> scheduled++; pending.add(action) })
        val afterStart = scheduled
        for (i in 1..10) f.places.value = (1..i).map { place(it) }
        assertTrue(scheduled - afterStart <= 1, "scheduled ${scheduled - afterStart} invalidations for 10 changes")
        pending.toList().also { pending.clear() }.forEach { it() }
        for (i in 11..20) f.places.value = (1..i).map { place(it) }
        assertTrue(scheduled - afterStart <= 2)
    }

    @Test
    fun rootScreenIsANavigationTemplateWithoutRouteInformation() {
        val ctx = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        val actions = FakeActions()
        var places = 0
        val screen = app.minemaps.car.NavScreen(ctx, actions, MutableStateFlow(LocationState(fix(), LocationStatus.OK)), { true }, {}, { places++ }, InvalidateScheduler { _, _ -> })
        ScreenController(screen).moveToState(Lifecycle.State.STARTED)
        val t = screen.onGetTemplate()
        assertIs<androidx.car.app.navigation.model.NavigationTemplate>(t)
        assertEquals(null, t.navigationInfo)
        assertEquals(null, t.destinationTravelEstimate)
        val map = t.mapActionStrip!!.actions
        assertEquals(4, map.size)
        assertTrue(map.contains(Action.PAN))
        val strip = t.actionStrip!!.actions
        assertEquals(2, strip.size)
        strip[0].onClickDelegate!!.sendClick(object : androidx.car.app.OnDoneCallback {
            override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) {}

            override fun onFailure(response: androidx.car.app.serialization.Bundleable) {}
        })
        strip[1].onClickDelegate!!.sendClick(object : androidx.car.app.OnDoneCallback {
            override fun onSuccess(response: androidx.car.app.serialization.Bundleable?) {}

            override fun onFailure(response: androidx.car.app.serialization.Bundleable) {}
        })
        assertEquals(1, places)
        assertEquals(1, actions.toggled)
    }

    @Test
    fun rootScreenWithoutPermissionOffersGrantAndShowsAMessage() {
        val ctx = TestCarContext.createCarContext(ApplicationProvider.getApplicationContext())
        val screen = app.minemaps.car.NavScreen(ctx, FakeActions(), MutableStateFlow(LocationState(null, LocationStatus.SEARCHING)), { false }, {}, {}, InvalidateScheduler { _, _ -> })
        ScreenController(screen).moveToState(Lifecycle.State.STARTED)
        val t = screen.onGetTemplate() as androidx.car.app.navigation.model.NavigationTemplate
        assertEquals("Grant on phone", t.actionStrip!!.actions[1].title.toString())
        assertIs<androidx.car.app.navigation.model.MessageInfo>(t.navigationInfo)
    }
}
