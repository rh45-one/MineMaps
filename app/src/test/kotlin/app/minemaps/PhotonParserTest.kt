package app.minemaps

import app.minemaps.search.PhotonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class PhotonParserTest {
    private val sample = """
    {"type":"FeatureCollection","features":[
     {"type":"Feature","geometry":{"type":"Point","coordinates":[6.6327025,46.5218269]},
      "properties":{"osm_type":"R","osm_id":1,"extent":[6.5876,46.5453,6.7230,46.4930],"country":"Switzerland","osm_key":"place","city":"Lausanne","countrycode":"CH","osm_value":"city","name":"Lausanne","state":"Vaud","type":"city"}},
     {"type":"Feature","geometry":{"type":"Point","coordinates":[2.2945,48.8584]},
      "properties":{"osm_key":"tourism","osm_value":"attraction","name":"Eiffel Tower","street":"Avenue Anatole France","housenumber":"5","district":"Gros-Caillou","city":"Paris","state":"Ile-de-France","country":"France","type":"house"}},
     {"type":"Feature","geometry":{"type":"Point","coordinates":[8.5,47.4]},
      "properties":{"osm_key":"building","osm_value":"yes","street":"Bahnhofstrasse","housenumber":"1","city":"Zurich","country":"Switzerland","type":"house"}},
     {"type":"Feature","geometry":{"type":"Point"},"properties":{"name":"broken"}}
    ]}
    """.trimIndent()

    @Test
    fun parsesResults() {
        val r = PhotonParser.parse(sample)
        assertEquals(3, r.size)
        val city = r[0]
        assertEquals("Lausanne", city.name)
        assertEquals(46.5218269, city.lat, 1e-9)
        assertEquals(6.6327025, city.lon, 1e-9)
        assertEquals("Vaud, Switzerland", city.address)
        assertEquals("City", city.typeLabel)
        assertEquals("Vaud, Switzerland \u00b7 City", city.subtitle)
        assertEquals(12.0, city.targetZoom())
        assertEquals(listOf(6.5876, 46.5453, 6.7230, 46.4930), assertNotNull(city.extent).toList())

        val eiffel = r[1]
        assertEquals("Avenue Anatole France 5, Gros-Caillou, Paris, Ile-de-France, France", eiffel.address)
        assertNull(eiffel.extent)
        assertEquals(18.0, eiffel.targetZoom())
        assertEquals("Bahnhofstrasse 1", r[2].name)
    }

    @Test
    fun zoomByType() {
        fun z(type: String) = PhotonParser.parse(
            """{"features":[{"geometry":{"coordinates":[1,2]},"properties":{"name":"x","type":"$type"}}]}""",
        ).single().targetZoom()
        assertEquals(5.0, z("country"))
        assertEquals(7.0, z("state"))
        assertEquals(14.0, z("district"))
        assertEquals(14.0, z("locality"))
        assertEquals(17.0, z("street"))
        assertEquals(18.0, z("other"))
    }

    @Test
    fun emptyAndGarbage() {
        assertEquals(0, PhotonParser.parse("""{"features":[]}""").size)
        assertEquals(0, PhotonParser.parse("""{}""").size)
    }
}
