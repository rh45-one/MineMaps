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
            alertFromTags(id, lat, lon, tags)?.let { out.add(it) }
        }
        out
    }.getOrDefault(emptyList())

    fun parsePostPass(text: String): List<RoadAlert> = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val features = root["features"] as? JsonArray ?: return emptyList()
        val out = ArrayList<RoadAlert>(features.size)
        for (el in features) {
            val f = el.jsonObject
            val props = f["properties"]?.jsonObject ?: continue
            val id = props["osm_id"]?.jsonPrimitive?.content ?: continue
            val coords = f["geometry"]?.jsonObject?.get("coordinates") as? JsonArray ?: continue
            val lon = coords.getOrNull(0)?.jsonPrimitive?.doubleOrNull ?: continue
            val lat = coords.getOrNull(1)?.jsonPrimitive?.doubleOrNull ?: continue
            if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val tags = props["tags"] as? JsonObject ?: JsonObject(emptyMap())
            alertFromTags(id, lat, lon, tags)?.let { out.add(it) }
        }
        out
    }.getOrDefault(emptyList())

    private fun alertFromTags(id: String, lat: Double, lon: Double, tags: JsonObject): RoadAlert? {
        val hw = (tags["highway"] as? JsonPrimitive)?.content.orEmpty()
        val enf = (tags["enforcement"] as? JsonPrimitive)?.content.orEmpty()
        val maxspeed = (tags["maxspeed"] as? JsonPrimitive)?.content.orEmpty()
        val hazard = (tags["hazard"] as? JsonPrimitive)?.content.orEmpty()
        val desc = (tags["description"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            ?: (tags["note"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val name = (tags["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return when {
            hw == "speed_camera" || enf in setOf("maxspeed", "average_speed", "traffic_signals", "check") -> {
                val label = when (enf) {
                    "average_speed" -> "Section Speed Radar"
                    "traffic_signals" -> "Red-Light / Speed Camera"
                    else -> "Speed Camera / Radar"
                }
                val detail = listOfNotNull(
                    maxspeed.takeIf { it.isNotBlank() }?.let { "Limit: $it km/h" },
                    desc ?: name,
                ).joinToString(" \u00b7 ").ifBlank { "Speed enforcement radar" }
                RoadAlert("cam_$id", lat, lon, RoadAlertKind.SPEED_CAMERA, label, detail)
            }
            hw == "construction" -> {
                RoadAlert("haz_$id", lat, lon, RoadAlertKind.HAZARD, "Roadworks / Construction", name ?: desc ?: "Caution ahead")
            }
            hazard.isNotBlank() -> {
                val pretty = hazard.replace('_', ' ').replaceFirstChar { it.uppercase() }
                RoadAlert("haz_$id", lat, lon, RoadAlertKind.HAZARD, "Road Hazard: $pretty", desc ?: name ?: "Reported hazard")
            }
            else -> null
        }
    }

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
    private val postPassUrl: String = DEFAULT_POSTPASS_URL,
    private val nominatimUrl: String = DEFAULT_NOMINATIM_URL,
) {
    /** Fetches speed cameras (fixed, section, red-light), roadworks, and hazard nodes within ~14 km of (lat, lon). */
    suspend fun fetchNearby(lat: Double, lon: Double, radiusKm: Double = 14.0): List<RoadAlert> = withContext(Dispatchers.IO) {
        val dLat = (radiusKm / 110.54).coerceIn(0.01, 0.28)
        val dLon = (radiusKm / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))).coerceIn(0.01, 0.38)
        val s = (lat - dLat).coerceIn(-85.0, 85.0)
        val n = (lat + dLat).coerceIn(-85.0, 85.0)
        val w = (lon - dLon).coerceIn(-180.0, 180.0)
        val e = (lon + dLon).coerceIn(-180.0, 180.0)
        val http = clientProvider().newBuilder().callTimeout(10, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
        val merged = LinkedHashMap<String, RoadAlert>()

        // 1. Primary public speed-camera & road-hazard database: Geofabrik PostPass (full PostGIS OSM index, ~0.35s).
        runCatching {
            val env = String.format(Locale.US, "ST_MakeEnvelope(%.4f, %.4f, %.4f, %.4f, 4326)", w, s, e, n)
            val sql = "(SELECT osm_id, tags, geom FROM postpass_point WHERE (tags->>'highway' = 'speed_camera' OR tags->>'enforcement' IN ('maxspeed', 'average_speed', 'traffic_signals', 'check') OR tags ? 'hazard') AND geom && $env LIMIT 250) UNION ALL (SELECT osm_id, tags, ST_Centroid(geom) AS geom FROM postpass_line WHERE tags->>'highway' = 'construction' AND geom && $env LIMIT 60)"
            val url = "${postPassUrl.trimEnd('/')}?data=${java.net.URLEncoder.encode(sql, "UTF-8")}"
            val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
            http.newCall(req).execute().use { r ->
                if (r.isSuccessful) {
                    for (a in RoadAlertsParser.parsePostPass(RouteParsers.readBounded(r))) merged[a.id] = a
                }
            }
        }

        // 2. Supplement with Nominatim bounded tag search if PostPass returned fewer than 5 results.
        if (merged.size < 5) {
            val viewbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", w, n, e, s)
            for ((tagQuery, kind) in listOf("[highway=speed_camera]" to RoadAlertKind.SPEED_CAMERA, "[highway=construction]" to RoadAlertKind.HAZARD)) {
                runCatching {
                    val url = "${nominatimUrl.trimEnd('/')}?q=${java.net.URLEncoder.encode(tagQuery, "UTF-8")}&format=jsonv2&bounded=1&viewbox=$viewbox&limit=50&extratags=1"
                    val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                    http.newCall(req).execute().use { r ->
                        if (r.isSuccessful) {
                            for (a in RoadAlertsParser.parseNominatim(RouteParsers.readBounded(r), kind)) {
                                if (a.id !in merged) merged[a.id] = a
                            }
                        }
                    }
                }
            }
        }
        if (merged.isNotEmpty()) return@withContext merged.values.toList()

        // 3. Fallback to Overpass mirrors if both PostPass and Nominatim were unreachable.
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
        const val DEFAULT_POSTPASS_URL = "https://postpass.geofabrik.de/api/interpreter"
        const val DEFAULT_NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
    }
}
