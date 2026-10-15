package app.minemaps.engine.data

import app.minemaps.engine.geo.TileKey
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenMapTilesSource(
    private val tileJsonUrl: String,
    private val client: OkHttpClient,
    private val userAgent: String,
    private val cache: DiskTileCache,
    private val tileJsonTtlMillis: Long = 24L * 3600 * 1000,
    maxConcurrent: Int = 4,
) : TileSource {
    private class TileJson(val template: String, val minZoom: Int, val maxZoom: Int)

    private val permits = Semaphore(maxConcurrent)
    private val tileJsonMutex = Mutex()

    @Volatile
    private var tileJson: TileJson? = null

    @Volatile
    private var tileJsonFetchedAt = 0L

    override val maxZoom: Int get() = tileJson?.maxZoom ?: 14

    override suspend fun fetch(key: TileKey): ByteArray? {
        val tj = resolveTileJson()
        if (key.z < tj.minZoom || key.z > tj.maxZoom) return null
        val name = "t_${tj.template.hashCode().toUInt().toString(16)}_${key.z}_${key.x}_${key.y}"
        val cached = cache.get(name)
        if (cached != null && cache.isFresh(cached)) return cached.bytes.takeIf { it.isNotEmpty() }
        val url = tj.template.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())
        return try {
            val response = permits.withPermit { execute(url) }
            when (response.code) {
                200 -> response.body.also { cache.put(name, it) }
                404, 204 -> {
                    cache.put(name, ByteArray(0))
                    null
                }
                else -> throw IOException("HTTP ${response.code} for $url")
            }
        } catch (e: IOException) {
            if (cached != null) cached.bytes.takeIf { it.isNotEmpty() } else throw e
        }
    }

    private class Result(val code: Int, val body: ByteArray)

    private suspend fun execute(url: String): Result = suspendCancellableCoroutine { cont: CancellableContinuation<Result> ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r -> cont.resume(Result(r.code, r.body.bytes())) }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                }
            }
        })
    }

    private suspend fun resolveTileJson(): TileJson {
        tileJson?.let { if (System.currentTimeMillis() - tileJsonFetchedAt < tileJsonTtlMillis) return it }
        return tileJsonMutex.withLock {
            tileJson?.let { if (System.currentTimeMillis() - tileJsonFetchedAt < tileJsonTtlMillis) return it }
            val cached = cache.get(TILEJSON_NAME)
            if (cached != null && cache.isFresh(cached, tileJsonTtlMillis)) {
                return parse(String(cached.bytes, Charsets.UTF_8), cached.fetchedAtMillis)
            }
            try {
                val r = permits.withPermit { execute(tileJsonUrl) }
                if (r.code != 200) throw IOException("HTTP ${r.code} for $tileJsonUrl")
                val text = String(r.body, Charsets.UTF_8)
                val parsed = parse(text, System.currentTimeMillis())
                cache.put(TILEJSON_NAME, r.body)
                parsed
            } catch (e: IOException) {
                if (cached != null) parse(String(cached.bytes, Charsets.UTF_8), cached.fetchedAtMillis) else throw e
            }
        }
    }

    private fun parse(text: String, fetchedAt: Long): TileJson {
        val o = Json.parseToJsonElement(text).jsonObject
        val template = o["tiles"]!!.jsonArray[0].jsonPrimitive.content
        val tj = TileJson(
            template,
            o["minzoom"]?.jsonPrimitive?.int ?: 0,
            o["maxzoom"]?.jsonPrimitive?.int ?: 14,
        )
        tileJson = tj
        tileJsonFetchedAt = fetchedAt
        return tj
    }

    private companion object {
        const val TILEJSON_NAME = "tilejson"
    }
}
