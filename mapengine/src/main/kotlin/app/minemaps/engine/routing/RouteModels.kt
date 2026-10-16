package app.minemaps.engine.routing

import app.minemaps.engine.geo.LatLng
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

enum class TrafficSpeed { NORMAL, SLOW, TRAFFIC_JAM }

enum class TurnDirection { STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, U_TURN, ROUNDABOUT, ARRIVE, DEPART }

data class TrafficInterval(val startPointIndex: Int, val endPointIndex: Int, val speed: TrafficSpeed)

data class RouteStep(
    val instruction: String,
    val roadName: String,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val direction: TurnDirection,
    val maneuverPoint: LatLng,
)

data class RoutePlan(
    val points: List<LatLng>,
    val steps: List<RouteStep>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val trafficIntervals: List<TrafficInterval> = emptyList(),
    val providerLabel: String = "OSRM",
)

data class RouteProgress(
    val currentStep: RouteStep?,
    val distanceToNextStepMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val offRouteMeters: Double,
    val arrived: Boolean,
)

/** Google's standard encoded polyline format (precision 1e-5, or 1e-6 for polyline6). */
object PolylineCodec {
    private const val MAX_POINTS = 50_000

    fun decode(encoded: String, precision: Double = 1e5): List<LatLng> {
        if (encoded.isBlank()) return emptyList()
        val out = ArrayList<LatLng>(min(encoded.length / 4 + 1, 4096))
        var index = 0
        val len = encoded.length
        var lat = 0L
        var lng = 0L
        while (index < len && out.size < MAX_POINTS) {
            var result = 0L
            var shift = 0
            while (true) {
                if (index >= len || shift > 60) return out
                val b = encoded[index++].code - 63
                if (b < 0) return out
                result = result or ((b.toLong() and 0x1fL) shl shift)
                shift += 5
                if (b < 0x20) break
            }
            lat += if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)

            result = 0L
            shift = 0
            while (true) {
                if (index >= len || shift > 60) return out
                val b = encoded[index++].code - 63
                if (b < 0) return out
                result = result or ((b.toLong() and 0x1fL) shl shift)
                shift += 5
                if (b < 0x20) break
            }
            lng += if ((result and 1L) != 0L) (result shr 1).inv() else (result shr 1)

            val dLat = lat / precision
            val dLng = lng / precision
            if (dLat in -90.0..90.0 && dLng in -180.0..180.0) out.add(LatLng(dLat, dLng))
        }
        return out
    }
}

object RouteTracker {
    const val OFF_ROUTE_THRESHOLD_M = 85.0
    const val ARRIVAL_THRESHOLD_M = 30.0

    fun metersBetween(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dx = (bLon - aLon) * 111_320.0 * cos(Math.toRadians((aLat + bLat) * 0.5))
        val dy = (bLat - aLat) * 110_540.0
        return hypot(dx, dy)
    }

