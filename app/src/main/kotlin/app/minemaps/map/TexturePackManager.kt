package app.minemaps.map

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import app.minemaps.BuildConfig
import app.minemaps.data.SettingsRepository
import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.art.importer.ImportException
import app.minemaps.engine.art.importer.MinecraftImporter
import app.minemaps.engine.art.importer.OriginalAtlas
import app.minemaps.engine.art.importer.ZipTextureSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed interface PackUi {
    data object Idle : PackUi
    class Downloading(val bytes: Long, val total: Long) : PackUi
    class Importing(val percent: Int) : PackUi
    class Failed(val message: String) : PackUi
}

/** Imports the Minecraft pack on the device (pinned client jar or a user-supplied file) and keeps it across updates. */
class TexturePackManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: SettingsRepository,
    private val engine: suspend () -> MapEngine,
    private val client: suspend () -> OkHttpClient,
    private val installedCode: Int = BuildConfig.VERSION_CODE,
    private val jarUrl: String = MinecraftImporter.PINNED_URL,
    private val jarSha1: String = MinecraftImporter.PINNED_SHA1,
    private val jarSize: Long = MinecraftImporter.PINNED_SIZE,
) {
    val state = MutableStateFlow<PackUi>(PackUi.Idle)
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    private var job: Job? = null
    private var lastAuto = 0L
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val workDir get() = File(context.cacheDir, "pack-import")

    val busy: Boolean get() = job?.isActive == true

    fun isMetered(): Boolean = connectivity.isActiveNetworkMetered

    /** Wi-Fi style network: unmetered and not roaming. */
    fun onUnmeteredNetwork(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            (android.os.Build.VERSION.SDK_INT < 28 || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun start() {
        workDir.deleteRecursively()
        scope.launch { maybeAutoImport() }
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) scope.launch { maybeAutoImport() }
                }
            })
        }
    }

    suspend fun maybeAutoImport() {
        if (busy) return
        val s = settings.settings.first()
        val eng = engine()
        val pendingDue = s.texturesPending && installedCode > s.pendingSinceVersionCode && !eng.isPackAvailable(TexturePack.VANILLA)
        val reimport = s.texturePack == TexturePack.VANILLA && eng.storedPackNeedsReimport
        if (!pendingDue && !reimport) return
        if (!onUnmeteredNetwork()) return
        val now = System.currentTimeMillis()
        if (now - lastAuto < 60_000) return
        lastAuto = now
        importNow(auto = true)
    }

    fun importNow(auto: Boolean = false) {
        if (busy) return
        job = scope.launch {
            val jar = File(workDir, "client.jar")
            try {
                download(jar)
                runImport(jar, listOf("Minecraft ${MinecraftImporter.PINNED_VERSION} client jar (sha1 $jarSha1)"), MinecraftImporter.PINNED_VERSION, jarSha1, select = true)
                if (auto) messages.tryEmit("Minecraft textures installed")
            } catch (e: CancellationException) {
                state.value = PackUi.Idle
                throw e
            } catch (e: Exception) {
                fail(e, auto)
            } finally {
                jar.delete()
            }
        }
    }

    fun importFromFile(uri: Uri) {
        if (busy) return
        job = scope.launch {
            val copy = File(workDir, "import.zip")
            try {
                withContext(Dispatchers.IO) {
                    workDir.mkdirs()
                    val fileName = resolveFileName(uri)
                    var total = 0L
                    val md = MessageDigest.getInstance("SHA-1")
                    (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")).use { input ->
                        copy.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                total += n
                                if (total > MAX_IMPORT_BYTES) throw ImportException("That file is larger than 200 MB")
                                md.update(buf, 0, n)
                                out.write(buf, 0, n)
                                state.value = PackUi.Downloading(total, -1)
                            }
                        }
                    }
                    val sha = md.digest().joinToString("") { "%02x".format(it) }
                    val label = fileName?.let { "Resource pack: $it" } ?: "Custom resource pack (sha1 ${sha.take(10)})"
                    runImport(copy, listOf(label), "custom", sha, select = true, layerOverStored = true)
                }
            } catch (e: CancellationException) {
                state.value = PackUi.Idle
                throw e
            } catch (e: Exception) {
                fail(e, false)
            } finally {
                copy.delete()
            }
        }
    }

    private fun resolveFileName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.take(80)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }.getOrNull()

    fun cancel() {
        job?.cancel()
        job = null
        state.value = PackUi.Idle
        scope.launch(Dispatchers.IO) { workDir.deleteRecursively() }
    }

    fun dismissFailure() {
        if (state.value is PackUi.Failed) state.value = PackUi.Idle
    }

    fun armPending() {
        scope.launch { settings.setTexturesPending(true, installedCode) }
    }

    fun clearPending() {
        scope.launch { settings.setTexturesPending(false, 0) }
    }

    fun remove() {
        scope.launch {
            withContext(Dispatchers.IO) { engine().packStore.remove() }
            settings.setTexturePack(TexturePack.ORIGINAL)
            settings.setTexturesPending(false, 0)
            engine().reloadStoredPack()
        }
    }

    private suspend fun fail(e: Exception, auto: Boolean) {
        val msg = when (e) {
            is ImportException -> e.message ?: "Import failed"
            is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Download failed"
            else -> "Import failed"
        }
        android.util.Log.w("MineMaps", "texture import failed", e)
        state.value = if (auto) PackUi.Idle else PackUi.Failed(msg)
    }

    private suspend fun download(dest: File) {
        val http = client()
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val request = Request.Builder().url(jarUrl).header("User-Agent", BuildConfig.USER_AGENT).build()
            http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Mojang's server answered HTTP ${r.code}")
                if (r.request.url.scheme != "https") throw IOException("Download was not served over HTTPS")
                if (r.body.contentLength() > jarSize) throw IOException("Download is larger than expected")
                val md = MessageDigest.getInstance("SHA-1")
                var total = 0L
                r.body.byteStream().use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > jarSize) throw IOException("Download is larger than expected")
                            md.update(buf, 0, n)
                            out.write(buf, 0, n)
                            state.value = PackUi.Downloading(total, jarSize)
                        }
                    }
                }
                if (total != jarSize || md.digest().joinToString("") { "%02x".format(it) } != jarSha1) throw IOException("The download did not match Mojang's checksum")
            }
        }
    }

    private suspend fun runImport(file: File, desc: List<String>, version: String, sha1: String, select: Boolean, layerOverStored: Boolean = false) {
        val eng = engine()
        withContext(Dispatchers.Default) {
            state.value = PackUi.Importing(0)
            val existing = if (layerOverStored) eng.storedPack else null
            val baseAtlas = if (existing != null) {
                runCatching { OriginalAtlas(existing.atlasJson, AndroidPngDecoder.decode(existing.atlasPng)) }.getOrNull()
            } else null
            val original = baseAtlas ?: OriginalAtlas(EngineResources.atlasJson(TexturePack.ORIGINAL), AndroidPngDecoder.decode(EngineResources.atlasPng(TexturePack.ORIGINAL)))
            var fullDesc = desc
            val out = ZipTextureSource(file).use { src ->
                if (layerOverStored) {
                    src.packMetaDescription()?.let { metaDesc ->
                        fullDesc = listOf("${desc.first()} ($metaDesc)")
                    }
                }
                MinecraftImporter.import(listOf(src), original, AndroidPngDecoder) { state.value = PackUi.Importing(it) }
            }
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            eng.packStore.install(out, MinecraftImporter.sourceText(fullDesc, out.kept), PackMeta(version, sha1, MinecraftImporter.IMPORTER_VERSION, System.currentTimeMillis()))
        }
        eng.reloadStoredPack()
        if (select) settings.setTexturePack(TexturePack.VANILLA)
        settings.setTexturesPending(false, 0)
        state.value = PackUi.Idle
    }

    companion object {
        const val MAX_IMPORT_BYTES = 200L * 1024 * 1024
    }
}
