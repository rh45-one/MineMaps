package app.minemaps.engine.mvt

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

class MvtFormatException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

object MvtDecoder {
    const val MAX_COMPRESSED_BYTES = 8 * 1024 * 1024
    const val MAX_DECOMPRESSED_BYTES = 16 * 1024 * 1024
    const val MAX_LAYERS = 200
    const val MAX_FEATURES = 200_000
    const val MAX_GEOMETRY_INTS = 2_000_000
    private const val MAX_EXTENT = 65536

    private class Budget {
        var features = 0
        var geometryInts = 0
    }

    fun decode(data: ByteArray): VectorTile {
        try {
            return decodeInternal(data)
        } catch (e: MvtFormatException) {
            throw e
        } catch (e: IndexOutOfBoundsException) {
            throw MvtFormatException("malformed tile", e)
        } catch (e: NegativeArraySizeException) {
            throw MvtFormatException("malformed tile", e)
        } catch (e: ArithmeticException) {
            throw MvtFormatException("malformed tile", e)
        } catch (e: IllegalArgumentException) {
            throw MvtFormatException("malformed tile", e)
        } catch (e: IllegalStateException) {
            throw MvtFormatException("malformed tile", e)
        }
    }

    private fun decodeInternal(data: ByteArray): VectorTile {
        val bytes = if (data.size >= 2 && data[0] == 0x1f.toByte() && data[1] == 0x8b.toByte()) gunzip(data) else data
        if (bytes.isEmpty()) return VectorTile.EMPTY
        val r = Reader(bytes, 0, bytes.size)
        val layers = LinkedHashMap<String, Layer>()
        val budget = Budget()
        var layerCount = 0
        while (r.hasMore()) {
            val tag = r.tag()
            if (tag ushr 3 == 3 && tag and 7 == 2) {
                if (++layerCount > MAX_LAYERS) throw MvtFormatException("too many layers")
                val len = r.length()
                val layer = decodeLayer(Reader(bytes, r.pos, r.pos + len), budget)
                r.pos += len
                if (layer != null) layers[layer.name] = layer
            } else {
                r.skip(tag and 7)
            }
        }
        return VectorTile(layers)
    }

