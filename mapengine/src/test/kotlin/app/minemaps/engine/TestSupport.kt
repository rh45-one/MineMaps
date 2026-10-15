package app.minemaps.engine

import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.mvt.Feature
import app.minemaps.engine.mvt.GeomType
import app.minemaps.engine.mvt.Layer
import app.minemaps.engine.mvt.VectorTile
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

object TestSupport {
    fun loadAtlas(pack: TexturePack): TextureAtlas {
        val img = ImageIO.read(ByteArrayInputStream(EngineResources.atlasPng(pack)))
        val pixels = IntArray(img.width * img.height)
        img.getRGB(0, 0, img.width, img.height, pixels, 0, img.width)
        return TextureAtlas(img.width, img.height, pixels, EngineResources.atlasJson(pack))
    }

    val atlas: TextureAtlas by lazy { loadAtlas(TexturePack.ORIGINAL) }

    fun fixtureBytes(): ByteArray =
        TestSupport::class.java.classLoader.getResourceAsStream("fixtures/14_8296_5636.pbf")!!.use { it.readBytes() }

    fun polygon(vararg rings: IntArray, tags: Map<String, Any>, id: Long = 0) =
        Feature(id, GeomType.POLYGON, tags, rings.toList())

    fun line(path: IntArray, tags: Map<String, Any>) = Feature(0, GeomType.LINE, tags, listOf(path))

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int) = intArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    fun tile(layer: String, vararg features: Feature, extent: Int = 4096) =
        VectorTile(mapOf(layer to Layer(layer, extent, features.toList())))

    fun luminance(argb: Int): Double {
        val r = (argb shr 16) and 0xff
        val g = (argb shr 8) and 0xff
        val b = argb and 0xff
        return 0.299 * r + 0.587 * g + 0.114 * b
    }
}
