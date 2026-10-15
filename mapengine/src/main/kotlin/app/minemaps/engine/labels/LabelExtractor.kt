package app.minemaps.engine.labels

import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.mvt.Feature
import app.minemaps.engine.mvt.GeomType
import app.minemaps.engine.mvt.Layer
import app.minemaps.engine.mvt.VectorTile
import kotlin.math.atan2
import kotlin.math.hypot

enum class LabelKind {
    COUNTRY, STATE, CITY, TOWN, VILLAGE, HAMLET, SUBURB, QUARTER, NEIGHBOURHOOD, ISLAND, WATER, ROAD, ROAD_MINOR, POI_NAME
}

class Label(
    val text: String,
    val kind: LabelKind,
    val rank: Int,
    val wx: Double,
    val wy: Double,
    val angleDeg: Double,
    val minZoom: Int,
)

class Poi(val icon: String, val name: String, val wx: Double, val wy: Double, val minZoom: Int, val rank: Int = Int.MAX_VALUE)

class TileLabels(val labels: List<Label>, val pois: List<Poi>) {
    companion object {
        val EMPTY = TileLabels(emptyList(), emptyList())
    }
}

class LabelExtractor(private val names: NameResolver = NameResolver(NameResolver.Mode.LOCAL)) {
    companion object {
        fun extract(tile: VectorTile, vtKey: TileKey): TileLabels = LabelExtractor().extract(tile, vtKey)
    }

    fun extract(tile: VectorTile, vtKey: TileKey): TileLabels {
        val labels = ArrayList<Label>()
        val pois = ArrayList<Poi>()
        tile.layers["place"]?.let { placeLabels(it, vtKey, labels) }
        tile.layers["water_name"]?.let { waterLabels(it, vtKey, labels) }
        tile.layers["transportation_name"]?.let { roadLabels(it, vtKey, labels) }
        tile.layers["poi"]?.let { poiList(it, vtKey, pois) }
        return TileLabels(labels, pois)
    }

    private fun nameOf(f: Feature, isPlace: Boolean = false): String? = names.resolve(f.tags, isPlace)

    private fun placeLabels(layer: Layer, vtKey: TileKey, out: MutableList<Label>) {
        for (f in layer.features) {
            if (f.type != GeomType.POINT) continue
            val text = nameOf(f, isPlace = true) ?: continue
            val (kind, minZoom) = when (f.tags["class"] as? String) {
                "country" -> LabelKind.COUNTRY to 5
                "state", "province", "region" -> LabelKind.STATE to 6
                "city" -> LabelKind.CITY to 6
                "town" -> LabelKind.TOWN to 9
                "village" -> LabelKind.VILLAGE to 11
                "hamlet" -> LabelKind.HAMLET to 13
                "suburb" -> LabelKind.SUBURB to 12
                "quarter" -> LabelKind.QUARTER to 14
                "neighbourhood" -> LabelKind.NEIGHBOURHOOD to 14
                "island" -> LabelKind.ISLAND to 10
                else -> continue
            }
            val p = f.geometry.firstOrNull() ?: continue
            val rank = (f.tags["rank"] as? Number)?.toInt() ?: 100
            out.add(Label(text, kind, rank, wx(vtKey, layer, p[0]), wy(vtKey, layer, p[1]), 0.0, minZoom))
        }
    }

    private fun waterLabels(layer: Layer, vtKey: TileKey, out: MutableList<Label>) {
        for (f in layer.features) {
            val text = nameOf(f) ?: continue
            val minZoom = when (f.tags["class"] as? String) {
                "ocean" -> 0
                "sea" -> 3
                "lake" -> 15
                else -> 12
            }
            when (f.type) {
                GeomType.POINT -> f.geometry.firstOrNull()?.let {
                    out.add(Label(text, LabelKind.WATER, 0, wx(vtKey, layer, it[0]), wy(vtKey, layer, it[1]), 0.0, minZoom))
                }
                GeomType.LINE -> longestSegment(f)?.let { s ->
                    out.add(Label(text, LabelKind.WATER, 0, wx(vtKey, layer, s.mx), wy(vtKey, layer, s.my), s.angle, minZoom))
                }
                else -> {}
            }
        }
    }

