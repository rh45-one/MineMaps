package app.minemaps.engine

import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.inspect.FeatureInspector
import app.minemaps.engine.labels.LabelExtractor
import app.minemaps.engine.mvt.MvtDecoder
import app.minemaps.engine.mvt.MvtFormatException
import app.minemaps.engine.mvt.VectorTile
import app.minemaps.engine.raster.GridRasterizer
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class MvtFuzzTest {
    private val vtKey = TileKey(14, 8296, 5636)
    private val child = TileKey(16, 8296 * 4 + 1, 5636 * 4 + 1)

    private class Stats {
        var decoded = 0
        var rejected = 0
        var maxMs = 0L
    }

    private fun tryDecode(bytes: ByteArray, stats: Stats): VectorTile? {
        val t0 = System.nanoTime()
        val tile = try {
            MvtDecoder.decode(bytes).also { stats.decoded++ }
        } catch (e: MvtFormatException) {
            stats.rejected++
            null
        }
        stats.maxMs = maxOf(stats.maxMs, (System.nanoTime() - t0) / 1_000_000)
        return tile
    }

    private fun exercise(tile: VectorTile) {
        GridRasterizer.rasterize(tile, vtKey, vtKey)
        GridRasterizer.rasterize(tile, vtKey, child)
        LabelExtractor.extract(tile, vtKey)
        FeatureInspector.inspect(tile, vtKey, Mercator.lonToX(2.2945), Mercator.latToY(48.8584), 18.0)
    }

    private fun <T> within(ms: Long, name: String, block: () -> T): T {
        val ex = Executors.newSingleThreadExecutor { r -> Thread(r).apply { isDaemon = true } }
        try {
            return ex.submit(Callable { block() }).get(ms, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            fail("$name did not finish within $ms ms")
        } finally {
            ex.shutdownNow()
        }
    }

    private fun varint(v: Long): ByteArray {
        val out = ByteArrayOutputStream()
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
        return out.toByteArray()
    }

    private fun layer(vararg fields: ByteArray): ByteArray {
        val body = ByteArrayOutputStream()
        fields.forEach { body.write(it) }
        val b = body.toByteArray()
        return byteArrayOf(0x1a) + varint(b.size.toLong()) + b
    }

    private fun extentField(v: Long) = byteArrayOf(0x28) + varint(v)

    private fun nameField(n: String) = byteArrayOf(0x0a) + varint(n.length.toLong()) + n.toByteArray()

    @Test
    fun randomBytesNeverHangOrThrowOtherExceptions() {
        val rnd = Random(12345)
        val stats = Stats()
        val t0 = System.nanoTime()
        repeat(20_000) {
            val bytes = ByteArray(rnd.nextInt(4097)).also { rnd.nextBytes(it) }
            tryDecode(bytes, stats)?.let { exercise(it) }
        }
        val total = (System.nanoTime() - t0) / 1_000_000
        println("fuzz random: inputs=20000 decoded=${stats.decoded} rejected=${stats.rejected} maxDecodeMs=${stats.maxMs} totalMs=$total")
        assertEquals(20_000, stats.decoded + stats.rejected)
        assertTrue(total < 20_000, "random fuzz took $total ms")
        assertTrue(stats.maxMs < 500)
    }

    @Test
    fun mutatedFixtureNeverHangsAndDownstreamNeverThrows() {
        val base = TestSupport.fixtureBytes()
        val rnd = Random(777)
        val stats = Stats()
        var exercised = 0
        var maxExerciseMs = 0L
        val t0 = System.nanoTime()
        repeat(5_000) { i ->
            val b = base.copyOf()
            var out = b
            when (i % 5) {
                0 -> repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = (b[rnd.nextInt(b.size)].toInt() xor (1 shl rnd.nextInt(8))).toByte() }
                1 -> repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
                2 -> out = b.copyOf(rnd.nextInt(b.size))
                3 -> {
                    val at = rnd.nextInt(b.size)
                    out = b.copyOfRange(0, at) + varint(rnd.nextLong() ushr rnd.nextInt(64)) + b.copyOfRange(at, b.size)
                }
                else -> {
                    val at = rnd.nextInt(b.size)
                    out = b.copyOfRange(0, at) + ByteArray(1 + rnd.nextInt(4)).also { rnd.nextBytes(it) } + b.copyOfRange(at + rnd.nextInt(minOf(50, b.size - at)), b.size)
                }
            }
            val tile = tryDecode(out, stats)
            if (tile != null) {
                val e0 = System.nanoTime()
                exercise(tile)
                maxExerciseMs = maxOf(maxExerciseMs, (System.nanoTime() - e0) / 1_000_000)
                exercised++
            }
        }
        val total = (System.nanoTime() - t0) / 1_000_000
        println("fuzz mutations: inputs=5000 decoded=${stats.decoded} rejected=${stats.rejected} maxDecodeMs=${stats.maxMs} maxExerciseMs=$maxExerciseMs exercised=$exercised totalMs=$total")
        assertEquals(5_000, stats.decoded + stats.rejected)
        assertTrue(exercised > 100, "too few mutants decoded to be meaningful: $exercised")
    }

    @Test
    fun craftedNegativeAndOversizedLengthsAreRejected() {
        val negative = byteArrayOf(0x1a) + varint(-1L)
        val huge = byteArrayOf(0x1a) + varint(Int.MAX_VALUE.toLong()) + byteArrayOf(1, 2, 3)
        val hugeLong = byteArrayOf(0x1a) + varint(Long.MAX_VALUE)
        for ((name, bytes) in listOf("negative" to negative, "int-max" to huge, "long-max" to hugeLong)) {
            within(2000, name) {
                try {
                    MvtDecoder.decode(bytes)
                    fail("$name should be rejected")
                } catch (_: MvtFormatException) {
                }
            }
        }
    }

    @Test
    fun elevenByteVarintIsRejected() {
        val bytes = ByteArray(11) { 0x80.toByte() } + byteArrayOf(0x01)
        within(2000, "varint11") {
            try {
                MvtDecoder.decode(bytes)
                fail("should be rejected")
            } catch (_: MvtFormatException) {
            }
        }
    }

    @Test
    fun skippingWithBadLengthsCannotLoop() {
        // unknown field 9, wire type 2, length -1 / past end; wire types 3, 4, 6, 7
        for (tail in listOf(byteArrayOf(0x4a) + varint(-1L), byteArrayOf(0x4a, 0x7f), byteArrayOf(0x4b), byteArrayOf(0x4c), byteArrayOf(0x4e), byteArrayOf(0x4f), byteArrayOf(0x49, 1, 2))) {
            within(2000, "skip") {
                try {
                    MvtDecoder.decode(tail)
                    fail("should be rejected")
                } catch (_: MvtFormatException) {
                }
            }
        }
    }

    @Test
    fun gzipBombIsRejectedQuickly() {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { gz ->
            val chunk = ByteArray(1 shl 20)
            repeat(64) { gz.write(chunk) }
        }
        val bomb = bos.toByteArray()
        assertTrue(bomb.size < 200_000)
        within(2000, "gzip bomb") {
            try {
                MvtDecoder.decode(bomb)
                fail("should be rejected")
            } catch (e: MvtFormatException) {
                assertTrue(e.message!!.contains("too large"))
            }
        }
        val truncated = TestSupport.fixtureBytes().let { raw ->
            val b = ByteArrayOutputStream()
            GZIPOutputStream(b).use { it.write(raw) }
            b.toByteArray().let { it.copyOf(it.size / 2) }
        }
        within(2000, "truncated gzip") {
            try {
                MvtDecoder.decode(truncated)
                fail("should be rejected")
            } catch (_: MvtFormatException) {
            }
        }
    }

    @Test
    fun badExtentLayersAreSkipped() {
        for (extent in listOf(0L, -1L, 65537L, Int.MAX_VALUE.toLong() + 5, Long.MAX_VALUE)) {
            within(2000, "extent $extent") {
                val tile = MvtDecoder.decode(layer(nameField("x"), extentField(extent)))
                assertTrue(tile.layers.isEmpty(), "extent $extent")
            }
        }
        assertEquals(1, MvtDecoder.decode(layer(nameField("x"), extentField(65536))).layers.size)
        assertEquals(1, MvtDecoder.decode(layer(nameField("x"), extentField(1))).layers.size)
    }

    @Test
    fun layerCountLimitIsEnforced() {
        val one = layer(nameField("l"))
        val bomb = ByteArrayOutputStream().also { out -> repeat(MvtDecoder.MAX_LAYERS + 1) { out.write(one) } }.toByteArray()
        within(2000, "layers") {
            try {
                MvtDecoder.decode(bomb)
                fail("should be rejected")
            } catch (_: MvtFormatException) {
            }
        }
    }

    @Test
    fun manyUnpackedGeometryIntsStayLinearAndBounded() {
        // one feature with 300k single-varint geometry entries (field 4, wire type 0)
        val feat = ByteArrayOutputStream()
        repeat(300_000) { feat.write(0x20); feat.write(1) }
        val f = feat.toByteArray()
        val bytes = layer(nameField("l"), byteArrayOf(0x12) + varint(f.size.toLong()) + f)
        within(2000, "unpacked ints") { MvtDecoder.decode(bytes) }
        val tooMany = ByteArrayOutputStream()
        repeat(MvtDecoder.MAX_GEOMETRY_INTS + 10) { tooMany.write(0x20); tooMany.write(1) }
        val g = tooMany.toByteArray()
        val big = layer(nameField("l"), byteArrayOf(0x12) + varint(g.size.toLong()) + g)
        within(5000, "geometry limit") {
            try {
                MvtDecoder.decode(big)
                fail("should be rejected")
            } catch (_: MvtFormatException) {
            }
        }
    }
}
