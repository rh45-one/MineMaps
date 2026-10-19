package app.minemaps.engine.geo

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

data class PointD(val x: Double, val y: Double)

class TileSlot(val key: TileKey, val rawX: Int)

class Viewport(
    val centerX: Double,
    val centerY: Double,
    val zoom: Double,
    val widthPx: Int,
    val heightPx: Int,
    val pixelRatio: Float,
    val bearingDeg: Double = 0.0,
    val pitchDeg: Float = 0f,
) {
    init {
        require(centerX.isFinite() && centerY.isFinite() && zoom.isFinite() && bearingDeg.isFinite()) { "viewport centre/zoom/bearing must be finite" }
        require(widthPx > 0 && heightPx > 0 && pixelRatio.isFinite() && pixelRatio > 0f) { "viewport size/ratio must be positive" }
    }

    private val cosB = cos(Math.toRadians(bearingDeg))
    private val sinB = sin(Math.toRadians(bearingDeg))

    val worldSizePx: Double get() = 256.0 * pixelRatio * 2.0.pow(zoom)

    fun withBearing(deg: Double) = Viewport(centerX, centerY, zoom, widthPx, heightPx, pixelRatio, deg, pitchDeg)

    /** Screen position of a world point: the offset from the centre is rotated by -bearing (the screen's up points to compass bearing [bearingDeg]). */
    fun worldToScreen(wx: Double, wy: Double): PointD {
        val dx = (wx - centerX) * worldSizePx
        val dy = (wy - centerY) * worldSizePx
        return PointD(widthPx / 2.0 + dx * cosB + dy * sinB, heightPx / 2.0 - dx * sinB + dy * cosB)
    }

    fun screenToWorld(sx: Double, sy: Double): PointD {
        val dx = sx - widthPx / 2.0
        val dy = sy - heightPx / 2.0
        return PointD(centerX + (dx * cosB - dy * sinB) / worldSizePx, centerY + (dx * sinB + dy * cosB) / worldSizePx)
    }

    fun worldToScreenX(wx: Double): Double = worldToScreen(wx, centerY).x

    fun worldToScreenY(wy: Double): Double = worldToScreen(centerX, wy).y

    fun screenToWorldX(sx: Double): Double = screenToWorld(sx, heightPx / 2.0).x

    fun screenToWorldY(sy: Double): Double = screenToWorld(widthPx / 2.0, sy).y

    /** Axis-aligned world bounds (minX, minY, maxX, maxY) of the four screen corners (expanded toward the horizon when tilted). */
    fun worldBounds(): DoubleArray {
        val topY = if (pitchDeg > 1f) -heightPx * 0.55 else 0.0
        val sidePad = if (pitchDeg > 1f) widthPx * 0.22 else 0.0
        val c = listOf(
            screenToWorld(-sidePad, topY),
            screenToWorld(widthPx + sidePad, topY),
            screenToWorld(-sidePad, heightPx.toDouble()),
            screenToWorld(widthPx + sidePad, heightPx.toDouble()),
        )
        return doubleArrayOf(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })
    }

    fun tileZoom(): Int = floor(zoom).toInt().coerceIn(0, 20)

    fun tileScreenSize(z: Int): Double = 256.0 * pixelRatio * 2.0.pow(zoom - z)

    fun visibleTiles(z: Int): List<TileKey> {
        val n = 1 shl z
        val b = worldBounds()
        val x0 = floor(b[0] * n).toInt()
        val x1 = maxOf(x0, ceil(b[2] * n).toInt() - 1)
        val y0 = floor(b[1] * n).toInt().coerceIn(0, n - 1)
        val y1 = maxOf(y0, ceil(b[3] * n).toInt() - 1).coerceIn(0, n - 1)
        val xs = if (x1 - x0 + 1 >= n) (0 until n).toList() else (x0..x1).map { Math.floorMod(it, n) }.distinct()
        val cx = centerX * n
        val cy = centerY * n
        val tiles = ArrayList<TileKey>(xs.size * (y1 - y0 + 1))
        for (y in y0..y1) for (x in xs) tiles.add(TileKey(z, x, y))
        return tiles.sortedBy { t ->
            var dx = Math.abs(t.x + 0.5 - cx)
            dx = minOf(dx, n - dx)
            hypot(dx, t.y + 0.5 - cy)
        }
    }

    fun visibleTileSlots(z: Int): List<TileSlot> {
        val n = 1 shl z
        val b = worldBounds()
        val x0 = floor(b[0] * n).toInt()
        val x1 = maxOf(x0, ceil(b[2] * n).toInt() - 1)
        val y0 = floor(b[1] * n).toInt().coerceIn(0, n - 1)
        val y1 = maxOf(y0, ceil(b[3] * n).toInt() - 1).coerceIn(0, n - 1)
        val slots = ArrayList<TileSlot>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (y in y0..y1) for (x in x0..x1) slots.add(TileSlot(TileKey(z, Math.floorMod(x, n), y), x))
        return slots
    }

    companion object {
        /**
         * Computes the camera world centre `(cx, cy)` so that the player at `(playerWx, playerWy)` sits in the
         * lower third of the screen (`0.5 + forwardFraction` down from the top) with the road ahead extending upward
         * along [bearingDeg], matching Google Maps' navigation framing.
         */
        fun forwardLookCenter(
            playerWx: Double,
            playerWy: Double,
            zoom: Double,
            heightPx: Int,
            pixelRatio: Float,
            bearingDeg: Double,
            forwardFraction: Double = 0.18,
        ): PointD {
            val worldSizePx = 256.0 * pixelRatio * 2.0.pow(zoom)
            val rad = Math.toRadians(bearingDeg)
            val cosB = cos(rad)
            val sinB = sin(rad)
            val dy = -forwardFraction * heightPx
            val rawX = playerWx + (-dy * sinB) / worldSizePx
            val cx = rawX - floor(rawX)
            val cy = (playerWy + (dy * cosB) / worldSizePx).coerceIn(0.0, 1.0)
            return PointD(cx, cy)
        }
    }
}
