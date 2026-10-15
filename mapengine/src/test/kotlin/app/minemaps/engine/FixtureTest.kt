package app.minemaps.engine

import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.labels.LabelExtractor
import app.minemaps.engine.labels.LabelKind
import app.minemaps.engine.mvt.GeomType
import app.minemaps.engine.mvt.MvtDecoder
import app.minemaps.engine.raster.BlockGrid
import app.minemaps.engine.raster.GridRasterizer
import app.minemaps.engine.style.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FixtureTest {
    private val vtKey = TileKey(14, 8296, 5636)
    private val tile by lazy { MvtDecoder.decode(TestSupport.fixtureBytes()) }

    @Test
    fun hasExpectedLayers() {
        for (name in listOf("water", "transportation", "building", "poi")) {
            assertTrue(tile.layers[name]?.features?.isNotEmpty() == true, "layer $name missing, have ${tile.layers.keys}")
        }
        assertTrue(tile.layers["landcover"] != null || tile.layers["landuse"] != null)
    }

    @Test
    fun coordinatesStayNearTheTile() {
        for (layer in tile.layers.values) {
            val lo = -layer.extent / 8
            val hi = layer.extent * 9 / 8
            for (f in layer.features.filter { it.type != GeomType.POINT }) for (ring in f.geometry) {
                for (v in ring) assertTrue(v in lo..hi, "${layer.name}: $v outside [$lo,$hi]")
                assertTrue(ring.size % 2 == 0)
                if (f.type == GeomType.POLYGON) assertTrue(ring.size >= 6)
            }
        }
    }

    private fun proportions(grid: BlockGrid): Map<Block, Double> {
        val counts = IntArray(Block.entries.size)
        for (y in 0 until 256) for (x in 0 until 256) counts[grid.clsAt(x, y)]++
        return Block.entries.associateWith { counts[it.id] / 65536.0 }
    }

    @Test
    fun championDeMarsGridHasAllMajorClasses() {
        val gridKey = TileKey(16, 33185, 22545)
        val grid = GridRasterizer.rasterize(tile, vtKey, gridKey)
        val p = proportions(grid)
        println("proportions " + p.filterValues { it > 0 }.entries.joinToString { "${it.key}=%.1f%%".format(it.value * 100) })
        assertTrue(p.getValue(Block.WATER) > 0.01, "water ${p[Block.WATER]}")
        assertTrue(p.getValue(Block.BUILDING) > 0.01, "building ${p[Block.BUILDING]}")
        val roads = p.getValue(Block.ROAD_MAJOR) + p.getValue(Block.ROAD) + p.getValue(Block.ROAD_MINOR)
        assertTrue(roads > 0.01, "roads $roads")
        assertTrue(p.getValue(Block.PARK) + p.getValue(Block.GRASS) > 0.01)
        assertTrue(p.getValue(Block.PARK) > 0.01, "park ${p[Block.PARK]}")
        assertTrue(p.getValue(Block.GRASS) > 0.01, "grass ${p[Block.GRASS]}")
    }

    @Test
    fun overviewGridAtZ14IsPlausible() {
        val grid = GridRasterizer.rasterize(tile, vtKey, vtKey)
        val p = proportions(grid)
        assertTrue(p.getValue(Block.WATER) > 0.005)
        assertTrue(p.getValue(Block.BUILDING) > 0.05)
    }

    @Test
    fun labelsAndPois() {
        val labels = LabelExtractor.extract(tile, vtKey)
        assertTrue(labels.pois.isNotEmpty())
        assertTrue(labels.labels.any { it.kind == LabelKind.ROAD || it.kind == LabelKind.ROAD_MINOR })
        for (l in labels.labels) {
            assertTrue(l.wx >= 8296.0 / 16384 - 0.0001 && l.wx <= 8297.0 / 16384 + 0.0001, "wx ${l.wx}")
            assertTrue(l.angleDeg > -90.0 && l.angleDeg <= 90.0)
        }
        for (p in labels.pois) assertTrue(p.wy >= 5636.0 / 16384 - 0.0001 && p.wy <= 5637.0 / 16384 + 0.0001)
        assertEquals(labels.pois.size, labels.pois.map { it.icon }.size)
    }

    @Test
    fun inspectFindsSomethingNearTheEiffelTower() {
        val wx = app.minemaps.engine.geo.Mercator.lonToX(2.2945)
        val wy = app.minemaps.engine.geo.Mercator.latToY(48.8584)
        val r = app.minemaps.engine.inspect.FeatureInspector.inspect(tile, vtKey, wx, wy, 18.0)
        assertTrue(r != null && r.title.isNotBlank(), "inspect returned $r")
        val seine = app.minemaps.engine.inspect.FeatureInspector.inspect(tile, vtKey, app.minemaps.engine.geo.Mercator.lonToX(2.2900), app.minemaps.engine.geo.Mercator.latToY(48.8625), 16.0)
        println("inspect eiffel=${r?.title}/${r?.subtitle} seine=${seine?.title}/${seine?.subtitle}")
    }
}
