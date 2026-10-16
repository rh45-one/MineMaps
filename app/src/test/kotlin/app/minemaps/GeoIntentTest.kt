package app.minemaps

import app.minemaps.car.GeoIntent
import app.minemaps.car.GeoTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeoIntentTest {
    @Test
    fun plainCoordinates() {
        assertEquals(GeoTarget.Place(48.8584, 2.2945, null), GeoIntent.parse("geo:48.8584,2.2945"))
        assertEquals(GeoTarget.Place(-33.86, 151.2, null), GeoIntent.parse("GEO:-33.86,151.2;u=35"))
        assertEquals(GeoTarget.Place(48.0, 2.0, null), GeoIntent.parse("geo:48,2?z=14"))
    }

    @Test
    fun queryWithCoordinatesAndLabel() {
        assertEquals(GeoTarget.Place(48.8584, 2.2945, "Eiffel Tower"), GeoIntent.parse("geo:0,0?q=48.8584,2.2945(Eiffel%20Tower)"))
        assertEquals(GeoTarget.Place(48.8584, 2.2945, null), GeoIntent.parse("geo:0,0?q=48.8584,2.2945"))
        assertEquals(GeoTarget.Place(1.0, 2.0, "A+B"), GeoIntent.parse("geo:0,0?q=1,2(A%2BB)"))
    }

    @Test
    fun textQuery() {
        assertEquals(GeoTarget.Query("pizza near me"), GeoIntent.parse("geo:0,0?q=pizza%20near%20me"))
        assertEquals(GeoTarget.Query("caf\u00e9 paris"), GeoIntent.parse("geo:48.8,2.3?q=caf%C3%A9+paris&z=12"))
    }

    @Test
    fun malformedAndOutOfRangeInputsAreRejected() {
        for (bad in listOf(
            null, "", "geo:", "geo:abc", "geo:1", "geo:1,x", "geo:91,0", "geo:0,181", "geo:NaN,1", "geo:Infinity,1", "geo:0,0", "geo:0,0?q=", "geo:0,0?q=%00%01",
            "http://example.com", "geo:0,0?q=999,999(x)", "geo:0,0?z=3",
        )) assertNull(GeoIntent.parse(bad), "should reject: $bad")
    }

    @Test
    fun hostileValuesAreSanitisedAndBounded() {
        assertEquals(GeoTarget.Place(1.0, 2.0, null), GeoIntent.parse("geo:1,2,3?q=%"))
        val long = GeoIntent.parse("geo:0,0?q=" + "a".repeat(5000))
        assertNull(long)
        val capped = GeoIntent.parse("geo:0,0?q=" + "a".repeat(300)) as GeoTarget.Query
        assertEquals(200, capped.text.length)
        val ctrl = GeoIntent.parse("geo:0,0?q=ab%0Acd%07ef") as GeoTarget.Query
        assertEquals("abcdef", ctrl.text)
        val label = GeoIntent.parse("geo:1,2?q=1,2(%0A" + "x".repeat(200) + ")") as GeoTarget.Place
        assertTrue(label.label!!.length <= 80 && '\n' !in label.label!!)
        assertNull((GeoIntent.parse("geo:1,2?q=1,2(%00)") as GeoTarget.Place).label)
        // never throws on junk
        val rnd = java.util.Random(7)
        repeat(2000) { GeoIntent.parse("geo:" + String(CharArray(rnd.nextInt(40)) { (rnd.nextInt(95) + 32).toChar() })) }
    }

    @Test
    fun parsesSharedGoogleMapsTextAndUrls() {
        assertEquals(
            GeoTarget.Place(41.8902, 12.4922, "Colosseum"),
            GeoIntent.parseSharedText("https://www.google.com/maps/place/Colosseum/@41.8902,12.4922,17z/data=!3m1!4b1"),
        )
        assertEquals(
            GeoTarget.Place(41.89021, 12.49223, "Roman Forum"),
            GeoIntent.parseSharedText("Check this out: https://www.google.com/maps/place/Roman+Forum/@41.89,12.49,15z/data=!3d41.89021!4d12.49223"),
        )
        assertEquals(
            GeoTarget.Query("Colosseum, Piazza del Colosseo, Rome"),
            GeoIntent.parseSharedText("Colosseum\nPiazza del Colosseo, Rome\nhttps://maps.app.goo.gl/xyz123"),
        )
        assertEquals(
            GeoTarget.Place(51.5007, -0.1246, null),
            GeoIntent.parseSharedText("https://maps.google.com/?q=51.5007,-0.1246"),
        )
    }

    @Test
    fun parsesGoogleMapsMirrorNotificationsAndPlacesJson() {
        val m1 = app.minemaps.nav.GoogleMapsMirror.parseFields("250 m", "Turn right onto Via dei Fori Imperiali", "12 min \u00b7 4.2 km")!!
        assertEquals("250 m", m1.distanceText)
        assertEquals("Turn right onto Via dei Fori Imperiali", m1.instruction)
        assertEquals("12 min \u00b7 4.2 km", m1.etaText)
        assertEquals(app.minemaps.engine.routing.TurnDirection.RIGHT, m1.direction)

        val m2 = app.minemaps.nav.GoogleMapsMirror.parseFields("Keep left", "1.2 km \u00b7 Corso Vittorio", "5 min")!!
        assertEquals("1.2 km", m2.distanceText)
        assertEquals(app.minemaps.engine.routing.TurnDirection.SLIGHT_LEFT, m2.direction)
        assertNull(app.minemaps.nav.GoogleMapsMirror.parseFields("", "", ""))
        assertNull(app.minemaps.nav.GoogleMapsMirror.parseFields("Driving with Google Maps", "", ""))
        assertNull(app.minemaps.nav.GoogleMapsMirror.parseFields("Google Maps", "Driving with Google Maps", "Android Auto"))

        val placesJson = """
            {
              "places": [
                {
                  "displayName": { "text": "Antico Caff\u00e8 Greco" },
                  "formattedAddress": "Via dei Condotti, 86, Roma",
                  "location": { "latitude": 41.9059, "longitude": 12.4809 },
                  "primaryTypeDisplayName": { "text": "Coffee shop" }
                }
              ]
            }
        """.trimIndent()
        val parsed = app.minemaps.search.GooglePlacesParser.parse(placesJson)
        assertEquals(1, parsed.size)
        assertEquals("Antico Caff\u00e8 Greco", parsed[0].name)
        assertEquals("Via dei Condotti, 86, Roma", parsed[0].address)
        assertEquals("Coffee shop \u00b7 Google", parsed[0].typeLabel)
        assertEquals(41.9059, parsed[0].lat, 1e-4)
        assertEquals(12.4809, parsed[0].lon, 1e-4)
    }
}

