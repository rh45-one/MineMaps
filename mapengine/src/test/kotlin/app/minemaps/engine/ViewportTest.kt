package app.minemaps.engine

import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.geo.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ViewportTest {
    @Test
    fun screenWorldRoundTrip() {
        val v = Viewport(0.5063736, 0.3440208, 17.3, 1080, 1920, 2.625f)
        for ((sx, sy) in listOf(0.0 to 0.0, 540.0 to 960.0, 1079.0 to 1919.0, 123.4 to 987.6)) {
            val w = v.screenToWorld(sx, sy)
            val s = v.worldToScreen(w.x, w.y)
            assertEquals(sx, s.x, 1e-6)
            assertEquals(sy, s.y, 1e-6)
        }
        val c = v.worldToScreen(v.centerX, v.centerY)
        assertEquals(540.0, c.x, 1e-9)
        assertEquals(960.0, c.y, 1e-9)
    }

    @Test
    fun tileZoomIsClampedFloor() {
        fun tz(z: Double) = Viewport(0.5, 0.5, z, 100, 100, 1f).tileZoom()
        assertEquals(0, tz(-1.0))
        assertEquals(16, tz(16.99))
        assertEquals(20, tz(21.5))
    }

    @Test
    fun tileScreenSize() {
        val v = Viewport(0.5, 0.5, 17.5, 100, 100, 2f)
        assertEquals(256.0 * 2 * Math.pow(2.0, 1.5), v.tileScreenSize(16), 1e-9)
    }

    @Test
    fun visibleTilesKnownSize() {
        val tiles = Viewport(0.5, 0.5, 3.0, 512, 512, 1f).visibleTiles(3)
        assertEquals(4, tiles.size)
        assertEquals(setOf(3, 4), tiles.map { it.x }.toSet())
        assertEquals(setOf(3, 4), tiles.map { it.y }.toSet())
        val offset = Viewport(0.5, 0.5, 3.0, 300, 300, 1f).visibleTiles(3)
        assertEquals(4, offset.size)
        assertEquals(1, Viewport(0.5 + 128.0 / 2048, 0.5 + 128.0 / 2048, 3.0, 256, 256, 1f).visibleTiles(3).size)
    }

    @Test
    fun antimeridianWraps() {
        val v = Viewport(0.9995, 0.5, 6.0, 512, 256, 1f)
        val tiles = v.visibleTiles(6)
        val xs = tiles.map { it.x }.toSet()
        assertTrue(63 in xs, "expected last column in $xs")
        assertTrue(0 in xs, "expected wrap to column 0 in $xs")
        assertTrue(tiles.all { it.x in 0..63 && it.y in 0..63 })
    }

    @Test
    fun yIsClampedAtPoles() {
        val tiles = Viewport(0.5, 0.01, 2.0, 256, 1024, 1f).visibleTiles(2)
        assertTrue(tiles.all { it.y in 0..3 })
        assertTrue(tiles.contains(TileKey(2, 2, 0)))
    }

    @Test
    fun worldSmallerThanViewportDoesNotDuplicateTiles() {
        val tiles = Viewport(0.5, 0.5, 0.0, 1000, 256, 1f).visibleTiles(0)
        assertEquals(listOf(TileKey(0, 0, 0)), tiles)
    }

    @Test
    fun nonFiniteOrEmptyViewportsAreRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(Double.NaN, 0.5, 3.0, 100, 100, 1f) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(0.5, Double.POSITIVE_INFINITY, 3.0, 100, 100, 1f) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(0.5, 0.5, Double.NaN, 100, 100, 1f) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(0.5, 0.5, 3.0, 0, 100, 1f) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(0.5, 0.5, 3.0, 100, 100, 0f) }
    }

    @Test
    fun rotatedRoundTripAndOrientation() {
        for (b in listOf(0.0, 37.0, 90.0, 180.0, 271.0)) {
            val v = Viewport(0.5063736, 0.3440208, 15.3, 1080, 1920, 2.625f, b)
            for ((sx, sy) in listOf(0.0 to 0.0, 1080.0 to 1920.0, 540.0 to 960.0, 123.4 to 1777.7, 1000.0 to 5.0)) {
                val w = v.screenToWorld(sx, sy)
                val back = v.worldToScreen(w.x, w.y)
                assertEquals(sx, back.x, 1e-6, "x at bearing $b")
                assertEquals(sy, back.y, 1e-6, "y at bearing $b")
            }
            val c = v.worldToScreen(v.centerX, v.centerY)
            assertEquals(540.0, c.x, 1e-9)
            assertEquals(960.0, c.y, 1e-9)
        }
        // bearing 90: the screen's up points east, so a point east of the centre appears above it and north appears to the left
        val v = Viewport(0.5, 0.5, 10.0, 200, 200, 1f, 90.0)
        val east = v.worldToScreen(0.5 + 10.0 / v.worldSizePx, 0.5)
        assertEquals(100.0, east.x, 1e-6)
        assertEquals(90.0, east.y, 1e-6)
        val north = v.worldToScreen(0.5, 0.5 - 10.0 / v.worldSizePx)
        assertEquals(90.0, north.x, 1e-6)
        assertEquals(100.0, north.y, 1e-6)
    }

    @Test
    fun visibleTilesCoverAllCornersWhenRotated() {
        for (b in listOf(45.0, 135.0, 300.0, 10.0)) {
            val v = Viewport(0.5, 0.4, 14.0, 1080, 1920, 2f, b)
            val z = 14
            val n = 1 shl z
            val tiles = v.visibleTiles(z).toSet()
            for ((sx, sy) in listOf(0.0 to 0.0, 1080.0 to 0.0, 0.0 to 1920.0, 1080.0 to 1920.0, 540.0 to 960.0)) {
                val w = v.screenToWorld(sx, sy)
                val key = TileKey(z, Math.floorMod(Math.floor(w.x * n).toInt(), n), Math.floor(w.y * n).toInt())
                assertTrue(key in tiles, "corner ($sx,$sy) at bearing $b not covered")
            }
            if (b > 20.0) assertTrue(tiles.size > Viewport(0.5, 0.4, 14.0, 1080, 1920, 2f).visibleTiles(z).size)
        }
        val slots = Viewport(0.5, 0.4, 14.0, 1080, 1920, 2f, 45.0).visibleTileSlots(14)
        assertTrue(slots.isNotEmpty())
    }

    @Test
    fun nonFiniteBearingIsRejected() {
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(0.5, 0.5, 3.0, 100, 100, 1f, Double.NaN) }
    }
}