    private fun gunzip(data: ByteArray): ByteArray {
        if (data.size > MAX_COMPRESSED_BYTES) throw MvtFormatException("compressed tile too large")
        try {
            GZIPInputStream(ByteArrayInputStream(data)).use { input ->
                val out = ByteArrayOutputStream(minOf(data.size * 4, 1 shl 20))
                val buf = ByteArray(16 * 1024)
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_DECOMPRESSED_BYTES) throw MvtFormatException("decompressed tile too large")
                    out.write(buf, 0, n)
                }
                return out.toByteArray()
            }
        } catch (e: ZipException) {
            throw MvtFormatException("bad gzip data", e)
        } catch (e: java.io.EOFException) {
            throw MvtFormatException("truncated gzip data", e)
        } catch (e: java.io.IOException) {
            throw MvtFormatException("gzip error", e)
        }
    }

    private class IntList(private val limit: Int) {
        var a = IntArray(8)
        var n = 0
        fun add(v: Int) {
            if (n >= limit) throw MvtFormatException("too much geometry")
            if (n == a.size) a = a.copyOf(a.size * 2)
            a[n++] = v
        }
        fun toArray(): IntArray = a.copyOf(n)
    }

    private class RawFeature(val id: Long, val type: Int, val tags: IntArray, val geometry: IntArray)

    private fun decodeLayer(r: Reader, budget: Budget): Layer? {
        var name = ""
        var extent = 4096
        val keys = ArrayList<String>()
        val values = ArrayList<Any>()
        val raws = ArrayList<RawFeature>()
        while (r.hasMore()) {
            val tag = r.tag()
            when (tag ushr 3) {
                1 -> name = r.string()
                2 -> {
                    val len = r.length()
                    if (++budget.features > MAX_FEATURES) throw MvtFormatException("too many features")
                    val f = decodeRawFeature(Reader(r.buf, r.pos, r.pos + len), MAX_GEOMETRY_INTS - budget.geometryInts)
                    budget.geometryInts += f.geometry.size
                    if (budget.geometryInts > MAX_GEOMETRY_INTS) throw MvtFormatException("too much geometry")
                    raws.add(f)
                    r.pos += len
                }
                3 -> keys.add(r.string())
                4 -> {
                    val len = r.length()
                    values.add(decodeValue(Reader(r.buf, r.pos, r.pos + len)))
                    r.pos += len
                }
                5 -> extent = r.varint().let { if (it < 0 || it > Int.MAX_VALUE) 0 else it.toInt() }
                else -> r.skip(tag and 7)
            }
        }
        if (extent < 1 || extent > MAX_EXTENT) return null
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

    private fun decodeRawFeature(r: Reader, geometryBudget: Int): RawFeature {
        var id = 0L
        var type = 0
        val tags = IntList(geometryBudget)
        val geometry = IntList(geometryBudget)
        while (r.hasMore()) {
            val tag = r.tag()
            when (tag ushr 3) {
                1 -> id = r.varint()
                2 -> r.packedInts(tag and 7, tags)
                3 -> type = r.varint().toInt()
                4 -> r.packedInts(tag and 7, geometry)
                else -> r.skip(tag and 7)
            }
        }
        return RawFeature(id, type, tags.toArray(), geometry.toArray())
    }

    private fun decodeValue(r: Reader): Any {
        var result: Any = ""
        while (r.hasMore()) {
            val tag = r.tag()
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
        var a = IntArray(4)
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
                1 -> for (k in 0 until count) {
                    if (i + 1 >= g.size) {
                        cur?.let { out.add(it.build()) }
                        return out
                    }
                    x += unzig(g[i++])
                    y += unzig(g[i++])
                    cur?.let { out.add(it.build()) }
                    cur = IntBuilder().also { it.add(x, y) }
                }
                2 -> for (k in 0 until count) {
                    if (i + 1 >= g.size) {
                        cur?.let { out.add(it.build()) }
                        return out
                    }
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
                if (pos >= end) throw MvtFormatException("varint past end")
                if (shift >= 70) throw MvtFormatException("varint too long")
                val b = buf[pos++].toInt()
                if (shift < 64) result = result or ((b and 0x7f).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
            }
        }

        fun tag(): Int {
            val t = varint()
            if (t < 0 || t > Int.MAX_VALUE) throw MvtFormatException("bad field tag")
            return t.toInt()
        }

        /** A length-delimited length: 0 <= len <= remaining. */
        fun length(): Int {
            val l = varint()
            if (l < 0 || l > end - pos) throw MvtFormatException("bad length $l")
            return l.toInt()
        }

        fun fixed32(): Int {
            if (end - pos < 4) throw MvtFormatException("fixed32 past end")
            val v = (buf[pos].toInt() and 0xff) or ((buf[pos + 1].toInt() and 0xff) shl 8) or
                ((buf[pos + 2].toInt() and 0xff) shl 16) or ((buf[pos + 3].toInt() and 0xff) shl 24)
            pos += 4
            return v
        }

        fun fixed64(): Long {
            if (end - pos < 8) throw MvtFormatException("fixed64 past end")
            val lo = fixed32().toLong() and 0xffffffffL
            val hi = fixed32().toLong() and 0xffffffffL
            return lo or (hi shl 32)
        }

        fun string(): String {
            val len = length()
            val s = String(buf, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> varint()
                1 -> skipBytes(8)
                2 -> skipBytes(length())
                5 -> skipBytes(4)
                else -> throw MvtFormatException("unsupported wire type $wireType")
            }
        }

        private fun skipBytes(n: Int) {
            if (n < 0 || n > end - pos) throw MvtFormatException("skip past end")
            pos += n
        }

        fun packedInts(wireType: Int, into: IntList) {
            if (wireType == 0) {
                into.add(varint().toInt())
                return
            }
            if (wireType != 2) throw MvtFormatException("bad packed wire type $wireType")
            val len = length()
            val sub = Reader(buf, pos, pos + len)
            while (sub.hasMore()) into.add(sub.varint().toInt())
            pos += len
        }
    }
}
