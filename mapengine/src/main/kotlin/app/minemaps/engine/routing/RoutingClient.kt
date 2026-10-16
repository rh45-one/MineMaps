package app.minemaps.engine.routing

import app.minemaps.engine.geo.LatLng
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

object RouteParsers {
    private const val MAX_RESPONSE_BYTES = 2L * 1024 * 1024
    private val json = Json { ignoreUnknownKeys = true }

    internal fun readBounded(r: Response): String {
        if (r.body.contentLength() > MAX_RESPONSE_BYTES) throw IOException("Routing response too large")
        val src = r.body.source()
        val buf = okio.Buffer()
        while (buf.size <= MAX_RESPONSE_BYTES) {
            if (src.read(buf, 8192) < 0) return buf.readUtf8()
        }
        throw IOException("Routing response too large")
    }

    private fun str(o: JsonObject, key: String): String? =
        (o[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() }

    /** Parses an OSRM `/route/v1/driving/...` JSON response into a [RoutePlan]. */
    fun parseOsrm(text: String, latinize: (String) -> String = { it }): RoutePlan? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val routes = root["routes"] as? JsonArray ?: return null
        val route = routes.firstOrNull()?.jsonObject ?: return null
        val geom = str(route, "geometry") ?: return null
        val pts = PolylineCodec.decode(geom)
        if (pts.size < 2) return null
        val distance = route["distance"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val duration = route["duration"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0

        val steps = ArrayList<RouteStep>()
        val legs = route["legs"] as? JsonArray
        legs?.forEach { legEl ->
            val legSteps = legEl.jsonObject["steps"] as? JsonArray ?: return@forEach
            legSteps.forEach { stepEl ->
                val s = stepEl.jsonObject
                val m = s["maneuver"]?.jsonObject ?: return@forEach
                val loc = m["location"]?.jsonArray
                val lon = loc?.getOrNull(0)?.jsonPrimitive?.doubleOrNull
                val lat = loc?.getOrNull(1)?.jsonPrimitive?.doubleOrNull
                if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite()) return@forEach
                val type = str(m, "type").orEmpty()
                val mod = str(m, "modifier").orEmpty()
                val rawName = str(s, "name")?.let(latinize).orEmpty()
                val ref = str(s, "ref")?.let(latinize).orEmpty()
                val road = when {
                    rawName.isNotBlank() && ref.isNotBlank() && rawName != ref -> "$rawName ($ref)"
                    rawName.isNotBlank() -> rawName
                    else -> ref
                }
                val dir = osrmDirection(type, mod)
                val instruction = formatOsrmInstruction(type, dir, road)
                val sDist = s["distance"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                val sDur = s["duration"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
                steps.add(RouteStep(instruction, road, sDist, sDur, dir, LatLng(lat, lon)))
            }
        }
        RoutePlan(pts, steps, distance, duration, emptyList(), providerLabel = "OSRM")
    }.getOrNull()

    private fun osrmDirection(type: String, modifier: String): TurnDirection = when (type) {
        "arrive" -> TurnDirection.ARRIVE
        "depart" -> TurnDirection.DEPART
        "roundabout", "rotary", "roundabout turn", "exit roundabout", "exit rotary" -> TurnDirection.ROUNDABOUT
        else -> when (modifier) {
            "uturn" -> TurnDirection.U_TURN
            "sharp left" -> TurnDirection.SHARP_LEFT
            "left" -> TurnDirection.LEFT
            "slight left" -> TurnDirection.SLIGHT_LEFT
            "sharp right" -> TurnDirection.SHARP_RIGHT
            "right" -> TurnDirection.RIGHT
            "slight right" -> TurnDirection.SLIGHT_RIGHT
            else -> TurnDirection.STRAIGHT
        }
    }

    private fun formatOsrmInstruction(type: String, dir: TurnDirection, road: String): String {
        val onto = if (road.isNotBlank()) " onto $road" else ""
        return when (dir) {
            TurnDirection.ARRIVE -> "Arrive at destination"
            TurnDirection.DEPART -> if (road.isNotBlank()) "Head on $road" else "Start route"
            TurnDirection.ROUNDABOUT -> if (road.isNotBlank()) "Roundabout onto $road" else "Enter roundabout"
            TurnDirection.U_TURN -> "Make a U-turn$onto"
            TurnDirection.SHARP_LEFT -> "Sharp left$onto"
            TurnDirection.LEFT -> "Turn left$onto"
            TurnDirection.SLIGHT_LEFT -> "Keep left$onto"
            TurnDirection.SHARP_RIGHT -> "Sharp right$onto"
            TurnDirection.RIGHT -> "Turn right$onto"
            TurnDirection.SLIGHT_RIGHT -> "Keep right$onto"
            TurnDirection.STRAIGHT -> if (type == "merge") "Merge$onto" else if (road.isNotBlank()) "Continue on $road" else "Continue straight"
        }
    }

    /** Parses a Google Routes API v2 `computeRoutes` JSON response (with optional traffic speed intervals). */
    fun parseGoogleRoutes(text: String, latinize: (String) -> String = { it }): RoutePlan? = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val routes = root["routes"] as? JsonArray ?: return null
        val route = routes.firstOrNull()?.jsonObject ?: return null
        val encoded = route["polyline"]?.jsonObject?.let { str(it, "encodedPolyline") } ?: return null
        val pts = PolylineCodec.decode(encoded)
        if (pts.size < 2) return null
        val distance = route["distanceMeters"]?.jsonPrimitive?.doubleOrNull?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
        val duration = parseGoogleDuration(str(route, "duration"))

        val steps = ArrayList<RouteStep>()
        val intervals = ArrayList<TrafficInterval>()
        val legs = route["legs"] as? JsonArray
        legs?.forEach { legEl ->
            val leg = legEl.jsonObject
            (leg["steps"] as? JsonArray)?.forEach { stepEl ->
                val s = stepEl.jsonObject
                val nav = s["navigationInstruction"]?.jsonObject
                val rawInstr = nav?.let { str(it, "instructions") }?.replace('\n', ' ')?.trim()?.let(latinize).orEmpty()
                val maneuver = nav?.let { str(it, "maneuver") }.orEmpty()
                val dir = googleDirection(maneuver)
                val locObj = s["startLocation"]?.jsonObject?.get("latLng")?.jsonObject
                val lat = locObj?.get("latitude")?.jsonPrimitive?.doubleOrNull
                val lon = locObj?.get("longitude")?.jsonPrimitive?.doubleOrNull
                if (lat != null && lon != null && lat.isFinite() && lon.isFinite()) {
                    val sDist = s["distanceMeters"]?.jsonPrimitive?.doubleOrNull ?: 0.0
                    val sDur = parseGoogleDuration(str(s, "staticDuration"))
                    val instr = rawInstr.ifBlank { formatOsrmInstruction("", dir, "") }
                    steps.add(RouteStep(instr, "", sDist, sDur, dir, LatLng(lat, lon)))
                }
            }
            val advisory = leg["travelAdvisory"]?.jsonObject ?: route["travelAdvisory"]?.jsonObject
            (advisory?.get("speedReadingIntervals") as? JsonArray)?.forEach { ivEl ->
                val iv = ivEl.jsonObject
                val start = iv["startPolylinePointIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val end = iv["endPolylinePointIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val speed = when (str(iv, "speed")) {
                    "SLOW" -> TrafficSpeed.SLOW
                    "TRAFFIC_JAM" -> TrafficSpeed.TRAFFIC_JAM
                    else -> TrafficSpeed.NORMAL
                }
                if (end > start && start >= 0 && end < pts.size) {
                    intervals.add(TrafficInterval(start, end, speed))
                }
            }
        }
        if (intervals.isEmpty()) {
            val routeAdv = route["travelAdvisory"]?.jsonObject
            (routeAdv?.get("speedReadingIntervals") as? JsonArray)?.forEach { ivEl ->
                val iv = ivEl.jsonObject
                val start = iv["startPolylinePointIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val end = iv["endPolylinePointIndex"]?.jsonPrimitive?.intOrNull ?: 0
                val speed = when (str(iv, "speed")) {
                    "SLOW" -> TrafficSpeed.SLOW
                    "TRAFFIC_JAM" -> TrafficSpeed.TRAFFIC_JAM
                    else -> TrafficSpeed.NORMAL
                }
                if (end > start && start >= 0 && end < pts.size) {
                    intervals.add(TrafficInterval(start, end, speed))
                }
            }
        }
        RoutePlan(pts, steps, distance, duration, intervals, providerLabel = "Google Maps")
    }.getOrNull()

    private fun parseGoogleDuration(s: String?): Double {
        if (s.isNullOrBlank()) return 0.0
        return s.removeSuffix("s").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0
    }

    private fun googleDirection(maneuver: String): TurnDirection = when (maneuver) {
        "TURN_LEFT", "RAMP_LEFT", "FORK_LEFT", "FERRY_TRAIN" -> TurnDirection.LEFT
        "TURN_SLIGHT_LEFT", "KEEP_LEFT" -> TurnDirection.SLIGHT_LEFT
        "TURN_SHARP_LEFT" -> TurnDirection.SHARP_LEFT
        "TURN_RIGHT", "RAMP_RIGHT", "FORK_RIGHT" -> TurnDirection.RIGHT
        "TURN_SLIGHT_RIGHT", "KEEP_RIGHT" -> TurnDirection.SLIGHT_RIGHT
        "TURN_SHARP_RIGHT" -> TurnDirection.SHARP_RIGHT
        "UTURN_LEFT", "UTURN_RIGHT" -> TurnDirection.U_TURN
        "ROUNDABOUT_LEFT", "ROUNDABOUT_RIGHT" -> TurnDirection.ROUNDABOUT
        "DEPART" -> TurnDirection.DEPART
        else -> TurnDirection.STRAIGHT
    }
}

class RoutingClient(
    private val clientProvider: suspend () -> OkHttpClient,
    private val userAgent: String,
    private val osrmBaseUrl: String = DEFAULT_OSRM_URL,
    private val googleRoutesUrl: String = DEFAULT_GOOGLE_ROUTES_URL,
) {
    /** Calculates a driving route via OSRM (standalone) or Google Routes API v2 (when [googleApiKey] is non-blank). */
    suspend fun route(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        googleApiKey: String? = null,
        latinize: (String) -> String = { it },
    ): RoutePlan = withContext(Dispatchers.IO) {
        val key = googleApiKey?.trim()?.takeIf { it.isNotBlank() }
        if (key != null) {
            try {
                return@withContext routeGoogle(fromLat, fromLon, toLat, toLon, key, latinize)
            } catch (e: Exception) {
                // Fall back to OSRM if Google Routes fails or key lacks Routes permission, unless cancelled.
                if (e is kotlinx.coroutines.CancellationException) throw e
            }
        }
        routeOsrm(fromLat, fromLon, toLat, toLon, latinize)
    }

    suspend fun routeOsrm(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        latinize: (String) -> String = { it },
    ): RoutePlan = withContext(Dispatchers.IO) {
        val coords = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", fromLon, fromLat, toLon, toLat)
        val url = "${osrmBaseUrl.trimEnd('/')}/route/v1/driving/$coords?overview=full&geometries=polyline&steps=true"
        val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
        val http = clientProvider().newBuilder().callTimeout(20, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Routing failed (HTTP ${r.code})")
            val body = RouteParsers.readBounded(r)
            RouteParsers.parseOsrm(body, latinize) ?: throw IOException("No driving route found")
        }
    }

    suspend fun routeGoogle(
        fromLat: Double,
        fromLon: Double,
        toLat: Double,
        toLon: Double,
        apiKey: String,
        latinize: (String) -> String = { it },
    ): RoutePlan = withContext(Dispatchers.IO) {
        val payload = buildJsonObject {
            putJsonObject("origin") {
                putJsonObject("location") {
                    putJsonObject("latLng") {
                        put("latitude", fromLat)
                        put("longitude", fromLon)
                    }
                }
            }
            putJsonObject("destination") {
                putJsonObject("location") {
                    putJsonObject("latLng") {
                        put("latitude", toLat)
                        put("longitude", toLon)
                    }
                }
            }
            put("travelMode", "DRIVE")
            put("routingPreference", "TRAFFIC_AWARE")
            put("computeAlternativeRoutes", false)
            putJsonArray("extraComputations") { add(JsonPrimitive("TRAFFIC_ON_POLYLINE")) }
        }.toString()
        val req = Request.Builder()
            .url(googleRoutesUrl)
            .header("User-Agent", userAgent)
            .header("X-Goog-Api-Key", apiKey.trim())
            .header(
                "X-Goog-FieldMask",
                "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals,routes.legs.steps.navigationInstruction,routes.legs.steps.distanceMeters,routes.legs.steps.staticDuration,routes.legs.steps.startLocation,routes.legs.travelAdvisory.speedReadingIntervals",
            )
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        val http = clientProvider().newBuilder().callTimeout(20, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("Google Routes failed (HTTP ${r.code})")
            val body = RouteParsers.readBounded(r)
            RouteParsers.parseGoogleRoutes(body, latinize) ?: throw IOException("No Google route found")
        }
    }

    companion object {
        const val DEFAULT_OSRM_URL = "https://router.project-osrm.org"
        const val DEFAULT_GOOGLE_ROUTES_URL = "https://routes.googleapis.com/directions/v2:computeRoutes"
    }
}
