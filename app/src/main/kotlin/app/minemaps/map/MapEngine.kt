package app.minemaps.map

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import app.minemaps.BuildConfig
import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.labels.NameResolver
import app.minemaps.engine.data.DiskTileCache
import app.minemaps.engine.data.OpenMapTilesSource
import app.minemaps.engine.data.TileRepository
import okhttp3.OkHttpClient
import java.io.File

class MapEngine(context: Context, val client: OkHttpClient) {
    val cache = DiskTileCache(File(context.cacheDir, "tiles"))
    val source = OpenMapTilesSource(BuildConfig.TILEJSON_URL, client, BuildConfig.USER_AGENT, cache)
    val repository = TileRepository(source)
    private val atlases = HashMap<TexturePack, TextureAtlas>()
    val transliterator = AndroidTransliterator()

    fun nameResolver(mode: NameResolver.Mode) = NameResolver(mode, transliterator)

    val typeface: Typeface = loadTypeface(context)
    val memoryClassMb: Int = context.getSystemService(ActivityManager::class.java).memoryClass

    init {
        atlas(TexturePack.ORIGINAL)
        if (isPackAvailable(TexturePack.VANILLA)) atlas(TexturePack.VANILLA)
    }

    fun isPackAvailable(pack: TexturePack): Boolean = EngineResources.isAvailable(pack)

    /** The pack to actually use: the requested one if it is bundled, otherwise the original. */
    fun resolve(pack: TexturePack): TexturePack = if (isPackAvailable(pack)) pack else TexturePack.ORIGINAL

    @Synchronized
    fun atlas(pack: TexturePack): TextureAtlas = atlases.getOrPut(resolve(pack)) { decodeAtlas(resolve(pack)) }

    private fun decodeAtlas(pack: TexturePack): TextureAtlas {
        val bytes = EngineResources.atlasPng(pack)
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val atlas = TextureAtlas(bmp.width, bmp.height, pixels, EngineResources.atlasJson(pack))
        bmp.recycle()
        return atlas
    }

    private fun loadTypeface(context: Context): Typeface = try {
        val target = File(context.filesDir, "Monocraft.ttf")
        val bytes = EngineResources.fontStream().use { it.readBytes() }
        if (!target.exists() || target.length() != bytes.size.toLong()) target.writeBytes(bytes)
        Typeface.createFromFile(target)
    } catch (e: Exception) {
        Typeface.MONOSPACE
    }

    fun clearCache() = cache.clear()
}