    /** Evaluates the user's current position against [plan] and returns step/ETA progress. */
    fun evaluate(plan: RoutePlan, lat: Double, lon: Double): RouteProgress {
        val pts = plan.points
        if (pts.isEmpty()) {
            return RouteProgress(plan.steps.firstOrNull(), 0.0, plan.distanceMeters, plan.durationSeconds, 0.0, false)
        }
        val dest = pts.last()
        val distToDest = metersBetween(lat, lon, dest.lat, dest.lng)
        if (distToDest <= ARRIVAL_THRESHOLD_M) {
            return RouteProgress(
                currentStep = RouteStep("Arrived at destination", "", 0.0, 0.0, TurnDirection.ARRIVE, dest),
                distanceToNextStepMeters = 0.0,
                remainingDistanceMeters = 0.0,
                remainingDurationSeconds = 0.0,
                offRouteMeters = 0.0,
                arrived = true,
            )
        }

        // Find the closest segment on the polyline.
        var bestDist = Double.MAX_VALUE
        var bestSeg = 0
        var bestT = 0.0
        for (i in 0 until max(1, pts.size - 1)) {
            val a = pts[i]
            val b = pts.getOrElse(i + 1) { a }
            val (d, t) = pointToSegmentMeters(lat, lon, a.lat, a.lng, b.lat, b.lng)
            if (d < bestDist) {
                bestDist = d
                bestSeg = i
                bestT = t
            }
        }

        // Compute remaining polyline distance from (bestSeg, bestT) to the end.
        var remDist = 0.0
        if (pts.size >= 2) {
            val a = pts[bestSeg]
            val b = pts[bestSeg + 1]
            remDist += metersBetween(a.lat, a.lng, b.lat, b.lng) * (1.0 - bestT)
            for (i in (bestSeg + 1) until (pts.size - 1)) {
                remDist += metersBetween(pts[i].lat, pts[i].lng, pts[i + 1].lat, pts[i + 1].lng)
            }
        } else {
            remDist = distToDest
        }
        val total = max(plan.distanceMeters, 1.0)
        val fraction = (remDist / total).coerceIn(0.0, 1.0)
        val remDuration = plan.durationSeconds * fraction

        // Pick the next upcoming maneuver step ahead of our position along the polyline.
        var nextStep: RouteStep? = null
        var distToNext = remDist
        if (plan.steps.isNotEmpty()) {
            var chosenIdx = plan.steps.lastIndex
            for (sIdx in plan.steps.indices) {
                val s = plan.steps[sIdx]
                if (s.direction == TurnDirection.DEPART && plan.steps.size > 1) continue
                val stepSeg = nearestPointIndex(pts, s.maneuverPoint.lat, s.maneuverPoint.lng)
                if (stepSeg > bestSeg) {
                    chosenIdx = sIdx
                    break
                }
            }
            val step = plan.steps[chosenIdx]
            nextStep = step
            distToNext = metersBetween(lat, lon, step.maneuverPoint.lat, step.maneuverPoint.lng)
        }

        return RouteProgress(
            currentStep = nextStep,
            distanceToNextStepMeters = distToNext,
            remainingDistanceMeters = remDist,
            remainingDurationSeconds = remDuration,
            offRouteMeters = bestDist,
            arrived = false,
        )
    }

    private fun nearestPointIndex(pts: List<LatLng>, lat: Double, lon: Double): Int {
        var best = 0
        var bestD = Double.MAX_VALUE
        for (i in pts.indices) {
            val d = metersBetween(lat, lon, pts[i].lat, pts[i].lng)
            if (d < bestD) {
                bestD = d
                best = i
            }
        }
        return best
    }

    private fun pointToSegmentMeters(pLat: Double, pLon: Double, aLat: Double, aLon: Double, bLat: Double, bLon: Double): Pair<Double, Double> {
        val cosLat = cos(Math.toRadians((aLat + bLat + pLat) / 3.0))
        val ax = 0.0
        val ay = 0.0
        val bx = (bLon - aLon) * 111_320.0 * cosLat
        val by = (bLat - aLat) * 110_540.0
        val px = (pLon - aLon) * 111_320.0 * cosLat
        val py = (pLat - aLat) * 110_540.0
        val dx = bx - ax
        val dy = by - ay
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq <= 1e-6) 0.0 else ((px * dx + py * dy) / lenSq).coerceIn(0.0, 1.0)
        val cx = ax + t * dx
        val cy = ay + t * dy
        return hypot(px - cx, py - cy) to t
    }

    fun formatDistance(meters: Double): String {
        val m = max(0.0, meters)
        return when {
            m < 950 -> "${((m / 10.0).toInt() * 10).coerceAtLeast(10)} m"
            m < 10_000 -> String.format(java.util.Locale.US, "%.1f km", m / 1000.0)
            else -> "${(m / 1000.0).toInt()} km"
        }
    }

    fun formatDuration(seconds: Double): String {
        val mins = max(1, (seconds / 60.0).toInt())
        return if (mins < 60) "$mins min" else "${mins / 60} h ${mins % 60} min"
    }
}
