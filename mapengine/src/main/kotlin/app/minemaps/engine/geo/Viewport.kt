package app.minemaps.engine.geo

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

data class PointD(val x: Double, val y: Double)

class TileSlot(val key: TileKey, val rawX: Int)

class Viewport(
    val centerX: Double,
    val centerY: Double,
    val zoom: Double,
    val widthPx: Int,
    val heightPx: Int,
    val pixelRatio: Float,
) {
    init {
        require(centerX.isFinite() && centerY.isFinite() && zoom.isFinite()) { "viewport centre/zoom must be finite" }
        require(widthPx > 0 && heightPx > 0 && pixelRatio.isFinite() && pixelRatio > 0f) { "viewport size/ratio must be positive" }
    }

    val worldSizePx: Double get() = 256.0 * pixelRatio * 2.0.pow(zoom)

    fun worldToScreenX(wx: Double): Double = widthPx / 2.0 + (wx - centerX) * worldSizePx

    fun worldToScreenY(wy: Double): Double = heightPx / 2.0 + (wy - centerY) * worldSizePx

    fun screenToWorldX(sx: Double): Double = centerX + (sx - widthPx / 2.0) / worldSizePx

    fun screenToWorldY(sy: Double): Double = centerY + (sy - heightPx / 2.0) / worldSizePx

    fun worldToScreen(wx: Double, wy: Double): PointD = PointD(worldToScreenX(wx), worldToScreenY(wy))

    fun screenToWorld(sx: Double, sy: Double): PointD = PointD(screenToWorldX(sx), screenToWorldY(sy))

    fun tileZoom(): Int = floor(zoom).toInt().coerceIn(0, 20)

    fun tileScreenSize(z: Int): Double = 256.0 * pixelRatio * 2.0.pow(zoom - z)

    fun visibleTiles(z: Int): List<TileKey> {
        val n = 1 shl z
        val x0 = floor(screenToWorldX(0.0) * n).toInt()
        val x1 = maxOf(x0, ceil(screenToWorldX(widthPx.toDouble()) * n).toInt() - 1)
        val y0 = floor(screenToWorldY(0.0) * n).toInt().coerceIn(0, n - 1)
        val y1 = maxOf(y0, ceil(screenToWorldY(heightPx.toDouble()) * n).toInt() - 1).coerceIn(0, n - 1)
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
        val x0 = floor(screenToWorldX(0.0) * n).toInt()
        val x1 = maxOf(x0, ceil(screenToWorldX(widthPx.toDouble()) * n).toInt() - 1)
        val y0 = floor(screenToWorldY(0.0) * n).toInt().coerceIn(0, n - 1)
        val y1 = maxOf(y0, ceil(screenToWorldY(heightPx.toDouble()) * n).toInt() - 1).coerceIn(0, n - 1)
        val slots = ArrayList<TileSlot>((x1 - x0 + 1) * (y1 - y0 + 1))
        for (y in y0..y1) for (x in x0..x1) slots.add(TileSlot(TileKey(z, Math.floorMod(x, n), y), x))
        return slots
    }
}
