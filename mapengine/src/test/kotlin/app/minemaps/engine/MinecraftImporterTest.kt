package app.minemaps.engine

import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.art.importer.MinecraftImporter
import app.minemaps.engine.art.importer.OriginalAtlas
import app.minemaps.engine.art.importer.PngDecoder
import app.minemaps.engine.art.importer.PngWriter
import app.minemaps.engine.art.importer.Rgba
import app.minemaps.engine.art.importer.TextureSource
import app.minemaps.engine.art.importer.ZipTextureSource
import kotlinx.serialization.json.Json
import org.junit.Assume.assumeTrue
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private val imageIo = PngDecoder { bytes ->
    val bi = ImageIO.read(ByteArrayInputStream(bytes))
    val bands = bi.raster.numBands
    val gray = bi.colorModel !is java.awt.image.IndexColorModel && bi.colorModel.colorSpace.type == java.awt.color.ColorSpace.TYPE_GRAY
    Rgba(
        bi.width, bi.height,
        IntArray(bi.width * bi.height) {
            val x = it % bi.width
            val y = it / bi.width
            if (gray) {
                val v = bi.raster.getSample(x, y, 0)
                val a = if (bands == 2) bi.raster.getSample(x, y, 1) else 255
                (a shl 24) or (v shl 16) or (v shl 8) or v
            } else {
                bi.getRGB(x, y)
            }
        },
    )
}

private fun png(img: Rgba): ByteArray {
    val bi = BufferedImage(img.w, img.h, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until img.h) for (x in 0 until img.w) bi.setRGB(x, y, img[x, y])
    return ByteArrayOutputStream().also { ImageIO.write(bi, "png", it) }.toByteArray()
}

private fun original() = OriginalAtlas(
    EngineResources.atlasJson(TexturePack.ORIGINAL),
    imageIo.decode(EngineResources.atlasPng(TexturePack.ORIGINAL)),
)

class MinecraftImporterTest {
    @Test
    fun goldenAtlasIsPixelIdenticalToThePythonImporterOutput() {
        val jar = File(System.getProperty("user.home"), ".cache/minemaps-mc/client-26.3.jar")
        assumeTrue("client jar not cached", jar.exists())
        assumeTrue("vanilla pack not bundled", EngineResources.isAvailable(TexturePack.VANILLA))
        val out = ZipTextureSource(jar).use { MinecraftImporter.import(listOf(it), original(), imageIo) }
        val expected = imageIo.decode(EngineResources.atlasPng(TexturePack.VANILLA))
        val actual = imageIo.decode(out.atlasPng)
        assertEquals(expected.w, actual.w)
        assertEquals(expected.h, actual.h)
        var diff = 0
        var first = -1
        for (i in expected.px.indices) if (expected.px[i] != actual.px[i]) {
            diff++
            if (first < 0) first = i
        }
        assertEquals(0, diff, "pixels differ, first at index $first (x=${first % expected.w}, y=${first / expected.w})")
        assertEquals(Json.parseToJsonElement(EngineResources.atlasJson(TexturePack.VANILLA)), Json.parseToJsonElement(out.atlasJson))
        println("GOLDEN: ${expected.w}x${expected.h} atlas pixel-identical, atlas.json equal, kept=${out.kept}")
    }

    private fun zip(entries: Map<String, ByteArray>): File {
        val f = File.createTempFile("pack", ".zip")
        f.deleteOnExit()
        ZipOutputStream(f.outputStream()).use { z -> for ((n, b) in entries) { z.putNextEntry(ZipEntry(n)); z.write(b); z.closeEntry() } }
        return f
    }

    private fun solid(w: Int, h: Int, argb: Int) = Rgba(w, h, IntArray(w * h) { argb })

