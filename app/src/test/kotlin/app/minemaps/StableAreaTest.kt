package app.minemaps

import android.graphics.Rect
import app.minemaps.car.StableArea
import app.minemaps.engine.geo.Viewport
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
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

    @Test
    fun activeAreaIntersectsVisibleAndStableForLhdAndRhdSplitScreen() {
        // LHD split screen: Spotify on right [760..1280], full-width stableArea with template insets
        val lhd = assertNotNull(StableArea.activeArea(Rect(0, 0, 760, 720), Rect(16, 96, 1264, 680)))
        assertEquals(Rect(16, 96, 760, 680), lhd)
        assertEquals(388f, lhd.exactCenterX())

        // RHD / left-handed split screen: Spotify on left [0..520], full-width stableArea
        val rhd = assertNotNull(StableArea.activeArea(Rect(520, 0, 1280, 720), Rect(16, 96, 1264, 680)))
        assertEquals(Rect(520, 96, 1264, 680), rhd)
        assertEquals(892f, rhd.exactCenterX())

        // Disjoint stale stableArea falls back to visibleArea
        val disjoint = assertNotNull(StableArea.activeArea(Rect(0, 0, 500, 720), Rect(600, 0, 1280, 720)))
        assertEquals(Rect(0, 0, 500, 720), disjoint)
    }
}
