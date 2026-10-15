package app.minemaps.search

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class SearchResult(
    val name: String,
    val address: String,
    val typeLabel: String,
    val lat: Double,
    val lon: Double,
    val type: String?,
    val osmValue: String?,
    val extent: DoubleArray?,
    val local: Boolean = false,
    val osmKey: String? = null,
) {
    val subtitle: String get() = listOf(address, typeLabel).filter { it.isNotBlank() }.joinToString(" \u00b7 ")

    fun targetZoom(): Double = when (type) {
        "country" -> 5.0
        "state" -> 7.0
        "county" -> 9.0
        "city" -> 12.0
        "district", "locality" -> 14.0
        "street" -> 17.0
        "house" -> 18.0
        else -> when (osmValue) {
            "city", "town" -> 12.0
            "village" -> 13.0
            else -> 18.0
        }
    }
}

object PhotonParser {
    private val TEXT_KEYS = setOf("name", "street", "district", "city", "state", "country")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, latinize: (String) -> String = { it }): List<SearchResult> {
        val root = json.parseToJsonElement(text).jsonObject
        val features = root["features"] as? JsonArray ?: return emptyList()
        return features.mapNotNull { runCatching { parseFeature(it.jsonObject, latinize) }.getOrNull() }
    }

    /** [minLon, maxLat, maxLon, minLat] with min/max sorted, or null when any value is not a valid coordinate. */
    private fun normalizeExtent(v: List<Double>): DoubleArray? {
        if (v.size != 4 || v.any { !it.isFinite() }) return null
        val lonA = v[0]
        val latA = v[1]
        val lonB = v[2]
        val latB = v[3]
        if (listOf(lonA, lonB).any { it < -180.0 || it > 180.0 } || listOf(latA, latB).any { it < -90.0 || it > 90.0 }) return null
        return doubleArrayOf(minOf(lonA, lonB), maxOf(latA, latB), maxOf(lonA, lonB), minOf(latA, latB))
    }

    private fun rawStr(o: JsonObject, key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun parseFeature(f: JsonObject, latinize: (String) -> String): SearchResult? {
        val coords = f["geometry"]?.jsonObject?.get("coordinates")?.jsonArray ?: return null
        val lon = coords[0].jsonPrimitive.doubleOrNull ?: return null
        val lat = coords[1].jsonPrimitive.doubleOrNull ?: return null
        if (!lon.isFinite() || !lat.isFinite() || lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) return null
        val p = f["properties"]?.jsonObject ?: return null
        fun str(o: JsonObject, key: String): String? = rawStr(o, key)?.let { if (key in TEXT_KEYS) latinize(it) else it }
        val street = str(p, "street")
        val house = str(p, "housenumber")
        val streetLine = if (street != null) listOfNotNull(street, house).joinToString(" ") else null
        val name = str(p, "name") ?: streetLine ?: return null
        val type = str(p, "type")
        val osmValue = str(p, "osm_value")
        val osmKey = str(p, "osm_key")
        val parts = listOfNotNull(
            streetLine?.takeIf { it != name },
            str(p, "district")?.takeIf { it != name },
            str(p, "city")?.takeIf { it != name },
            str(p, "state")?.takeIf { it != name },
            str(p, "country")?.takeIf { it != name },
        ).distinct()
        val extent = (p["extent"] as? JsonArray)?.let { a ->
            val v = a.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            normalizeExtent(v)
        }
        val typeLabel = (osmValue ?: type)?.replace('_', ' ')?.replaceFirstChar { it.uppercase() } ?: ""
        return SearchResult(name, parts.joinToString(", "), typeLabel, lat, lon, type, osmValue, extent, osmKey = osmKey)
    }
}

class PhotonClient(private val clientProvider: suspend () -> OkHttpClient, private val baseUrl: String, private val userAgent: String) {
    suspend fun search(query: String, biasLat: Double?, biasLon: Double?, lang: String?, latinize: (String) -> String = { it }, zoom: Double? = null): List<SearchResult> {
        val url = baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("api").addPathSegment("")
            .addQueryParameter("q", query.take(MAX_QUERY_CHARS))
            .addQueryParameter("limit", "8")
            .apply {
                if (biasLat != null && biasLon != null) {
                    addQueryParameter("lat", "%.5f".format(java.util.Locale.US, biasLat))
                    addQueryParameter("lon", "%.5f".format(java.util.Locale.US, biasLon))
                    addQueryParameter("location_bias_scale", "0.2")
                    if (zoom != null && zoom.isFinite()) addQueryParameter("zoom", Math.round(zoom).toInt().coerceIn(10, 16).toString())
                }
                if (lang in SUPPORTED_LANGS) addQueryParameter("lang", lang)
            }
            .build()
        val t0 = SystemClock.elapsedRealtime()
        val request = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val client = clientProvider().newBuilder().readTimeout(30, java.util.concurrent.TimeUnit.SECONDS).callTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build()
        val tStart = SystemClock.elapsedRealtime()
        var tHeaders = tStart
        val body = suspendCancellableCoroutine<String> { cont ->
            val call = client.newCall(request)
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    cont.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    tHeaders = SystemClock.elapsedRealtime()
                    response.use { r ->
                        if (!r.isSuccessful) {
                            cont.resumeWithException(IOException("Search failed (HTTP ${r.code})"))
                        } else {
                            try {
                                cont.resume(readLimited(r))
                            } catch (e: IOException) {
                                cont.resumeWithException(e)
                            }
                        }
                    }
                }
            })
        }
        val parsed = PhotonParser.parse(body, latinize)
        val tDone = SystemClock.elapsedRealtime()
        runCatching { Log.d(TAG, "photon qlen=${query.length} client=${tStart - t0}ms headers=${tHeaders - tStart}ms body+parse=${tDone - tHeaders}ms total=${tDone - t0}ms results=${parsed.size}") }
        return parsed
    }

    @Volatile
    private var lastPrewarm = 0L

    /** Opens a connection (DNS + TLS) ahead of the first search; at most once per minute, errors ignored. */
    suspend fun prewarm() {
        val now = SystemClock.elapsedRealtime()
        if (lastPrewarm != 0L && now - lastPrewarm < PREWARM_INTERVAL_MS) return
        lastPrewarm = now
        runCatching {
            val client = clientProvider().newBuilder().callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url(baseUrl.toHttpUrl()).head().header("User-Agent", userAgent).build()).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {}

                override fun onResponse(call: Call, response: Response) {
                    response.close()
                }
            })
        }
    }

    private fun readLimited(r: Response): String {
        if (r.body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Search response too large")
        val source = r.body.source()
        val buffer = okio.Buffer()
        while (buffer.size <= MAX_RESPONSE_BYTES) {
            if (source.read(buffer, 8192) < 0) return buffer.readUtf8()
        }
        throw IOException("Search response too large")
    }

    companion object {
        const val TAG = "MineMapsSearch"
        const val MAX_QUERY_CHARS = 200
        const val PREWARM_INTERVAL_MS = 60_000L
        const val MAX_RESPONSE_BYTES = 1L * 1024 * 1024
        private val SUPPORTED_LANGS = setOf("en", "de", "fr")
    }
}
