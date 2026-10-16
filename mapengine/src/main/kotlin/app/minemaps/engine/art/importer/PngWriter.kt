package app.minemaps.engine.art.importer

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.Deflater

/** Minimal RGBA PNG encoder (8-bit, filter 0): exact, with no premultiplication round trip. */
object PngWriter {
    fun encode(img: Rgba): ByteArray {
        val raw = ByteArray((img.w * 4 + 1) * img.h)
        var o = 0
        for (y in 0 until img.h) {
            raw[o++] = 0
            for (x in 0 until img.w) {
                val p = img[x, y]
                raw[o++] = (p shr 16).toByte()
                raw[o++] = (p shr 8).toByte()
                raw[o++] = p.toByte()
                raw[o++] = (p ushr 24).toByte()
            }
        }
        val deflater = Deflater(9)
        deflater.setInput(raw)
        deflater.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!deflater.finished()) z.write(buf, 0, deflater.deflate(buf))
        deflater.end()
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val ihdr = ByteArray(13)
        putInt(ihdr, 0, img.w)
        putInt(ihdr, 4, img.h)
        ihdr[8] = 8
        ihdr[9] = 6
        chunk(out, "IHDR", ihdr)
        chunk(out, "IDAT", z.toByteArray())
        chunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun putInt(b: ByteArray, at: Int, v: Int) {
        b[at] = (v ushr 24).toByte()
        b[at + 1] = (v ushr 16).toByte()
        b[at + 2] = (v ushr 8).toByte()
        b[at + 3] = v.toByte()
    }

    private fun chunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        val len = ByteArray(4)
        putInt(len, 0, data.size)
        out.write(len)
        val body = type.toByteArray(Charsets.US_ASCII) + data
        out.write(body)
        val crc = CRC32().apply { update(body) }.value.toInt()
        val c = ByteArray(4)
        putInt(c, 0, crc)
        out.write(c)
    }
}
