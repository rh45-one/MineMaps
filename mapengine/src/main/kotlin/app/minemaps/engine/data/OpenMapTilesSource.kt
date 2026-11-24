package app.minemaps.engine.data

import app.minemaps.engine.geo.TileKey
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OpenMapTilesSource(
    private val tileJsonUrl: String,
    private val client: OkHttpClient,
    private val userAgent: String,
    private val cache: DiskTileCache,
    private val tileJsonTtlMillis: Long = 24L * 3600 * 1000,
    maxConcurrent: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis,
) : TileSource {
    private class TileJson(val template: String, val minZoom: Int, val maxZoom: Int)
    private class TileJsonState(val tileJson: TileJson, val fetchedAt: Long, val retryAt: Long = 0L)

    private val tileClient: OkHttpClient by lazy { client.newBuilder().callTimeout(TILE_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS).build() }

    private val permits = Semaphore(maxConcurrent)
    private val tileJsonMutex = Mutex()
    private val tileJsonStateLock = Any()

    @Volatile
    private var tileJsonState: TileJsonState? = null

    override val reportsNetworkSuccess: Boolean = true

    @Volatile
    override var onNetworkSuccess: (() -> Unit)? = null

    override val maxZoom: Int get() = tileJsonState?.tileJson?.maxZoom ?: 14

    override suspend fun fetch(key: TileKey): ByteArray? {
        val tj = resolveTileJson()
        if (key.z < tj.minZoom || key.z > tj.maxZoom) return null
        val name = "t_${tj.template.hashCode().toUInt().toString(16)}_${key.z}_${key.x}_${key.y}"
        val cached = cache.get(name)
        if (cached != null && cache.isFresh(cached)) return cached.bytes.takeIf { it.isNotEmpty() }
        val url = tj.template.replace("{z}", key.z.toString()).replace("{x}", key.x.toString()).replace("{y}", key.y.toString())
        return try {
            val response = permits.withPermit { execute(url, MAX_TILE_BYTES, tileClient) }
            when (response.code) {
                200 -> {
                    onNetworkSuccess?.invoke()
                    response.body.also { cache.put(name, it) }
                }
                404, 204 -> {
                    onNetworkSuccess?.invoke()
                    cache.put(name, ByteArray(0))
                    null
                }
                else -> throw IOException("HTTP ${response.code} for $url")
            }
        } catch (e: IOException) {
            if (cached != null) cached.bytes.takeIf { it.isNotEmpty() } else throw e
        }
    }

    override fun cachedKeys(z: Int): List<TileKey> {
        val tj = tileJsonState?.tileJson ?: loadCachedTileJson() ?: return emptyList()
        val prefix = "t_${tj.template.hashCode().toUInt().toString(16)}_${z}_"
        return cache.names().mapNotNull { n ->
            if (!n.startsWith(prefix)) return@mapNotNull null
            val p = n.removePrefix(prefix).split('_')
            if (p.size == 2) TileKey(z, p[0].toIntOrNull() ?: return@mapNotNull null, p[1].toIntOrNull() ?: return@mapNotNull null) else null
        }
    }

    private fun loadCachedTileJson(): TileJson? {
        tileJsonState?.let { return it.tileJson }
        val cached = cache.get(TILEJSON_NAME) ?: return null
        val parsed = runCatching { parse(String(cached.bytes, Charsets.UTF_8)) }.getOrNull() ?: return null
        return synchronized(tileJsonStateLock) {
            tileJsonState?.let { return@synchronized it.tileJson }
            tileJsonState = TileJsonState(parsed, cached.fetchedAtMillis)
            parsed
        }
    }

    private fun publishState(tileJson: TileJson, fetchedAt: Long, retryAt: Long = 0L): TileJson {
        synchronized(tileJsonStateLock) {
            tileJsonState = TileJsonState(tileJson, fetchedAt, retryAt)
        }
        return tileJson
    }

    private class Result(val code: Int, val body: ByteArray)

    private suspend fun execute(url: String, maxBytes: Long, httpClient: OkHttpClient = client): Result = suspendCancellableCoroutine { cont: CancellableContinuation<Result> ->
        val call = httpClient.newCall(Request.Builder().url(url).header("User-Agent", userAgent).build())
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { r -> cont.resume(Result(r.code, readLimited(r, maxBytes))) }
                } catch (e: IOException) {
                    cont.resumeWithException(e)
                }
            }
        })
    }

    private fun readLimited(r: Response, maxBytes: Long): ByteArray {
        if (r.code != 200) return ByteArray(0)
        val declared = r.body.contentLength()
        if (declared > maxBytes) throw IOException("response too large ($declared bytes)")
        val source = r.body.source()
        val buffer = okio.Buffer()
        while (buffer.size <= maxBytes) {
            if (source.read(buffer, 8192) < 0) return buffer.readByteArray()
        }
        throw IOException("response too large (more than $maxBytes bytes)")
    }

    private suspend fun resolveTileJson(): TileJson {
        val now = clock()
        tileJsonState?.let { if (now - it.fetchedAt < tileJsonTtlMillis || now < it.retryAt) return it.tileJson }
        return tileJsonMutex.withLock {
            val t = clock()
            tileJsonState?.let { if (t - it.fetchedAt < tileJsonTtlMillis || t < it.retryAt) return@withLock it.tileJson }
            val cached = cache.get(TILEJSON_NAME)
            if (cached != null && cache.isFresh(cached, tileJsonTtlMillis)) {
                runCatching { parse(String(cached.bytes, Charsets.UTF_8)) }.getOrNull()?.let { parsed ->
                    return@withLock publishState(parsed, cached.fetchedAtMillis)
                }
            }
            try {
                val r = permits.withPermit { execute(tileJsonUrl, MAX_TILEJSON_BYTES) }
                if (r.code != 200) throw IOException("HTTP ${r.code} for $tileJsonUrl")
                val text = String(r.body, Charsets.UTF_8)
                val parsed = parse(text)
                publishState(parsed, clock())
                onNetworkSuccess?.invoke()
                cache.put(TILEJSON_NAME, r.body)
                parsed
            } catch (e: IOException) {
                if (cached != null) {
                    val stale = runCatching { parse(String(cached.bytes, Charsets.UTF_8)) }.getOrNull()
                    if (stale != null) {
                        return@withLock publishState(stale, cached.fetchedAtMillis, retryAt = clock() + TILEJSON_RETRY_COOLDOWN_MS)
                    }
                }
                throw e
            }
        }
    }

    private fun parse(text: String): TileJson = try {
        val o = Json.parseToJsonElement(text).jsonObject
        val template = o["tiles"]?.jsonArray?.firstOrNull()?.jsonPrimitive?.takeIf { it.isString }?.content
        if (template.isNullOrBlank()) throw IOException("invalid TileJSON")
        validateTemplate(template)
        val maxZoom = (o["maxzoom"]?.jsonPrimitive?.intOrNull ?: 14).coerceIn(0, MAX_ZOOM_LIMIT)
        val minZoom = (o["minzoom"]?.jsonPrimitive?.intOrNull ?: 0).coerceIn(0, maxZoom)
        TileJson(template, minZoom, maxZoom)
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("invalid TileJSON", e)
    }

    companion object {
        private const val TILEJSON_NAME = "tilejson"
        const val MAX_TILE_BYTES = 8L * 1024 * 1024
        const val MAX_TILEJSON_BYTES = 1L * 1024 * 1024
        const val TILE_CALL_TIMEOUT_SECONDS = 30L
        const val TILEJSON_RETRY_COOLDOWN_MS = 60_000L
        const val MAX_ZOOM_LIMIT = 16
        private val LOCAL_HOSTS = setOf("localhost", "127.0.0.1", "10.0.2.2", "::1", "[::1]")

        /** https only (http for loopback/emulator-host dev servers) and must contain {z}, {x} and {y}. */
        fun validateTemplate(template: String) {
            if (!template.contains("{z}") || !template.contains("{x}") || !template.contains("{y}")) throw IOException("invalid TileJSON")
            val probe = template.replace("{z}", "0").replace("{x}", "0").replace("{y}", "0").toHttpUrlOrNull()
                ?: throw IOException("invalid TileJSON")
            val ok = probe.scheme == "https" || (probe.scheme == "http" && probe.host in LOCAL_HOSTS)
            if (!ok) throw IOException("invalid TileJSON")
        }
    }
}