    @Test
    fun syntheticPackWithNestedFolderHdTextureAndFallbackToOriginal() {
        val p = "assets/minecraft/textures/"
        val sand = solid(32, 32, 0xFF112233.toInt())
        val file = zip(
            mapOf(
                "MyPack/${p}block/sand.png" to png(sand),
                "MyPack/${p}block/gravel.png" to png(solid(16, 16, 0xFF445566.toInt())),
            ),
        )
        val orig = original()
        val out = ZipTextureSource(file).use { MinecraftImporter.import(listOf(it), orig, imageIo) }
        val atlas = imageIo.decode(out.atlasPng)
        val meta = Json.parseToJsonElement(out.atlasJson) as kotlinx.serialization.json.JsonObject
        fun cell(arr: kotlinx.serialization.json.JsonElement): Rgba {
            val a = arr as kotlinx.serialization.json.JsonArray
            val cx = (a[0] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
            val cy = (a[1] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
            return atlas.crop(cx * 16, cy * 16, cx * 16 + 16, cy * 16 + 16)
        }
        val textures = meta["textures"] as kotlinx.serialization.json.JsonObject
        val sandCells = textures["sand"] as kotlinx.serialization.json.JsonArray
        assertEquals(2, sandCells.size)
        val c = cell(sandCells[0])
        assertTrue(c.px.all { it == 0xFF112233.toInt() }, "HD 32px sand is box-downscaled to 16px")
        assertTrue(cell((textures["gravel"] as kotlinx.serialization.json.JsonArray)[0]).px.all { it == 0xFF445566.toInt() })
        // grass has no source texture: the original MineMaps cells are kept
        val origMeta = Json.parseToJsonElement(orig.json) as kotlinx.serialization.json.JsonObject
        val origGrass = ((origMeta["textures"] as kotlinx.serialization.json.JsonObject)["grass"] as kotlinx.serialization.json.JsonArray)
        val grassCells = textures["grass"] as kotlinx.serialization.json.JsonArray
        assertEquals(origGrass.size, grassCells.size)
        val og = orig.atlas.crop(
            (origGrass[0] as kotlinx.serialization.json.JsonArray)[0].toString().toInt() * 16,
            (origGrass[0] as kotlinx.serialization.json.JsonArray)[1].toString().toInt() * 16,
            (origGrass[0] as kotlinx.serialization.json.JsonArray)[0].toString().toInt() * 16 + 16,
            (origGrass[0] as kotlinx.serialization.json.JsonArray)[1].toString().toInt() * 16 + 16,
        )
        assertTrue(cell(grassCells[0]).px.contentEquals(og.px))
        assertTrue(out.kept.any { it.startsWith("grass (") })
        // every original name is present
        for (n in (origMeta["icons"] as kotlinx.serialization.json.JsonObject).keys) assertTrue(n in (meta["icons"] as kotlinx.serialization.json.JsonObject).keys)
    }

    @Test
    fun boxDownscaleKeepsPremultipliedAlphaCorrect() {
        val img = Rgba(2, 2, intArrayOf(0xFFFF0000.toInt(), 0x00000000, 0x00000000, 0x00000000))
        val s = img.resizeBox(1, 1)
        assertEquals(0x40, s[0, 0] ushr 24)
        assertEquals(0xFF, (s[0, 0] shr 16) and 255)
        assertEquals(0, s[0, 0] and 255)
    }

    @Test
    fun zipWithoutTexturesIsRejectedAndMissingEverythingStillYieldsTheOriginalPack() {
        val bad = zip(mapOf("readme.txt" to "x".toByteArray()))
        assertFailsWith<app.minemaps.engine.art.importer.ImportException> { ZipTextureSource(bad) }
        val empty = object : TextureSource {
            override fun read(rel: String): ByteArray? = null
        }
        val orig = original()
        val out = MinecraftImporter.import(listOf(empty), orig, imageIo)
        assertEquals(orig.atlas.w, imageIo.decode(out.atlasPng).w)
        assertEquals(Json.parseToJsonElement(orig.json).let { (it as kotlinx.serialization.json.JsonObject)["textures"]!!.toString().length > 0 }, true)
    }

    @Test
    fun pngWriterRoundTripKeepsTransparentPixelColours() {
        val img = Rgba(3, 1, intArrayOf(0x00ABCDEF, 0x80010203.toInt(), 0xFFFFFFFF.toInt()))
        val back = imageIo.decode(PngWriter.encode(img))
        assertTrue(img.px.contentEquals(back.px))
    }

    @Test
    fun customResourcePackSupportsMissingColormapsLegacyFoldersHdChestAndPackMcmeta() {
        val file = zip(
            mapOf(
                "pack.mcmeta" to """{"pack":{"pack_format":15,"description":"\u00a76Faithful \u00a7a32x\nCustom Pack"}}""".toByteArray(),
                "assets/minecraft/textures/blocks/grass_top.png" to png(solid(32, 32, 0xFFCCCCCC.toInt())),
                "assets/minecraft/textures/blocks/grass_path_top.png" to png(solid(32, 32, 0xFFAA8855.toInt())),
                "assets/minecraft/textures/items/apple_golden.png" to png(solid(32, 32, 0xFFFFD700.toInt())),
                "assets/minecraft/textures/entity/chest/normal.png" to png(solid(128, 128, 0xFF8B5A2B.toInt())),
            ),
        )
        val orig = original()
        ZipTextureSource(file).use { src ->
            assertEquals("Faithful 32x Custom Pack", src.packMetaDescription())
            val out = MinecraftImporter.import(listOf(src), orig, imageIo)
            assertTrue(out.kept.none { it.startsWith("grass (") }, "grass should import using default colormap even when colormap/grass.png is absent")
            assertTrue(out.kept.none { it.startsWith("dirt_path (") }, "legacy blocks/grass_path_top.png should map to dirt_path")
            assertTrue(out.kept.none { it.startsWith("golden_apple (") }, "legacy items/apple_golden.png should map to golden_apple")
            assertTrue(out.kept.none { it.startsWith("chest (") }, "128x128 HD entity/chest/normal.png should downscale and compose chest icon")
        }
    }
}
