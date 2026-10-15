package app.minemaps.engine.mvt

import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

object MvtDecoder {
    fun decode(data: ByteArray): VectorTile {
        val bytes = if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) {
            GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes() }
        } else {
            data
        }
        if (bytes.isEmpty()) return VectorTile.EMPTY
        val r = Reader(bytes, 0, bytes.size)
        val layers = LinkedHashMap<String, Layer>()
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            if (tag ushr 3 == 3 && tag and 7 == 2) {
                val len = r.varint().toInt()
                val layer = decodeLayer(Reader(bytes, r.pos, r.pos + len))
                r.pos += len
                layers[layer.name] = layer
            } else {
                r.skip(tag and 7)
            }
        }
        return VectorTile(layers)
    }

    private class RawFeature(val id: Long, val type: Int, val tags: IntArray, val geometry: IntArray)

    private fun decodeLayer(r: Reader): Layer {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val raws = ArrayList<RawFeature>()
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            when (tag ushr 3) {
                1 -> name = r.string()
                2 -> {
                    val len = r.varint().toInt()
                    raws.add(decodeRawFeature(Reader(r.buf, r.pos, r.pos + len)))
                    r.pos += len
                }
                3 -> keys.add(r.string())
                4 -> {
                    val len = r.varint().toInt()
                    values.add(decodeValue(Reader(r.buf, r.pos, r.pos + len)))
                    r.pos += len
                }
                5 -> extent = r.varint().toInt()
                else -> r.skip(tag and 7)
            }
        }
        val features = ArrayList<Feature>(raws.size)
        for (raw in raws) {
            val type = when (raw.type) {
                1 -> GeomType.POINT
                2 -> GeomType.LINE
                3 -> GeomType.POLYGON
                else -> continue
            }
            val tags = LinkedHashMap<String, Any>()
            var i = 0
            while (i + 1 < raw.tags.size) {
                val k = keys.getOrNull(raw.tags[i])
                val v = values.getOrNull(raw.tags[i + 1])
                if (k != null && v != null) tags[k] = v
                i += 2
            }
            features.add(Feature(raw.id, type, tags, decodeGeometry(type, raw.geometry)))
        }
        return Layer(name, extent, features)
    }

    private fun decodeRawFeature(r: Reader): RawFeature {
        var id = 0L
        var type = 0
        var tags = IntArray(0)
        var geometry = IntArray(0)
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            when (tag ushr 3) {
                1 -> id = r.varint()
                2 -> tags = r.packedInts(tag and 7, tags)
                3 -> type = r.varint().toInt()
                4 -> geometry = r.packedInts(tag and 7, geometry)
                else -> r.skip(tag and 7)
            }
        }
        return RawFeature(id, type, tags, geometry)
    }

    private fun decodeValue(r: Reader): Any {
        var result: Any = ""
        while (r.hasMore()) {
            val tag = r.varint().toInt()
            when (tag ushr 3) {
                1 -> result = r.string()
                2 -> result = java.lang.Float.intBitsToFloat(r.fixed32()).toDouble()
                3 -> result = java.lang.Double.longBitsToDouble(r.fixed64())
                4 -> result = r.varint()
                5 -> result = r.varint()
                6 -> {
                    val v = r.varint()
                    result = (v ushr 1) xor -(v and 1)
                }
                7 -> result = r.varint() != 0L
                else -> r.skip(tag and 7)
            }
        }
        return result
    }

    private class IntBuilder {
        var a = IntArray(16)
        var n = 0
        fun add(x: Int, y: Int) {
            if (n + 2 > a.size) a = a.copyOf(a.size * 2)
            a[n++] = x
            a[n++] = y
        }
        fun build(): IntArray = a.copyOf(n)
    }

    private fun decodeGeometry(type: GeomType, g: IntArray): List<IntArray> {
        val out = ArrayList<IntArray>()
        var cur: IntBuilder? = null
        var x = 0
        var y = 0
        var i = 0
        while (i < g.size) {
            val cmd = g[i++]
            val id = cmd and 7
            val count = cmd ushr 3
            when (id) {
                1 -> repeat(count) {
                    if (i + 1 >= g.size) return out.also { cur?.let { c -> out.add(c.build()) } }
                    x += unzig(g[i++])
                    y += unzig(g[i++])
                    cur?.let { out.add(it.build()) }
                    cur = IntBuilder().also { it.add(x, y) }
                }
                2 -> repeat(count) {
                    if (i + 1 >= g.size) return out.also { cur?.let { c -> out.add(c.build()) } }
                    x += unzig(g[i++])
                    y += unzig(g[i++])
                    cur?.add(x, y)
                }
                7 -> {}
                else -> break
            }
        }
        cur?.let { out.add(it.build()) }
        if (type == GeomType.POLYGON) out.removeAll { it.size < 6 }
        return out
    }

    private fun unzig(v: Int): Int = (v ushr 1) xor -(v and 1)

    private class Reader(val buf: ByteArray, var pos: Int, val end: Int) {
        fun hasMore() = pos < end

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                val b = buf[pos++].toInt()
                result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun fixed32(): Int {
            val v = (buf[pos].toInt() and 0xff) or ((buf[pos + 1].toInt() and 0xff) shl 8) or
                ((buf[pos + 2].toInt() and 0xff) shl 16) or ((buf[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }

        fun fixed64(): Long {
            val lo = fixed32().toLong() and 0xffffffffL
            val hi = fixed32().toLong() and 0xffffffffL
            return lo or (hi shl 32)
        }

        fun string(): String {
            val len = varint().toInt()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> pos += 8
                2 -> pos += varint().toInt()
                5 -> pos += 4
                else -> throw IllegalStateException("unsupported wire type $wireType")
            }
        }

        fun packedInts(wireType: Int, existing: IntArray): IntArray {
            if (wireType == 0) return existing + varint().toInt()
            val len = varint().toInt()
            val stop = pos + len
            var out = IntArray(maxOf(16, len))
            var n = 0
            while (pos < stop) {
                if (n == out.size) out = out.copyOf(out.size * 2)
                out[n++] = varint().toInt()
            }
            return if (existing.isEmpty()) out.copyOf(n) else existing + out.copyOf(n)
        }
    }
}
