package app.minemaps.engine.art

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class Anchor(val x: Int, val y: Int)

class TextureAtlas(val width: Int, val height: Int, val argb: IntArray, val json: String) {
    class Variant(val mips: Array<IntArray>) {
        fun mip(size: Int): IntArray = mips[MIP_SIZES.indexOf(size).also { require(it >= 0) { "no mip of size $size" } }]
    }

    val tileSize: Int
    private val textures = HashMap<String, List<Variant>>()
    private val icons = HashMap<String, IntArray>()
    val bookmarkIcons: List<String>
    val tintableIcons: Set<String>
    private val anchors = HashMap<String, Anchor>()

    init {
        require(argb.size == width * height) { "atlas pixels ${argb.size} != ${width}x$height" }
        val root = Json.parseToJsonElement(json).jsonObject
        tileSize = root["tileSize"]?.jsonPrimitive?.int ?: 16
        require(tileSize == 16) { "unsupported tile size $tileSize" }
        for ((name, v) in root["textures"]?.jsonObject ?: JsonObject(emptyMap())) {
            textures[name] = v.jsonArray.map { cell -> buildVariant(cell.jsonArray) }
        }
        for ((name, v) in root["icons"]?.jsonObject ?: JsonObject(emptyMap())) {
            icons[name] = cellPixels(v.jsonArray)
        }
        for ((name, v) in root["anchors"]?.jsonObject ?: JsonObject(emptyMap())) {
            val a = v.jsonArray
            anchors[name] = Anchor(a[0].jsonPrimitive.int, a[1].jsonPrimitive.int)
        }
        bookmarkIcons = (root["bookmarkIcons"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()).filter { it in icons }
        tintableIcons = (root["tintableIcons"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()).toSet()
    }

    private fun cellPixels(cell: JsonArray): IntArray {
        val cx = cell[0].jsonPrimitive.int * tileSize
        val cy = cell[1].jsonPrimitive.int * tileSize
        val out = IntArray(tileSize * tileSize)
        for (y in 0 until tileSize) System.arraycopy(argb, (cy + y) * width + cx, out, y * tileSize, tileSize)
        return out
    }

    private fun buildVariant(cell: JsonArray): Variant {
        val base = cellPixels(cell)
        return Variant(Array(MIP_SIZES.size) { i -> downsample(base, tileSize, MIP_SIZES[i]) })
    }

    fun textureNames(): Set<String> = textures.keys

    fun hasTexture(name: String): Boolean = textures.containsKey(name)

    fun variants(name: String): List<Variant> = textures[name] ?: error("unknown texture '$name'")

    fun variantCount(name: String): Int = variants(name).size

    fun mip(name: String, variant: Int, size: Int): IntArray = variants(name)[variant].mip(size)

    fun averageColor(name: String, variant: Int = 0): Int = mip(name, variant, 1)[0]

    fun icon(name: String): IntArray? = icons[name]

    /** Icon pixel coordinates (0..16) that sit on the geographic point; the centre when the icon has no anchor. */
    fun anchor(name: String): Anchor = anchors[name] ?: Anchor(tileSize / 2, tileSize / 2)

    fun iconNames(): Set<String> = icons.keys

    companion object {
        /** Per-channel multiply of [pixels] by [tint] (alpha of the pixel is kept). */
        fun tintIcon(pixels: IntArray, tint: Int): IntArray {
            val tr = (tint shr 16) and 0xff
            val tg = (tint shr 8) and 0xff
            val tb = tint and 0xff
            return IntArray(pixels.size) { i ->
                val p = pixels[i]
                val r = ((p shr 16) and 0xff) * tr / 255
                val g = ((p shr 8) and 0xff) * tg / 255
                val b = (p and 0xff) * tb / 255
                (p and 0xff000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
        }

        val MIP_SIZES = intArrayOf(16, 8, 4, 2, 1)

        fun downsample(src: IntArray, srcSize: Int, dstSize: Int): IntArray {
            if (srcSize == dstSize) return src.copyOf()
            val f = srcSize / dstSize
            val n = f * f
            val out = IntArray(dstSize * dstSize)
            for (y in 0 until dstSize) for (x in 0 until dstSize) {
                var a = 0
                var r = 0
                var g = 0
                var b = 0
                for (yy in 0 until f) for (xx in 0 until f) {
                    val p = src[(y * f + yy) * srcSize + x * f + xx]
                    a += p ushr 24
                    r += (p shr 16) and 0xff
                    g += (p shr 8) and 0xff
                    b += p and 0xff
                }
                val h = n / 2
                out[y * dstSize + x] = ((a + h) / n shl 24) or ((r + h) / n shl 16) or ((g + h) / n shl 8) or ((b + h) / n)
            }
            return out
        }
    }
}
