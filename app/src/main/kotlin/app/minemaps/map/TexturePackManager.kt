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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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
    private val lifecycleLock = Any()
    private val autoMutex = kotlinx.coroutines.sync.Mutex()
    private val workMutex = kotlinx.coroutines.sync.Mutex()
    private var job: Job? = null
    private var removeCount = 0
    private var lastAuto = 0L
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val workDir get() = File(context.cacheDir, "pack-import")

    internal var onImportStep: ((Int) -> Unit)? = null

    val busy: Boolean get() = synchronized(lifecycleLock) { job?.isActive == true }

    private val hasPendingRemove: Boolean get() = synchronized(lifecycleLock) { removeCount > 0 }

    private fun updateState(owner: Job, ui: PackUi) {
        synchronized(lifecycleLock) {
            if (job === owner && owner.isActive) {
                state.value = ui
            }
        }
    }

    private fun finishSuccess(owner: Job) {
        synchronized(lifecycleLock) {
            if (job === owner) {
                job = null
                state.value = PackUi.Idle
            }
        }
    }

    private fun finishCancelled(owner: Job) {
        synchronized(lifecycleLock) {
            if (job === owner) {
                job = null
                state.value = PackUi.Idle
            }
        }
    }

    fun isMetered(): Boolean = connectivity.isActiveNetworkMetered

    /** Wi-Fi style network: unmetered and not roaming. */
    fun onUnmeteredNetwork(): Boolean {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork ?: return false) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) &&
            (android.os.Build.VERSION.SDK_INT < 28 || caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING)) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun start() {
        scope.launch {
            withContext(Dispatchers.IO) {
                workMutex.withLock {
                    if (!busy && !hasPendingRemove) workDir.deleteRecursively()
                }
            }
            maybeAutoImport()
        }
        runCatching {
            connectivity.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) scope.launch { maybeAutoImport() }
                }
            })
        }
    }

    suspend fun maybeAutoImport() {
        autoMutex.withLock {
            if (busy || hasPendingRemove) return
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
    }

    fun importNow(auto: Boolean = false) {
        val newJob = synchronized(lifecycleLock) {
            if (job?.isActive == true || (auto && removeCount > 0)) return
            val created = scope.launch(start = CoroutineStart.LAZY) {
                val owner = coroutineContext[Job]!!
                try {
                    workMutex.withLock {
                        ensureActive()
                        val jar = File(workDir, "client.jar")
                        try {
                            download(owner, jar)
                            ensureActive()
                            runImport(
                                owner = owner,
                                file = jar,
                                desc = listOf("Minecraft ${MinecraftImporter.PINNED_VERSION} client jar (sha1 $jarSha1)"),
                                version = MinecraftImporter.PINNED_VERSION,
                                sha1 = jarSha1,
                                select = true,
                            )
                            finishSuccess(owner)
                            if (auto) messages.tryEmit("Minecraft textures installed")
                        } finally {
                            jar.delete()
                            workDir.deleteRecursively()
                        }
                    }
                } catch (e: CancellationException) {
                    finishCancelled(owner)
                    throw e
                } catch (e: Exception) {
                    if (!coroutineContext[Job]!!.isActive) {
                        finishCancelled(owner)
                        throw CancellationException("Texture import cancelled", e)
                    }
                    fail(owner, e, auto)
                }
            }
            if (created.isCancelled || created.isCompleted) return
            job = created
            state.value = PackUi.Downloading(0, jarSize)
            created.invokeOnCompletion { synchronized(lifecycleLock) { if (job === created) job = null } }
            created
        }
        newJob.start()
    }

    fun importFromFile(uri: Uri) {
        val newJob = synchronized(lifecycleLock) {
            if (job?.isActive == true) return
            val created = scope.launch(start = CoroutineStart.LAZY) {
                val owner = coroutineContext[Job]!!
                try {
                    workMutex.withLock {
                        ensureActive()
                        val copy = File(workDir, "import.zip")
                        try {
                            withContext(Dispatchers.IO) {
                                ensureActive()
                                workDir.mkdirs()
                                val fileName = resolveFileName(uri)
                                var total = 0L
                                val md = MessageDigest.getInstance("SHA-1")
                                (context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")).use { input ->
                                    val done = AtomicBoolean(false)
                                    val closeWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                                        try {
                                            awaitCancellation()
                                        } finally {
                                            if (!done.get()) runCatching { input.close() }
                                        }
                                    }
                                    try {
                                        copy.outputStream().use { out ->
                                            val buf = ByteArray(64 * 1024)
                                            while (true) {
                                                currentCoroutineContext().ensureActive()
                                                owner.ensureActive()
                                                val n = input.read(buf)
                                                currentCoroutineContext().ensureActive()
                                                owner.ensureActive()
                                                if (n < 0) break
                                                total += n
                                                if (total > MAX_IMPORT_BYTES) throw ImportException("That file is larger than 200 MB")
                                                md.update(buf, 0, n)
                                                out.write(buf, 0, n)
                                                updateState(owner, PackUi.Downloading(total, -1))
                                            }
                                        }
                                    } finally {
                                        done.set(true)
                                        closeWatcher.cancel()
                                    }
                                }
                                currentCoroutineContext().ensureActive()
                                owner.ensureActive()
                                val sha = md.digest().joinToString("") { "%02x".format(it) }
                                val label = fileName?.let { "Resource pack: $it" } ?: "Custom resource pack (sha1 ${sha.take(10)})"
                                runImport(owner, copy, listOf(label), "custom", sha, select = true, layerOverStored = true)
                            }
                            finishSuccess(owner)
                        } finally {
                            copy.delete()
                            workDir.deleteRecursively()
                        }
                    }
                } catch (e: CancellationException) {
                    finishCancelled(owner)
                    throw e
                } catch (e: Exception) {
                    if (!coroutineContext[Job]!!.isActive) {
                        finishCancelled(owner)
                        throw CancellationException("Texture import cancelled", e)
                    }
                    fail(owner, e, false)
                }
            }
            if (created.isCancelled || created.isCompleted) return
            job = created
            state.value = PackUi.Downloading(0, -1)
            created.invokeOnCompletion { synchronized(lifecycleLock) { if (job === created) job = null } }
            created
        }
        newJob.start()
    }

    private fun resolveFileName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.take(80)?.trim()?.takeIf { it.isNotEmpty() } else null
        }
    }.getOrNull()

    fun cancel() {
        val prev = synchronized(lifecycleLock) {
            val p = job
            job = null
            state.value = PackUi.Idle
            p
        }
        prev?.cancel()
    }

    fun dismissFailure() {
        synchronized(lifecycleLock) {
            if (job == null && state.value is PackUi.Failed) {
                state.value = PackUi.Idle
            }
        }
    }

    fun armPending() {
        scope.launch { settings.setTexturesPending(true, installedCode) }
    }

    fun clearPending() {
        scope.launch { settings.setTexturesPending(false, 0) }
    }

    fun remove() {
        val prev = synchronized(lifecycleLock) {
            removeCount++
            val p = job
            job = null
            state.value = PackUi.Idle
            p
        }
        prev?.cancel()
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                workMutex.withLock {
                    withContext(Dispatchers.IO) {
                        val eng = engine()
                        eng.packStore.remove()
                        workDir.deleteRecursively()
                        settings.setTexturePack(TexturePack.ORIGINAL)
                        settings.setTexturesPending(false, 0)
                        eng.reloadStoredPack()
                    }
                }
            } finally {
                synchronized(lifecycleLock) {
                    removeCount--
                }
            }
        }
    }

    private fun fail(owner: Job, e: Exception, auto: Boolean) {
        val msg = when (e) {
            is ImportException -> e.message ?: "Import failed"
            is IOException -> e.message?.takeIf { it.isNotBlank() } ?: "Download failed"
            else -> "Import failed"
        }
        android.util.Log.w("MineMaps", "texture import failed", e)
        synchronized(lifecycleLock) {
            if (job === owner) {
                job = null
                state.value = if (auto) PackUi.Idle else PackUi.Failed(msg)
            }
        }
    }

    private suspend fun download(owner: Job, dest: File) {
        val http = client()
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val request = Request.Builder().url(jarUrl).header("User-Agent", BuildConfig.USER_AGENT).build()
            val call = http.newBuilder().callTimeout(0, TimeUnit.MILLISECONDS).readTimeout(30, TimeUnit.SECONDS).build().newCall(request)
            val done = AtomicBoolean(false)
            val cancelWatcher = launch(Dispatchers.IO, start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    if (!done.get()) call.cancel()
                }
            }
            try {
                currentCoroutineContext().ensureActive()
                owner.ensureActive()
                call.execute().use { r ->
                    currentCoroutineContext().ensureActive()
                    owner.ensureActive()
                    if (!r.isSuccessful) throw IOException("Mojang's server answered HTTP ${r.code}")
                    if (r.request.url.scheme != "https") throw IOException("Download was not served over HTTPS")
                    if (r.body.contentLength() > jarSize) throw IOException("Download is larger than expected")
                    val md = MessageDigest.getInstance("SHA-1")
                    var total = 0L
                    r.body.byteStream().use { input ->
                        dest.outputStream().use { out ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                owner.ensureActive()
                                val n = input.read(buf)
                                currentCoroutineContext().ensureActive()
                                owner.ensureActive()
                                if (n < 0) break
                                total += n
                                if (total > jarSize) throw IOException("Download is larger than expected")
                                md.update(buf, 0, n)
                                out.write(buf, 0, n)
                                updateState(owner, PackUi.Downloading(total, jarSize))
                            }
                        }
                    }
                    currentCoroutineContext().ensureActive()
                    owner.ensureActive()
                    if (total != jarSize || md.digest().joinToString("") { "%02x".format(it) } != jarSha1) {
                        throw IOException("The download did not match Mojang's checksum")
                    }
                }
            } catch (t: Throwable) {
                dest.delete()
                if (t is CancellationException) throw t
                currentCoroutineContext().ensureActive()
                owner.ensureActive()
                throw t
            } finally {
                done.set(true)
                cancelWatcher.cancel()
            }
        }
    }

    private suspend fun runImport(
        owner: Job,
        file: File,
        desc: List<String>,
        version: String,
        sha1: String,
        select: Boolean,
        layerOverStored: Boolean = false,
    ) {
        val eng = engine()
        withContext(Dispatchers.Default) {
            val importJob = currentCoroutineContext()[Job]
            importJob?.ensureActive()
            owner.ensureActive()
            updateState(owner, PackUi.Importing(0))
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
                MinecraftImporter.import(
                    sources = listOf(src),
                    original = original,
                    decoder = AndroidPngDecoder,
                    onProgress = { pct ->
                        updateState(owner, PackUi.Importing(pct))
                        onImportStep?.invoke(pct)
                    },
                    checkCancelled = {
                        owner.ensureActive()
                        importJob?.ensureActive()
                    },
                )
            }
            importJob?.ensureActive()
            owner.ensureActive()
            eng.packStore.install(
                out,
                MinecraftImporter.sourceText(fullDesc, out.kept),
                PackMeta(version, sha1, MinecraftImporter.IMPORTER_VERSION, System.currentTimeMillis()),
                checkCancelled = {
                    owner.ensureActive()
                    importJob?.ensureActive()
                },
            )
            eng.reloadStoredPack()
        }
        if (select) settings.setTexturePack(TexturePack.VANILLA)
        settings.setTexturesPending(false, 0)
    }

    companion object {
        const val MAX_IMPORT_BYTES = 200L * 1024 * 1024
    }
}
