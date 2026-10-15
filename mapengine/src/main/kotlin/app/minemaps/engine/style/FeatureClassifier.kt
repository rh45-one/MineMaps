package app.minemaps.engine.style

import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.mvt.Feature
import app.minemaps.engine.mvt.GeomType
import app.minemaps.engine.util.Hash
import kotlin.math.abs

class RenderOp(val block: Block, val phase: Int, val widthM: Double) {
    val isFill: Boolean get() = widthM <= 0.0
}

object FeatureClassifier {
    const val PHASE_LANDUSE = 0
    const val PHASE_LANDCOVER = 1
    const val PHASE_WATER = 2
    const val PHASE_WATERWAY = 3
    const val PHASE_AEROWAY = 4
    const val PHASE_TRANSPORT = 5
    const val PHASE_BUILDING = 6

    val LAYER_ORDER = listOf("landuse", "landcover", "water", "waterway", "aeroway", "transportation", "building")

    private val PARK_SUBCLASSES = setOf("park", "garden", "golf_course", "recreation_ground", "village_green", "playground")
    private val PARK_LANDUSE = setOf("pitch", "playground", "stadium", "theme_park", "zoo")
    private val INDUSTRIAL_LANDUSE = setOf("commercial", "industrial", "retail", "railway", "garages", "quarry")
    private val RAIL_SUBCLASSES = setOf("rail", "narrow_gauge", "light_rail", "preserved")
    private const val MAT_BRICK = 0
    private const val MAT_OAK = 1
    private const val MAT_SPRUCE = 2
    private const val MAT_STONE = 3
    private const val MAT_CONCRETE = 4
    private const val MAT_TERRACOTTA = 5

    const val LARGE_BUILDING_AREA_M2 = 1500.0

    fun classify(layer: String, f: Feature, zoom: Int): RenderOp? {
        val cls = f.tags["class"] as? String
        val sub = f.tags["subclass"] as? String
        val polygon = f.type == GeomType.POLYGON
        val line = f.type == GeomType.LINE
        return when (layer) {
            "landuse" -> if (!polygon) null else when (cls) {
                in PARK_LANDUSE -> RenderOp(Block.PARK, PHASE_LANDUSE, 0.0)
                in INDUSTRIAL_LANDUSE -> RenderOp(Block.INDUSTRIAL, PHASE_LANDUSE, 0.0)
                "cemetery" -> RenderOp(Block.CEMETERY, PHASE_LANDUSE, 0.0)
                "residential", "suburb", "neighbourhood" -> RenderOp(Block.RESIDENTIAL, PHASE_LANDUSE, 0.0)
                else -> null
            }
            "landcover" -> if (!polygon) null else {
                val block = when (cls) {
                    "grass" -> if (sub in PARK_SUBCLASSES) Block.PARK else Block.GRASS
                    "wood" -> Block.FOREST
                    "sand" -> Block.SAND
                    "farmland" -> Block.FARMLAND
                    "wetland" -> Block.WETLAND
                    "ice" -> Block.SNOW
                    "rock" -> Block.ROCK
                    else -> null
                }
                block?.let { RenderOp(it, PHASE_LANDCOVER, 0.0) }
            }
            "water" -> if (!polygon || f.tags["brunnel"] == "tunnel") null else RenderOp(Block.WATER, PHASE_WATER, 0.0)
            "waterway" -> if (!line || f.tags["brunnel"] == "tunnel") null else {
                val (w, minZ) = when (cls) {
                    "river" -> 10.0 to 0
                    "canal" -> 8.0 to 12
                    "stream" -> 2.0 to 14
                    "drain", "ditch" -> 1.0 to 15
                    else -> return null
                }
                if (zoom < minZ) null else RenderOp(Block.WATER, PHASE_WATERWAY, w)
            }
            "aeroway" -> when {
                polygon && (cls == "runway" || cls == "taxiway" || cls == "apron") -> RenderOp(Block.RUNWAY, PHASE_AEROWAY, 0.0)
                line && cls == "runway" -> RenderOp(Block.RUNWAY, PHASE_AEROWAY, 40.0)
                line && cls == "taxiway" -> RenderOp(Block.RUNWAY, PHASE_AEROWAY, 18.0)
                else -> null
            }
            "transportation" -> classifyTransport(f, cls, sub, polygon, line, zoom)
            "building" -> if (polygon && zoom >= 14) RenderOp(Block.BUILDING, PHASE_BUILDING, 0.0) else null
            else -> null
        }
    }

