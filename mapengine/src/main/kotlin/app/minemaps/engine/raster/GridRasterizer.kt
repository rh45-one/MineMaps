package app.minemaps.engine.raster

import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.mvt.Feature
import app.minemaps.engine.mvt.GeomType
import app.minemaps.engine.mvt.VectorTile
import app.minemaps.engine.style.Block
import app.minemaps.engine.style.FeatureClassifier
import app.minemaps.engine.style.RenderOp
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

object GridRasterizer {
    private const val LO = -BlockGrid.MARGIN
    private const val HI = BlockGrid.TILE + BlockGrid.MARGIN
    private const val MAX_LINE_WIDTH_M = 40.0

    fun rasterize(tile: VectorTile, vtKey: TileKey, gridKey: TileKey): BlockGrid {
        require(gridKey.z >= vtKey.z) { "grid zoom ${gridKey.z} below vector tile zoom ${vtKey.z}" }
        require(gridKey.ancestor(vtKey.z) == vtKey) { "grid $gridKey is not inside vector tile $vtKey" }
        val grid = BlockGrid()
        val gz = gridKey.z
        val metersPerCell = Mercator.metersPerBlock(gridKey.centerLat()) * (1 shl (Mercator.BLOCK_ZOOM - min(gz, Mercator.BLOCK_ZOOM)))
        val scan = Scanline()
        val dz = gz - vtKey.z
        val maxHalfWidthCells = FeatureClassifier.widthInCells(MAX_LINE_WIDTH_M, metersPerCell) / 2.0 + 1.0
        val phases = Array(FeatureClassifier.PHASE_BUILDING + 1) { ArrayList<DrawOp>() }
        for (layerName in FeatureClassifier.LAYER_ORDER) {
            val layer = tile.layers[layerName] ?: continue
            val scale = layer.extent.toDouble() * (1.0 / (1 shl dz)) / BlockGrid.TILE
            val ox = (gridKey.x.toLong() - (vtKey.x.toLong() shl dz)).toDouble() * BlockGrid.TILE * scale
            val oy = (gridKey.y.toLong() - (vtKey.y.toLong() shl dz)).toDouble() * BlockGrid.TILE * scale
            val infl = maxHalfWidthCells * scale
            val areaMinX = ox + LO * scale - infl
            val areaMaxX = ox + HI * scale + infl
            val areaMinY = oy + LO * scale - infl
            val areaMaxY = oy + HI * scale + infl
            for (f in layer.features) {
                val bb = f.bounds()
                if (bb[2] < areaMinX || bb[0] > areaMaxX || bb[3] < areaMinY || bb[1] > areaMaxY) continue
                val op = FeatureClassifier.classify(layerName, f, gz) ?: continue
                val aux = if (op.block == Block.BUILDING) FeatureClassifier.buildingAux(f, layer.extent, vtKey) else 0
                phases[op.phase].add(DrawOp(op, f, aux, layer.extent, scale, ox, oy))
            }
        }
        phases[FeatureClassifier.PHASE_TRANSPORT].sortByDescending { it.op.widthM }
        for (phase in phases) for (d in phase) {
            val op = d.op
            val f = d.feature
            val aux = d.aux
            val scale = d.scale
            val ox = d.ox
            val oy = d.oy
            if (op.isFill) {
                if (f.type == GeomType.POLYGON) fillPolygon(grid, scan, f, ox, oy, scale, op.block, aux)
            } else if (f.type == GeomType.LINE) {
                val widthCells = FeatureClassifier.widthInCells(op.widthM, metersPerCell)
                for (path in f.geometry) drawLine(grid, path, ox, oy, scale, widthCells / 2.0, op.block, aux)
            }
        }
        return grid
    }

    private class DrawOp(val op: RenderOp, val feature: Feature, val aux: Int, val extent: Int, val scale: Double, val ox: Double, val oy: Double)

    private class Scanline {
        val rows = HI - LO
        var xs = Array(rows) { DoubleArray(8) }
        val counts = IntArray(rows)

        fun reset() = counts.fill(0)

        fun add(row: Int, x: Double) {
            var a = xs[row]
            if (counts[row] == a.size) {
                a = a.copyOf(a.size * 2)
                xs[row] = a
            }
            a[counts[row]++] = x
        }
    }

