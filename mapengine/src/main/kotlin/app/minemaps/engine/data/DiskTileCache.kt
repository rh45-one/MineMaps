package app.minemaps.engine.data

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

class DiskTileCache(
    val dir: File,
    val maxBytes: Long = 200L * 1024 * 1024,
    val ttlMillis: Long = 30L * 24 * 3600 * 1000,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Entry(val bytes: ByteArray, val fetchedAtMillis: Long)

    private val lock = Any()
    private var totalBytes = -1L

    init {
        dir.mkdirs()
    }

    fun isFresh(entry: Entry, ttl: Long = ttlMillis): Boolean = clock() - entry.fetchedAtMillis <= ttl

    fun get(name: String): Entry? {
        val file = fileFor(name)
        return try {
            val raw = file.readBytes()
            if (raw.size < HEADER) return null
            val ts = ByteBuffer.wrap(raw, 0, HEADER).getLong()
            file.setLastModified(clock())
            Entry(raw.copyOfRange(HEADER, raw.size), ts)
        } catch (e: IOException) {
            null
        }
    }

    fun put(name: String, bytes: ByteArray) {
        val file = fileFor(name)
        val tmp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        val payload = ByteBuffer.allocate(HEADER + bytes.size).putLong(clock()).put(bytes).array()
        synchronized(lock) {
            ensureTotal()
            val previous = if (file.exists()) file.length() else 0L
            Files.write(tmp.toPath(), payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            totalBytes += payload.size - previous
            if (totalBytes > maxBytes) evict()
        }
    }

    fun clear() {
        synchronized(lock) {
            dir.listFiles { f -> f.name.endsWith(EXT) }?.forEach { it.delete() }
            totalBytes = 0L
        }
    }

    fun sizeBytes(): Long = synchronized(lock) {
        ensureTotal()
        totalBytes
    }

    private fun ensureTotal() {
        if (totalBytes < 0) {
            totalBytes = dir.listFiles { f -> f.name.endsWith(EXT) }?.sumOf { it.length() } ?: 0L
        }
    }

    private fun evict() {
        val files = dir.listFiles { f -> f.name.endsWith(EXT) }?.sortedBy { it.lastModified() } ?: return
        for (f in files) {
            if (totalBytes <= maxBytes) break
            val len = f.length()
            if (f.delete()) totalBytes -= len
        }
    }

    private fun fileFor(name: String): File = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + EXT)

    private companion object {
        const val HEADER = 8
        const val EXT = ".tile"
    }
}
