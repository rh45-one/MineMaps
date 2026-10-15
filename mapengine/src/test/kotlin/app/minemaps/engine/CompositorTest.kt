package app.minemaps.engine

import app.minemaps.engine.TestSupport.luminance
import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.raster.BlockGrid
import app.minemaps.engine.render.TileCompositor
import app.minemaps.engine.style.Block
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CompositorTest {
    private val atlas = TestSupport.atlas
    private val compositor = TileCompositor(atlas)

    private fun fill(grid: BlockGrid, block: Block, xs: IntRange, ys: IntRange, aux: Int = 0) {
        for (y in ys) for (x in xs) grid.set(x, y, block, aux)
    }

    private fun gridOf(block: Block): BlockGrid {
        val g = BlockGrid()
        fill(g, block, -4..259, -4..259)
        return g
    }

    private fun mean(pixels: IntArray, xs: IntRange, ys: IntRange): Double {
        var sum = 0.0
        var n = 0
        for (y in ys) for (x in xs) {
            sum += luminance(pixels[y * 256 + x])
            n++
        }
        return sum / n
    }

    @Test
    fun mipsAverageCorrectly() {
        val checker = IntArray(16) { i -> if ((i % 4 + i / 4) % 2 == 0) 0xff000000.toInt() else 0xffffffff.toInt() }
        val half = TextureAtlas.downsample(checker, 4, 2)
        assertTrue(half.all { it == 0xff808080.toInt() }, half.joinToString { it.toString(16) })
        assertContentEquals(intArrayOf(0xff808080.toInt()), TextureAtlas.downsample(checker, 4, 1))

        for (name in atlas.textureNames()) {
            val m16 = atlas.mip(name, 0, 16)
            val m1 = atlas.mip(name, 0, 1)[0]
            for ((shift, chan) in listOf(16 to "r", 8 to "g", 0 to "b")) {
                val avg = m16.sumOf { (it shr shift) and 0xff } / 256.0
                assertEquals(avg, ((m1 shr shift) and 0xff).toDouble(), 1.0, "$name $chan")
            }
            val m2 = atlas.mip(name, 0, 2)
            val m2avg = m2.sumOf { (it shr 8) and 0xff } / 4.0
            assertEquals(m2avg, ((m1 shr 8) and 0xff).toDouble(), 1.0, "$name mip2->1")
            assertEquals(1, atlas.mip(name, 0, 1).size)
            assertEquals(64, atlas.mip(name, 0, 8).size)
        }
        assertEquals(256, atlas.icon("apple")!!.size)
        assertTrue(atlas.variantCount("park") == 4)
    }

    @Test
    fun allWaterGridOnlyProducesWaterColours() {
        val avg = atlas.averageColor("water")
        for (key in listOf(TileKey(14, 1, 1), TileKey(16, 5, 5))) {
            val px = compositor.compose(gridOf(Block.WATER), key, false)
            for (p in px) {
                assertEquals(0xff, p ushr 24)
                for (shift in listOf(16, 8, 0)) {
                    val v = (p shr shift) and 0xff
                    val a = (avg shr shift) and 0xff
                    assertTrue(v >= a * 0.87 * 0.94 - 1.5 && v <= a * 0.87 * 1.06 + 1.5, "channel $shift: $v vs $a")
                }
                assertTrue((p and 0xff) > ((p shr 16) and 0xff), "water should be blue-ish")
            }
        }
        val textured = compositor.compose(gridOf(Block.WATER), TileKey(18, 4, 4), false)
        assertTrue(textured.all { (it and 0xff) > ((it shr 16) and 0xff) })
    }

    @Test
    fun buildingSouthEdgeIsDarkerThanInterior() {
        val g = gridOf(Block.GRASS)
        fill(g, Block.BUILDING, 100..170, 100..170, aux = 3)
        val overview = compositor.compose(g, TileKey(16, 10, 10), false)
        val edge = mean(overview, 110..160, 170..170)
        val interior = mean(overview, 110..160, 130..140)
        assertTrue(edge < interior * 0.85, "overview edge=$edge interior=$interior")
        val north = mean(overview, 110..160, 100..100)
        assertTrue(north > interior * 1.03, "north=$north interior=$interior")

        val textured = compositor.compose(g, TileKey(18, 4 * 10, 4 * 10), false)
        val edgeT = mean(textured, 4 * 110..4 * 160, 4 * 40 - 3..4 * 40 + 0)
        assertEquals(256, textured.size / 256)
        val g2 = gridOf(Block.GRASS)
        fill(g2, Block.BUILDING, 10..20, 10..20, aux = 0)
        val t2 = compositor.compose(g2, TileKey(18, 0, 0), false)
        val south = mean(t2, 40..80, 4 * 20..4 * 20 + 3)
        val inner = mean(t2, 40..80, 4 * 14..4 * 14 + 3)
        assertTrue(south < inner * 0.9, "textured south=$south inner=$inner")
        assertTrue(edgeT >= 0.0)
    }

    @Test
    fun shadowFallsSouthEastOfBuildings() {
        val g = gridOf(Block.GRASS)
        fill(g, Block.BUILDING, 100..110, 100..110, aux = 2 shl 4)
        val px = compositor.compose(g, TileKey(16, 3, 3), false)
        val shadowed = mean(px, 112..113, 112..113)
        val plain = mean(px, 140..145, 140..145)
        assertTrue(shadowed < plain * 0.9, "shadowed=$shadowed plain=$plain")
        val noShadowZoom = compositor.compose(g, TileKey(15, 3, 3), false)
        assertTrue(mean(noShadowZoom, 112..113, 112..113) > plain * 0.9)
    }

    @Test
    fun nightIsDarker() {
        val g = gridOf(Block.GRASS)
        fill(g, Block.ROAD, 0..255, 100..120)
        for (key in listOf(TileKey(15, 1, 1), TileKey(17, 8, 8))) {
            val day = compositor.compose(g, key, false)
            val night = compositor.compose(g, key, true)
            assertTrue(mean(night, 0..255, 0..255) < mean(day, 0..255, 0..255) * 0.7)
            for (i in day.indices) {
                assertTrue((night[i] and 0xff) >= (day[i] and 0xff) * 0.6 && ((night[i] shr 16) and 0xff) <= ((day[i] shr 16) and 0xff))
            }
        }
    }

    @Test
    fun determinism() {
        val g = gridOf(Block.PARK)
        fill(g, Block.FOREST, 20..80, 20..80)
        fill(g, Block.CEMETERY, 120..200, 120..200)
        for (key in listOf(TileKey(16, 7, 9), TileKey(19, 8 * 7 + 3, 8 * 9 + 2))) {
            val a = compositor.compose(g, key, false)
            val b = TileCompositor(atlas).compose(g, key, false)
            assertContentEquals(a, b)
        }
        assertNotEquals(
            compositor.compose(g, TileKey(16, 7, 9), false).toList(),
            compositor.compose(g, TileKey(16, 8, 9), false).toList(),
        )
    }

    @Test
    fun variationIsKeyedOnGlobalCoordinates() {
        val g = gridOf(Block.GRASS)
        val a = compositor.compose(g, TileKey(17, 2 * 40 + 1, 2 * 25), false)
        val shifted = BlockGrid()
        fill(shifted, Block.GRASS, -4..259, -4..259)
        val b = compositor.compose(shifted, TileKey(17, 2 * 40 + 1, 2 * 25), false)
        assertContentEquals(a, b)
        val other = compositor.compose(g, TileKey(17, 2 * 40 + 1, 2 * 25 + 1), false)
        assertNotEquals(a.toList(), other.toList())
    }

    @Test
    fun buildingSpanningTileEdgeIsSeamless() {
        val left = gridOf(Block.GRASS)
        val right = gridOf(Block.GRASS)
        fill(left, Block.BUILDING, 240..259, 60..200)
        fill(right, Block.BUILDING, -4..19, 60..200)
        val l = compositor.compose(left, TileKey(16, 100, 50), false)
        val r = compositor.compose(right, TileKey(16, 101, 50), false)
        val lEdge = mean(l, 255..255, 80..180)
        val lInner = mean(l, 250..252, 80..180)
        assertTrue(lEdge > lInner * 0.95 && lEdge < lInner * 1.05, "left edge=$lEdge inner=$lInner")
        val rEdge = mean(r, 0..0, 80..180)
        val rInner = mean(r, 3..5, 80..180)
        assertTrue(rEdge > rInner * 0.95 && rEdge < rInner * 1.05, "right edge=$rEdge inner=$rInner")

        val clipped = gridOf(Block.GRASS)
        fill(clipped, Block.BUILDING, 240..255, 60..200)
        val c = compositor.compose(clipped, TileKey(16, 100, 50), false)
        assertTrue(mean(c, 255..255, 80..180) < mean(c, 250..252, 80..180) * 0.92)
    }

    @Test
    fun parkFlowersAppearAtTexturedZoomOnly() {
        val g = gridOf(Block.PARK)
        val overview = compositor.compose(g, TileKey(16, 1, 1), false)
        val avg = atlas.averageColor("park")
        assertTrue(overview.all { (it shr 16 and 0xff) in 0..255 })
        val textured = compositor.compose(g, TileKey(20, 5, 5), false)
        val flowerReds = textured.count { ((it shr 16) and 0xff) > 180 && ((it shr 8) and 0xff) < 100 }
        assertTrue(flowerReds > 0, "expected red flower pixels in textured park, avg=${avg.toString(16)}")
    }

    @Test
    fun residentialTintsOnlyAtLowZoom() {
        val res = gridOf(Block.RESIDENTIAL)
        val grass = gridOf(Block.GRASS)
        val key13 = TileKey(13, 3, 3)
        val key15 = TileKey(15, 3, 3)
        val expected = listOf("stone", "roof_brick", "roof_concrete", "grass").map { atlas.averageColor(it) }
        val avgR = expected.sumOf { (it shr 16) and 0xff } / 4
        val low = compositor.compose(res, key13, false)
        assertEquals(avgR.toDouble(), low.map { (it shr 16) and 0xff }.average(), 4.0)
        assertTrue(low.map { (it shr 16) and 0xff }.average() != compositor.compose(grass, key13, false).map { (it shr 16) and 0xff }.average())
        assertContentEquals(compositor.compose(grass, key15, false), compositor.compose(res, key15, false))
        assertContentEquals(compositor.compose(grass, TileKey(18, 5, 5), false), compositor.compose(res, TileKey(18, 5, 5), false))
    }
}
