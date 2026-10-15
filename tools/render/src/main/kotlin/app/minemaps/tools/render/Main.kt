package app.minemaps.tools.render

import app.minemaps.engine.art.EngineResources
import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.data.DiskTileCache
import app.minemaps.engine.data.MapTileProvider
import app.minemaps.engine.data.OpenMapTilesSource
import app.minemaps.engine.data.TileRepository
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.geo.Viewport
import app.minemaps.engine.labels.Label
import app.minemaps.engine.labels.LabelKind
import app.minemaps.engine.labels.Poi
import app.minemaps.engine.labels.TileLabels
import app.minemaps.engine.layout.LabelLayout
import app.minemaps.engine.layout.RenderProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.AffineTransform
import java.awt.geom.Rectangle2D
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.imageio.ImageIO
import kotlin.system.exitProcess

private const val USER_AGENT = "MineMaps-dev/0.1 (github.com/hugo/minemaps)"
private const val TILEJSON = "https://tiles.openfreemap.org/planet"

private class Options(
    val lat: Double,
    val lon: Double,
    val zoom: Int,
    val width: Int,
    val height: Int,
    val out: File,
    val night: Boolean,
    val pack: TexturePack,
)

private fun parse(args: Array<String>): Options {
    val m = HashMap<String, String>()
    var night = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--night" -> night = true
            else -> if (a.startsWith("--") && i + 1 < args.size) m[a.removePrefix("--")] = args[++i] else {
                System.err.println("unknown argument $a")
                exitProcess(2)
            }
        }
        i++
    }
    return Options(
        m["lat"]?.toDouble() ?: 48.8584,
        m["lon"]?.toDouble() ?: 2.2945,
        m["zoom"]?.toInt() ?: 16,
        m["width"]?.toInt() ?: 1024,
        m["height"]?.toInt() ?: 768,
        File(m["out"] ?: "build/render/out.png"),
        night,
        when (m["pack"]?.lowercase()) {
            null, "original" -> TexturePack.ORIGINAL
            "vanilla" -> TexturePack.VANILLA
            else -> {
                System.err.println("--pack must be original or vanilla")
                exitProcess(2)
            }
        },
    )
}

internal fun loadAtlas(pack: TexturePack = TexturePack.ORIGINAL): TextureAtlas {
    if (!EngineResources.isAvailable(pack)) {
        System.err.println("texture pack $pack is not bundled (run tools/art/import_minecraft.py and rebuild)")
        exitProcess(2)
    }
    val img = ImageIO.read(ByteArrayInputStream(EngineResources.atlasPng(pack)))
    val px = IntArray(img.width * img.height)
    img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
    return TextureAtlas(img.width, img.height, px, EngineResources.atlasJson(pack))
}

private fun iconImage(atlas: TextureAtlas, name: String): BufferedImage? {
    val px = atlas.icon(name) ?: return null
    return BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB).also { it.setRGB(0, 0, 16, 16, px, 0, 16) }
}

private class TextPainter(private val g: Graphics2D) {
    private val pixelFont: Font? = runCatching {
        EngineResources.fontStream().use { Font.createFont(Font.TRUETYPE_FONT, it) }
    }.getOrNull()
    private val fallback = Font(Font.SANS_SERIF, Font.BOLD, 12)
    val placed = ArrayList<Rectangle2D>()

    fun fontFor(text: String, size: Float): Font {
        val f = pixelFont?.deriveFont(size)
        return if (f != null && f.canDisplayUpTo(text) == -1) f else fallback.deriveFont(size)
    }

    fun tryPlace(box: Rectangle2D): Boolean {
        if (placed.any { it.intersects(box) }) return false
        placed.add(box)
        return true
    }

    fun draw(text: String, cx: Double, cy: Double, size: Float, angleDeg: Double, fill: Color): Boolean {
        g.font = fontFor(text, size)
        val fm = g.fontMetrics
        val tw = fm.stringWidth(text).toDouble()
        val th = fm.height.toDouble()
        val at = AffineTransform.getRotateInstance(Math.toRadians(angleDeg), cx, cy)
        val saved = g.transform
        g.transform(at)
        val x = (cx - tw / 2).toFloat()
        val y = (cy + fm.ascent - th / 2).toFloat()
        g.color = Color(0, 0, 0, 230)
        for (dx in -1..1) for (dy in -1..1) if (dx != 0 || dy != 0) g.drawString(text, x + dx, y + dy)
        g.color = fill
        g.drawString(text, x, y)
        g.transform = saved
        return true
    }
}

private fun placeSize(kind: LabelKind): Float = when (kind) {
    LabelKind.COUNTRY -> 22f
    LabelKind.STATE -> 18f
    LabelKind.CITY -> 20f
    LabelKind.TOWN -> 16f
    LabelKind.VILLAGE, LabelKind.ISLAND -> 14f
    LabelKind.HAMLET, LabelKind.SUBURB -> 13f
    LabelKind.QUARTER, LabelKind.NEIGHBOURHOOD -> 12f
    LabelKind.WATER -> 12f
    LabelKind.ROAD -> 12f
    LabelKind.ROAD_MINOR -> 11f
    LabelKind.POI_NAME -> 11f
}

