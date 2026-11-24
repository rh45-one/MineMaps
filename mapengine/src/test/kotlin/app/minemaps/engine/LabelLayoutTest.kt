package app.minemaps.engine

import app.minemaps.engine.geo.Viewport
import app.minemaps.engine.labels.Label
import app.minemaps.engine.labels.LabelKind
import app.minemaps.engine.labels.Poi
import app.minemaps.engine.layout.LabelLayout
import app.minemaps.engine.layout.RenderProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelLayoutTest {
    private val vp = Viewport(0.5, 0.5, 18.0, 1000, 1000, 1f)
    private val measure: (String, LabelKind) -> Pair<Float, Float> = { t, _ -> (t.length * 10f) to 20f }

    private fun label(text: String, sx: Double, sy: Double, kind: LabelKind = LabelKind.CITY, rank: Int = 1, angle: Double = 0.0, minZoom: Int = 0): Label {
        val w = vp.screenToWorld(sx, sy)
        return Label(text, kind, rank, w.x, w.y, angle, minZoom)
    }

    private fun poi(sx: Double, sy: Double, rank: Int = 1, name: String = "", minZoom: Int = 16): Poi {
        val w = vp.screenToWorld(sx, sy)
        return Poi("apple", name, w.x, w.y, minZoom, rank)
    }

    @Test
    fun higherPriorityPlaceWinsCollision() {
        val village = label("Village", 500.0, 500.0, LabelKind.VILLAGE)
        val city = label("City", 510.0, 505.0, LabelKind.CITY)
        val out = LabelLayout.layout(listOf(village, city), emptyList(), vp, measure, RenderProfile.PHONE)
        assertEquals(listOf("City"), out.labels.map { it.label.text })
    }

    @Test
    fun rankBreaksTiesWithinSameKind() {
        val a = label("Aaaa", 500.0, 500.0, LabelKind.TOWN, rank = 9)
        val b = label("Bbbb", 505.0, 500.0, LabelKind.TOWN, rank = 2)
        val out = LabelLayout.layout(listOf(a, b), emptyList(), vp, measure, RenderProfile.PHONE)
        assertEquals(listOf("Bbbb"), out.labels.map { it.label.text })
    }

    @Test
    fun textPaddingIsSixPixels() {
        val a = label("aaaa", 300.0, 300.0)
        val near = label("bbbb", 300.0 + 40 + 5, 300.0)
        val far = label("cccc", 300.0 + 40 + 7, 300.0)
        assertEquals(1, LabelLayout.layout(listOf(a, near), emptyList(), vp, measure, RenderProfile.PHONE).labels.size)
        assertEquals(2, LabelLayout.layout(listOf(a, far), emptyList(), vp, measure, RenderProfile.PHONE).labels.size)
    }

    @Test
    fun iconsAndTextCollide() {
        val l = label("Label", 500.0, 500.0)
        val p = poi(500.0, 510.0)
        val out = LabelLayout.layout(listOf(l), listOf(p), vp, measure, RenderProfile.PHONE)
        assertEquals(1, out.labels.size)
        assertEquals(0, out.icons.size)
        val far = poi(500.0, 600.0)
        assertEquals(1, LabelLayout.layout(listOf(l), listOf(far), vp, measure, RenderProfile.PHONE).icons.size)
    }

    @Test
    fun iconPaddingIsEightPixels() {
        val a = poi(300.0, 300.0)
        val close = poi(300.0 + 32 + 7, 300.0)
        val ok = poi(300.0 + 32 + 9, 300.0)
        assertEquals(1, LabelLayout.layout(emptyList(), listOf(a, close), vp, measure, RenderProfile.PHONE).icons.size)
        assertEquals(2, LabelLayout.layout(emptyList(), listOf(a, ok), vp, measure, RenderProfile.PHONE).icons.size)
    }

    @Test
    fun rotatedRoadUsesRotatedBounds() {
        val flat = label("long road name", 500.0, 500.0, LabelKind.ROAD, angle = 0.0)
        val probeBelow = label("x", 500.0, 535.0, LabelKind.CITY)
        assertEquals(2, LabelLayout.layout(listOf(flat, probeBelow), emptyList(), vp, measure, RenderProfile.PHONE).labels.size)
        val steep = label("long road name", 500.0, 500.0, LabelKind.ROAD, angle = 60.0)
        val out = LabelLayout.layout(listOf(steep, probeBelow), emptyList(), vp, measure, RenderProfile.PHONE)
        assertEquals(listOf("x"), out.labels.map { it.label.text })
    }

    @Test
    fun roadsAndWaterComeAfterPlaces() {
        val road = label("Road", 500.0, 500.0, LabelKind.ROAD, rank = 1)
        val place = label("Place", 505.0, 500.0, LabelKind.HAMLET, rank = 50)
        val out = LabelLayout.layout(listOf(road, place), emptyList(), vp, measure, RenderProfile.PHONE)
        assertEquals(listOf("Place"), out.labels.map { it.label.text })
    }

    private fun grid(n: Int, step: Double) = (0 until n).map { i -> poi(20.0 + (i % 20) * step, 20.0 + (i / 20) * step, rank = i) }

    @Test
    fun phoneIconCapIsForty() {
        val out = LabelLayout.layout(emptyList(), grid(300, 48.0), vp, measure, RenderProfile.PHONE)
        assertEquals(40, out.icons.size)
        assertEquals((0 until 40).toList(), out.icons.map { it.poi.rank })
    }

    @Test
    fun phoneLabelCapIsSixty() {
        val labels = (0 until 300).map { i -> label("L$i", 40.0 + (i % 12) * 80.0, 20.0 + (i / 12) * 38.0, rank = i) }
        assertEquals(60, LabelLayout.layout(labels, emptyList(), vp, measure, RenderProfile.PHONE).labels.size)
        assertEquals(25, LabelLayout.layout(labels, emptyList(), vp, measure, RenderProfile.CAR).labels.size)
    }

    @Test
    fun carIconsOnlyFromZoom18AndCapped() {
        val pois = grid(100, 48.0)
        val z18 = LabelLayout.layout(emptyList(), pois, vp, measure, RenderProfile.CAR)
        assertEquals(8, z18.icons.size)
        val z17 = Viewport(0.5, 0.5, 17.2, 1000, 1000, 1f)
        val low = LabelLayout.layout(emptyList(), pois.map { Poi(it.icon, it.name, it.wx, it.wy, 16, it.rank) }, z17, measure, RenderProfile.CAR)
        assertEquals(0, low.icons.size)
    }

    @Test
    fun poiNamesOnlyOnPhoneAtZoom18() {
        val p = poi(500.0, 500.0, name = "Bakery")
        val phone = LabelLayout.layout(emptyList(), listOf(p), vp, measure, RenderProfile.PHONE)
        assertTrue(phone.icons.single().name != null)
        assertEquals(null, LabelLayout.layout(emptyList(), listOf(p), vp, measure, RenderProfile.CAR).icons.single().name)
        val z17 = Viewport(0.5, 0.5, 17.5, 1000, 1000, 1f)
        val w = z17.screenToWorld(500.0, 500.0)
        val low = LabelLayout.layout(emptyList(), listOf(Poi("apple", "Bakery", w.x, w.y, 16, 1)), z17, measure, RenderProfile.PHONE)
        assertEquals(null, low.icons.single().name)
    }

    @Test
    fun layoutIsDeterministicAndStableUnderPanning() {
        val labels = (0 until 40).map { i -> label("Name$i", 100.0 + (i % 8) * 90.0, 100.0 + (i / 8) * 40.0, LabelKind.entries[i % 5], rank = i % 7) }
        val pois = grid(60, 60.0).map { poi(it.wx.let { _ -> 0.0 }, 0.0, rank = it.rank) }.let { _ -> grid(60, 60.0) }
        val a = LabelLayout.layout(labels, pois, vp, measure, RenderProfile.PHONE)
        val b = LabelLayout.layout(labels, pois, vp, measure, RenderProfile.PHONE)
        assertEquals(a.labels.map { it.label.text }, b.labels.map { it.label.text })
        val panned = Viewport(0.5 + 3.0 / (256.0 * (1 shl 18)), 0.5 + 2.0 / (256.0 * (1 shl 18)), 18.0, 1000, 1000, 1f)
        val c = LabelLayout.layout(labels, pois, panned, measure, RenderProfile.PHONE)
        assertEquals(a.labels.map { it.label.text }, c.labels.map { it.label.text })
        assertEquals(a.icons.map { it.poi.rank }, c.icons.map { it.poi.rank })
    }

    @Test
    fun obstaclesSuppressCollidingPoiIconsButNotPlaces() {
        val p = poi(500.0, 500.0)
        val far = poi(700.0, 700.0)
        val place = label("Name", 500.0, 520.0)
        val ob = app.minemaps.engine.layout.Obstacle(480f, 480f, 520f, 520f)
        val out = LabelLayout.layout(listOf(place), listOf(p, far), vp, measure, RenderProfile.PHONE, 32f, listOf(ob))
        assertEquals(listOf(1), out.icons.map { it.poi.rank }.take(1).map { 1 })
        assertEquals(1, out.icons.size)
        assertEquals(far.wx, out.icons.single().poi.wx)
        assertEquals(1, out.labels.size)
        val without = LabelLayout.layout(emptyList(), listOf(p, far), vp, measure, RenderProfile.PHONE, 32f)
        assertEquals(2, without.icons.size)
    }

    @Test
    fun orientedLabelAnglesFollowTheBearingAndStayUpright() {
        assertEquals(0.0, LabelLayout.normalizeAngle(180.0), 1e-9)
        assertEquals(90.0, LabelLayout.normalizeAngle(90.0), 1e-9)
        assertEquals(-80.0, LabelLayout.normalizeAngle(100.0), 1e-9)
        assertEquals(30.0, LabelLayout.normalizeAngle(-150.0), 1e-9)
        val road = Label("Rue", LabelKind.ROAD, 3, 0.5, 0.5, 20.0, 10, oriented = true)
        val point = Label("Town", LabelKind.CITY, 3, 0.5, 0.5 - 1e-6, 0.0, 10)
        for ((bearing, expected) in listOf(0.0 to 20.0, 30.0 to -10.0, 90.0 to -70.0, 200.0 to -0.0 + LabelLayout.normalizeAngle(20.0 - 200.0))) {
            val v = Viewport(0.5, 0.5, 18.0, 1000, 1000, 1f, bearing)
            val out = LabelLayout.layout(listOf(road, point), emptyList(), v, measure, RenderProfile.PHONE)
            assertEquals(expected, out.labels.first { it.label.text == "Rue" }.angleDeg.toDouble(), 1e-3, "road at bearing $bearing")
            assertEquals(0f, out.labels.first { it.label.text == "Town" }.angleDeg, "point label stays upright at $bearing")
        }
    }

    @Test
    fun priorityObstaclesKeepPlaceAndRoadLabelsOffTheMarker() {
        val onMarker = label("Cairo", 500.0, 505.0)
        val away = label("Giza", 800.0, 200.0)
        val marker = app.minemaps.engine.layout.Obstacle(480f, 480f, 520f, 520f)
        val without = LabelLayout.layout(listOf(onMarker, away), emptyList(), vp, measure, RenderProfile.PHONE, 32f)
        assertEquals(2, without.labels.size)
        val with = LabelLayout.layout(listOf(onMarker, away), emptyList(), vp, measure, RenderProfile.PHONE, 32f, priorityObstacles = listOf(marker))
        assertEquals(listOf("Giza"), with.labels.map { it.label.text })
    }

    @Test
    fun splitScreenClipsLabelsAndIconsToVisiblePaneAndPreservesFocalCenterAcrossBearings() {
        // 1280x720 surface with LHD split-screen map pane [0..760] and obscured Spotify pane [760..1280]
        val cx = 0.5
        val cy = 0.5
        for (bearing in listOf(0.0, 90.0, 180.0, 270.0, 137.5)) {
            val split = Viewport(
                centerX = cx, centerY = cy, zoom = 18.0,
                widthPx = 1280, heightPx = 720, pixelRatio = 1f,
                bearingDeg = bearing, pitchDeg = 38f,
                focalX = 380.0, focalY = 360.0,
                visLeft = 0.0, visTop = 0.0, visRight = 760.0, visBottom = 720.0,
            )
            // Center must map to split-screen focal point regardless of heading-up bearing
            val p = split.worldToScreen(cx, cy)
            assertEquals(380.0, p.x, 1e-6, "focalX at bearing $bearing")
            assertEquals(360.0, p.y, 1e-6, "focalY at bearing $bearing")
            // Round-trip screenToWorld at off-center point under rotation
            val w = split.screenToWorld(200.0, 500.0)
            val back = split.worldToScreen(w.x, w.y)
            assertEquals(200.0, back.x, 1e-6, "round-trip x at bearing $bearing")
            assertEquals(500.0, back.y, 1e-6, "round-trip y at bearing $bearing")

            // Forward-look navigation center keeps player at focalX and lower third of pane height
            val fl = Viewport.forwardLookCenter(cx, cy, 18.0, 600, 1f, bearing, 0.16)
            val navVp = Viewport(fl.x, fl.y, 18.0, 1280, 720, 1f, bearing, 38f, focalX = 380.0, focalY = 360.0, visLeft = 0.0, visTop = 60.0, visRight = 760.0, visBottom = 660.0)
            val playerScreen = navVp.worldToScreen(cx, cy)
            assertEquals(380.0, playerScreen.x, 1e-6, "nav player x at bearing $bearing")
            assertEquals(360.0 + 0.16 * 600, playerScreen.y, 1e-6, "nav player y at bearing $bearing")
        }

        // Labels inside obscured pane [760..1280] must not consume CAR budget
        val splitNorth = Viewport(cx, cy, 18.0, 1280, 720, 1f, 0.0, 0f, focalX = 380.0, focalY = 360.0, visLeft = 0.0, visTop = 0.0, visRight = 760.0, visBottom = 720.0)
        val obscuredLabels = (0 until 30).map { i ->
            val w = splitNorth.screenToWorld(820.0 + (i % 5) * 90.0, 40.0 + (i / 5) * 60.0)
            Label("Obscured$i", LabelKind.CITY, i, w.x, w.y, 0.0, 0)
        }
        val visibleLabelPt = splitNorth.screenToWorld(380.0, 360.0)
        val visibleLabel = Label("VisibleCity", LabelKind.CITY, 100, visibleLabelPt.x, visibleLabelPt.y, 0.0, 0)
        val out = LabelLayout.layout(obscuredLabels + visibleLabel, emptyList(), splitNorth, measure, RenderProfile.CAR)
        assertEquals(listOf("VisibleCity"), out.labels.map { it.label.text })
    }

    @Test
    fun tiltedViewportRetainsTopHorizonLabelsAndClipsPostTiltSplitScreen() {
        val splitTilted = Viewport(
            centerX = 0.5, centerY = 0.5, zoom = 18.0,
            widthPx = 1280, heightPx = 720, pixelRatio = 1f,
            bearingDeg = 0.0, pitchDeg = 38f,
            focalX = 388.0, focalY = 388.0,
            visLeft = 16.0, visTop = 96.0, visRight = 760.0, visBottom = 680.0,
            tiltDensity = 1f,
        )
        fun labelAt(text: String, sx: Double, sy: Double, rank: Int = 1): Label {
            val w = splitTilted.screenToWorld(sx, sy)
            return Label(text, LabelKind.CITY, rank, w.x, w.y, 0.0, 0)
        }
        fun poiAt(name: String, sx: Double, sy: Double, rank: Int = 1): Poi {
            val w = splitTilted.screenToWorld(sx, sy)
            return Poi("apple", name, w.x, w.y, 16, rank)
        }

        // Top-horizon label has pre-tilt y = -100 (< visTop 96) but post-tilt y ~= 120.3 (>= visTop 96)
        val horizon = labelAt("HorizonCity", 388.0, -100.0, rank = 1)
        // Second label 32 px below pre-tilt (-68.0) does NOT collide pre-tilt (32 > 20+6),
        // but compresses to ~20 px post-tilt (< 20+6) and must collide with HorizonCity.
        val horizonCompressed = labelAt("HorizonDup", 388.0, -68.0, rank = 2)

        // Bottom-right labels/POIs inside pre-tilt visRight=760 (at x=745/750, y=670) project post-tilt to x > 760
        // and must be clipped instead of consuming RenderProfile.CAR budgets.
        val spilledLabels = (0 until 30).map { i -> labelAt("S$i", 755.0, 670.0, rank = i + 3) }
        val spilledPois = (0 until 10).map { i -> poiAt("Spilled$i", 758.0, 670.0, rank = i + 1) }

        val visibleCity = labelAt("VisibleCity", 388.0, 388.0, rank = 100)
        val visiblePoi = poiAt("VisiblePoi", 388.0, 450.0, rank = 100)

        val out = LabelLayout.layout(
            listOf(horizon, horizonCompressed, visibleCity) + spilledLabels,
            spilledPois + visiblePoi,
            splitTilted,
            measure,
            RenderProfile.CAR,
        )

        val placedTexts = out.labels.map { it.label.text }
        assertTrue("HorizonCity" in placedTexts, "expected top-horizon label to survive post-tilt clipping, got $placedTexts")
        assertTrue("HorizonDup" !in placedTexts, "expected foreshortened top-horizon label to collide post-tilt, got $placedTexts")
        assertTrue("VisibleCity" in placedTexts, "expected VisibleCity not to be evicted by post-tilt clipped labels, got $placedTexts")
        assertEquals(listOf("HorizonCity", "VisibleCity"), placedTexts)
        // Stored label coordinates remain pre-tilt for exact pan-reuse translation
        val placedHorizon = out.labels.first { it.label.text == "HorizonCity" }
        assertEquals(-100.0f, placedHorizon.cy, 1e-2f)

        assertEquals(1, out.icons.size)
        assertEquals("VisiblePoi", out.icons.single().poi.name)
    }

    @Test
    fun panMarginRetainsEnteringLabelsWithoutStealingCarBudget() {
        val split = Viewport(
            centerX = 0.5, centerY = 0.5, zoom = 18.0,
            widthPx = 1280, heightPx = 720, pixelRatio = 1f,
            bearingDeg = 0.0, pitchDeg = 0f,
            focalX = 388.0, focalY = 388.0,
            visLeft = 16.0, visTop = 96.0, visRight = 760.0, visBottom = 680.0,
        )
        fun labelAt(text: String, sx: Double, sy: Double, rank: Int): Label {
            val w = split.screenToWorld(sx, sy)
            return Label(text, LabelKind.CITY, rank, w.x, w.y, 0.0, 0)
        }
        fun poiAt(name: String, sx: Double, sy: Double, rank: Int): Poi {
            val w = split.screenToWorld(sx, sy)
            return Poi("apple", name, w.x, w.y, 16, rank)
        }

        // 30 high-priority labels in the obscured pan margin [790..910] (within 160 px of visRight=760)
        val marginObscured = (0 until 30).map { i ->
            labelAt("M$i", 810.0 + (i % 3) * 40.0, 120.0 + (i / 3) * 40.0, rank = i)
        }
        val inside = labelAt("InsideCity", 388.0, 388.0, rank = 200)
        val enteringLeft = labelAt("EnteringLeft", -30.0, 388.0, rank = 1)

        // Bookmark obstacle in the margin should suppress margin POIs, NOT margin places
        val bookmarkInMargin = app.minemaps.engine.layout.Obstacle(-46f, 372f, -14f, 404f)
        val blockedMarginPoi = poiAt("BlockedPoi", -30.0, 388.0, rank = 1)
        val visiblePoi = poiAt("VisiblePoi", 388.0, 480.0, rank = 200)

        val out = LabelLayout.layout(
            marginObscured + inside + enteringLeft,
            listOf(blockedMarginPoi, visiblePoi),
            split,
            measure,
            RenderProfile.CAR,
            obstacles = listOf(bookmarkInMargin),
            panMarginPx = 160f,
        )

        val texts = out.labels.map { it.label.text }
        assertTrue("InsideCity" in texts, "in-pane label must not be crowded out by margin labels: $texts")
        assertTrue("EnteringLeft" in texts, "pan-margin entering label must be retained: $texts")
        assertEquals(listOf("VisiblePoi"), out.icons.map { it.poi.name })
    }
}
