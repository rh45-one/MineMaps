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
    private const val NAME_GAP = 2f

    private class Boxes {
        var data = FloatArray(5 * 64)
        var n = 0

        fun collides(x0: Float, y0: Float, x1: Float, y1: Float, pad: Float): Boolean {
            var i = 0
            while (i < n) {
                val o = i * 5
                val g = if (pad > data[o + 4]) pad else data[o + 4]
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
    ): PlacedLayout {
        val zoom = floor(viewport.zoom).toInt()
        val sw = viewport.widthPx.toFloat()
        val sh = viewport.heightPx.toFloat()
        val boxes = Boxes()
        val placedLabels = ArrayList<PlacedLabel>()
        val placedIcons = ArrayList<PlacedIcon>()

        fun isLine(l: Label) = l.kind == LabelKind.ROAD || l.kind == LabelKind.ROAD_MINOR
        val places = labels.filter { it.minZoom <= zoom && !isLine(it) && it.kind != LabelKind.WATER }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.rank }))
        val water = labels.filter { it.minZoom <= zoom && it.kind == LabelKind.WATER }
        val roads = labels.filter { it.minZoom <= zoom && isLine(it) }.sortedBy { it.rank }

        fun placeLabel(l: Label) {
            if (placedLabels.size >= profile.maxLabels) return
            val (w0, h0) = measure(l.text, l.kind)
            val w = w0 + 0f
            val h = h0 + 0f
            val pt = viewport.worldToScreen(l.wx, l.wy)
            val cx = pt.x.toFloat()
            val cy = pt.y.toFloat()
            val angle = if (l.oriented) normalizeAngle(l.angleDeg - viewport.bearingDeg) else l.angleDeg
            val rad = Math.toRadians(angle)
            val c = abs(cos(rad)).toFloat()
            val s = abs(sin(rad)).toFloat()
            val bw = w * c + h * s
            val bh = w * s + h * c
            val x0 = cx - bw / 2
            val y0 = cy - bh / 2
            val x1 = cx + bw / 2
            val y1 = cy + bh / 2
            if (x1 < 0 || x0 > sw || y1 < 0 || y0 > sh) return
            if (boxes.collides(x0, y0, x1, y1, TEXT_PAD)) return
            boxes.add(x0, y0, x1, y1, TEXT_PAD)
            placedLabels.add(PlacedLabel(l, cx, cy, w, h, angle.toFloat()))
        }

        places.forEach(::placeLabel)

        for (o in obstacles) boxes.add(o.x0, o.y0, o.x1, o.y1, ICON_PAD)

        if (zoom >= profile.minIconZoom) {
            val half = iconSizePx / 2
            val candidates = pois.filter { it.minZoom <= zoom }.sortedBy { it.rank }
            for (p in candidates) {
                if (placedIcons.size >= profile.maxIcons) break
                val pt = viewport.worldToScreen(p.wx, p.wy)
                val cx = pt.x.toFloat()
                val cy = pt.y.toFloat()
                val x0 = cx - half
                val y0 = cy - half
                val x1 = cx + half
                val y1 = cy + half
                if (x1 < 0 || x0 > sw || y1 < 0 || y0 > sh) continue
                if (boxes.collides(x0, y0, x1, y1, ICON_PAD)) continue
                var name: PlacedText? = null
                if (profile.poiNames && zoom >= POI_NAME_MIN_ZOOM && p.name.isNotBlank()) {
                    val (w, h) = measure(p.name, LabelKind.POI_NAME)
                    val ncy = y1 + NAME_GAP + h / 2
                    val nx0 = cx - w / 2
                    val nx1 = cx + w / 2
                    val ny0 = ncy - h / 2
                    val ny1 = ncy + h / 2
                    if (!boxes.collides(nx0, ny0, nx1, ny1, TEXT_PAD)) {
                        boxes.add(nx0, ny0, nx1, ny1, TEXT_PAD)
                        name = PlacedText(p.name, cx, ncy, w, h)
                    }
                }
                boxes.add(x0, y0, x1, y1, ICON_PAD)
                placedIcons.add(PlacedIcon(p, cx, cy, iconSizePx, name))
            }
        }

        water.forEach(::placeLabel)
        roads.forEach(::placeLabel)
        return PlacedLayout(placedLabels, placedIcons)
    }
}
