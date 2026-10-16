package app.minemaps.search

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

object GooglePlacesParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String, latinize: (String) -> String = { it }): List<SearchResult> = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val places = root["places"] as? JsonArray ?: return emptyList()
        places.mapNotNull { el -> runCatching { parsePlace(el.jsonObject, latinize) }.getOrNull() }
    }.getOrDefault(emptyList())

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    private fun parsePlace(p: JsonObject, latinize: (String) -> String): SearchResult? {
        val loc = p["location"]?.jsonObject ?: return null
        val lat = loc["latitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        val lon = loc["longitude"]?.jsonPrimitive?.doubleOrNull ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val rawName = p["displayName"]?.jsonObject?.let { str(it, "text") } ?: return null
        val name = latinize(rawName)
        val addr = str(p, "formattedAddress")?.let(latinize).orEmpty()
        val typeName = (p["primaryTypeDisplayName"]?.jsonObject?.let { str(it, "text") } ?: str(p, "primaryType"))
            ?.replace('_', ' ')
            ?.replaceFirstChar { it.uppercase() }
            ?.let(latinize)
            .orEmpty()
        val label = if (typeName.isNotBlank()) "$typeName \u00b7 Google" else "Google Maps"
        return SearchResult(name, addr, label, lat, lon, type = "house", osmValue = null, extent = null, landmark = true)
    }
}

class GooglePlacesClient(
    private val clientProvider: suspend () -> OkHttpClient,
    private val userAgent: String,
    private val url: String = DEFAULT_URL,
) {
    suspend fun search(
        query: String,
        apiKey: String,
        biasLat: Double?,
        biasLon: Double?,
        lang: String?,
        latinize: (String) -> String = { it },
    ): List<SearchResult> = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            put("textQuery", query.take(PhotonClient.MAX_QUERY_CHARS))
            put("maxResultCount", 8)
            if (!lang.isNullOrBlank()) put("languageCode", lang)
            if (biasLat != null && biasLon != null && biasLat.isFinite() && biasLon.isFinite()) {
                putJsonObject("locationBias") {
                    putJsonObject("circle") {
                        putJsonObject("center") {
                            put("latitude", biasLat.coerceIn(-90.0, 90.0))
                            put("longitude", biasLon.coerceIn(-180.0, 180.0))
                        }
                        put("radius", 15000.0)
                    }
                }
            }
        }.toString()
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("X-Goog-Api-Key", apiKey.trim())
            .header("X-Goog-FieldMask", "places.displayName,places.formattedAddress,places.location,places.primaryType,places.primaryTypeDisplayName")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val http = clientProvider().newBuilder().callTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Google Places failed (HTTP ${r.code})")
            val src = r.body.source()
            val buf = okio.Buffer()
            while (buf.size <= PhotonClient.MAX_RESPONSE_BYTES) {
                if (src.read(buf, 8192) < 0) return@use GooglePlacesParser.parse(buf.readUtf8(), latinize)
            }
            throw IOException("Google Places response too large")
        }
    }

    companion object {
        const val DEFAULT_URL = "https://places.googleapis.com/v1/places:searchText"
    }
}
