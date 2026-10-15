package app.minemaps.engine

import app.minemaps.engine.TestSupport.line
import app.minemaps.engine.TestSupport.polygon
import app.minemaps.engine.TestSupport.rect
import app.minemaps.engine.TestSupport.tile
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.raster.BlockGrid
import app.minemaps.engine.raster.GridRasterizer
import app.minemaps.engine.style.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RasterizerTest {
    private val vt = TileKey(14, 8296, 5636)
    private val wood = mapOf<String, Any>("class" to "wood")

    private fun BlockGrid.count(b: Block, xs: IntRange = 0..255, ys: IntRange = 0..255): Int {
        var n = 0
        for (y in ys) for (x in xs) if (clsAt(x, y) == b.id) n++
        return n
    }

    @Test
    fun axisAlignedSquareFillsExactCells() {
        val t = tile("landcover", polygon(rect(1600, 800, 3200, 2400), tags = wood))
        val g = GridRasterizer.rasterize(t, vt, vt)
        assertEquals(100 * 100, g.count(Block.FOREST))
        for (y in 50..149) for (x in 100..199) assertEquals(Block.FOREST.id, g.clsAt(x, y), "cell $x,$y")
        assertEquals(Block.GRASS.id, g.clsAt(99, 100))
        assertEquals(Block.GRASS.id, g.clsAt(200, 100))
        assertEquals(Block.GRASS.id, g.clsAt(150, 49))
        assertEquals(Block.GRASS.id, g.clsAt(150, 150))
    }

    @Test
    fun holeStaysEmpty() {
        val t = tile("landcover", polygon(rect(1600, 800, 3200, 2400), rect(2000, 1200, 2800, 2000), tags = wood))
        val g = GridRasterizer.rasterize(t, vt, vt)
        assertEquals(100 * 100 - 50 * 50, g.count(Block.FOREST))
        assertEquals(Block.GRASS.id, g.clsAt(150, 100))
        assertEquals(Block.FOREST.id, g.clsAt(110, 60))
        assertEquals(Block.FOREST.id, g.clsAt(180, 140))
    }

    @Test
    fun lineWidthInCells() {
        val t = tile("transportation", line(intArrayOf(0, 2048, 4096, 2048), mapOf("class" to "primary")))
        val g = GridRasterizer.rasterize(t, vt, vt)
        val mpc = Mercator.metersPerBlock(vt.centerLat()) * 4
        val expected = Math.round(12.0 / mpc).toInt()
        assertEquals(2, expected)
        assertEquals(expected, g.count(Block.ROAD, 50..50, 0..255))
        assertEquals(expected * 256, g.count(Block.ROAD))
        assertEquals(Block.ROAD.id, g.clsAt(50, 127))
        assertEquals(Block.ROAD.id, g.clsAt(50, 128))
    }

    @Test
    fun roadsAreSkippedBelowTheirMinimumZoom() {
        val t = tile("transportation", line(intArrayOf(0, 2048, 4096, 2048), mapOf("class" to "minor")))
        val z13 = TileKey(13, 4148, 2818)
        assertEquals(0, GridRasterizer.rasterize(t, z13, z13).count(Block.ROAD_MINOR))
        assertTrue(GridRasterizer.rasterize(t, vt, vt).count(Block.ROAD_MINOR) > 0)
    }

    @Test
    fun widerRoadsAreDrawnFirst() {
        val t = tile(
            "transportation",
            line(intArrayOf(0, 2048, 4096, 2048), mapOf("class" to "minor")),
            line(intArrayOf(0, 2048, 4096, 2048), mapOf("class" to "motorway")),
        )
        val g = GridRasterizer.rasterize(t, vt, vt)
        assertEquals(Block.ROAD_MINOR.id, g.clsAt(10, 128))
    }

    @Test
    fun overzoomMapsTheRightQuarter() {
        val child = TileKey(16, 8296 * 4 + 1, 5636 * 4 + 2)
        val t = tile("landcover", polygon(rect(1024, 2048, 1536, 3072), tags = wood))
        val g = GridRasterizer.rasterize(t, vt, child)
        assertEquals(128 * 256, g.count(Block.FOREST))
        assertEquals(Block.FOREST.id, g.clsAt(0, 0))
        assertEquals(Block.FOREST.id, g.clsAt(127, 255))
        assertEquals(Block.GRASS.id, g.clsAt(128, 0))
        val other = GridRasterizer.rasterize(t, vt, TileKey(16, 8296 * 4 + 1, 5636 * 4 + 1))
        assertEquals(0, other.count(Block.FOREST))
        val full = tile("landcover", polygon(rect(1024, 2048, 2048, 3072), tags = wood))
        val whole = GridRasterizer.rasterize(full, vt, child)
        assertEquals(256 * 256, whole.count(Block.FOREST))
        assertEquals(Block.GRASS.id, whole.clsAt(-1, 10))
    }

    @Test
    fun marginCellsComeFromBufferGeometry() {
        val child = TileKey(16, 8296 * 4 + 1, 5636 * 4 + 2)
        val t = tile("landcover", polygon(rect(1000, 2000, 2100, 3100), tags = wood))
        val g = GridRasterizer.rasterize(t, vt, child)
        for (y in -BlockGrid.MARGIN until 256 + BlockGrid.MARGIN) {
            for (x in -BlockGrid.MARGIN until 256 + BlockGrid.MARGIN) {
                assertEquals(Block.FOREST.id, g.clsAt(x, y), "cell $x,$y")
            }
        }
    }

    @Test
    fun roofMaterialDistribution() {
        val vtKey = TileKey(14, 8296, 5636)
        fun share(rect: IntArray, n: Int): IntArray {
            val counts = IntArray(6)
            for (id in 1..n) {
                val f = polygon(rect, tags = emptyMap(), id = id.toLong())
                counts[Block.auxMaterial(app.minemaps.engine.style.FeatureClassifier.buildingAux(f, 4096, vtKey))]++
            }
            return counts
        }
        val n = 20000
        val small = share(rect(0, 0, 60, 60), n)
        val expectedSmall = doubleArrayOf(0.30, 0.20, 0.15, 0.15, 0.0, 0.20)
        for (i in 0 until 6) assertEquals(expectedSmall[i], small[i] / n.toDouble(), 0.015, "small material $i")
        val large = share(rect(0, 0, 1000, 1000), n)
        val expectedLarge = doubleArrayOf(0.20, 0.0, 0.0, 0.40, 0.40, 0.0)
        for (i in 0 until 6) assertEquals(expectedLarge[i], large[i] / n.toDouble(), 0.015, "large material $i")
    }

    @Test
    fun residentialLanduseIsDrawnUnderLandcover() {
        val t = app.minemaps.engine.mvt.VectorTile(
            mapOf(
                "landuse" to app.minemaps.engine.mvt.Layer("landuse", 4096, listOf(polygon(rect(0, 0, 4096, 4096), tags = mapOf("class" to "residential")))),
                "landcover" to app.minemaps.engine.mvt.Layer("landcover", 4096, listOf(polygon(rect(0, 0, 2048, 4096), tags = wood))),
            ),
        )
        val g = GridRasterizer.rasterize(t, vt, vt)
        assertEquals(Block.FOREST.id, g.clsAt(10, 10))
        assertEquals(Block.RESIDENTIAL.id, g.clsAt(200, 10))
        assertEquals(19, Block.RESIDENTIAL.id)
    }

    @Test
    fun buildingsCarryMaterialAndHeight() {
        val small = polygon(rect(1000, 1000, 1060, 1060), tags = mapOf("render_height" to 25.0), id = 12345)
        val big = polygon(rect(2000, 1000, 3000, 2000), tags = mapOf("render_height" to 5L), id = 999)
        val g = GridRasterizer.rasterize(tile("building", small, big), vt, vt)
        val a = g.auxAt(1030 / 16, 1030 / 16)
        assertEquals(Block.BUILDING.id, g.clsAt(1030 / 16, 1030 / 16))
        assertEquals(2, Block.auxHeightBucket(a))
        assertTrue(Block.auxMaterial(a) in intArrayOf(0, 1, 2, 5))
        val b = g.auxAt(2500 / 16, 1500 / 16)
        assertEquals(0, Block.auxHeightBucket(b))
        assertTrue(Block.auxMaterial(b) in intArrayOf(0, 3, 4), "large footprint -> stone/concrete/brick, got ${Block.auxMaterial(b)}")
    }
}
