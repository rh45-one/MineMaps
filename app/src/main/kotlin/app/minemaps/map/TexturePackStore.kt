package app.minemaps.map

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.minemaps.engine.art.importer.ImportOutput
import app.minemaps.engine.art.importer.PngDecoder
import app.minemaps.engine.art.importer.Rgba
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.MessageDigest

object AndroidPngDecoder : PngDecoder {
    override fun decode(bytes: ByteArray): Rgba {
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("not a PNG")
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val out = Rgba(bmp.width, bmp.height, pixels)
        bmp.recycle()
        return out
    }
}

class PackMeta(val mcVersion: String, val sourceSha1: String, val importerVersion: Int, val importedAt: Long)

private fun sha256(vararg parts: ByteArray): String = MessageDigest.getInstance("SHA-256").also { md -> parts.forEach { md.update(it) } }.digest().joinToString("") { "%02x".format(it) }

class StoredPack(val atlasPng: ByteArray, val atlasJson: String, val source: String, val meta: PackMeta)

/** The imported Minecraft pack in `filesDir/packs/vanilla/`, written atomically (temp dir, then rename). */
class TexturePackStore(filesDir: File) {
    private val parent = File(filesDir, "packs")
    private val dir = File(parent, "vanilla")

    val exists: Boolean get() = dir.exists()

    /** The pack, or null when absent or invalid (missing/corrupt files, or lacking a name of [required]). */
    fun load(required: Set<String>): StoredPack? = try {
        val json = File(dir, "atlas.json").readText()
        val png = File(dir, "atlas.png").readBytes()
        val root = Json.parseToJsonElement(json).jsonObject
        val names = (root["textures"]?.jsonObject?.keys.orEmpty().map { "t:$it" } + root["icons"]?.jsonObject?.keys.orEmpty().map { "i:$it" }).toSet()
        if (!names.containsAll(required)) null else {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(png, 0, png.size, opts)
            if (opts.outWidth != 256 || opts.outHeight < 16 || opts.outHeight % 16 != 0) null else {
                val m = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
                if (m["filesSha256"]!!.jsonPrimitive.content != sha256(png, json.toByteArray())) return null
                val meta = PackMeta(m["mcVersion"]!!.jsonPrimitive.content, m["sourceSha1"]!!.jsonPrimitive.content, m["importerVersion"]!!.jsonPrimitive.int, m["importedAt"]!!.jsonPrimitive.content.toLong())
                StoredPack(png, json, runCatching { File(dir, "SOURCE.txt").readText() }.getOrDefault(""), meta)
            }
        }
    } catch (e: Exception) {
        null
    }

    fun install(out: ImportOutput, source: String, meta: PackMeta) {
        parent.mkdirs()
        val tmp = File(parent, "vanilla.tmp")
        tmp.deleteRecursively()
        tmp.mkdirs()
        File(tmp, "atlas.png").writeBytes(out.atlasPng)
        File(tmp, "atlas.json").writeText(out.atlasJson)
        File(tmp, "SOURCE.txt").writeText(source)
        File(tmp, "meta.json").writeText(
            buildJsonObject {
                put("mcVersion", meta.mcVersion)
                put("sourceSha1", meta.sourceSha1)
                put("importerVersion", meta.importerVersion)
                put("importedAt", meta.importedAt.toString())
                put("filesSha256", sha256(out.atlasPng, out.atlasJson.toByteArray()))
            }.toString(),
        )
        val old = File(parent, "vanilla.old")
        old.deleteRecursively()
        if (dir.exists() && !dir.renameTo(old)) dir.deleteRecursively()
        if (!tmp.renameTo(dir)) {
            old.renameTo(dir)
            error("Could not store the texture pack")
        }
        old.deleteRecursively()
    }

    fun remove() {
        dir.deleteRecursively()
        File(parent, "vanilla.tmp").deleteRecursively()
    }
}
