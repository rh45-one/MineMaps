package app.minemaps.engine.raster

import app.minemaps.engine.style.Block

class BlockGrid {
    val cls = ByteArray(SIZE * SIZE)
    val aux = ByteArray(SIZE * SIZE)

    fun index(x: Int, y: Int): Int = (y + MARGIN) * SIZE + (x + MARGIN)

    fun clsAt(x: Int, y: Int): Int = cls[index(x, y)].toInt()

    fun auxAt(x: Int, y: Int): Int = aux[index(x, y)].toInt() and 0xff

    fun blockAt(x: Int, y: Int): Block = Block.fromId(clsAt(x, y))

    fun set(x: Int, y: Int, block: Block, auxValue: Int = 0) {
        if (x < -MARGIN || y < -MARGIN || x >= TILE + MARGIN || y >= TILE + MARGIN) return
        val i = index(x, y)
        cls[i] = block.byteId
        aux[i] = auxValue.toByte()
    }

    companion object {
        const val TILE = 256
        const val MARGIN = 4
        const val SIZE = TILE + 2 * MARGIN
    }
}
