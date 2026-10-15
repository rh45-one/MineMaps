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

    private fun feature(coords: String, extent: String? = null) =
        """{"features":[{"geometry":{"coordinates":$coords},"properties":{"name":"x"${if (extent != null) ",\"extent\":$extent" else ""}}}]}"""

    @Test
    fun invalidCoordinatesAreDropped() {
        assertEquals(0, PhotonParser.parse(feature("[1e999, 2]")).size)
        assertEquals(0, PhotonParser.parse(feature("[2, -1e999]")).size)
        assertEquals(0, PhotonParser.parse(feature("[2, 91]")).size)
        assertEquals(0, PhotonParser.parse(feature("[2, -90.5]")).size)
        assertEquals(0, PhotonParser.parse(feature("[180.5, 10]")).size)
        assertEquals(0, PhotonParser.parse(feature("[-181, 10]")).size)
        assertEquals(0, PhotonParser.parse(feature("[\"a\", 10]")).size)
        assertEquals(1, PhotonParser.parse(feature("[180, 90]")).size)
        assertEquals(1, PhotonParser.parse(feature("[-180, -90]")).size)
    }

    @Test
    fun extentsAreValidatedAndNormalised() {
        val swapped = PhotonParser.parse(feature("[1, 2]", "[10.0, 20.0, 5.0, 30.0]")).single().extent!!
        assertEquals(listOf(5.0, 30.0, 10.0, 20.0), swapped.toList())
        assertNull(PhotonParser.parse(feature("[1, 2]", "[1e999, 20.0, 5.0, 30.0]")).single().extent)
        assertNull(PhotonParser.parse(feature("[1, 2]", "[10.0, 120.0, 5.0, 30.0]")).single().extent)
        assertNull(PhotonParser.parse(feature("[1, 2]", "[10.0, 20.0, 500.0, 30.0]")).single().extent)
        assertNull(PhotonParser.parse(feature("[1, 2]", "[10.0, 20.0]")).single().extent)
    }

    private fun clientReturning(onRequest: (okhttp3.Request) -> okhttp3.Response) =
        okhttp3.OkHttpClient.Builder().addInterceptor { onRequest(it.request()) }.build()

    private fun ok(req: okhttp3.Request, body: String) = okhttp3.Response.Builder().request(req).protocol(okhttp3.Protocol.HTTP_1_1)
        .code(200).message("ok").body(okhttp3.ResponseBody.create(null, body.toByteArray())).build()

    @Test
    fun queryIsCappedAndOversizedResponsesRejected() = kotlinx.coroutines.runBlocking<Unit> {
        var seenUrl: okhttp3.HttpUrl? = null
        val client = clientReturning { req -> seenUrl = req.url; ok(req, """{"features":[]}""") }
        app.minemaps.search.PhotonClient({ client }, "https://photon.test", "ua").search("q".repeat(5000), 1.0, 2.0, "en")
        assertEquals(app.minemaps.search.PhotonClient.MAX_QUERY_CHARS, seenUrl!!.queryParameter("q")!!.length)
        assertEquals("en", seenUrl!!.queryParameter("lang"))
        val big = clientReturning { req -> ok(req, """{"features":[],"pad":"${"x".repeat(app.minemaps.search.PhotonClient.MAX_RESPONSE_BYTES.toInt() + 100)}"}""") }
        kotlin.test.assertFailsWith<java.io.IOException> {
            app.minemaps.search.PhotonClient({ big }, "https://photon.test", "ua").search("paris", null, null, null)
        }
        Unit
    }
}
