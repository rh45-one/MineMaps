package app.minemaps.engine.layout

import app.minemaps.engine.geo.Viewport
import app.minemaps.engine.labels.Label
import app.minemaps.engine.labels.LabelKind
import app.minemaps.engine.labels.Poi
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

enum class RenderProfile(
    val textScale: Float,
    val iconScale: Float,
    val maxIcons: Int,
    val maxLabels: Int,
    val minIconZoom: Int,
    val poiNames: Boolean,
) {
    PHONE(1.0f, 1.0f, 40, 60, 0, true),
    CAR(1.3f, 1.3f, 8, 25, 18, false),
}

class PlacedText(val text: String, val cx: Float, val cy: Float, val width: Float, val height: Float)

class PlacedLabel(
    val label: Label,
    val cx: Float,
    val cy: Float,
    val width: Float,
    val height: Float,
    val angleDeg: Float,
)

class PlacedIcon(val poi: Poi, val cx: Float, val cy: Float, val size: Float, val name: PlacedText?)

class Obstacle(val x0: Float, val y0: Float, val x1: Float, val y1: Float)

class PlacedLayout(val labels: List<PlacedLabel>, val icons: List<PlacedIcon>)

object LabelLayout {
    /** Angle in (-90, 90] equivalent to [a] for upright text. */
    fun normalizeAngle(a: Double): Double {
        val x = ((a % 180.0) + 180.0) % 180.0
        return if (x > 90.0) x - 180.0 else x
    }

    const val TEXT_PAD = 6f
    const val ICON_PAD = 8f
    const val POI_NAME_MIN_ZOOM = 18
    const val NAME_GAP = 2f

    private class Boxes {
        var data = FloatArray(5 * 64)
        var n = 0

        fun collides(x0: Float, y0: Float, x1: Float, y1: Float, pad: Float, ignoreNegative: Boolean = false): Boolean {
            var i = 0
            while (i < n) {
                val o = i * 5
                val stored = data[o + 4]
                if (ignoreNegative && stored < 0f) {
                    i++
                    continue
                }
                val op = if (stored < 0f) -stored else stored
                val g = if (pad > op) pad else op
                if (x0 < data[o + 2] + g && x1 > data[o] - g && y0 < data[o + 3] + g && y1 > data[o + 1] - g) return true
                i++
            }
            return false
        }

        fun add(x0: Float, y0: Float, x1: Float, y1: Float, pad: Float) {
            if ((n + 1) * 5 > data.size) data = data.copyOf(data.size * 2)
            val o = n * 5
            data[o] = x0
            data[o + 1] = y0
            data[o + 2] = x1
            data[o + 3] = y1
            data[o + 4] = pad
            n++
        }
    }

