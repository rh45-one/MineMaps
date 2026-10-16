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
import app.minemaps.engine.art.importer.MinecraftImporter
import kotlinx.serialization.json.jsonObject
import app.minemaps.engine.labels.NameResolver
import app.minemaps.engine.data.DiskTileCache
import app.minemaps.engine.data.OpenMapTilesSource
import app.minemaps.engine.data.TileRepository
import app.minemaps.engine.search.PlaceIndex
import okhttp3.OkHttpClient
import java.io.File

class MapEngine(context: Context, val client: OkHttpClient, placeIndex: PlaceIndex? = null) {
    val cache = DiskTileCache(File(context.cacheDir, "tiles"))
    val source = OpenMapTilesSource(BuildConfig.TILEJSON_URL, client, BuildConfig.USER_AGENT, cache)
    val repository = TileRepository(source).also { repo ->
        if (placeIndex != null) {
            val resolver = NameResolver(NameResolver.Mode.ENGLISH, AndroidTransliterator())
            repo.onDecoded = { key, tile -> placeIndex.add(key, tile, resolver) }
        }
    }
    private val atlases = HashMap<TexturePack, TextureAtlas>()
    val packStore = TexturePackStore(context.filesDir)

    @Volatile
    private var stored: StoredPack? = null
    val packRevision = kotlinx.coroutines.flow.MutableStateFlow(0)
    val transliterator = AndroidTransliterator()

    fun nameResolver(mode: NameResolver.Mode) = NameResolver(mode, transliterator)

    val typeface: Typeface = loadTypeface(context)
    val memoryClassMb: Int = context.getSystemService(ActivityManager::class.java).memoryClass

    private val requiredNames: Set<String> = run {
        val root = kotlinx.serialization.json.Json.parseToJsonElement(EngineResources.atlasJson(TexturePack.ORIGINAL)).jsonObject
        (root["textures"]?.jsonObject?.keys.orEmpty().map { "t:$it" } + root["icons"]?.jsonObject?.keys.orEmpty().map { "i:$it" }).toSet()
    }

    init {
        reloadStoredPack(notify = false)
        atlas(TexturePack.ORIGINAL)
        if (isPackAvailable(TexturePack.VANILLA)) atlas(TexturePack.VANILLA)
    }

    val bundledVanilla: Boolean get() = EngineResources.isAvailable(TexturePack.VANILLA)

    val storedPack: StoredPack? get() = stored

    /** A stored pack exists on disk but is invalid or was made by an older importer. */
    val storedPackNeedsReimport: Boolean
        get() = !bundledVanilla && (stored == null && packStore.exists || (stored?.meta?.importerVersion ?: Int.MAX_VALUE) < MinecraftImporter.IMPORTER_VERSION)

    /** Re-reads the stored pack from disk (after an import or removal) and drops its cached atlas. */
    @Synchronized
    fun reloadStoredPack(notify: Boolean = true) {
        stored = packStore.load(requiredNames)?.takeIf { runCatching { decode(it.atlasPng, it.atlasJson) }.isSuccess }
        atlases.remove(TexturePack.VANILLA)
        if (notify) packRevision.value = packRevision.value + 1
    }

    /** Bundled pack (self-builds) wins over the imported one. */
    fun isPackAvailable(pack: TexturePack): Boolean = pack == TexturePack.ORIGINAL || bundledVanilla || stored != null

    /** The pack to actually use: the requested one if it is bundled, otherwise the original. */
    fun resolve(pack: TexturePack): TexturePack = if (isPackAvailable(pack)) pack else TexturePack.ORIGINAL

    @Synchronized
    fun atlas(pack: TexturePack): TextureAtlas = atlases.getOrPut(resolve(pack)) { decodeAtlas(resolve(pack)) }

    private fun decodeAtlas(pack: TexturePack): TextureAtlas {
        if (pack == TexturePack.VANILLA && !bundledVanilla) {
            val s = stored ?: error("no stored pack")
            return decode(s.atlasPng, s.atlasJson)
        }
        return decode(EngineResources.atlasPng(pack), EngineResources.atlasJson(pack))
    }

    private fun decode(bytes: ByteArray, json: String): TextureAtlas {
        val options = BitmapFactory.Options().apply {
            inPremultiplied = false
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        val atlas = TextureAtlas(bmp.width, bmp.height, pixels, json)
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