private fun overlay(
    g: Graphics2D,
    vp: Viewport,
    zoom: Int,
    atlas: TextureAtlas,
    all: Collection<TileLabels>,
    width: Int,
    height: Int,
) {
    val painter = TextPainter(g)
    val labels = ArrayList<Label>()
    val pois = ArrayList<Poi>()
    for (t in all) {
        labels.addAll(t.labels)
        pois.addAll(t.pois)
    }
    val measure = { text: String, kind: LabelKind ->
        g.font = painter.fontFor(text, placeSize(kind))
        g.fontMetrics.stringWidth(text).toFloat() to g.fontMetrics.height.toFloat()
    }
    val layout = LabelLayout.layout(labels, pois, vp, measure, RenderProfile.PHONE, 32f)
    for (icon in layout.icons) {
        val img = iconImage(atlas, icon.poi.icon) ?: continue
        g.drawImage(img, (icon.cx - 16).toInt(), (icon.cy - 16).toInt(), 32, 32, null)
        icon.name?.let { painter.draw(it.text, it.cx.toDouble(), it.cy.toDouble(), placeSize(LabelKind.POI_NAME), 0.0, Color.WHITE) }
    }
    for (l in layout.labels) {
        val fill = when (l.label.kind) {
            LabelKind.WATER -> Color(0xbfe3f2)
            LabelKind.ROAD, LabelKind.ROAD_MINOR -> Color(0xfff2c0)
            else -> Color.WHITE
        }
        painter.draw(l.label.text, l.cx.toDouble(), l.cy.toDouble(), placeSize(l.label.kind), l.angleDeg.toDouble(), fill)
    }
}

fun main(args: Array<String>): Unit = runBlocking {
    if ("--bench" in args) {
        runBench()
        return@runBlocking
    }
    val opt = parse(args)
    val z = opt.zoom
    val cache = DiskTileCache(File(System.getProperty("user.home"), ".cache/minemaps-dev"))
    val client = OkHttpClient()
    val repo = TileRepository(OpenMapTilesSource(TILEJSON, client, USER_AGENT, cache))
    val atlas = loadAtlas(opt.pack)
    val ready = ConcurrentHashMap<TileKey, IntArray>()
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val provider = MapTileProvider(repo, atlas, scope) { key, _, px -> ready[key] = px }

    val vp = Viewport(Mercator.lonToX(opt.lon), Mercator.latToY(opt.lat), z.toDouble(), opt.width, opt.height, 1f)
    val tiles = vp.visibleTiles(z)
    val vtKeys = tiles.map { provider.vectorTileKey(it) }.distinct()
    println("zoom $z: ${tiles.size} tiles, ${vtKeys.size} vector tiles, night=${opt.night}")
    val start = System.currentTimeMillis()
    tiles.forEach { provider.request(it, opt.night) }
    val labelResults = HashMap<TileKey, TileLabels>()
    while (System.currentTimeMillis() - start < 180_000) {
        for (k in vtKeys) if (k !in labelResults) provider.labels(k)?.let { labelResults[k] = it }
        if (ready.size >= tiles.size && labelResults.size >= vtKeys.size) break
        delay(50)
    }
    if (ready.size < tiles.size) {
        System.err.println("timed out: ${ready.size}/${tiles.size} tiles ready")
        exitProcess(1)
    }

    val image = BufferedImage(opt.width, opt.height, BufferedImage.TYPE_INT_RGB)
    val g = image.createGraphics()
    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR)
    g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF)
    val n = 1 shl z
    for (t in tiles) {
        val px = ready.getValue(t)
        val tileImg = BufferedImage(256, 256, BufferedImage.TYPE_INT_RGB)
        tileImg.setRGB(0, 0, 256, 256, px, 0, 256)
        g.drawImage(tileImg, Math.round(vp.worldToScreenX(t.x.toDouble() / n)).toInt(), Math.round(vp.worldToScreenY(t.y.toDouble() / n)).toInt(), null)
    }
    overlay(g, vp, z, atlas, vtKeys.sortedWith(compareBy({ it.z }, { it.x }, { it.y })).mapNotNull { labelResults[it] }, opt.width, opt.height)
    iconImage(atlas, "player")?.let { g.drawImage(it, opt.width / 2 - 16, opt.height / 2 - 16, 32, 32, null) }
    g.dispose()
    opt.out.absoluteFile.parentFile.mkdirs()
    ImageIO.write(image, "png", opt.out)
    println("wrote ${opt.out.absolutePath}")
    println("timings: ${provider.timings.summary()}")
    println("wall: ${System.currentTimeMillis() - start} ms")
    println("done")
}
