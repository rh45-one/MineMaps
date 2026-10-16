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
}

class RoadAlertsClient(
    private val clientProvider: suspend () -> OkHttpClient,
    private val userAgent: String,
    private val overpassUrl: String = DEFAULT_OVERPASS_URL,
) {
    /** Fetches speed cameras (radars), roadworks, and hazard nodes within ~8 km of (lat, lon). */
    suspend fun fetchNearby(lat: Double, lon: Double, radiusKm: Double = 8.0): List<RoadAlert> = withContext(Dispatchers.IO) {
        val dLat = (radiusKm / 110.54).coerceIn(0.01, 0.25)
        val dLon = (radiusKm / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.1))).coerceIn(0.01, 0.35)
        val s = (lat - dLat).coerceIn(-85.0, 85.0)
        val n = (lat + dLat).coerceIn(-85.0, 85.0)
        val w = (lon - dLon).coerceIn(-180.0, 180.0)
        val e = (lon + dLon).coerceIn(-180.0, 180.0)
        val bbox = String.format(Locale.US, "%.4f,%.4f,%.4f,%.4f", s, w, n, e)
        val query = """[out:json][timeout:12];(node["highway"="speed_camera"]($bbox);node["enforcement"="maxspeed"]($bbox);node["hazard"]($bbox);way["highway"="construction"]($bbox););out center 120;"""
        val req = Request.Builder()
            .url(overpassUrl)
            .header("User-Agent", userAgent)
            .post("data=${java.net.URLEncoder.encode(query, "UTF-8")}".toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        val http = clientProvider().newBuilder().callTimeout(15, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Overpass HTTP ${r.code}")
            val body = RouteParsers.readBounded(r)
            RoadAlertsParser.parseOverpass(body)
        }
    }

    companion object {
        const val DEFAULT_OVERPASS_URL = "https://overpass-api.de/api/interpreter"
    }
}
