package app.minemaps

import app.minemaps.car.StableArea
import app.minemaps.engine.geo.Viewport
import kotlin.test.Test
import kotlin.test.assertEquals

class StableAreaTest {
    private data class Case(val w: Int, val h: Int, val l: Int, val t: Int, val r: Int, val b: Int, val zoom: Double)

    @Test
    fun targetLandsAtStableAreaCentre() {
        val cases = listOf(
            Case(1280, 720, 0, 0, 1280, 720, 17.0),
            Case(1280, 720, 400, 0, 1280, 720, 17.0),
            Case(1920, 1080, 640, 120, 1900, 960, 15.5),
            Case(800, 480, 0, 80, 800, 400, 18.3),
            Case(2400, 960, 900, 0, 2400, 960, 12.0),
        )
        val cx = 0.5063736
        val cy = 0.3440208
        for (c in cases) {
            val probe = Viewport(cx, cy, c.zoom, c.w, c.h, 1.5f)
            val off = StableArea.worldOffset(c.w, c.h, c.l, c.t, c.r, c.b, probe.worldSizePx)
            val vp = Viewport(cx + off[0], cy + off[1], c.zoom, c.w, c.h, 1.5f)
            val p = vp.worldToScreen(cx, cy)
            assertEquals((c.l + c.r) / 2.0, p.x, 1e-6, "x for $c")
            assertEquals((c.t + c.b) / 2.0, p.y, 1e-6, "y for $c")
        }
    }

    @Test
    fun fullSurfaceAreaHasNoOffset() {
        val off = StableArea.worldOffset(1000, 600, 0, 0, 1000, 600, 1e6)
        assertEquals(0.0, off[0], 0.0)
        assertEquals(0.0, off[1], 0.0)
    }
}