    fun layout(
        labels: List<Label>,
        pois: List<Poi>,
        viewport: Viewport,
        measure: (String, LabelKind) -> Pair<Float, Float>,
        profile: RenderProfile,
        iconSizePx: Float = 32f,
        obstacles: List<Obstacle> = emptyList(),
        priorityObstacles: List<Obstacle> = emptyList(),
        panMarginPx: Float = 0f,
    ): PlacedLayout {
        val zoom = floor(viewport.zoom).toInt()
        val vLeft = viewport.visLeft.toFloat()
        val vTop = viewport.visTop.toFloat()
        val vRight = viewport.visRight.toFloat()
        val vBottom = viewport.visBottom.toFloat()
        val margin = panMarginPx.coerceAtLeast(0f)
        val mLeft = vLeft - margin
        val mTop = vTop - margin
        val mRight = vRight + margin
        val mBottom = vBottom + margin
        val boxes = Boxes()
        val placedLabels = ArrayList<PlacedLabel>()
        val placedIcons = ArrayList<PlacedIcon>()

        fun isLine(l: Label) = l.kind == LabelKind.ROAD || l.kind == LabelKind.ROAD_MINOR
        val places = labels.filter { it.minZoom <= zoom && !isLine(it) && it.kind != LabelKind.WATER }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.rank }))
        val water = labels.filter { it.minZoom <= zoom && it.kind == LabelKind.WATER }
        val roads = labels.filter { it.minZoom <= zoom && isLine(it) }.sortedBy { it.rank }

        var inPaneLabels = 0
        var marginLabels = 0
        var inPaneIcons = 0
        var marginIcons = 0

        fun placeLabel(l: Label, wantMargin: Boolean) {
            val count = if (wantMargin) marginLabels else inPaneLabels
            if (count >= profile.maxLabels) return
            val (w0, h0) = measure(l.text, l.kind)
            val w = w0 + 0f
            val h = h0 + 0f
            val pt = viewport.worldToScreen(l.wx, l.wy)
            val cx = pt.x.toFloat()
            val cy = pt.y.toFloat()
            val tpt = viewport.projectTilt(cx.toDouble(), cy.toDouble())
            val tx = tpt.x.toFloat()
            val ty = tpt.y.toFloat()
            val angle = if (l.oriented) normalizeAngle(l.angleDeg - viewport.bearingDeg) else l.angleDeg
            val rad = Math.toRadians(angle)
            val c = abs(cos(rad)).toFloat()
            val s = abs(sin(rad)).toFloat()
            val bw = w * c + h * s
            val bh = w * s + h * c
            val x0 = tx - bw / 2
            val y0 = ty - bh / 2
            val x1 = tx + bw / 2
            val y1 = ty + bh / 2
            val inPane = x1 >= vLeft && x0 <= vRight && y1 >= vTop && y0 <= vBottom
            if (wantMargin) {
                if (inPane || x1 < mLeft || x0 > mRight || y1 < mTop || y0 > mBottom) return
            } else {
                if (!inPane) return
            }
            val ignoreBookmarks = l.kind != LabelKind.WATER && !isLine(l)
            if (boxes.collides(x0, y0, x1, y1, TEXT_PAD, ignoreBookmarks)) return
            boxes.add(x0, y0, x1, y1, TEXT_PAD)
            placedLabels.add(PlacedLabel(l, cx, cy, w, h, angle.toFloat()))
            if (wantMargin) marginLabels++ else inPaneLabels++
        }

        val poiCandidates = if (zoom >= profile.minIconZoom) {
            pois.filter { it.minZoom <= zoom }.sortedBy { it.rank }
        } else {
            emptyList()
        }

        fun placePois(wantMargin: Boolean) {
            if (poiCandidates.isEmpty()) return
            val half = iconSizePx / 2
            for (p in poiCandidates) {
                val count = if (wantMargin) marginIcons else inPaneIcons
                if (count >= profile.maxIcons) break
                val pt = viewport.worldToScreen(p.wx, p.wy)
                val cx = pt.x.toFloat()
                val cy = pt.y.toFloat()
                val tpt = viewport.projectTilt(cx.toDouble(), cy.toDouble())
                val tx = tpt.x.toFloat()
                val ty = tpt.y.toFloat()
                val x0 = tx - half
                val y0 = ty - half
                val x1 = tx + half
                val y1 = ty + half
                val inPane = x1 >= vLeft && x0 <= vRight && y1 >= vTop && y0 <= vBottom
                if (wantMargin) {
                    if (inPane || x1 < mLeft || x0 > mRight || y1 < mTop || y0 > mBottom) continue
                } else {
                    if (!inPane) continue
                }
                if (boxes.collides(x0, y0, x1, y1, ICON_PAD)) continue
                var name: PlacedText? = null
                if (profile.poiNames && zoom >= POI_NAME_MIN_ZOOM && p.name.isNotBlank()) {
                    val (w, h) = measure(p.name, LabelKind.POI_NAME)
                    val ncy = y1 + NAME_GAP + h / 2
                    val nx0 = tx - w / 2
                    val nx1 = tx + w / 2
                    val ny0 = ncy - h / 2
                    val ny1 = ncy + h / 2
                    if (!boxes.collides(nx0, ny0, nx1, ny1, TEXT_PAD)) {
                        boxes.add(nx0, ny0, nx1, ny1, TEXT_PAD)
                        name = PlacedText(p.name, cx, cy + half + NAME_GAP + h / 2, w, h)
                    }
                }
                boxes.add(x0, y0, x1, y1, ICON_PAD)
                placedIcons.add(PlacedIcon(p, cx, cy, iconSizePx, name))
                if (wantMargin) marginIcons++ else inPaneIcons++
            }
        }

        for (o in priorityObstacles) boxes.add(o.x0, o.y0, o.x1, o.y1, TEXT_PAD)
        for (o in obstacles) boxes.add(o.x0, o.y0, o.x1, o.y1, -ICON_PAD)

        // Pass 1: visible in-pane items (protected from off-pane margin items stealing profile budgets).
        places.forEach { placeLabel(it, wantMargin = false) }
        placePois(wantMargin = false)
        water.forEach { placeLabel(it, wantMargin = false) }
        roads.forEach { placeLabel(it, wantMargin = false) }

        // Pass 2: pan-buffer items outside the visible pane for smooth pan reuse.
        if (margin > 0f) {
            places.forEach { placeLabel(it, wantMargin = true) }
            placePois(wantMargin = true)
            water.forEach { placeLabel(it, wantMargin = true) }
            roads.forEach { placeLabel(it, wantMargin = true) }
        }
        return PlacedLayout(placedLabels, placedIcons)
    }
}
