package app.minemaps.engine

import app.minemaps.engine.data.DiskTileCache
import app.minemaps.engine.data.OpenMapTilesSource
import app.minemaps.engine.geo.TileKey
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SourceHardeningTest {
    private val tileJsonUrl = "https://tiles.test/planet"
    private val goodTileJson = """{"tiles":["https://tiles.test/v1/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14}"""

    private class Server(var tileJson: () -> Response?, var tile: () -> Response?)

    private fun resp(req: Request, code: Int, body: ByteArray) =
        Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(code).message("m").body(body.toResponseBody(null)).build()

    private fun fixture(
        tileJsonBody: String? = goodTileJson,
        tileCode: Int = 200,
        tileBody: ByteArray = byteArrayOf(1, 2, 3),
        now: () -> Long = { 1_000L },
        ttl: Long = 30L * 24 * 3600 * 1000,
    ): Triple<OpenMapTilesSource, DiskTileCache, MutableMap<String, Pair<Int, ByteArray>>> {
        val responses = mutableMapOf<String, Pair<Int, ByteArray>>()
        responses["tj"] = 200 to (tileJsonBody ?: "").toByteArray()
        responses["tile"] = tileCode to tileBody
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val key = if (req.url.toString() == tileJsonUrl) "tj" else "tile"
            val (code, body) = responses.getValue(key)
            resp(req, code, body)
        }).build()
        val dir = Files.createTempDirectory("src").toFile()
        val cache = DiskTileCache(dir, ttlMillis = ttl, clock = now)
        return Triple(OpenMapTilesSource(tileJsonUrl, client, "test-agent", cache), cache, responses)
    }

    private fun fetchFails(source: OpenMapTilesSource, key: TileKey = TileKey(14, 1, 1)) {
        assertFailsWith<IOException> { runBlocking { source.fetch(key) } }
    }

    @Test
    fun invalidTileJsonIsIoExceptionAndNeverCached() {
        for (body in listOf(
            "not json", "[]", "{}", """{"tiles":[]}""", """{"tiles":[""]}""", """{"tiles":[5]}""", """{"tiles":"x"}""",
            """{"tiles":["ftp://tiles.test/{z}/{x}/{y}"]}""",
            """{"tiles":["javascript:alert({z}{x}{y})"]}""",
            """{"tiles":["http://evil.example/{z}/{x}/{y}"]}""",
            """{"tiles":["https://tiles.test/{z}/{x}"]}""",
            """{"tiles":["https://tiles.test/z/x/y"]}""",
        )) {
            val (source, cache, _) = fixture(tileJsonBody = body)
            fetchFails(source)
            assertNull(cache.get("tilejson"), "invalid TileJSON must not be cached: $body")
        }
    }

    @Test
    fun loopbackHttpIsAllowedForDevServers() {
        for (host in listOf("10.0.2.2:8080", "localhost:8080", "127.0.0.1")) {
            val (source, _, _) = fixture(tileJsonBody = """{"tiles":["http://$host/{z}/{x}/{y}.pbf"]}""")
            assertContentEquals(byteArrayOf(1, 2, 3), runBlocking { source.fetch(TileKey(14, 1, 1)) })
        }
    }

    @Test
    fun zoomsAreClamped() {
        val (source, _, _) = fixture(tileJsonBody = """{"tiles":["https://tiles.test/{z}/{x}/{y}.pbf"],"minzoom":-5,"maxzoom":99}""")
        runBlocking { source.fetch(TileKey(16, 1, 1)) }
        assertEquals(16, source.maxZoom)
        val (s2, _, _) = fixture(tileJsonBody = """{"tiles":["https://tiles.test/{z}/{x}/{y}.pbf"],"minzoom":9,"maxzoom":3}""")
        runBlocking { s2.fetch(TileKey(3, 0, 0)) }
        assertEquals(3, s2.maxZoom)
    }

    @Test
    fun oversizedBodiesAreRejected() {
        val (source, _, _) = fixture(tileBody = ByteArray(OpenMapTilesSource.MAX_TILE_BYTES.toInt() + 10))
        fetchFails(source)
        val big = """{"tiles":["https://tiles.test/{z}/{x}/{y}.pbf"],"pad":"${"x".repeat(OpenMapTilesSource.MAX_TILEJSON_BYTES.toInt() + 10)}"}"""
        val (s2, cache, _) = fixture(tileJsonBody = big)
        fetchFails(s2)
        assertNull(cache.get("tilejson"))
        val (ok, _, _) = fixture(tileBody = ByteArray(OpenMapTilesSource.MAX_TILE_BYTES.toInt() - 10))
        assertEquals(OpenMapTilesSource.MAX_TILE_BYTES.toInt() - 10, runBlocking { ok.fetch(TileKey(14, 1, 1)) }!!.size)
    }

    @Test
    fun serverErrorsAreIoExceptionsAndStaleTilesAreServed() {
        var now = 1_000L
        val (source, _, responses) = fixture(now = { now }, ttl = 100)
        val key = TileKey(14, 3, 4)
        assertContentEquals(byteArrayOf(1, 2, 3), runBlocking { source.fetch(key) })
        now += 500
        for (code in listOf(500, 429, 503)) {
            responses["tile"] = code to ByteArray(0)
            assertContentEquals(byteArrayOf(1, 2, 3), runBlocking { source.fetch(key) }, "stale served on HTTP $code")
        }
        val other = TileKey(14, 9, 9)
        responses["tile"] = 500 to ByteArray(0)
        fetchFails(source, other)
        responses["tile"] = 429 to ByteArray(0)
        fetchFails(source, other)
    }

    @Test
    fun notFoundIsAnEmptyTile() {
        val (source, _, _) = fixture(tileCode = 404)
        assertNull(runBlocking { source.fetch(TileKey(14, 1, 1)) })
    }

    @Test
    fun cacheWriteFailureDoesNotFailTheDownload() {
        val dir = Files.createTempDirectory("ro").toFile()
        val cache = DiskTileCache(dir)
        val responses = goodTileJson
        val client = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            resp(req, 200, if (req.url.toString() == tileJsonUrl) responses.toByteArray() else byteArrayOf(7, 7))
        }).build()
        val source = OpenMapTilesSource(tileJsonUrl, client, "ua", cache)
        dir.deleteRecursively()
        dir.writeText("a plain file where the cache directory should be")
        assertContentEquals(byteArrayOf(7, 7), runBlocking { source.fetch(TileKey(14, 1, 1)) })
        dir.delete()
    }

    @Test
    fun putIntoReadOnlyDirDoesNotThrowAndLeavesNoTempFiles() {
        val dir = Files.createTempDirectory("ro").toFile()
        val cache = DiskTileCache(dir)
        assertTrue(dir.setWritable(false))
        try {
            if (!dir.canWrite()) {
                cache.put("a", ByteArray(100))
                assertNull(cache.get("a"))
                assertEquals(0, dir.listFiles()!!.size)
            }
        } finally {
            dir.setWritable(true)
        }
        cache.put("a", ByteArray(100))
        assertNotNull(cache.get("a"))
    }

    @Test
    fun truncatedOrCorruptFilesAreMissesAndDeleted() {
        val dir = Files.createTempDirectory("cc").toFile()
        val cache = DiskTileCache(dir)
        cache.put("t", ByteArray(500) { it.toByte() })
        val f = dir.listFiles { x -> x.name.endsWith(".tile") }!!.single()
        RandomAccessFile(f, "rw").use { it.setLength(f.length() / 2) }
        assertNull(cache.get("t"))
        assertTrue(!f.exists())
        cache.put("short", ByteArray(10))
        val g = dir.listFiles { x -> x.name.endsWith(".tile") }!!.single()
        g.writeBytes(byteArrayOf(1, 2, 3))
        assertNull(cache.get("short"))
        assertTrue(!g.exists())
        cache.put("t", byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), cache.get("t")!!.bytes)
        File(dir, "x.tile").writeBytes(ByteArray(0))
    }
}
