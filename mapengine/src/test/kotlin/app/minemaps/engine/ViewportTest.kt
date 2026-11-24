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

    @Test
    fun tiltProjectionAndInverseRoundTrip() {
        // Below or at 0.5f threshold, projectTilt and inverseTilt are exact identities.
        for (pitch in listOf(0f, 0.5f)) {
            val v = Viewport(0.5, 0.5, 17.0, 1280, 720, 1f, pitchDeg = pitch, focalX = 380.0, focalY = 360.0)
            val p = v.projectTilt(123.4, -200.0)
            assertEquals(123.4, p.x, 1e-9)
            assertEquals(-200.0, p.y, 1e-9)
            val inv = v.inverseTilt(123.4, -200.0)
            assertEquals(123.4, inv.x, 1e-9)
            assertEquals(-200.0, inv.y, 1e-9)
        }

        // Pivot calculation with insets and pivot invariance
        val inset = Viewport(
            0.5, 0.5, 17.0, 1280, 720, 1f, pitchDeg = 38f,
            focalX = 388.0, focalY = 388.0, visLeft = 16.0, visTop = 96.0, visRight = 760.0, visBottom = 680.0,
            tiltDensity = 0.75f,
        )
        assertEquals(96.0 + (680.0 - 96.0) * 0.68, inset.tiltPivotY, 1e-9)
        val pivotProj = inset.projectTilt(inset.focalX, inset.tiltPivotY)
        assertEquals(inset.focalX, pivotProj.x, 1e-9)
        assertEquals(inset.tiltPivotY, pivotProj.y, 1e-9)

        // Exact analytical check: 1280x720, focalX=380, density=1.0, pitch=38 -> inverseTilt(0,0)
        val car = Viewport(0.5, 0.5, 17.0, 1280, 720, 1f, pitchDeg = 38f, focalX = 380.0, focalY = 360.0, tiltDensity = 1f)
        val topLeftPre = car.inverseTilt(0.0, 0.0)
        assertEquals(-137.456, topLeftPre.x, 0.01)
        assertEquals(-356.455, topLeftPre.y, 0.01)

        // Round-trip across varying densities (including pixelRatio != tiltDensity) and focal points
        for ((pr, td) in listOf(1f to 0.75f, 1f to 1f, 2f to 2f, 1.5f to 3f)) {
            for (fx in listOf(380.0, 540.0, 900.0)) {
                val v = Viewport(0.5, 0.5, 17.3, 1280, 720, pr, bearingDeg = 45.0, pitchDeg = 38f, focalX = fx, focalY = 360.0, tiltDensity = td)
                assertEquals(td, v.withBearing(90.0).tiltDensity)
                for ((sx, sy) in listOf(0.0 to 0.0, 1280.0 to 0.0, 0.0 to 720.0, 1280.0 to 720.0, 640.0 to 100.0)) {
                    val pre = v.inverseTilt(sx, sy)
                    val back = v.projectTilt(pre.x, pre.y)
                    assertEquals(sx, back.x, 1e-6, "round-trip x ($sx,$sy) pr=$pr td=$td fx=$fx")
                    assertEquals(sy, back.y, 1e-6, "round-trip y ($sx,$sy) pr=$pr td=$td fx=$fx")
                }
            }
        }

        // Horizon denominator guard at extreme pitch: finite and no sign flip below pivot
        val extreme = Viewport(0.5, 0.5, 17.0, 1280, 2400, 1f, pitchDeg = 85f, tiltDensity = 0.75f)
        val topExtreme = extreme.inverseTilt(0.0, 0.0)
        assertTrue(topExtreme.x.isFinite() && topExtreme.y.isFinite() && topExtreme.y < extreme.tiltPivotY)
        val botExtreme = extreme.inverseTilt(1280.0, 2400.0)
        assertTrue(botExtreme.x.isFinite() && botExtreme.y.isFinite() && botExtreme.y > extreme.tiltPivotY)

        // Invalid pitch / tiltDensity rejected
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(0.5, 0.5, 17.0, 1280, 720, 1f, pitchDeg = Float.NaN) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(0.5, 0.5, 17.0, 1280, 720, 1f, tiltDensity = 0f) }
        kotlin.test.assertFailsWith<IllegalArgumentException> { Viewport(0.5, 0.5, 17.0, 1280, 720, 1f, tiltDensity = Float.POSITIVE_INFINITY) }
    }

    @Test
    fun tiltedWorldBoundsAndVisibleTilesCoverAllSurfaceCorners() {
        data class SurfaceCase(
            val w: Int,
            val h: Int,
            val fx: Double,
            val fy: Double,
            val vL: Double,
            val vT: Double,
            val vR: Double,
            val vB: Double,
        )
        val surfaces = listOf(
            SurfaceCase(1280, 720, 380.0, 360.0, 0.0, 0.0, 760.0, 720.0),
            SurfaceCase(1280, 720, 900.0, 360.0, 520.0, 0.0, 1280.0, 720.0),
            SurfaceCase(1280, 720, 388.0, 388.0, 16.0, 96.0, 760.0, 680.0),
            SurfaceCase(1920, 1080, 960.0, 540.0, 0.0, 0.0, 1920.0, 1080.0),
            SurfaceCase(2400, 960, 750.0, 480.0, 0.0, 0.0, 1500.0, 960.0),
            SurfaceCase(2400, 960, 1650.0, 480.0, 900.0, 0.0, 2400.0, 960.0),
            SurfaceCase(1080, 2400, 540.0, 1200.0, 0.0, 0.0, 1080.0, 2400.0),
        )
        val densities = listOf(1f to 0.75f, 1f to 1f, 2f to 2f, 1.5f to 3f)
        val bearings = listOf(0.0, 45.0, 90.0, 135.0, 270.0)
        val zoom = 17.3
        val z = 17
        val n = 1 shl z

        for (sc in surfaces) {
            for ((pr, td) in densities) {
                for (bearing in bearings) {
                    val vp = Viewport(
                        0.5063736, 0.3440208, zoom, sc.w, sc.h, pr, bearing, 38f,
                        focalX = sc.fx, focalY = sc.fy,
                        visLeft = sc.vL, visTop = sc.vT, visRight = sc.vR, visBottom = sc.vB,
                        tiltDensity = td,
                    )
                    val bounds = vp.worldBounds()
                    val tiles = vp.visibleTiles(z).toSet()
                    val slotKeys = vp.visibleTileSlots(z).map { it.key }.toSet()
                    assertEquals(tiles, slotKeys)
                    for ((sx, sy) in listOf(0.0 to 0.0, sc.w.toDouble() to 0.0, 0.0 to sc.h.toDouble(), sc.w.toDouble() to sc.h.toDouble())) {
                        val pre = vp.inverseTilt(sx, sy)
                        val w = vp.screenToWorld(pre.x, pre.y)
                        assertTrue(w.x >= bounds[0] - 1e-12 && w.x <= bounds[2] + 1e-12, "x out of bounds for $sc pr=$pr td=$td b=$bearing corner=($sx,$sy)")
                        assertTrue(w.y >= bounds[1] - 1e-12 && w.y <= bounds[3] + 1e-12, "y out of bounds for $sc pr=$pr td=$td b=$bearing corner=($sx,$sy)")
                        val key = TileKey(z, Math.floorMod(Math.floor(w.x * n).toInt(), n), Math.floor(w.y * n).toInt())
                        assertTrue(key in tiles, "corner ($sx,$sy) tile $key missing for $sc pr=$pr td=$td b=$bearing")
                    }
                }
            }
        }
    }

    @Test
    fun retainRingPaddedViewportIsAlwaysSupersetOfTiltedVisibleTiles() {
        val panes = listOf(
            // LHD split screen with insets, RHD split screen, full screen, wide split, portrait phone
            listOf(1280.0, 720.0, 388.0, 388.0, 16.0, 96.0, 760.0, 680.0),
            listOf(1280.0, 720.0, 892.0, 388.0, 520.0, 96.0, 1264.0, 680.0),
            listOf(1920.0, 1080.0, 960.0, 540.0, 0.0, 0.0, 1920.0, 1080.0),
            listOf(2400.0, 960.0, 750.0, 480.0, 0.0, 0.0, 1500.0, 960.0),
            listOf(1080.0, 2400.0, 540.0, 1200.0, 0.0, 0.0, 1080.0, 2400.0),
        )
        for (p in panes) {
            for ((pr, td) in listOf(1f to 0.75f, 1f to 1f, 1.5f to 3f, 2f to 2f)) {
                for (zoom in listOf(10.0, 14.5, 17.0, 17.99)) {
                    for (pitch in listOf(0f, 38f, 50f)) {
                        for (bearing in listOf(0.0, 45.0, 90.0, 135.0, 180.0, 270.0, 315.0)) {
                            val vp = Viewport(
                                0.5063736, 0.3440208, zoom, p[0].toInt(), p[1].toInt(), pr, bearing, pitch,
                                focalX = p[2], focalY = p[3],
                                visLeft = p[4], visTop = p[5], visRight = p[6], visBottom = p[7],
                                tiltDensity = td,
                            )
                            val z = vp.tileZoom()
                            val pad = kotlin.math.ceil(vp.tileScreenSize(z)).toInt()
                            val padded = Viewport(
                                vp.centerX, vp.centerY, vp.zoom,
                                vp.widthPx + 2 * pad, vp.heightPx + 2 * pad,
                                vp.pixelRatio, vp.bearingDeg, vp.pitchDeg,
                                focalX = vp.focalX + pad, focalY = vp.focalY + pad,
                                visLeft = vp.visLeft + pad, visTop = vp.visTop + pad,
                                visRight = vp.visRight + pad, visBottom = vp.visBottom + pad,
                                tiltDensity = vp.tiltDensity,
                            )
                            val visible = vp.visibleTiles(z).toSet()
                            val retained = padded.visibleTiles(z).toSet()
                            assertTrue(
                                retained.containsAll(visible),
                                "retained missing ${visible - retained} for ${p[0]}x${p[1]} pr=$pr td=$td z=$zoom pitch=$pitch b=$bearing",
                            )
                        }
                    }
                }
            }
        }
    }
}
