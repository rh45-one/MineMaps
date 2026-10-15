package app.minemaps.engine.render

import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.raster.BlockGrid
import app.minemaps.engine.style.Block
import app.minemaps.engine.util.Hash

class TileCompositor(private val atlas: TextureAtlas) {
    private val blocks = Block.entries.toTypedArray()
    private val overviewColor = IntArray(blocks.size) { atlas.averageColor(blocks[it].texture) }
    private val residentialLowZoom: Int = run {
        val c = listOf("stone", "roof_brick", "roof_concrete", "grass").map { atlas.averageColor(it) }
        fun avg(shift: Int) = c.sumOf { (it shr shift) and 0xff } / c.size
        (0xff shl 24) or (avg(16) shl 16) or (avg(8) shl 8) or avg(0)
    }
    private val materialColor = IntArray(Block.BUILDING_MATERIALS.size) { atlas.averageColor(Block.BUILDING_MATERIALS[it]) }

    private val slotNames = ArrayList<String>()
    private fun slot(name: String): Int {
        val i = slotNames.indexOf(name)
        if (i >= 0) return i
        slotNames.add(name)
        return slotNames.size - 1
    }
    private val blockSlot = IntArray(blocks.size) { slot(blocks[it].texture) }
    private val materialSlot = IntArray(Block.BUILDING_MATERIALS.size) { slot(Block.BUILDING_MATERIALS[it]) }
    private val graveSlot = slot("grave")
    private val grassSlot = slot(Block.GRASS.texture)
    private val variantCounts = IntArray(slotNames.size) { atlas.variantCount(slotNames[it]) }
    private val slotMips = Array(slotNames.size) { s ->
        Array(variantCounts[s]) { v -> Array(TextureAtlas.MIP_SIZES.size) { m -> atlas.mip(slotNames[s], v, TextureAtlas.MIP_SIZES[m]) } }
    }

    fun compose(grid: BlockGrid, key: TileKey, night: Boolean): IntArray {
        require(key.z in 0..20) { "unsupported zoom ${key.z}" }
        val out = IntArray(TILE * TILE)
        val nr = if (night) NIGHT_R else 1f
        val ng = if (night) NIGHT_G else 1f
        val nb = if (night) NIGHT_B else 1f
        if (key.z <= GRID_ZOOM) overview(grid, key, out, nr, ng, nb) else textured(grid, key, out, nr, ng, nb)
        return out
    }

    private class Context(val depth: ByteArray?, val canopy: BooleanArray?, val canopyWidth: Int, val canopyX0: Int, val canopyY0: Int) {
        fun canopyAt(x: Int, y: Int): Boolean = canopy != null && canopy[(y - canopyY0 + 1) * canopyWidth + (x - canopyX0 + 1)]
    }

    private fun overview(grid: BlockGrid, key: TileKey, out: IntArray, nr: Float, ng: Float, nb: Float) {
        val shadow = key.z >= GRID_ZOOM
        val trees = key.z == GRID_ZOOM && Terrain.hasTreeBlocks(grid)
        val canopy = if (trees) Terrain.canopy(grid, key.x * TILE, key.y * TILE, 0, 0, TILE) else null
        val ctx = Context(Terrain.waterDepth(grid), canopy, TILE + 2, 0, 0)
        val forestSlot = blockSlot[Block.FOREST.id]
        val leafMip = TextureAtlas.MIP_SIZES.size - 1
        for (y in 0 until TILE) for (x in 0 until TILE) {
            val cls = grid.clsAt(x, y)
            var f = 1f
            val base = if (cls == Block.BUILDING.id) {
                materialColor[Block.auxMaterial(grid.auxAt(x, y)).coerceIn(0, materialColor.size - 1)]
            } else if (cls == Block.RESIDENTIAL.id && key.z <= RESIDENTIAL_TINT_MAX_ZOOM) {
                residentialLowZoom
            } else if (canopy != null && ctx.canopyAt(x, y)) {
                val h = Hash.mix(key.x * TILE + x, key.y * TILE + y, 7)
                slotMips[forestSlot][h % variantCounts[forestSlot]][leafMip][0]
            } else if (canopy != null && cls == Block.FOREST.id) {
                overviewColor[Block.GRASS.id]
            } else {
                overviewColor[cls.coerceIn(0, overviewColor.size - 1)]
            }
            val variation = 0.94f + 0.12f * (Hash.mix(key.x * TILE + x, key.y * TILE + y, key.z) and 0xff) / 255f
            f = variation * shading(grid, x, y, shadow, ctx)
            out[y * TILE + x] = tint(base, f * nr, f * ng, f * nb)
        }
    }

