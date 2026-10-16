package app.minemaps.engine.inspect

import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.labels.NameResolver
import app.minemaps.engine.mvt.Feature
import app.minemaps.engine.mvt.GeomType
import app.minemaps.engine.mvt.Layer
import app.minemaps.engine.mvt.VectorTile
import app.minemaps.engine.style.FeatureClassifier
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

enum class InspectKind { POI, BUILDING, ROAD, WATER, LAND }

class InspectResult(val title: String, val subtitle: String, val kind: InspectKind, val wx: Double, val wy: Double, val roadClass: String? = null)

object FeatureInspector {
    private const val POI_RADIUS_PX = 24.0
    private const val MIN_ROAD_RADIUS_PX = 8.0
    private const val NAME_RADIUS_PX = 14.0

    private val currentNames = ThreadLocal.withInitial { NameResolver(NameResolver.Mode.LOCAL) }

    fun inspect(
        tile: VectorTile, vtKey: TileKey, wx: Double, wy: Double, zoom: Double, pixelRatio: Float = 1f,
        names: NameResolver = NameResolver(NameResolver.Mode.LOCAL),
    ): InspectResult? {
        currentNames.set(names)
        return try {
            inspectTile(tile, vtKey, wx, wy, zoom, pixelRatio)
        } finally {
            currentNames.remove()
        }
    }

    private fun inspectTile(tile: VectorTile, vtKey: TileKey, wx: Double, wy: Double, zoom: Double, pixelRatio: Float): InspectResult? {
        val n = (1 shl vtKey.z).toDouble()
        val worldSizePx = 256.0 * pixelRatio * 2.0.pow(zoom)
        val lat = Mercator.yToLat(wy)
        val metersPerPx = Mercator.EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / worldSizePx

        fun ux(layer: Layer) = (wx * n - vtKey.x) * layer.extent
        fun uy(layer: Layer) = (wy * n - vtKey.y) * layer.extent
        fun unitsPerPx(layer: Layer) = layer.extent * n / worldSizePx

        tile.layers["poi"]?.let { layer ->
            val px = ux(layer)
            val py = uy(layer)
            val limit = POI_RADIUS_PX * unitsPerPx(layer)
            var best: Feature? = null
            var bestD = Double.MAX_VALUE
            for (f in layer.features) {
                if (f.type != GeomType.POINT || nameOf(f) == null) continue
                val g = f.geometry.firstOrNull() ?: continue
                val d = hypot(g[0] - px, g[1] - py)
                if (d <= limit && d < bestD) {
                    bestD = d
                    best = f
                }
            }
            best?.let { f ->
                val g = f.geometry[0]
                val sub = (f.tags["subclass"] as? String) ?: (f.tags["class"] as? String) ?: "place"
                return InspectResult(
                    nameOf(f)!!, pretty(sub), InspectKind.POI,
                    (vtKey.x + g[0].toDouble() / layer.extent) / n, (vtKey.y + g[1].toDouble() / layer.extent) / n,
                )
            }
        }

        tile.layers["building"]?.let { layer ->
            val px = ux(layer)
            val py = uy(layer)
            for (f in layer.features) {
                if (f.type != GeomType.POLYGON || !inBounds(f, px, py) || !contains(f, px, py)) continue
                val h = (f.tags["render_height"] as? Number)?.toDouble()
                return InspectResult(
                    nameOf(f) ?: "Building",
                    if (h != null && h > 0) "Height: ${Math.round(h)} m" else "Building",
                    InspectKind.BUILDING, wx, wy,
                )
            }
        }

        tile.layers["transportation"]?.let { layer ->
            val px = ux(layer)
            val py = uy(layer)
            val upp = unitsPerPx(layer)
            var best: Feature? = null
            var bestD = Double.MAX_VALUE
            for (f in layer.features) {
                if (f.type != GeomType.LINE || f.tags["brunnel"] == "tunnel") continue
                val widthM = FeatureClassifier.classify("transportation", f, 20)?.widthM ?: continue
                val limit = maxOf(MIN_ROAD_RADIUS_PX, widthM / 2 / metersPerPx) * upp
                val d = distanceToLines(f, px, py)
                if (d <= limit && d < bestD) {
                    bestD = d
                    best = f
                }
            }
            best?.let { f ->
                val cls = f.tags["class"] as? String ?: "road"
                val name = nearestName(tile.layers["transportation_name"], f, px, py, NAME_RADIUS_PX * upp, cls)
                return InspectResult(name ?: roadTitle(cls), roadTitle(cls), InspectKind.ROAD, wx, wy, roadClass = cls)
            }
        }

        tile.layers["waterway"]?.let { layer ->
            val px = ux(layer)
            val py = uy(layer)
            val limit = MIN_ROAD_RADIUS_PX * unitsPerPx(layer)
            for (f in layer.features) {
                if (f.type != GeomType.LINE) continue
                if (distanceToLines(f, px, py) <= limit) {
                    val cls = (f.tags["class"] as? String) ?: "waterway"
                    return InspectResult(nameOf(f) ?: pretty(cls), pretty(cls), InspectKind.WATER, wx, wy)
                }
            }
        }

        tile.layers["water"]?.let { layer ->
            val px = ux(layer)
            val py = uy(layer)
            for (f in layer.features) {
                if (f.type != GeomType.POLYGON || f.tags["brunnel"] == "tunnel" || !inBounds(f, px, py) || !contains(f, px, py)) continue
                val named = tile.layers["water_name"]?.features?.firstOrNull { nameOf(it) != null }
                return InspectResult(named?.let { nameOf(it) } ?: "Water", pretty((f.tags["class"] as? String) ?: "water"), InspectKind.WATER, wx, wy)
            }
        }

        for ((layerName, label) in listOf("landcover" to "Land cover", "landuse" to "Land use")) {
            val layer = tile.layers[layerName] ?: continue
            val px = ux(layer)
            val py = uy(layer)
            for (f in layer.features.asReversed()) {
                if (f.type != GeomType.POLYGON || !inBounds(f, px, py) || !contains(f, px, py)) continue
                val cls = f.tags["class"] as? String ?: continue
                val sub = f.tags["subclass"] as? String
                val title = if (layerName == "landcover" && sub != null && cls == "grass" && sub != "grass") sub else cls
                return InspectResult(pretty(title), label, InspectKind.LAND, wx, wy)
            }
        }
        return null
    }

