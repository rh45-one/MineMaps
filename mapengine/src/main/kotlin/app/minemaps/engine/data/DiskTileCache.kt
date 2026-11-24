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

    @Volatile
    internal var beforeCorruptLock: (() -> Unit)? = null

    init {
        dir.mkdirs()
    }

    fun isFresh(entry: Entry, ttl: Long = ttlMillis): Boolean = clock() - entry.fetchedAtMillis <= ttl

    fun get(name: String): Entry? {
        val file = fileFor(name)
        return try {
            val raw = file.readBytes()
            val entry = parseEntry(raw) ?: run {
                beforeCorruptLock?.invoke()
                synchronized(lock) {
                    if (!file.exists()) return null
                    val current = file.readBytes()
                    val recovered = parseEntry(current)
                    if (recovered == null) {
                        val len = file.length()
                        if (file.delete() && totalBytes >= 0) totalBytes = maxOf(0L, totalBytes - len)
                    }
                    recovered
                }
            } ?: return null
            touch(file)
            entry
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            null
        }
    }

    private fun parseEntry(raw: ByteArray): Entry? {
        if (raw.size < HEADER) return null
        val buf = ByteBuffer.wrap(raw, 0, HEADER)
        val ts = buf.getLong()
        val len = buf.getInt()
        if (len < 0 || len != raw.size - HEADER) return null
        return Entry(raw.copyOfRange(HEADER, raw.size), ts)
    }

    private fun touch(file: File) {
        val now = clock()
        if (now >= 0L) file.setLastModified(now)
    }

    /** Best effort: any failure (disk full, read-only dir, ...) is swallowed and leaves no temp file behind. */
    fun put(name: String, bytes: ByteArray) {
        val file = fileFor(name)
        val tmp = File(dir, "${file.name}.${System.nanoTime()}.tmp")
        try {
            if (bytes.size.toLong() + HEADER > maxBytes) {
                synchronized(lock) {
                    if (file.exists()) {
                        ensureTotal()
                        val len = file.length()
                        if (file.delete()) totalBytes = maxOf(0L, totalBytes - len)
                    }
                }
                return
            }
            synchronized(lock) {
                val now = clock()
                val payload = ByteBuffer.allocate(HEADER + bytes.size).putLong(now).putInt(bytes.size).put(bytes).array()
                ensureTotal()
                dir.mkdirs()
                val previous = if (file.exists()) file.length() else 0L
                Files.write(tmp.toPath(), payload, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                if (now >= 0L) file.setLastModified(now)
                totalBytes += payload.size - previous
                if (totalBytes > maxBytes) evict(protectedFile = file)
            }
        } catch (e: IOException) {
            tmp.delete()
        } catch (e: RuntimeException) {
            tmp.delete()
        }
    }

    fun clear() {
        synchronized(lock) {
            totalBytes = dir.listFiles { f -> f.name.endsWith(EXT) }
                ?.sumOf { if (it.delete()) 0L else it.length() } ?: 0L
        }
    }

    /** File base names (sanitised cache keys) currently on disk. */
    fun names(): List<String> = dir.listFiles { f -> f.name.endsWith(EXT) }?.map { it.name.removeSuffix(EXT) } ?: emptyList()

    fun sizeBytes(): Long = synchronized(lock) {
        ensureTotal()
        totalBytes
    }

    private fun ensureTotal() {
        if (totalBytes < 0) {
            totalBytes = dir.listFiles { f -> f.name.endsWith(EXT) }?.sumOf { it.length() } ?: 0L
        }
    }

    private fun evict(protectedFile: File? = null) {
        val target = maxBytes - maxBytes / 10L
        val entries = dir.listFiles { f -> f.name.endsWith(EXT) }
            ?.map { Triple(it, it.lastModified(), it.length()) }
            ?.sortedWith(compareBy({ it.first == protectedFile }, { it.second }))
            ?: return
        totalBytes = entries.sumOf { it.third }
        var remaining = entries.size
        for ((f, _, len) in entries) {
            if (totalBytes <= maxBytes && (totalBytes <= target || remaining <= 1)) break
            if (f.delete()) {
                totalBytes = maxOf(0L, totalBytes - len)
                remaining--
            }
        }
    }

    private fun fileFor(name: String): File = File(dir, name.replace(Regex("[^A-Za-z0-9._-]"), "_") + EXT)

    private companion object {
        const val HEADER = 12
        const val EXT = ".tile"
    }
}