    private fun textured(grid: BlockGrid, key: TileKey, out: IntArray, nr: Float, ng: Float, nb: Float) {
        val dz = key.z - GRID_ZOOM
        val s = 1 shl dz
        val perTile = TILE / s
        val ax = key.x shr dz
        val ay = key.y shr dz
        val offX = (key.x - (ax shl dz)) * perTile
        val offY = (key.y - (ay shl dz)) * perTile
        val canopy = if (Terrain.hasTreeBlocks(grid)) Terrain.canopy(grid, ax * TILE, ay * TILE, offX, offY, perTile) else null
        val ctx = Context(Terrain.waterDepth(grid), canopy, perTile + 2, offX, offY)
        for (by in 0 until perTile) for (bx in 0 until perTile) {
            val lx = offX + bx
            val ly = offY + by
            val gbx = ax * TILE + lx
            val gby = ay * TILE + ly
            val cls = grid.clsAt(lx, ly)
            val block = Block.fromId(cls)
            val f = shading(grid, lx, ly, true, ctx)
            var slot = blockSlot[cls.coerceIn(0, blockSlot.size - 1)]
            var variant: Int
            val h = Hash.mix(gbx, gby, 7)
            when (block) {
                Block.BUILDING -> {
                    slot = materialSlot[Block.auxMaterial(grid.auxAt(lx, ly)).coerceIn(0, materialSlot.size - 1)]
                    variant = h % variantCounts[slot]
                }
                Block.PARK -> {
                    if (ctx.canopyAt(lx, ly)) {
                        slot = blockSlot[Block.FOREST.id]
                        variant = h % variantCounts[slot]
                    } else {
                        val n = variantCounts[slot]
                        val sub = (h ushr 8) % 2
                        variant = if (n >= 4 && h % 100 < 6) 2 + sub else sub % n
                    }
                }
                Block.CEMETERY -> {
                    if (h % 100 < 12) {
                        slot = graveSlot
                        variant = 0
                    } else {
                        slot = grassSlot
                        variant = (h ushr 8) % variantCounts[slot]
                    }
                }
                Block.FOREST -> {
                    if (!ctx.canopyAt(lx, ly)) slot = grassSlot
                    variant = h % variantCounts[slot]
                }
                else -> variant = h % variantCounts[slot]
            }
            val pixels = slotMips[slot][variant][TextureAtlas.MIP_SIZES.size - 1 - dz]
            val fr = f * nr
            val fg = f * ng
            val fb = f * nb
            for (yy in 0 until s) {
                val o = (by * s + yy) * TILE + bx * s
                for (xx in 0 until s) out[o + xx] = tint(pixels[yy * s + xx], fr, fg, fb)
            }
        }
    }

    private fun shading(grid: BlockGrid, x: Int, y: Int, shadow: Boolean, ctx: Context): Float {
        val cls = grid.clsAt(x, y)
        var f = 1f
        if (cls == Block.BUILDING.id) {
            if (grid.clsAt(x, y + 1) != Block.BUILDING.id) f *= 0.72f
            if (grid.clsAt(x + 1, y) != Block.BUILDING.id) f *= 0.85f
            if (grid.clsAt(x, y - 1) != Block.BUILDING.id) f *= 1.12f
        } else if (cls == Block.WATER.id) {
            val depth = ctx.depth
            if (depth != null) f *= Terrain.depthFactor(depth[(y + BlockGrid.MARGIN) * BlockGrid.SIZE + x + BlockGrid.MARGIN].toInt())
        } else {
            if (ctx.canopy != null && Terrain.isTreeBlock(cls)) {
                if (ctx.canopyAt(x, y)) {
                    if (!ctx.canopyAt(x, y - 1) || !ctx.canopyAt(x - 1, y)) f *= 1.12f
                    if (!ctx.canopyAt(x, y + 1) || !ctx.canopyAt(x + 1, y)) f *= 0.80f
                } else if (cls == Block.FOREST.id) {
                    f *= 0.80f
                    if (ctx.canopyAt(x - 1, y - 1)) f *= 0.75f
                }
            }
            if (shadow) {
                for (d in 1..BlockGrid.MARGIN) {
                    if (grid.clsAt(x - d, y - d) == Block.BUILDING.id &&
                        d <= 1 + Block.auxHeightBucket(grid.auxAt(x - d, y - d))
                    ) {
                        f *= 0.78f
                        break
                    }
                }
            }
        }
        return f
    }

    companion object {
        const val TILE = 256
        const val GRID_ZOOM = 16
        const val RESIDENTIAL_TINT_MAX_ZOOM = 13
        const val NIGHT_R = 0.42f
        const val NIGHT_G = 0.48f
        const val NIGHT_B = 0.68f

        fun tint(argb: Int, fr: Float, fg: Float, fb: Float): Int {
            val r = (((argb shr 16) and 0xff) * fr + 0.5f).toInt().coerceIn(0, 255)
            val g = (((argb shr 8) and 0xff) * fg + 0.5f).toInt().coerceIn(0, 255)
            val b = ((argb and 0xff) * fb + 0.5f).toInt().coerceIn(0, 255)
            return (0xff shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
