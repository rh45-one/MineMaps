package app.minemaps.tools.render

import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.mvt.MvtDecoder
import app.minemaps.engine.raster.BlockGrid
import app.minemaps.engine.raster.GridRasterizer
import app.minemaps.engine.render.TileCompositor
import java.io.File
import java.util.zip.CRC32

private const val WARMUP = 5
private const val RUNS = 50

private fun crc(grid: BlockGrid): Long {
    val c = CRC32()
    c.update(grid.cls)
    c.update(grid.aux)
    return c.value
}

private fun crc(pixels: IntArray): Long {
    val c = CRC32()
    val b = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        b[4 * i] = (p ushr 24).toByte()
        b[4 * i + 1] = (p ushr 16).toByte()
        b[4 * i + 2] = (p ushr 8).toByte()
        b[4 * i + 3] = p.toByte()
    }
    c.update(b)
    return c.value
}

private fun measure(name: String, block: () -> Long) {
    var checksum = 0L
    repeat(WARMUP) { checksum = block() }
    val ms = DoubleArray(RUNS)
    for (i in 0 until RUNS) {
        val t0 = System.nanoTime()
        checksum = block()
        ms[i] = (System.nanoTime() - t0) / 1e6
    }
    val sorted = ms.sorted()
    val p95 = sorted[(RUNS * 0.95).toInt().coerceAtMost(RUNS - 1)]
    println("%-28s mean=%7.2f ms  p95=%7.2f ms  min=%7.2f ms  crc=%08x".format(name, ms.average(), p95, sorted.first(), checksum))
}

fun runBench() {
    val file = File("mapengine/src/test/resources/fixtures/14_8296_5636.pbf")
    val tile = MvtDecoder.decode(file.readBytes())
    val atlas = loadAtlas()
    val vt = TileKey(14, 8296, 5636)
    val g16 = TileKey(16, 33185, 22545)
    val compositor = TileCompositor(atlas)
    val grid = GridRasterizer.rasterize(tile, vt, g16)
    println("bench: single-threaded, $WARMUP warm-up + $RUNS timed iterations, fixture ${file.name}")
    measure("rasterize z16 grid") { crc(GridRasterizer.rasterize(tile, vt, g16)) }
    measure("rasterize z14 grid") { crc(GridRasterizer.rasterize(tile, vt, vt)) }
    measure("compose overview z16") { crc(compositor.compose(grid, g16, false)) }
    measure("compose textured z18") { crc(compositor.compose(grid, TileKey(18, 33185 * 4 + 1, 22545 * 4 + 2), false)) }
    measure("compose textured z20") { crc(compositor.compose(grid, TileKey(20, 33185 * 16 + 5, 22545 * 16 + 7), false)) }
}
