package app.minemaps.engine

import app.minemaps.engine.mvt.GeomType
import app.minemaps.engine.mvt.MvtDecoder
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class Pb {
    val out = ByteArrayOutputStream()

    fun varint(v: Long) {
        var x = v
        while (x and 0x7fL.inv() != 0L) {
            out.write(((x and 0x7f) or 0x80).toInt())
            x = x ushr 7
        }
        out.write(x.toInt())
    }

    fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

    fun uint(field: Int, v: Long) {
        tag(field, 0)
        varint(v)
    }

    fun bytes(field: Int, b: ByteArray) {
        tag(field, 2)
        varint(b.size.toLong())
        out.write(b)
    }

    fun str(field: Int, s: String) = bytes(field, s.toByteArray())

    fun packed(field: Int, values: List<Int>) {
        val p = Pb()
        values.forEach { p.varint(it.toLong() and 0xffffffffL) }
        bytes(field, p.out.toByteArray())
    }

    fun fixed64(field: Int, bits: Long) {
        tag(field, 1)
        for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xff).toInt())
    }

    fun toBytes(): ByteArray = out.toByteArray()
}

private fun zig(v: Int) = (v shl 1) xor (v shr 31)
private fun cmd(id: Int, count: Int) = (count shl 3) or id

private fun moveTo(vararg xy: Int) = listOf(cmd(1, xy.size / 2)) + xy.map { zig(it) }
private fun lineTo(vararg xy: Int) = listOf(cmd(2, xy.size / 2)) + xy.map { zig(it) }
private val close = listOf(cmd(7, 1))

private fun feature(id: Long?, type: Int, tags: List<Int>, geom: List<Int>): ByteArray {
    val f = Pb()
    if (id != null) f.uint(1, id)
    f.packed(2, tags)
    f.uint(3, type.toLong())
    f.packed(4, geom)
    return f.toBytes()
}

private fun value(build: Pb.() -> Unit): ByteArray = Pb().apply(build).toBytes()

class MvtDecoderTest {
    private fun sampleTile(): ByteArray {
        val l = Pb()
        l.uint(15, 2)
        l.str(1, "test")
        l.bytes(2, feature(7, 1, listOf(0, 0, 1, 1), moveTo(25, 17)))
        l.bytes(2, feature(8, 2, listOf(2, 2, 3, 3), moveTo(2, 2) + lineTo(0, 8, 8, 0)))
        val polyGeom = moveTo(0, 0) + lineTo(10, 0, 0, 10, -10, 0) + close +
            moveTo(3, -7) + lineTo(0, 4, 4, 0, 0, -4) + close
        l.bytes(2, feature(9, 3, listOf(4, 4), polyGeom))
        for (k in listOf("name", "n", "w", "flag", "ratio")) l.str(3, k)
        l.bytes(4, value { str(1, "Pt") })
        l.bytes(4, value { uint(4, 42) })
        l.bytes(4, value { fixed64(3, java.lang.Double.doubleToLongBits(1.5)) })
        l.bytes(4, value { uint(7, 1) })
        l.bytes(4, value { uint(6, zig(-5).toLong()) })
        l.uint(5, 4096)
        val tile = Pb()
        tile.bytes(3, l.toBytes())
        return tile.toBytes()
    }

    @Test
    fun decodesHandBuiltTile() {
        val tile = MvtDecoder.decode(sampleTile())
        val layer = tile.layers.getValue("test")
        assertEquals(4096, layer.extent)
        assertEquals(3, layer.features.size)

        val point = layer.features[0]
        assertEquals(GeomType.POINT, point.type)
        assertEquals(7L, point.id)
        assertEquals(1, point.geometry.size)
        assertContentEquals(intArrayOf(25, 17), point.geometry[0])
        assertEquals("Pt", point.tags["name"])
        assertEquals(42L, point.tags["n"])

        val line = layer.features[1]
        assertEquals(GeomType.LINE, line.type)
        assertEquals(1, line.geometry.size)
        assertContentEquals(intArrayOf(2, 2, 2, 10, 10, 10), line.geometry[0])
        assertEquals(1.5, line.tags["w"])
        assertEquals(true, line.tags["flag"])

        val poly = layer.features[2]
        assertEquals(GeomType.POLYGON, poly.type)
        assertEquals(2, poly.geometry.size)
        assertContentEquals(intArrayOf(0, 0, 10, 0, 10, 10, 0, 10), poly.geometry[0])
        assertContentEquals(intArrayOf(3, 3, 3, 7, 7, 7, 7, 3), poly.geometry[1])
        assertEquals(-5L, poly.tags["ratio"])
    }

    @Test
    fun multiPointProducesOneArrayPerPoint() {
        val l = Pb()
        l.str(1, "pts")
        l.bytes(2, feature(null, 1, emptyList(), listOf(cmd(1, 2), zig(5), zig(6), zig(1), zig(-2))))
        val tile = Pb().also { it.bytes(3, l.toBytes()) }.toBytes()
        val f = MvtDecoder.decode(tile).layers.getValue("pts").features.single()
        assertEquals(2, f.geometry.size)
        assertContentEquals(intArrayOf(5, 6), f.geometry[0])
        assertContentEquals(intArrayOf(6, 4), f.geometry[1])
        assertEquals(4096, MvtDecoder.decode(tile).layers.getValue("pts").extent)
    }

    @Test
    fun gzipInputIsDecodedTransparently() {
        val raw = sampleTile()
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(raw) }
        val gz = bos.toByteArray()
        assertTrue(gz[0] == 0x1f.toByte() && gz[1] == 0x8b.toByte())
        val a = MvtDecoder.decode(raw).layers.getValue("test")
        val b = MvtDecoder.decode(gz).layers.getValue("test")
        assertEquals(a.features.size, b.features.size)
        assertContentEquals(a.features[2].geometry[1], b.features[2].geometry[1])
    }
}
