package app.minemaps

import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.art.importer.ImportOutput
import app.minemaps.engine.art.importer.MinecraftImporter
import app.minemaps.engine.art.importer.PngWriter
import app.minemaps.engine.art.importer.Rgba
import app.minemaps.map.PackMeta
import app.minemaps.map.TexturePackStore
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TexturePackStoreTest {
    private lateinit var filesDir: File
    private lateinit var store: TexturePackStore
    private lateinit var originalJson: String
    private lateinit var requiredNames: Set<String>

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("tp-store-test").toFile()
        store = TexturePackStore(filesDir)
        originalJson = EngineResources.atlasJson(TexturePack.ORIGINAL)
        val root = Json.parseToJsonElement(originalJson).jsonObject
        requiredNames = (
            root["textures"]?.jsonObject?.keys.orEmpty().map { "t:$it" } +
                root["icons"]?.jsonObject?.keys.orEmpty().map { "i:$it" }
            ).toSet()
    }

    @After
    fun tearDown() {
        filesDir.deleteRecursively()
    }

    private fun packOutput(argb: Int): ImportOutput {
        val atlas = Rgba(256, 256, IntArray(256 * 256) { argb })
        return ImportOutput(PngWriter.encode(atlas), originalJson, emptyList())
    }

    @Test
    fun cancelledInstallPreservesExistingPackAndCleansTempDir() {
        val packA = packOutput(0xFF112233.toInt())
        val metaA = PackMeta("26.3-A", "shaA", MinecraftImporter.IMPORTER_VERSION, 1000L)
        store.install(packA, "Imported from: Pack A\n", metaA)

        val vanillaDir = File(filesDir, "packs/vanilla")
        val snapPng = File(vanillaDir, "atlas.png").readBytes()
        val snapJson = File(vanillaDir, "atlas.json").readBytes()
        val snapMeta = File(vanillaDir, "meta.json").readBytes()
        val snapSource = File(vanillaDir, "SOURCE.txt").readBytes()

        val packB = packOutput(0xFF445566.toInt())
        val metaB = PackMeta("26.3-B", "shaB", MinecraftImporter.IMPORTER_VERSION, 2000L)

        // Cancel immediately before lock
        assertFailsWith<CancellationException> {
            store.install(packB, "Imported from: Pack B\n", metaB) {
                throw CancellationException("cancelled early")
            }
        }

        // Cancel mid-staging after vanilla.tmp has been created and files written into it
        var checks = 0
        assertFailsWith<CancellationException> {
            store.install(packB, "Imported from: Pack B\n", metaB) {
                if (++checks >= 5) {
                    assertTrue(File(filesDir, "packs/vanilla.tmp").exists(), "vanilla.tmp should exist during staging")
                    throw CancellationException("cancelled mid-staging")
                }
            }
        }

        assertFalse(File(filesDir, "packs/vanilla.tmp").exists(), "vanilla.tmp must be deleted in finally")
        assertFalse(File(filesDir, "packs/vanilla.old").exists(), "vanilla.old must not exist")
        assertTrue(store.exists)
        assertContentEquals(snapPng, File(vanillaDir, "atlas.png").readBytes())
        assertContentEquals(snapJson, File(vanillaDir, "atlas.json").readBytes())
        assertContentEquals(snapMeta, File(vanillaDir, "meta.json").readBytes())
        assertContentEquals(snapSource, File(vanillaDir, "SOURCE.txt").readBytes())
        val loaded = store.load(requiredNames)
        assertNotNull(loaded)
        assertEquals("26.3-A", loaded.meta.mcVersion)
    }

    @Test
    fun removeCleansVanillaTmpAndOldDirectoriesAndRecoversInterruptedSwap() {
        val packA = packOutput(0xFF112233.toInt())
        val metaA = PackMeta("26.3-A", "shaA", MinecraftImporter.IMPORTER_VERSION, 1000L)
        store.install(packA, "Imported from: Pack A\n", metaA)

        val packsDir = File(filesDir, "packs")
        val tmpDir = File(packsDir, "vanilla.tmp").apply { mkdirs(); File(this, "stale.txt").writeText("x") }
        val oldDir = File(packsDir, "vanilla.old").apply { mkdirs(); File(this, "stale.txt").writeText("y") }
        val vanillaDir = File(packsDir, "vanilla")

        store.remove()

        assertFalse(vanillaDir.exists())
        assertFalse(tmpDir.exists())
        assertFalse(oldDir.exists())
        assertFalse(store.exists)
        assertNull(store.load(requiredNames))

        // Simulate interrupted swap: vanilla missing, valid pack sitting in vanilla.old
        store.install(packA, "Imported from: Pack A\n", metaA)
        assertTrue(vanillaDir.renameTo(oldDir))
        assertFalse(vanillaDir.exists())
        assertTrue(oldDir.exists())

        assertTrue(store.exists, "exists should recover interrupted swap from vanilla.old")
        val recovered = store.load(requiredNames)
        assertNotNull(recovered)
        assertEquals("26.3-A", recovered.meta.mcVersion)
        assertTrue(vanillaDir.exists())
        assertFalse(oldDir.exists())
    }

    @Test
    fun concurrentInstallLoadAndRemoveNeverExposeTransientMissingPackOrResurrectOld() {
        val packA = packOutput(0xFF112233.toInt())
        val metaA = PackMeta("26.3-A", "shaA", MinecraftImporter.IMPORTER_VERSION, 1000L)
        val packB = packOutput(0xFF445566.toInt())
        val metaB = PackMeta("26.3-B", "shaB", MinecraftImporter.IMPORTER_VERSION, 2000L)
        store.install(packA, "Imported from: Pack A\n", metaA)

        val stopReaders = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(1)

        val reader = Thread {
            started.countDown()
            while (!stopReaders.get() && failure.get() == null) {
                try {
                    if (!store.exists) {
                        failure.compareAndSet(null, AssertionError("store.exists returned false during concurrent install"))
                        break
                    }
                    val loaded = store.load(requiredNames)
                    if (loaded == null) {
                        failure.compareAndSet(null, AssertionError("store.load returned null during concurrent install"))
                        break
                    }
                    if (loaded.meta.mcVersion !in setOf("26.3-A", "26.3-B")) {
                        failure.compareAndSet(null, AssertionError("unexpected version ${loaded.meta.mcVersion}"))
                        break
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        reader.start()
        assertTrue(started.await(5, TimeUnit.SECONDS))

        repeat(30) { i ->
            if (i % 2 == 0) {
                store.install(packB, "Imported from: Pack B\n", metaB)
            } else {
                store.install(packA, "Imported from: Pack A\n", metaA)
            }
        }
        stopReaders.set(true)
        reader.join(5000)
        assertFalse(reader.isAlive)
        failure.get()?.let { throw it }

        // Now verify remove() racing with install() never resurrects vanilla.old
        val inStaging = CountDownLatch(1)
        val allowFinish = CountDownLatch(1)
        val installer = Thread {
            store.install(packB, "Imported from: Pack B\n", metaB) {
                if (inStaging.count > 0) {
                    inStaging.countDown()
                    assertTrue(allowFinish.await(5, TimeUnit.SECONDS))
                }
            }
        }
        installer.start()
        assertTrue(inStaging.await(5, TimeUnit.SECONDS))
        val removerDone = CountDownLatch(1)
        val remover = Thread {
            store.remove()
            removerDone.countDown()
        }
        remover.start()
        allowFinish.countDown()
        installer.join(5000)
        assertTrue(removerDone.await(5, TimeUnit.SECONDS))
        remover.join(5000)

        assertFalse(File(filesDir, "packs/vanilla").exists())
        assertFalse(File(filesDir, "packs/vanilla.tmp").exists())
        assertFalse(File(filesDir, "packs/vanilla.old").exists())
        assertFalse(store.exists)
        assertNull(store.load(requiredNames))
    }
}
