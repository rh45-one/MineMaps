package app.minemaps.engine.render

import app.minemaps.engine.raster.BlockGrid
import app.minemaps.engine.style.Block
import app.minemaps.engine.util.Hash

object Terrain {
    const val MAX_DEPTH = 4

    fun isTreeBlock(cls: Int): Boolean = cls == Block.FOREST.id || cls == Block.PARK.id

    /** True when the global block lies inside a decorative tree canopy. Depends only on global block coordinates. */
    fun isCanopy(gx: Int, gy: Int, forest: Boolean): Boolean {
        val size = if (forest) FOREST_CELL else PARK_CELL
        val percent = if (forest) FOREST_PERCENT else PARK_PERCENT
        val salt = if (forest) 301 else 302
        val cellX = Math.floorDiv(gx, size)
        val cellY = Math.floorDiv(gy, size)
        for (cy in cellY - 1..cellY + 1) for (cx in cellX - 1..cellX + 1) {
            val h = Hash.mix(cx, cy, salt)
            if (h % 100 >= percent) continue
            val px = cx * size + (h ushr 8) % size
            val py = cy * size + (h ushr 16) % size
            val r = if (forest) 1 + ((h ushr 24) and 1) else 1
            val dx = gx - px
            val dy = gy - py
            if (dx * dx + dy * dy <= r * r + 1) return true
        }
        return false
    }

    fun hasTreeBlocks(grid: BlockGrid): Boolean {
        val f = Block.FOREST.byteId
        val p = Block.PARK.byteId
        for (c in grid.cls) if (c == f || c == p) return true
        return false
    }

    /**
     * Canopy flags for local blocks lx0-1..lx0+n and ly0-1..ly0+n; index (ly - ly0 + 1) * (n + 2) + (lx - lx0 + 1).
     * [gox]/[goy] are the global block coordinates of local (0,0).
     */
    fun canopy(grid: BlockGrid, gox: Int, goy: Int, lx0: Int, ly0: Int, n: Int): BooleanArray {
        val w = n + 2
        val out = BooleanArray(w * w)
        for (j in 0 until w) for (i in 0 until w) {
            val x = lx0 + i - 1
            val y = ly0 + j - 1
            val cls = grid.clsAt(x, y)
            if (!isTreeBlock(cls)) continue
            out[j * w + i] = isCanopy(gox + x, goy + y, cls == Block.FOREST.id)
        }
        return out
    }

    /** Chebyshev distance to the nearest non-water cell, capped at [MAX_DEPTH]; 0 for non-water. Null when the grid has no water. */
    fun waterDepth(grid: BlockGrid): ByteArray? {
        val water = Block.WATER.byteId
        var any = false
        for (c in grid.cls) if (c == water) {
            any = true
            break
        }
        if (!any) return null
        val s = BlockGrid.SIZE
        val d = ByteArray(s * s)
        val big = MAX_DEPTH.toByte()
        for (i in d.indices) d[i] = if (grid.cls[i] == water) big else 0
        for (y in 0 until s) for (x in 0 until s) {
            val i = y * s + x
            if (d[i].toInt() == 0) continue
            var m = d[i].toInt()
            if (x > 0) m = minOf(m, d[i - 1] + 1)
            if (y > 0) {
                m = minOf(m, d[i - s] + 1)
                if (x > 0) m = minOf(m, d[i - s - 1] + 1)
                if (x < s - 1) m = minOf(m, d[i - s + 1] + 1)
            }
            d[i] = m.toByte()
        }
        for (y in s - 1 downTo 0) for (x in s - 1 downTo 0) {
            val i = y * s + x
            if (d[i].toInt() == 0) continue
            var m = d[i].toInt()
            if (x < s - 1) m = minOf(m, d[i + 1] + 1)
            if (y < s - 1) {
                m = minOf(m, d[i + s] + 1)
                if (x < s - 1) m = minOf(m, d[i + s + 1] + 1)
                if (x > 0) m = minOf(m, d[i + s - 1] + 1)
            }
            d[i] = m.toByte()
        }
        return d
    }

    fun depthFactor(depth: Int): Float = when {
        depth <= 1 -> 1.10f
        depth == 2 -> 1.00f
        depth == 3 -> 0.93f
        else -> 0.87f
    }

    private const val FOREST_CELL = 4
    private const val PARK_CELL = 9
    private const val FOREST_PERCENT = 85
    private const val PARK_PERCENT = 35
}
