package app.minemaps.engine

import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.raster.BlockGrid
import app.minemaps.engine.render.Terrain
import app.minemaps.engine.render.TileCompositor
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.style.Block
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TerrainTest {
    private fun fill(g: BlockGrid, b: Block, xs: IntRange, ys: IntRange) {
        for (y in ys) for (x in xs) g.set(x, y, b)
    }

    private fun allOf(b: Block) = BlockGrid().also { fill(it, b, -4..259, -4..259) }

    @Test
    fun canopyIsDeterministicAndSeamlessAcrossNeighbouringTiles() {
        for (block in listOf(Block.FOREST, Block.PARK)) {
            val a = allOf(block)
            val b = allOf(block)
            val n = BlockGrid.TILE
            val w = n + 2
            val ca = Terrain.canopy(a, 7 * n, 9 * n, 0, 0, n)
            val cb = Terrain.canopy(b, 8 * n, 9 * n, 0, 0, n)
            for (y in 0 until n) {
                assertEquals(ca[(y + 1) * w + n + 1], cb[(y + 1) * w + 1], "east edge of A vs west edge of B at row $y")
                assertEquals(cb[(y + 1) * w + 0], ca[(y + 1) * w + n], "west margin of B vs last column of A at row $y")
            }
            val again = Terrain.canopy(a, 7 * n, 9 * n, 0, 0, n)
            assertTrue(ca.contentEquals(again))
            val share = ca.count { it }.toDouble() / ca.size
            if (block == Block.FOREST) assertTrue(share in 0.25..0.85, "forest canopy share $share") else assertTrue(share in 0.03..0.5, "park canopy share $share")
        }
    }

    @Test
    fun sameGlobalBlockGivesSameResultFromDifferentRegionOrigins() {
        val g = allOf(Block.FOREST)
        val big = Terrain.canopy(g, 5000, 7000, 0, 0, BlockGrid.TILE)
        val sub = Terrain.canopy(g, 5000, 7000, 64, 32, 64)
        for (y in 0 until 64) for (x in 0 until 64) {
            assertEquals(big[(32 + y + 1) * (BlockGrid.TILE + 2) + 64 + x + 1], sub[(y + 1) * 66 + x + 1])
        }
    }

    @Test
    fun canopyNeverAppearsOnOtherClasses() {
        val g = allOf(Block.FOREST)
        fill(g, Block.ROAD, 0..255, 100..110)
        fill(g, Block.WATER, 50..80, 0..255)
        fill(g, Block.BUILDING, 200..230, 0..255)
        val c = Terrain.canopy(g, 0, 0, 0, 0, BlockGrid.TILE)
        var forestCanopy = 0
        for (y in 0 until 256) for (x in 0 until 256) {
            val on = c[(y + 1) * 258 + x + 1]
            if (!Terrain.isTreeBlock(g.clsAt(x, y))) assertFalse(on, "canopy on class ${g.clsAt(x, y)} at $x,$y") else if (on) forestCanopy++
        }
        assertTrue(forestCanopy > 1000)
    }

    @Test
    fun waterDepthByDistanceOnALake() {
        val g = allOf(Block.GRASS)
        fill(g, Block.WATER, 100..108, 100..108)
        val d = Terrain.waterDepth(g)!!
        fun depth(x: Int, y: Int) = d[(y + BlockGrid.MARGIN) * BlockGrid.SIZE + x + BlockGrid.MARGIN].toInt()
        assertEquals(1, depth(100, 104))
        assertEquals(1, depth(100, 100))
        assertEquals(2, depth(101, 104))
        assertEquals(3, depth(102, 104))
        assertEquals(4, depth(104, 104))
        assertEquals(0, depth(99, 104))
        assertEquals(1.10f, Terrain.depthFactor(depth(100, 104)))
        assertEquals(1.00f, Terrain.depthFactor(depth(101, 104)))
        assertEquals(0.93f, Terrain.depthFactor(depth(102, 104)))
        assertEquals(0.87f, Terrain.depthFactor(depth(104, 104)))
        assertEquals(null, Terrain.waterDepth(allOf(Block.GRASS)))
    }

    @Test
    fun lakeCentreIsDarkerThanItsEdgeInTheComposedTile() {
        val atlas = TestSupport.atlas
        val g = allOf(Block.GRASS)
        fill(g, Block.WATER, 100..140, 100..140)
        val px = TileCompositor(atlas).compose(g, TileKey(16, 5, 5), false)
        fun lum(x0: Int, y0: Int, x1: Int, y1: Int): Double {
            var s = 0.0
            var n = 0
            for (y in y0..y1) for (x in x0..x1) { s += TestSupport.luminance(px[y * 256 + x]); n++ }
            return s / n
        }
        assertTrue(lum(118, 118, 122, 122) < lum(100, 110, 100, 130) * 0.9)
    }

    @Test
    fun treesAppearAtTexturedZoomAndRaiseShading() {
        val atlas = TestSupport.atlas
        val comp = TileCompositor(atlas)
        val g = allOf(Block.FOREST)
        val px = comp.compose(g, TileKey(18, 4 * 20, 4 * 20), false)
        val leaves = atlas.averageColor("leaves")
        val grass = atlas.averageColor("grass")
        fun green(p: Int) = ((p shr 8) and 0xff) - ((p shr 16) and 0xff)
        assertTrue(px.any { green(it) > 0 })
        assertTrue(leaves != grass)
        val z15 = comp.compose(g, TileKey(15, 3, 3), false)
        assertEquals(256 * 256, z15.size)
    }

    @Test
    fun tintMultipliesAndKeepsOutlineDark() {
        val atlas = TestSupport.atlas
        val player = atlas.icon("player")!!
        val white = TextureAtlas.tintIcon(player, 0xFFFFFFFF.toInt())
        assertTrue(white.contentEquals(player))
        assertEquals(0xFFE53935.toInt(), TextureAtlas.tintIcon(intArrayOf(0xFFFFFFFF.toInt()), 0xFFE53935.toInt())[0])
        for (tint in listOf(0xFFE53935.toInt(), 0xFF7CB342.toInt(), 0xFFEC407A.toInt(), 0xFF1E88E5.toInt())) {
            val t = TextureAtlas.tintIcon(player, tint)
            var outline = 0
            for (i in player.indices) {
                val p = player[i]
                if ((p ushr 24) == 0) continue
                val maxIn = maxOf((p shr 16) and 0xff, (p shr 8) and 0xff, p and 0xff)
                if (maxIn <= 0x20) {
                    outline++
                    val q = t[i]
                    assertTrue(maxOf((q shr 16) and 0xff, (q shr 8) and 0xff, q and 0xff) <= 0x20)
                }
            }
            assertTrue(outline > 5, "player icon should have a dark outline")
        }
    }

    @Test
    fun bookmarkIconsComeFromAtlasJson() {
        val icons = TestSupport.atlas.bookmarkIcons
        assertEquals("pin", icons.first())
        assertTrue(icons.size >= 30)
        assertTrue(icons.all { TestSupport.atlas.icon(it) != null })
        assertEquals(setOf("player"), TestSupport.atlas.tintableIcons)
    }
}