    private fun classifyTransport(f: Feature, cls: String?, sub: String?, polygon: Boolean, line: Boolean, zoom: Int): RenderOp? {
        if (cls == null || f.tags["brunnel"] == "tunnel") return null
        if (polygon) {
            val minZ = if (cls == "pier") 14 else 15
            return if ((cls == "path" || cls == "pier" || cls == "pedestrian") && zoom >= minZ) {
                RenderOp(Block.PEDESTRIAN, PHASE_TRANSPORT, 0.0)
            } else {
                null
            }
        }
        if (!line) return null
        val (block, w, minZ) = when (cls) {
            "motorway" -> Triple(Block.ROAD_MAJOR, 16.0, 0)
            "trunk" -> Triple(Block.ROAD_MAJOR, 14.0, 0)
            "primary" -> Triple(Block.ROAD, 12.0, 0)
            "secondary" -> Triple(Block.ROAD, 10.0, 12)
            "tertiary" -> Triple(Block.ROAD, 9.0, 12)
            "minor" -> Triple(Block.ROAD_MINOR, 7.0, 14)
            "service" -> Triple(Block.ROAD_MINOR, 4.0, 15)
            "busway" -> Triple(Block.ROAD_MINOR, 6.0, 14)
            "track" -> Triple(Block.PATH, 3.0, 15)
            "bridleway" -> Triple(Block.PATH, 2.0, 15)
            "path" -> if (sub == "pedestrian") Triple(Block.PEDESTRIAN, 4.0, 15) else Triple(Block.PATH, 2.0, 15)
            "pier" -> Triple(Block.PEDESTRIAN, 4.0, 14)
            "rail" -> if (sub in RAIL_SUBCLASSES) Triple(Block.RAIL, 3.0, 14) else return null
            else -> return null
        }
        return if (zoom < minZ) null else RenderOp(block, PHASE_TRANSPORT, w)
    }

    fun widthInCells(widthM: Double, metersPerCell: Double): Int =
        maxOf(1, Math.round(widthM / metersPerCell).toInt())

    fun heightBucket(f: Feature): Int {
        val h = (f.tags["render_height"] as? Number)?.toDouble() ?: return 0
        return when {
            h < 8 -> 0
            h < 20 -> 1
            h < 50 -> 2
            else -> 3
        }
    }

    fun footprintAreaM2(f: Feature, extent: Int, vtKey: TileKey): Double {
        var twice = 0.0
        for (ring in f.geometry) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                twice += ring[2 * j].toDouble() * ring[2 * i + 1] - ring[2 * i].toDouble() * ring[2 * j + 1]
                j = i
            }
        }
        val blocksPerTile = 256.0 * (1 shl (Mercator.BLOCK_ZOOM - vtKey.z)).toDouble()
        val metersPerUnit = Mercator.metersPerBlock(vtKey.centerLat()) * blocksPerTile / extent
        return abs(twice) / 2.0 * metersPerUnit * metersPerUnit
    }

    fun buildingAux(f: Feature, extent: Int, vtKey: TileKey): Int {
        val seed: Long = if (f.id != 0L) {
            f.id
        } else {
            val g = f.geometry.firstOrNull()
            val gx = vtKey.x.toLong() * extent + (g?.getOrNull(0) ?: 0)
            val gy = vtKey.y.toLong() * extent + (g?.getOrNull(1) ?: 0)
            gx * 73856093L xor gy * 19349663L
        }
        val percent = ((Hash.mix64(seed).ushr(8) and 0x7fffffffL) % 100).toInt()
        val material = if (footprintAreaM2(f, extent, vtKey) > LARGE_BUILDING_AREA_M2) {
            when {
                percent < 40 -> MAT_STONE
                percent < 80 -> MAT_CONCRETE
                else -> MAT_BRICK
            }
        } else {
            when {
                percent < 30 -> MAT_BRICK
                percent < 50 -> MAT_OAK
                percent < 65 -> MAT_SPRUCE
                percent < 85 -> MAT_TERRACOTTA
                else -> MAT_STONE
            }
        }
        return material or (heightBucket(f) shl Block.AUX_HEIGHT_SHIFT)
    }
}