    private fun roadLabels(layer: Layer, vtKey: TileKey, out: MutableList<Label>) {
        for (f in layer.features) {
            if (f.type != GeomType.LINE) continue
            val text = nameOf(f) ?: continue
            val cls = f.tags["class"] as? String
            val minor = cls == "minor" || cls == "service" || cls == "track" || cls == "path" || cls == "busway"
            val s = longestSegment(f) ?: continue
            val rank = when (cls) {
                "motorway" -> 1
                "trunk" -> 2
                "primary" -> 3
                "secondary" -> 4
                "tertiary" -> 5
                else -> 6
            }
            out.add(
                Label(
                    text, if (minor) LabelKind.ROAD_MINOR else LabelKind.ROAD, rank,
                    wx(vtKey, layer, s.mx), wy(vtKey, layer, s.my), s.angle, if (minor) 16 else 14,
                ),
            )
        }
    }

    private fun poiList(layer: Layer, vtKey: TileKey, out: MutableList<Poi>) {
        for (f in layer.features) {
            if (f.type != GeomType.POINT) continue
            val icon = PoiIcons.iconFor(f.tags["class"] as? String, f.tags["subclass"] as? String) ?: continue
            val p = f.geometry.firstOrNull() ?: continue
            val rank = (f.tags["rank"] as? Number)?.toInt()
            val minZoom = when {
                rank == null -> 18
                rank <= 10 -> 16
                rank <= 25 -> 17
                else -> 18
            }
            out.add(Poi(icon, nameOf(f) ?: "", wx(vtKey, layer, p[0]), wy(vtKey, layer, p[1]), minZoom, rank ?: Int.MAX_VALUE))
        }
    }

    private class Segment(val mx: Int, val my: Int, val angle: Double)

    private fun longestSegment(f: Feature): Segment? {
        var best: Segment? = null
        var bestLen = -1.0
        for (path in f.geometry) {
            var i = 0
            while (i + 3 < path.size) {
                val dx = (path[i + 2] - path[i]).toDouble()
                val dy = (path[i + 3] - path[i + 1]).toDouble()
                val len = hypot(dx, dy)
                if (len > bestLen) {
                    bestLen = len
                    var a = Math.toDegrees(atan2(dy, dx))
                    if (a > 90.0) a -= 180.0
                    if (a <= -90.0) a += 180.0
                    best = Segment((path[i] + path[i + 2]) / 2, (path[i + 1] + path[i + 3]) / 2, a)
                }
                i += 2
            }
        }
        return best
    }

    private fun wx(key: TileKey, layer: Layer, u: Int): Double = (key.x + u.toDouble() / layer.extent) / (1 shl key.z)

    private fun wy(key: TileKey, layer: Layer, v: Int): Double = (key.y + v.toDouble() / layer.extent) / (1 shl key.z)
}

object LabelFilter {
    fun apply(labels: List<Label>, showStreetNames: Boolean): List<Label> =
        if (showStreetNames) labels else labels.filter { it.kind != LabelKind.ROAD && it.kind != LabelKind.ROAD_MINOR }
}

object PoiIcons {
    private val groups = mapOf(
        "meat" to listOf("restaurant", "fast_food", "food_court"),
        "bread" to listOf("bakery"),
        "apple" to listOf("grocery", "supermarket", "greengrocer", "convenience"),
        "mug" to listOf("cafe", "bar", "pub", "beer", "ice_cream"),
        "emerald" to listOf("shop", "clothing_store", "gift", "jewelry", "mall", "department_store"),
        "gold_ingot" to listOf("bank", "atm", "money"),
        "coal" to listOf("fuel"),
        "potion" to listOf("pharmacy", "hospital", "doctors", "dentist", "clinic"),
        "book" to listOf("school", "college", "university", "library", "kindergarten"),
        "sapling" to listOf("park", "garden", "playground", "zoo"),
        "minecart" to listOf("railway", "bus", "parking", "car", "bicycle_rental", "ferry_terminal"),
        "bed" to listOf("lodging", "hotel", "hostel", "motel"),
        "pickaxe" to listOf("hardware", "doityourself", "car_repair"),
        "compass" to listOf("attraction", "museum", "monument", "viewpoint", "castle", "art_gallery"),
        "bell" to listOf("place_of_worship"),
        "fish" to listOf("seafood", "marina", "harbor"),
    )

    private val byKey: Map<String, String> = buildMap {
        for ((icon, keys) in groups) for (k in keys) put(k, icon)
    }

    private val excludedSubclasses = setOf(
        "bus_stop", "platform", "stop_position", "tram_stop", "bicycle_parking", "motorcycle_parking",
        "bench", "waste_basket", "vending_machine", "toilets", "taxi",
    )

    fun iconFor(cls: String?, subclass: String?): String? {
        if (subclass != null && subclass in excludedSubclasses) return null
        if (cls == "parking" && subclass != "parking") return null
        return (subclass?.let { byKey[it] }) ?: (cls?.let { byKey[it] })
    }
}