    private fun nameOf(f: Feature): String? = currentNames.get().resolve(f.tags)

    private fun pretty(s: String): String = s.replace('_', ' ').replaceFirstChar { it.uppercase() }

    private fun roadTitle(cls: String): String = when (cls) {
        "motorway" -> "Motorway"
        "trunk" -> "Trunk road"
        "primary" -> "Primary road"
        "secondary" -> "Secondary road"
        "tertiary" -> "Tertiary road"
        "minor" -> "Street"
        "service" -> "Service road"
        "rail" -> "Railway"
        "path" -> "Path"
        else -> pretty(cls)
    }

    private fun nearestName(layer: Layer?, from: Feature, px: Double, py: Double, limit: Double, cls: String): String? {
        layer ?: return null
        var best: String? = null
        var bestD = Double.MAX_VALUE
        for (f in layer.features) {
            if (f.type != GeomType.LINE) continue
            val name = nameOf(f) ?: continue
            if (f.tags["class"] != cls) continue
            val d = distanceToLines(f, px, py)
            if (d <= limit && d < bestD) {
                bestD = d
                best = name
            }
        }
        return best
    }

    private fun inBounds(f: Feature, x: Double, y: Double): Boolean {
        val b = f.bounds()
        return x >= b[0] && x <= b[2] && y >= b[1] && y <= b[3]
    }

    private fun contains(f: Feature, x: Double, y: Double): Boolean {
        var inside = false
        for (ring in f.geometry) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val xi = ring[2 * i].toDouble()
                val yi = ring[2 * i + 1].toDouble()
                val xj = ring[2 * j].toDouble()
                val yj = ring[2 * j + 1].toDouble()
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    private fun distanceToLines(f: Feature, x: Double, y: Double): Double {
        var best = Double.MAX_VALUE
        for (path in f.geometry) {
            var i = 0
            while (i + 3 < path.size) {
                val d = segmentDistance(x, y, path[i].toDouble(), path[i + 1].toDouble(), path[i + 2].toDouble(), path[i + 3].toDouble())
                if (d < best) best = d
                i += 2
            }
        }
        return best
    }

    private fun segmentDistance(px: Double, py: Double, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0.0) 0.0 else (((px - x0) * dx + (py - y0) * dy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (x0 + t * dx), py - (y0 + t * dy))
    }
}
