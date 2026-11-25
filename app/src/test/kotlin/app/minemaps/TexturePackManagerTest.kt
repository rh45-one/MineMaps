package app.minemaps

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import app.minemaps.data.SettingsRepository
import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.art.importer.ImportOutput
import app.minemaps.engine.art.importer.MinecraftImporter
import app.minemaps.engine.art.importer.PngWriter
import app.minemaps.engine.art.importer.Rgba
import app.minemaps.map.MapEngine
import app.minemaps.map.PackMeta
import app.minemaps.map.PackUi
import app.minemaps.map.TexturePackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TexturePackManagerTest {
    private lateinit var context: Context
    private lateinit var dsDir: File
    private lateinit var scope: CoroutineScope
    private lateinit var settings: SettingsRepository
    private lateinit var originalJson: String
    private lateinit var requiredNames: Set<String>

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "packs").deleteRecursively()
        File(context.cacheDir, "pack-import").deleteRecursively()
        Shadows.shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryClass(256)
        dsDir = Files.createTempDirectory("tpm-ds").toFile()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = SettingsRepository(
            PreferenceDataStoreFactory.create(scope = scope) {
                File(dsDir, "settings.preferences_pb")
            },
        )
        originalJson = EngineResources.atlasJson(TexturePack.ORIGINAL)
        val root = Json.parseToJsonElement(originalJson).jsonObject
        requiredNames = (
            root["textures"]?.jsonObject?.keys.orEmpty().map { "t:$it" } +
                root["icons"]?.jsonObject?.keys.orEmpty().map { "i:$it" }
            ).toSet()
    }

    @After
    fun tearDown() {
        scope.cancel()
        Shadows.shadowOf(Looper.getMainLooper()).idle()
        File(context.filesDir, "packs").deleteRecursively()
        File(context.cacheDir, "pack-import").deleteRecursively()
        dsDir.deleteRecursively()
    }

    private fun solid(w: Int, h: Int, argb: Int) = Rgba(w, h, IntArray(w * h) { argb })

    private fun buildSyntheticPackBytes(
        grassColor: Int = 0xFFBBBBBB.toInt(),
        sandColor: Int = 0xFFD8C280.toInt(),
    ): ByteArray {
        val p = "assets/minecraft/textures/"
        val entries = mapOf(
            "${p}block/grass_block_top.png" to PngWriter.encode(solid(16, 16, grassColor)),
            "${p}block/sand.png" to PngWriter.encode(solid(16, 16, sandColor)),
            "${p}block/water_still.png" to PngWriter.encode(solid(16, 32, 0xFF88AAFF.toInt())),
            "${p}colormap/grass.png" to PngWriter.encode(solid(256, 256, 0xFF77BB44.toInt())),
            "${p}item/apple.png" to PngWriter.encode(solid(16, 16, 0xFFCC2222.toInt())),
            "${p}map/decorations/red_banner.png" to PngWriter.encode(solid(16, 16, 0xFFEE1111.toInt())),
            "${p}entity/chest/normal.png" to PngWriter.encode(solid(64, 64, 0xFF996633.toInt())),
        )
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return baos.toByteArray()
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun preInstallPackA(engine: MapEngine, argb: Int = 0xFF112233.toInt()) {
        val out = ImportOutput(PngWriter.encode(solid(256, 256, argb)), originalJson, emptyList())
        engine.packStore.install(
            out,
            "Imported from: Pack A\n",
            PackMeta("26.3-existing", "existing-sha", MinecraftImporter.IMPORTER_VERSION, 1000L),
        )
        runBlocking { settings.setTexturePack(TexturePack.VANILLA) }
        engine.reloadStoredPack(notify = false)
    }

    private fun awaitLatch(latch: CountDownLatch, msg: String) {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Timed out waiting for: $msg")
    }

    private fun awaitCondition(msg: String, cond: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!cond()) {
            if (System.nanoTime() >= deadline) throw AssertionError("Timed out waiting for: $msg")
            LockSupport.parkNanos(500_000L)
        }
    }

    private fun collectStates(manager: TexturePackManager, list: CopyOnWriteArrayList<PackUi>): Job =
        scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            manager.state.collect { list.add(it) }
        }

    private fun okHttpResponse(
        request: okhttp3.Request,
        contentLength: Long,
        source: Source,
    ): Response {
        val body = object : ResponseBody() {
            override fun contentType() = "application/java-archive".toMediaType()
            override fun contentLength() = contentLength
            override fun source(): BufferedSource = source.buffer()
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body)
            .build()
    }

    @Test
    fun cancelDuringBlockedOkHttpHeadersAbortsCallPromptlyAndCleansTemp() {
        val packBytes = buildSyntheticPackBytes()
        val packSha1 = sha1Hex(packBytes)
        val headersEntered = CountDownLatch(1)
        val callCanceledLatch = CountDownLatch(1)
        val capturedCanceledCall = AtomicReference<Call?>(null)

        val httpClient = OkHttpClient.Builder()
            .eventListenerFactory {
                object : EventListener() {
                    override fun canceled(call: Call) {
                        capturedCanceledCall.set(call)
                        callCanceledLatch.countDown()
                    }
                }
            }
            .addInterceptor { chain ->
                headersEntered.countDown()
                awaitLatch(callCanceledLatch, "OkHttp call cancellation during headers")
                if (chain.call().isCanceled()) throw IOException("canceled")
                Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(500)
                    .message("Unexpected")
                    .body(ByteArray(0).toResponseBody(null))
                    .build()
            }
            .build()

        val engine = MapEngine(context, httpClient)
        val manager = TexturePackManager(
            context = context,
            scope = scope,
            settings = settings,
            engine = { engine },
            client = { httpClient },
            jarUrl = "https://test.local/client.jar",
            jarSha1 = packSha1,
            jarSize = packBytes.size.toLong(),
        )

        val observed = CopyOnWriteArrayList<PackUi>()
        val collector = collectStates(manager, observed)
        try {
            manager.importNow()
            awaitLatch(headersEntered, "OkHttp interceptor entered")

            observed.clear()
            manager.cancel()

            awaitLatch(callCanceledLatch, "OkHttp Call.cancel() was not triggered promptly")
            awaitCondition("manager idle and workDir removed") {
                !manager.busy && !File(context.cacheDir, "pack-import").exists()
            }

            assertEquals(true, capturedCanceledCall.get()?.isCanceled())
            assertEquals(PackUi.Idle, manager.state.value)
            assertTrue(
                observed.none { it is PackUi.Failed || it is PackUi.Downloading || it is PackUi.Importing },
                "unexpected state emissions after cancel: $observed",
            )
            assertFalse(File(context.cacheDir, "pack-import/client.jar").exists())
            assertFalse(engine.isPackAvailable(TexturePack.VANILLA))
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun cancelDuringBlockedOkHttpStreamUnblocksInputReadAbortsCallAndCleansTemp() {
        val packBytes = buildSyntheticPackBytes()
        val packSha1 = sha1Hex(packBytes)
        val secondReadBlocked = CountDownLatch(1)
        val readGate = CountDownLatch(1)
        val callCanceledLatch = CountDownLatch(1)
        val streamAborted = AtomicBoolean(false)

        val httpClient = OkHttpClient.Builder()
            .eventListenerFactory {
                object : EventListener() {
                    override fun canceled(call: Call) {
                        streamAborted.set(true)
                        callCanceledLatch.countDown()
                        readGate.countDown()
                    }
                }
            }
            .addInterceptor { chain ->
                val src = object : Source {
                    private var offset = 0
                    private var chunkCount = 0

                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (offset >= packBytes.size) return -1L
                        if (chunkCount == 0) {
                            chunkCount++
                            val n = minOf(64, packBytes.size - offset)
                            sink.write(packBytes, offset, n)
                            offset += n
                            return n.toLong()
                        }
                        secondReadBlocked.countDown()
                        awaitLatch(readGate, "stream read gate")
                        if (streamAborted.get()) throw IOException("Socket closed")
                        val n = minOf(byteCount.toInt(), packBytes.size - offset)
                        sink.write(packBytes, offset, n)
                        offset += n
                        return n.toLong()
                    }

                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() {}
                }
                okHttpResponse(chain.request(), packBytes.size.toLong(), src)
            }
            .build()

        val engine = MapEngine(context, httpClient)
        val manager = TexturePackManager(
            context = context,
            scope = scope,
            settings = settings,
            engine = { engine },
            client = { httpClient },
            jarUrl = "https://test.local/client.jar",
            jarSha1 = packSha1,
            jarSize = packBytes.size.toLong(),
        )

        val observed = CopyOnWriteArrayList<PackUi>()
        val collector = collectStates(manager, observed)
        try {
            manager.importNow()
            awaitLatch(secondReadBlocked, "second read blocked")
            awaitCondition("state Downloading with progress > 0") {
                val s = manager.state.value
                s is PackUi.Downloading && s.bytes > 0
            }
            assertTrue(File(context.cacheDir, "pack-import/client.jar").exists())

            observed.clear()
            manager.cancel()

            awaitLatch(callCanceledLatch, "call.cancel() on blocked stream")
            awaitCondition("manager idle and workDir cleaned") {
                !manager.busy && !File(context.cacheDir, "pack-import").exists()
            }

            assertEquals(PackUi.Idle, manager.state.value)
            assertTrue(
                observed.none { it is PackUi.Failed || it is PackUi.Downloading || it is PackUi.Importing },
                "unexpected state emissions after cancel: $observed",
            )
            assertFalse(File(context.cacheDir, "pack-import/client.jar").exists())
            assertFalse(File(context.cacheDir, "pack-import").exists())
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun cancelDuringBlockedImportFromFileStreamDoesNotResurrectDownloadingOrFailed() {
        val packBytes = buildSyntheticPackBytes()
        val httpClient = OkHttpClient()
        val engine = MapEngine(context, httpClient)
        val manager = TexturePackManager(
            context = context,
            scope = scope,
            settings = settings,
            engine = { engine },
            client = { httpClient },
        )

        // Part A: close() unblocks read() with IOException
        val uriA = Uri.parse("content://app.minemaps.test/packA.zip")
        val secondReadBlockedA = CountDownLatch(1)
        val readGateA = CountDownLatch(1)
        val closedLatchA = CountDownLatch(1)
        val closedA = AtomicBoolean(false)

        Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uriA) {
            object : InputStream() {
                private var pos = 0
                private var firstDone = false

                override fun read(): Int {
                    val b = ByteArray(1)
                    return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
                }

                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (closedA.get()) throw IOException("closed")
                    if (!firstDone) {
                        firstDone = true
                        val n = minOf(64, len, packBytes.size - pos)
                        System.arraycopy(packBytes, pos, b, off, n)
                        pos += n
                        return n
                    }
                    secondReadBlockedA.countDown()
                    awaitLatch(readGateA, "SAF stream gate A")
                    if (closedA.get()) throw IOException("closed")
                    return -1
                }

                override fun close() {
                    closedA.set(true)
                    readGateA.countDown()
                    closedLatchA.countDown()
                }
            }
        }

        val observed = CopyOnWriteArrayList<PackUi>()
        val collector = collectStates(manager, observed)
        try {
            manager.importFromFile(uriA)
            awaitLatch(secondReadBlockedA, "SAF second read blocked A")
            awaitCondition("Downloading from file") {
                val s = manager.state.value
                s is PackUi.Downloading && s.bytes > 0
            }

            observed.clear()
            manager.cancel()

            awaitLatch(closedLatchA, "SAF stream closed on cancel")
            awaitCondition("workDir cleaned A") {
                !manager.busy && !File(context.cacheDir, "pack-import").exists()
            }
            assertEquals(PackUi.Idle, manager.state.value)
            assertTrue(observed.none { it is PackUi.Downloading || it is PackUi.Failed || it is PackUi.Importing })
            assertFalse(File(context.cacheDir, "pack-import/import.zip").exists())

            // Part B: late bytes returned right after cancel without throwing from read()
            val uriB = Uri.parse("content://app.minemaps.test/packB.zip")
            val secondReadBlockedB = CountDownLatch(1)
            val readGateB = CountDownLatch(1)
            val secondChunkReturnedB = CountDownLatch(1)
            Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uriB) {
                object : InputStream() {
                    private var pos = 0
                    private var chunk = 0

                    override fun read(): Int {
                        val b = ByteArray(1)
                        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (chunk == 0) {
                            chunk++
                            val n = minOf(64, len, packBytes.size - pos)
                            System.arraycopy(packBytes, pos, b, off, n)
                            pos += n
                            return n
                        }
                        if (chunk == 1) {
                            chunk++
                            secondReadBlockedB.countDown()
                            awaitLatch(readGateB, "SAF stream gate B")
                            val n = minOf(64, len, packBytes.size - pos)
                            System.arraycopy(packBytes, pos, b, off, n)
                            pos += n
                            secondChunkReturnedB.countDown()
                            return n
                        }
                        return -1
                    }
                }
            }

            manager.importFromFile(uriB)
            awaitLatch(secondReadBlockedB, "SAF second read blocked B")
            awaitCondition("Downloading from file B") {
                val s = manager.state.value
                s is PackUi.Downloading && s.bytes > 0
            }

            observed.clear()
            manager.cancel()
            readGateB.countDown()
            awaitLatch(secondChunkReturnedB, "late chunk delivered after cancel")
            awaitCondition("workDir cleaned B") {
                !manager.busy && !File(context.cacheDir, "pack-import").exists()
            }
            assertEquals(PackUi.Idle, manager.state.value)
            assertTrue(
                observed.none { it is PackUi.Downloading || it is PackUi.Failed || it is PackUi.Importing },
                "late bytes resurrected UI state: $observed",
            )
            assertFalse(File(context.cacheDir, "pack-import/import.zip").exists())
        } finally {
            collector.cancel()
        }
    }

    @Test
    fun cancelMidMinecraftImporterAbortsCooperativelyWithoutCorruptingExistingStoredPack() {
        val packBBytes = buildSyntheticPackBytes(grassColor = 0xFFAA5500.toInt(), sandColor = 0xFF998877.toInt())
        val packBSha1 = sha1Hex(packBBytes)

        val httpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val src = object : Source {
                    private var offset = 0
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (offset >= packBBytes.size) return -1L
                        val n = minOf(byteCount.toInt(), packBBytes.size - offset)
                        sink.write(packBBytes, offset, n)
                        offset += n
                        return n.toLong()
                    }
                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() {}
                }
                okHttpResponse(chain.request(), packBBytes.size.toLong(), src)
            }
            .build()

        val engine = MapEngine(context, httpClient)
        preInstallPackA(engine)

        val vanillaDir = File(context.filesDir, "packs/vanilla")
        val snapPng = File(vanillaDir, "atlas.png").readBytes()
        val snapJson = File(vanillaDir, "atlas.json").readBytes()
        val snapMeta = File(vanillaDir, "meta.json").readBytes()
        val snapSource = File(vanillaDir, "SOURCE.txt").readBytes()
        val revBefore = engine.packRevision.value

        val manager = TexturePackManager(
            context = context,
            scope = scope,
            settings = settings,
            engine = { engine },
            client = { httpClient },
            jarUrl = "https://test.local/client.jar",
            jarSha1 = packBSha1,
            jarSize = packBBytes.size.toLong(),
        )

        val enteredImport = AtomicBoolean(false)
        val importStepEntered = CountDownLatch(1)
        val allowImportResume = CountDownLatch(1)
        manager.onImportStep = { _ ->
            if (enteredImport.compareAndSet(false, true)) {
                importStepEntered.countDown()
                awaitLatch(allowImportResume, "allow import resume")
            }
        }

        val observed = CopyOnWriteArrayList<PackUi>()
        val receivedMessages = CopyOnWriteArrayList<String>()
        val stateCollector = collectStates(manager, observed)
        val msgCollector = scope.launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            manager.messages.collect { receivedMessages.add(it) }
        }
        try {
            manager.importNow(auto = false)
            awaitLatch(importStepEntered, "MinecraftImporter step entered")
            awaitCondition("state Importing") { manager.state.value is PackUi.Importing && manager.busy }

            observed.clear()
            manager.cancel()
            allowImportResume.countDown()

            awaitCondition("workDir cleaned after import cancel") {
                !manager.busy && !File(context.cacheDir, "pack-import").exists()
            }

            assertEquals(PackUi.Idle, manager.state.value)
            assertTrue(
                observed.none { it is PackUi.Failed || it is PackUi.Importing || it is PackUi.Downloading },
                "unexpected state emissions after cancel: $observed",
            )
            assertTrue(receivedMessages.isEmpty())
            assertEquals(revBefore, engine.packRevision.value)
            assertTrue(engine.packStore.exists)
            val loaded = engine.packStore.load(requiredNames)
            assertNotNull(loaded)
            assertEquals("26.3-existing", loaded.meta.mcVersion)
            assertContentEquals(snapPng, File(vanillaDir, "atlas.png").readBytes())
            assertContentEquals(snapJson, File(vanillaDir, "atlas.json").readBytes())
            assertContentEquals(snapMeta, File(vanillaDir, "meta.json").readBytes())
            assertContentEquals(snapSource, File(vanillaDir, "SOURCE.txt").readBytes())
            assertFalse(File(context.filesDir, "packs/vanilla.tmp").exists())
            assertFalse(File(context.filesDir, "packs/vanilla.old").exists())
            assertFalse(File(context.cacheDir, "pack-import/client.jar").exists())
        } finally {
            stateCollector.cancel()
            msgCollector.cancel()
        }
    }

    @Test
    fun cancelFollowedImmediatelyByRestartReflectsBusyStateAndCompletesSecondImport() {
        // Test 5A: importNow() -> cancel() -> importNow()
        run {
            val pack2Bytes = buildSyntheticPackBytes(grassColor = 0xFF33AA55.toInt(), sandColor = 0xFFEEDD99.toInt())
            val pack2Sha1 = sha1Hex(pack2Bytes)
            val attempt = AtomicInteger(0)
            val job1Streaming = CountDownLatch(1)
            val readGate1 = CountDownLatch(1)
            val aborted1 = AtomicBoolean(false)
            val job1InCloseHoldingMutex = CountDownLatch(1)
            val allowJob1Unwind = CountDownLatch(1)
            val job2Started = CountDownLatch(1)
            val allowJob2Finish = CountDownLatch(1)

            val httpClient = OkHttpClient.Builder()
                .eventListenerFactory {
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            aborted1.set(true)
                            readGate1.countDown()
                        }
                    }
                }
                .addInterceptor { chain ->
                    val callNum = attempt.incrementAndGet()
                    if (callNum == 1) {
                        val src = object : Source {
                            private var sentFirst = false
                            private val heldOnce = AtomicBoolean(false)
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                if (!sentFirst) {
                                    sentFirst = true
                                    val n = minOf(64, pack2Bytes.size)
                                    sink.write(pack2Bytes, 0, n)
                                    return n.toLong()
                                }
                                job1Streaming.countDown()
                                awaitLatch(readGate1, "job1 readGate1")
                                if (aborted1.get()) throw IOException("canceled")
                                return -1L
                            }

                            override fun timeout(): Timeout = Timeout.NONE
                            override fun close() {
                                if (heldOnce.compareAndSet(false, true)) {
                                    job1InCloseHoldingMutex.countDown()
                                    awaitLatch(allowJob1Unwind, "job1 unwind gate while holding workMutex")
                                }
                            }
                        }
                        okHttpResponse(chain.request(), pack2Bytes.size.toLong(), src)
                    } else {
                        job2Started.countDown()
                        awaitLatch(allowJob2Finish, "job2 finish gate")
                        val src = object : Source {
                            private var off = 0
                            override fun read(sink: Buffer, byteCount: Long): Long {
                                if (off >= pack2Bytes.size) return -1L
                                val n = minOf(byteCount.toInt(), pack2Bytes.size - off)
                                sink.write(pack2Bytes, off, n)
                                off += n
                                return n.toLong()
                            }
                            override fun timeout(): Timeout = Timeout.NONE
                            override fun close() {}
                        }
                        okHttpResponse(chain.request(), pack2Bytes.size.toLong(), src)
                    }
                }
                .build()

            val engine = MapEngine(context, httpClient)
            val manager = TexturePackManager(
                context = context,
                scope = scope,
                settings = settings,
                engine = { engine },
                client = { httpClient },
                jarUrl = "https://test.local/client.jar",
                jarSha1 = pack2Sha1,
                jarSize = pack2Bytes.size.toLong(),
            )

            manager.importNow()
            awaitLatch(job1Streaming, "job1 streaming")

            manager.cancel()
            awaitLatch(job1InCloseHoldingMutex, "job1 inside close() holding workMutex")

            // Immediately restart while Job 1 is still holding workMutex
            manager.importNow()
            assertTrue(manager.busy, "busy must be true while restart is queued")
            assertFalse(manager.state.value is PackUi.Idle, "restart behind workMutex must NOT stay PackUi.Idle while busy")

            allowJob1Unwind.countDown()
            awaitLatch(job2Started, "job2 started")

            assertTrue(manager.busy)
            assertFalse(manager.state.value is PackUi.Idle, "finishing cancelled Job 1 must NOT reset Job 2 back to Idle")

            allowJob2Finish.countDown()
            awaitCondition("job2 completes cleanly") {
                !manager.busy && manager.state.value == PackUi.Idle && !File(context.cacheDir, "pack-import").exists()
            }

            assertTrue(engine.isPackAvailable(TexturePack.VANILLA))
            assertEquals(pack2Sha1, engine.packStore.load(requiredNames)?.meta?.sourceSha1)
            assertEquals(TexturePack.VANILLA, runBlocking { settings.settings.first().texturePack })
        }

        // Clean between 5A and 5B
        File(context.filesDir, "packs").deleteRecursively()
        File(context.cacheDir, "pack-import").deleteRecursively()
        runBlocking { settings.setTexturePack(TexturePack.ORIGINAL) }

        // Test 5B: importFromFile(uri1) -> cancel() -> importFromFile(uri2)
        run {
            val pack2Bytes = buildSyntheticPackBytes(grassColor = 0xFF6644CC.toInt(), sandColor = 0xFFCCAA66.toInt())
            val pack2Sha1 = sha1Hex(pack2Bytes)
            val httpClient = OkHttpClient()
            val engine = MapEngine(context, httpClient)
            val manager = TexturePackManager(
                context = context,
                scope = scope,
                settings = settings,
                engine = { engine },
                client = { httpClient },
            )
            engine.reloadStoredPack(notify = false)

            val uri1 = Uri.parse("content://app.minemaps.test/restart1.zip")
            val uri2 = Uri.parse("content://app.minemaps.test/restart2.zip")
            val job1Streaming = CountDownLatch(1)
            val readGate1 = CountDownLatch(1)
            val closed1 = AtomicBoolean(false)
            val job1InCloseHoldingMutex = CountDownLatch(1)
            val allowJob1Unwind = CountDownLatch(1)
            val job2Started = CountDownLatch(1)
            val allowJob2Finish = CountDownLatch(1)

            Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri1) {
                object : InputStream() {
                    private var firstDone = false
                    override fun read(): Int {
                        val b = ByteArray(1)
                        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!firstDone) {
                            firstDone = true
                            val n = minOf(64, len, pack2Bytes.size)
                            System.arraycopy(pack2Bytes, 0, b, off, n)
                            return n
                        }
                        job1Streaming.countDown()
                        awaitLatch(readGate1, "uri1 readGate1")
                        if (closed1.get()) throw IOException("closed")
                        return -1
                    }

                    private val heldOnce = AtomicBoolean(false)

                    override fun close() {
                        closed1.set(true)
                        readGate1.countDown()
                        if (heldOnce.compareAndSet(false, true)) {
                            job1InCloseHoldingMutex.countDown()
                            awaitLatch(allowJob1Unwind, "uri1 unwind gate while holding workMutex")
                        }
                    }
                }
            }

            Shadows.shadowOf(context.contentResolver).registerInputStreamSupplier(uri2) {
                object : InputStream() {
                    private var pos = 0
                    private var signaled = false
                    override fun read(): Int {
                        val b = ByteArray(1)
                        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
                    }

                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (!signaled) {
                            signaled = true
                            job2Started.countDown()
                            awaitLatch(allowJob2Finish, "uri2 finish gate")
                        }
                        if (pos >= pack2Bytes.size) return -1
                        val n = minOf(len, pack2Bytes.size - pos)
                        System.arraycopy(pack2Bytes, pos, b, off, n)
                        pos += n
                        return n
                    }
                }
            }

            manager.importFromFile(uri1)
            awaitLatch(job1Streaming, "uri1 streaming")

            manager.cancel()
            awaitLatch(job1InCloseHoldingMutex, "uri1 inside close() holding workMutex")

            manager.importFromFile(uri2)
            assertTrue(manager.busy, "busy must be true while importFromFile restart is queued")
            assertFalse(manager.state.value is PackUi.Idle, "restart behind workMutex must NOT stay PackUi.Idle while busy")

            allowJob1Unwind.countDown()
            awaitLatch(job2Started, "uri2 started")

            assertTrue(manager.busy)
            assertFalse(manager.state.value is PackUi.Idle, "finishing cancelled uri1 must NOT reset uri2 back to Idle")

            allowJob2Finish.countDown()
            awaitCondition("uri2 completes cleanly") {
                !manager.busy && manager.state.value == PackUi.Idle && !File(context.cacheDir, "pack-import").exists()
            }

            assertTrue(engine.isPackAvailable(TexturePack.VANILLA))
            assertEquals(pack2Sha1, engine.packStore.load(requiredNames)?.meta?.sourceSha1)
            assertEquals(TexturePack.VANILLA, runBlocking { settings.settings.first().texturePack })
        }
    }

    @Test
    fun concurrentRemoveCancelsActiveImportWaitsForMutexAndNeverResurrectsDeletedPack() {
        // Case A: remove() mid-MinecraftImporter.import right before packStore.install
        run {
            val packBBytes = buildSyntheticPackBytes(grassColor = 0xFF993311.toInt(), sandColor = 0xFF776655.toInt())
            val packBSha1 = sha1Hex(packBBytes)

            val httpClient = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    val src = object : Source {
                        private var off = 0
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (off >= packBBytes.size) return -1L
                            val n = minOf(byteCount.toInt(), packBBytes.size - off)
                            sink.write(packBBytes, off, n)
                            off += n
                            return n.toLong()
                        }
                        override fun timeout(): Timeout = Timeout.NONE
                        override fun close() {}
                    }
                    okHttpResponse(chain.request(), packBBytes.size.toLong(), src)
                }
                .build()

            val engine = MapEngine(context, httpClient)
            preInstallPackA(engine)
            runBlocking { settings.setTexturesPending(true, 5) }

            val manager = TexturePackManager(
                context = context,
                scope = scope,
                settings = settings,
                engine = { engine },
                client = { httpClient },
                jarUrl = "https://test.local/client.jar",
                jarSha1 = packBSha1,
                jarSize = packBBytes.size.toLong(),
            )

            val pausedOnce = AtomicBoolean(false)
            val importMidRun = CountDownLatch(1)
            val allowImportToFinish = CountDownLatch(1)
            manager.onImportStep = { pct ->
                if (pct > 0 && pausedOnce.compareAndSet(false, true)) {
                    importMidRun.countDown()
                    awaitLatch(allowImportToFinish, "allow import to resume after remove()")
                }
            }

            manager.importNow()
            awaitLatch(importMidRun, "import mid-run")
            val revBefore = engine.packRevision.value

            manager.remove()
            allowImportToFinish.countDown()

            awaitCondition("remove() completes and cleans pack A/B") {
                !manager.busy &&
                    engine.packRevision.value > revBefore &&
                    !File(context.filesDir, "packs/vanilla").exists() &&
                    !File(context.cacheDir, "pack-import").exists() &&
                    runBlocking { settings.settings.first().texturePack == TexturePack.ORIGINAL }
            }

            assertFalse(engine.packStore.exists)
            assertFalse(File(context.filesDir, "packs/vanilla").exists())
            assertFalse(File(context.filesDir, "packs/vanilla.tmp").exists())
            assertFalse(File(context.filesDir, "packs/vanilla.old").exists())
            assertNull(engine.storedPack)
            assertFalse(engine.isPackAvailable(TexturePack.VANILLA))
            val finalSettings = runBlocking { settings.settings.first() }
            assertEquals(TexturePack.ORIGINAL, finalSettings.texturePack)
            assertFalse(finalSettings.texturesPending)
            assertEquals(PackUi.Idle, manager.state.value)
            assertFalse(manager.busy)
        }

        // Clean between Case A and Case B
        File(context.filesDir, "packs").deleteRecursively()
        File(context.cacheDir, "pack-import").deleteRecursively()

        // Case B: remove() during blocked download() stream followed by stream release
        run {
            val packBBytes = buildSyntheticPackBytes(grassColor = 0xFF4488CC.toInt(), sandColor = 0xFFAA9977.toInt())
            val packBSha1 = sha1Hex(packBBytes)
            val secondReadBlocked = CountDownLatch(1)
            val readGate = CountDownLatch(1)
            val callCanceledLatch = CountDownLatch(1)
            val streamAborted = AtomicBoolean(false)

            val httpClient = OkHttpClient.Builder()
                .eventListenerFactory {
                    object : EventListener() {
                        override fun canceled(call: Call) {
                            streamAborted.set(true)
                            callCanceledLatch.countDown()
                            readGate.countDown()
                        }
                    }
                }
                .addInterceptor { chain ->
                    val src = object : Source {
                        private var sentFirst = false
                        override fun read(sink: Buffer, byteCount: Long): Long {
                            if (!sentFirst) {
                                sentFirst = true
                                val n = minOf(64, packBBytes.size)
                                sink.write(packBBytes, 0, n)
                                return n.toLong()
                            }
                            secondReadBlocked.countDown()
                            awaitLatch(readGate, "Case B stream gate")
                            if (streamAborted.get()) throw IOException("Socket closed")
                            return -1L
                        }
                        override fun timeout(): Timeout = Timeout.NONE
                        override fun close() {}
                    }
                    okHttpResponse(chain.request(), packBBytes.size.toLong(), src)
                }
                .build()

            val engine = MapEngine(context, httpClient)
            preInstallPackA(engine)
            runBlocking { settings.setTexturesPending(true, 5) }

            val manager = TexturePackManager(
                context = context,
                scope = scope,
                settings = settings,
                engine = { engine },
                client = { httpClient },
                jarUrl = "https://test.local/client.jar",
                jarSha1 = packBSha1,
                jarSize = packBBytes.size.toLong(),
            )

            manager.importNow()
            awaitLatch(secondReadBlocked, "Case B blocked stream")
            val revBefore = engine.packRevision.value

            manager.remove()
            awaitLatch(callCanceledLatch, "Case B call canceled by remove()")
            awaitCondition("Case B remove completes cleanly") {
                !manager.busy &&
                    engine.packRevision.value > revBefore &&
                    !engine.packStore.exists &&
                    !File(context.cacheDir, "pack-import").exists() &&
                    runBlocking { settings.settings.first().texturePack == TexturePack.ORIGINAL }
            }

            assertFalse(File(context.filesDir, "packs/vanilla").exists())
            assertFalse(File(context.filesDir, "packs/vanilla.tmp").exists())
            assertFalse(File(context.filesDir, "packs/vanilla.old").exists())
            assertNull(engine.storedPack)
            assertFalse(engine.isPackAvailable(TexturePack.VANILLA))
            val finalSettings = runBlocking { settings.settings.first() }
            assertEquals(TexturePack.ORIGINAL, finalSettings.texturePack)
            assertFalse(finalSettings.texturesPending)
            assertEquals(PackUi.Idle, manager.state.value)
        }
    }
}