    private fun fillPolygon(grid: BlockGrid, scan: Scanline, f: Feature, ox: Double, oy: Double, scale: Double, block: Block, aux: Int) {
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        var minX = Double.MAX_VALUE
        var maxX = -Double.MAX_VALUE
        for (ring in f.geometry) {
            var i = 0
            while (i < ring.size) {
                val x = (ring[i] - ox) / scale
                val y = (ring[i + 1] - oy) / scale
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
        if (maxX < LO || minX > HI || maxY < LO || minY > HI) return
        scan.reset()
        for (ring in f.geometry) {
            val n = ring.size / 2
            var j = n - 1
            for (i in 0 until n) {
                val x0 = (ring[2 * j] - ox) / scale
                val y0 = (ring[2 * j + 1] - oy) / scale
                val x1 = (ring[2 * i] - ox) / scale
                val y1 = (ring[2 * i + 1] - oy) / scale
                j = i
                if (y0 == y1) continue
                val lo = min(y0, y1)
                val hi = max(y0, y1)
                val rFirst = max(ceil(lo - 0.5).toInt(), LO)
                val rLast = min(ceil(hi - 0.5).toInt() - 1, HI - 1)
                for (row in rFirst..rLast) {
                    val yc = row + 0.5
                    val t = (yc - y0) / (y1 - y0)
                    scan.add(row - LO, x0 + t * (x1 - x0))
                }
            }
        }
        for (row in LO until HI) {
            val c = scan.counts[row - LO]
            if (c < 2) continue
            val xs = scan.xs[row - LO]
            java.util.Arrays.sort(xs, 0, c)
            var k = 0
            while (k + 1 < c) {
                val first = max(ceil(xs[k] - 0.5).toInt(), LO)
                val last = min(ceil(xs[k + 1] - 0.5).toInt() - 1, HI - 1)
                for (x in first..last) grid.set(x, row, block, aux)
                k += 2
            }
        }
    }

    private fun drawLine(grid: BlockGrid, path: IntArray, ox: Double, oy: Double, scale: Double, hw: Double, block: Block, aux: Int) {
        val n = path.size / 2
        if (n == 0) return
        if (n == 1) {
            capsule(grid, (path[0] - ox) / scale, (path[1] - oy) / scale, (path[0] - ox) / scale, (path[1] - oy) / scale, hw, block, aux)
            return
        }
        for (i in 0 until n - 1) {
            capsule(
                grid,
                (path[2 * i] - ox) / scale, (path[2 * i + 1] - oy) / scale,
                (path[2 * i + 2] - ox) / scale, (path[2 * i + 3] - oy) / scale,
                hw, block, aux,
            )
        }
    }

    private fun capsule(grid: BlockGrid, x0: Double, y0: Double, x1: Double, y1: Double, hw: Double, block: Block, aux: Int) {
        if (max(x0, x1) + hw < LO || min(x0, x1) - hw > HI || max(y0, y1) + hw < LO || min(y0, y1) - hw > HI) return
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        val hw2 = hw * hw
        val rFirst = max(ceil(min(y0, y1) - hw - 0.5).toInt(), LO)
        val rLast = min(floor(max(y0, y1) + hw - 0.5).toInt(), HI - 1)
        for (row in rFirst..rLast) {
            val yc = row + 0.5
            val bandLo = yc - hw
            val bandHi = yc + hw
            var xa: Double
            var xb: Double
            if (dy == 0.0) {
                xa = min(x0, x1)
                xb = max(x0, x1)
            } else {
                val t0 = ((bandLo - y0) / dy).coerceIn(0.0, 1.0)
                val t1 = ((bandHi - y0) / dy).coerceIn(0.0, 1.0)
                val xs0 = x0 + dx * t0
                val xs1 = x0 + dx * t1
                xa = min(xs0, xs1)
                xb = max(xs0, xs1)
            }
            val cFirst = max(ceil(xa - hw - 0.5).toInt(), LO)
            val cLast = min(floor(xb + hw - 0.5).toInt(), HI - 1)
            for (col in cFirst..cLast) {
                val xc = col + 0.5
                val d2 = if (len2 == 0.0) {
                    (xc - x0) * (xc - x0) + (yc - y0) * (yc - y0)
                } else {
                    val t = (((xc - x0) * dx + (yc - y0) * dy) / len2).coerceIn(0.0, 1.0)
                    val px = x0 + t * dx - xc
                    val py = y0 + t * dy - yc
                    px * px + py * py
                }
                if (d2 <= hw2) grid.set(col, row, block, aux)
            }
        }
    }
}
