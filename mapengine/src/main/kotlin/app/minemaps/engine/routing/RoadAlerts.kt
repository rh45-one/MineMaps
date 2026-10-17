package app.minemaps.engine.routing

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.cos

enum class RoadAlertKind { SPEED_CAMERA, HAZARD }

data class RoadAlert(
    val id: String,
    val lat: Double,
    val lon: Double,
    val kind: RoadAlertKind,
    val label: String,
    val detail: String = "",
)

object RoadAlertsParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseOverpass(text: String): List<RoadAlert> = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val elements = root["elements"] as? JsonArray ?: return emptyList()
        val out = ArrayList<RoadAlert>(elements.size)
        for (el in elements) {
            val o = el.jsonObject
            val id = o["id"]?.jsonPrimitive?.content ?: continue
            val lat = o["lat"]?.jsonPrimitive?.doubleOrNull ?: o["center"]?.jsonObject?.get("lat")?.jsonPrimitive?.doubleOrNull ?: continue
            val lon = o["lon"]?.jsonPrimitive?.doubleOrNull ?: o["center"]?.jsonObject?.get("lon")?.jsonPrimitive?.doubleOrNull ?: continue
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val tags = o["tags"]?.jsonObject ?: JsonObject(emptyMap())
            val hw = (tags["highway"] as? JsonPrimitive)?.content.orEmpty()
            val enf = (tags["enforcement"] as? JsonPrimitive)?.content.orEmpty()
            val maxspeed = (tags["maxspeed"] as? JsonPrimitive)?.content.orEmpty()
            val hazard = (tags["hazard"] as? JsonPrimitive)?.content.orEmpty()
            when {
                hw == "speed_camera" || enf == "maxspeed" || enf == "average_speed" -> {
                    val detail = if (maxspeed.isNotBlank()) "Limit: $maxspeed km/h" else "Speed enforcement radar"
                    out.add(RoadAlert("cam_$id", lat, lon, RoadAlertKind.SPEED_CAMERA, "Speed Camera / Radar", detail))
                }
                hw == "construction" -> {
                    out.add(RoadAlert("haz_$id", lat, lon, RoadAlertKind.HAZARD, "Roadworks / Construction", "Caution ahead"))
                }
                hazard.isNotBlank() -> {
                    val pretty = hazard.replace('_', ' ').replaceFirstChar { it.uppercase() }
                    out.add(RoadAlert("haz_$id", lat, lon, RoadAlertKind.HAZARD, "Road Hazard: $pretty", "Reported hazard"))
                }
            }
        }
        out
    }.getOrDefault(emptyList())

    fun parseNominatim(text: String, defaultKind: RoadAlertKind): List<RoadAlert> = runCatching {
        val arr = json.parseToJsonElement(text) as? JsonArray ?: return emptyList()
        val out = ArrayList<RoadAlert>(arr.size)
        for (el in arr) {
            val o = el.jsonObject
            val id = o["osm_id"]?.jsonPrimitive?.content ?: o["place_id"]?.jsonPrimitive?.content ?: continue
            val lat = o["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
            val lon = o["lon"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: continue
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val type = (o["type"] as? JsonPrimitive)?.content.orEmpty()
            val extra = o["extratags"] as? JsonObject ?: JsonObject(emptyMap())
            val maxspeed = (extra["maxspeed"] as? JsonPrimitive)?.content.orEmpty()
            val desc = (extra["description"] as? JsonPrimitive)?.content.orEmpty()
            val kind = when (type) {
                "speed_camera" -> RoadAlertKind.SPEED_CAMERA
                "construction", "hazard" -> RoadAlertKind.HAZARD
                else -> defaultKind
            }
            if (kind == RoadAlertKind.SPEED_CAMERA) {
                val detail = listOfNotNull(
                    maxspeed.takeIf { it.isNotBlank() }?.let { "Limit: $it km/h" },
                    desc.takeIf { it.isNotBlank() },
                ).joinToString(" \u00b7 ").ifBlank { "Speed enforcement radar" }
                out.add(RoadAlert("cam_$id", lat, lon, RoadAlertKind.SPEED_CAMERA, "Speed Camera / Radar", detail))
            } else {
                val name = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
                out.add(RoadAlert("haz_$id", lat, lon, RoadAlertKind.HAZARD, "Roadworks / Construction", name ?: "Caution ahead"))
            }
        }
        out
    }.getOrDefault(emptyList())
}

class RoadAlertsClient(
    private val clientProvider: suspend () -> OkHttpClient,
    private val userAgent: String,
    private val overpassUrl: String = DEFAULT_OVERPASS_URL,
    private val nominatimUrl: String = DEFAULT_NOMINATIM_URL,
) {
    /** Fetches speed cameras (radars), roadworks, and hazard nodes within ~10 km of (lat, lon). */
    suspend fun fetchNearby(lat: Double, lon: Double, radiusKm: Double = 10.0): List<RoadAlert> = withContext(Dispatchers.IO) {
        val dLat = (radiusKm / 110.54).coerceIn(0.01, 0.25)
        val dLon = (radiusKm / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))).coerceIn(0.01, 0.35)
        val s = (lat - dLat).coerceIn(-85.0, 85.0)
        val n = (lat + dLat).coerceIn(-85.0, 85.0)
        val w = (lon - dLon).coerceIn(-180.0, 180.0)
        val e = (lon + dLon).coerceIn(-180.0, 180.0)
        val http = clientProvider().newBuilder().callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()

        // 1. Fast Nominatim tag lookup (~0.3s-0.6s, unaffected by public Overpass 502/504 queue backlogs).
        val merged = LinkedHashMap<String, RoadAlert>()
        val viewbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", w, n, e, s)
        for ((tagQuery, kind) in listOf("[highway=speed_camera]" to RoadAlertKind.SPEED_CAMERA, "[highway=construction]" to RoadAlertKind.HAZARD)) {
            runCatching {
                val url = "${nominatimUrl.trimEnd('/')}?q=${java.net.URLEncoder.encode(tagQuery, "UTF-8")}&format=jsonv2&bounded=1&viewbox=$viewbox&limit=50&extratags=1"
                val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                http.newCall(req).execute().use { r ->
                    if (r.isSuccessful) {
                        val body = RouteParsers.readBounded(r)
                        for (a in RoadAlertsParser.parseNominatim(body, kind)) merged[a.id] = a
                    }
                }
            }
        }
        if (merged.isNotEmpty()) return@withContext merged.values.toList()

        // 2. Fallback to Overpass mirrors if Nominatim returned nothing or was unreachable.
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", s, w, n, e)
        val query = """[out:json][timeout:8];(node["highway"="speed_camera"]($bbox);node["enforcement"="maxspeed"]($bbox);node["hazard"]($bbox);way["highway"="construction"]($bbox););out center 120;"""
        val bodyStr = "data=${java.net.URLEncoder.encode(query, "UTF-8")}"
        for (endpoint in listOf(overpassUrl, FALLBACK_OVERPASS_URL)) {
            val res = runCatching {
                val req = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", userAgent)
                    .post(bodyStr.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
                    .build()
                http.newCall(req).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("Overpass HTTP ${r.code}")
                    RoadAlertsParser.parseOverpass(RouteParsers.readBounded(r))
                }
            }.getOrNull()
            if (res != null) return@withContext res
        }
        throw IOException("Road alerts unavailable")
    }

    companion object {
        const val DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter"
        const val FALLBACK_OVERPASS_URL = "https://lz4.overpass-api.de/api/interpreter"
        const val DEFAULT_NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
    }
}
