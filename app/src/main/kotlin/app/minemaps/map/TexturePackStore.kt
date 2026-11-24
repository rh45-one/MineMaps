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
    const val MAX_PNG_DIM = 4096
    const val MAX_PNG_PIXELS = 16_777_216L
    private val PNG_SIG = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    private fun checkDimensions(w: Int, h: Int) {
        if (w <= 0 || h <= 0 || w > MAX_PNG_DIM || h > MAX_PNG_DIM || w.toLong() * h.toLong() > MAX_PNG_PIXELS) {
            throw IllegalArgumentException("PNG dimensions out of bounds: ${w}x${h}")
        }
    }

    override fun decode(bytes: ByteArray): Rgba {
        if (bytes.size < 24 || !PNG_SIG.indices.all { bytes[it] == PNG_SIG[it] } ||
            bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) {
            throw IllegalArgumentException("not a PNG")
        }
        val ihdr = java.nio.ByteBuffer.wrap(bytes, 16, 8)
        checkDimensions(ihdr.int, ihdr.int)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        checkDimensions(bounds.outWidth, bounds.outHeight)
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: error("not a PNG")
        return try {
            val pixels = IntArray(bmp.width * bmp.height)
            bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
            Rgba(bmp.width, bmp.height, pixels)
        } finally {
            bmp.recycle()
        }
    }
}

class PackMeta(val mcVersion: String, val sourceSha1: String, val importerVersion: Int, val importedAt: Long)

private fun sha256(vararg parts: ByteArray): String = MessageDigest.getInstance("SHA-256").also { md -> parts.forEach { md.update(it) } }.digest().joinToString("") { "%02x".format(it) }

class StoredPack(val atlasPng: ByteArray, val atlasJson: String, val source: String, val meta: PackMeta)

/** The imported Minecraft pack in `filesDir/packs/vanilla/`, written atomically (temp dir, then rename). */
class TexturePackStore(filesDir: File) {
    private val lock = Any()
    private val parent = File(filesDir, "packs")
    private val dir = File(parent, "vanilla")
    private val tmpDir = File(parent, "vanilla.tmp")
    private val oldDir = File(parent, "vanilla.old")

    private fun recoverOldIfNeeded() {
        if (!dir.exists() && oldDir.exists()) {
            oldDir.renameTo(dir)
        } else if (dir.exists() && oldDir.exists()) {
            oldDir.deleteRecursively()
        }
    }

    val exists: Boolean
        get() = synchronized(lock) {
            recoverOldIfNeeded()
            dir.exists()
        }

    /** The pack, or null when absent or invalid (missing/corrupt files, or lacking a name of [required]). */
    fun load(required: Set<String>): StoredPack? = synchronized(lock) {
        recoverOldIfNeeded()
        try {
            val json = File(dir, "atlas.json").readText()
            val png = File(dir, "atlas.png").readBytes()
            val root = Json.parseToJsonElement(json).jsonObject
            val names = (root["textures"]?.jsonObject?.keys.orEmpty().map { "t:$it" } + root["icons"]?.jsonObject?.keys.orEmpty().map { "i:$it" }).toSet()
            if (!names.containsAll(required)) null else {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(png, 0, png.size, opts)
                if (opts.outWidth != 256 || opts.outHeight < 16 || opts.outHeight % 16 != 0) null else {
                    val m = Json.parseToJsonElement(File(dir, "meta.json").readText()).jsonObject
                    if (m["filesSha256"]!!.jsonPrimitive.content != sha256(png, json.toByteArray())) return@synchronized null
                    val meta = PackMeta(m["mcVersion"]!!.jsonPrimitive.content, m["sourceSha1"]!!.jsonPrimitive.content, m["importerVersion"]!!.jsonPrimitive.int, m["importedAt"]!!.jsonPrimitive.content.toLong())
                    StoredPack(png, json, runCatching { File(dir, "SOURCE.txt").readText() }.getOrDefault(""), meta)
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    fun install(out: ImportOutput, source: String, meta: PackMeta, checkCancelled: () -> Unit = {}) {
        checkCancelled()
        synchronized(lock) {
            recoverOldIfNeeded()
            checkCancelled()
            parent.mkdirs()
            tmpDir.deleteRecursively()
            tmpDir.mkdirs()
            try {
                checkCancelled()
                File(tmpDir, "atlas.png").writeBytes(out.atlasPng)
                checkCancelled()
                File(tmpDir, "atlas.json").writeText(out.atlasJson)
                checkCancelled()
                File(tmpDir, "SOURCE.txt").writeText(source)
                checkCancelled()
                File(tmpDir, "meta.json").writeText(
                    buildJsonObject {
                        put("mcVersion", meta.mcVersion)
                        put("sourceSha1", meta.sourceSha1)
                        put("importerVersion", meta.importerVersion)
                        put("importedAt", meta.importedAt.toString())
                        put("filesSha256", sha256(out.atlasPng, out.atlasJson.toByteArray()))
                    }.toString(),
                )
                checkCancelled()
                oldDir.deleteRecursively()
                if (dir.exists() && !dir.renameTo(oldDir)) dir.deleteRecursively()
                if (!tmpDir.renameTo(dir)) {
                    if (!dir.exists() && oldDir.exists()) oldDir.renameTo(dir)
                    error("Could not store the texture pack")
                }
                oldDir.deleteRecursively()
            } finally {
                tmpDir.deleteRecursively()
            }
        }
    }

    fun remove() = synchronized(lock) {
        dir.deleteRecursively()
        tmpDir.deleteRecursively()
        oldDir.deleteRecursively()
    }
}