class NavigateHandlerTest {
    private class Actions : app.minemaps.car.CarMapActions {
        override val following = kotlinx.coroutines.flow.MutableStateFlow(true)
        override val networkState = kotlinx.coroutines.flow.MutableStateFlow(app.minemaps.engine.data.NetState.OK)
        override val streetNames = kotlinx.coroutines.flow.MutableStateFlow(true)
        var shown: Pair<Double, Double>? = null

        override fun zoomIn() {}

        override fun zoomOut() {}

        override fun recenter() {}

        override fun flyTo(lat: Double, lon: Double) {}

        override fun toggleStreetNames() {}

        override fun showPlace(lat: Double, lon: Double, title: String) {
            shown = lat to lon
        }
    }

    private fun result(name: String) = app.minemaps.search.SearchResult(name, "", "", 1.0, 2.0, null, null, null)

    @Test
    fun coordinatesShowThePlaceWithTheNoDirectionsToast() {
        val a = Actions()
        val toasts = mutableListOf<String>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        app.minemaps.car.NavigateHandler(scope, a, { emptyList() }, { toasts += it }, {}).handle("geo:0,0?q=48.86,2.29(Tower)")
        assertEquals(48.86 to 2.29, a.shown)
        assertEquals(listOf(app.minemaps.car.SearchResultsScreen.NO_DIRECTIONS), toasts)
    }

    @Test
    fun textQuerySearchesAndShowsResults_andFailuresToast() {
        val a = Actions()
        var shown: List<app.minemaps.search.SearchResult>? = null
        val toasts = mutableListOf<String>()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        var asked: String? = null
        app.minemaps.car.NavigateHandler(scope, a, { asked = it; listOf(result("A"), result("B")) }, { toasts += it }, { shown = it }).handle("geo:0,0?q=pizza")
        assertEquals("pizza", asked)
        assertEquals(listOf("A", "B"), shown!!.map { it.name })
        app.minemaps.car.NavigateHandler(scope, a, { throw java.io.IOException("x") }, { toasts += it }, { shown = null }).handle("geo:0,0?q=pizza")
        assertEquals(listOf("A", "B"), shown!!.map { it.name })
        assertEquals(listOf("Search is unavailable"), toasts)
    }

    @Test
    fun hostileDataDoesNothing() {
        val a = Actions()
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined)
        for (bad in listOf(null, "", "geo:999,999", "javascript:alert(1)", "geo:0,0")) app.minemaps.car.NavigateHandler(scope, a, { error("no search") }, { error("no toast") }, { error("no results") }).handle(bad)
        assertEquals(null, a.shown)
    }
}
