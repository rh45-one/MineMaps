package app.minemaps.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.Log
import app.minemaps.engine.data.MapTileProvider
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.geo.Viewport
import app.minemaps.engine.inspect.InspectResult
import app.minemaps.engine.labels.Label
import app.minemaps.engine.labels.LabelKind
import app.minemaps.engine.labels.Poi
import app.minemaps.engine.art.Anchor
import app.minemaps.engine.art.TextureAtlas
import app.minemaps.engine.art.TexturePack
import app.minemaps.engine.data.NetState
import app.minemaps.engine.labels.LabelFilter
import app.minemaps.engine.labels.NameResolver
import app.minemaps.engine.layout.LabelLayout
import app.minemaps.engine.layout.Obstacle
import app.minemaps.engine.layout.PlacedLayout
import app.minemaps.engine.layout.RenderProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.EnumMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt

class MapRenderer(
    private val engine: MapEngine,
    val profile: RenderProfile,
    private val density: Float,
    pixelRatioOverride: Float? = null,
    initialPack: TexturePack = TexturePack.ORIGINAL,
    private val onInvalidate: () -> Unit,
) {
    private data class TileId(val key: TileKey, val night: Boolean, val pack: TexturePack)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val labelVersion = AtomicInteger()
    private var nameResolver = engine.nameResolver(NameResolver.Mode.ENGLISH)
    private var generation = 0
    private var activePack: TexturePack = engine.resolve(initialPack)
    private var atlas: TextureAtlas = engine.atlas(activePack)
    private val _networkState = MutableStateFlow(NetState.OK)
    private var networkJob: Job? = null

    @Volatile
    private var provider: MapTileProvider = createProvider()

    val networkState: StateFlow<NetState> = _networkState

    private fun createProvider(): MapTileProvider {
        val gen = ++generation
        val p = MapTileProvider(engine.repository, atlas, scope) { key, night, argb -> onTile(key, night, argb, gen) }
        p.setNameResolver(nameResolver)
        p.onLabelsReady = {
            if (gen == generation) {
                labelVersion.incrementAndGet()
                onInvalidate()
            }
        }
        p.onLowMemory = { evictHalf() }
        p.timings.rasterize.listener = { ms -> Log.d(PERF_TAG, "rasterize %.2f".format(ms)) }
        p.timings.compose.listener = { ms -> Log.d(PERF_TAG, "compose %.2f".format(ms)) }
        networkJob?.cancel()
        networkJob = scope.launch { p.networkState.collect { _networkState.value = it } }
        return p
    }

    /** Switches the texture pack: new provider/compositor, all pack-dependent caches dropped. */
    var pack: TexturePack
        get() = activePack
        set(value) {
            val resolved = engine.resolve(value)
            if (resolved == activePack) return
            val old = provider
            old.retainOnly(emptyList())
            activePack = resolved
            atlas = engine.atlas(resolved)
            synchronized(bitmaps) {
                bitmaps.clear()
                cacheBytes = 0
            }
            iconBitmaps.clear()
            lastLayout = null
            lastLayoutKey = null
            lastRetainHash = 0
            provider = createProvider()
            labelVersion.incrementAndGet()
            onInvalidate()
        }
    val pixelRatio: Float = pixelRatioOverride ?: max(1f, density / 2f)

    @Volatile
    var showIcons: Boolean = true
        set(value) {
            field = value
            labelVersion.incrementAndGet()
            onInvalidate()
        }

    var nameMode: NameResolver.Mode = NameResolver.Mode.ENGLISH
        set(value) {
            if (field == value) return
            field = value
            nameResolver = engine.nameResolver(value)
            provider.setNameResolver(nameResolver)
            labelVersion.incrementAndGet()
            onInvalidate()
        }

    @Volatile
    var showStreetNames: Boolean = true
        set(value) {
            field = value
            labelVersion.incrementAndGet()
            onInvalidate()
        }

    private val maxCacheBytes = engine.memoryClassMb / 8L * 1024 * 1024
    private var cacheBytes = 0L
    private val bitmaps = object : LinkedHashMap<TileId, Bitmap>(128, 0.75f, true) {}

    private val tilePaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
        isDither = false
    }
    private val iconPaint = Paint().apply {
        isFilterBitmap = false
        isAntiAlias = false
    }
    private val iconFactor = max(2, (density * 1.5f * profile.iconScale).roundToInt())
    private val iconBitmaps = HashMap<String, Bitmap>()
    private val dst = Rect()
    private val src = Rect()

    private val fillPaints = EnumMap<LabelKind, Paint>(LabelKind::class.java)
    private val strokePaints = EnumMap<LabelKind, Paint>(LabelKind::class.java)
    private val measureCache = HashMap<String, Pair<Float, Float>>()

    private var lastLayoutKey: LayoutKey? = null
    private var lastLayout: PlacedLayout? = null

    private data class LayoutKey(
        val bearing: Double,
        val cy: Double,
        val zoom: Double,
        val w: Int,
        val h: Int,
        val version: Int,
        val icons: Boolean,
        val vts: List<TileKey>,
        val bookmarks: List<Bookmark>,
        val pack: TexturePack,
        val markers: List<Double>,
    )

    private var drawFrames = 0
    private var drawNs = 0L
    private var tileNs = 0L
    private var labelNs = 0L
    private var lastRetainHash = 0
    private val accuracyFill = Paint().apply {
        color = 0x332B7BFF
        style = Paint.Style.FILL
    }
    private val accuracyStroke = Paint().apply {
        color = 0x992B7BFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        isAntiAlias = true
    }

    private fun onTile(key: TileKey, night: Boolean, argb: IntArray, gen: Int) {
        if (gen != generation) return
        val bmp = try {
            Bitmap.createBitmap(TILE, TILE, Bitmap.Config.RGB_565).also { it.setPixels(argb, 0, TILE, 0, 0, TILE, TILE) }
        } catch (e: OutOfMemoryError) {
            evictHalf()
            return
        }
        synchronized(bitmaps) {
            bitmaps.put(TileId(key, night, activePack), bmp)?.let { cacheBytes -= it.byteCount }
            cacheBytes += bmp.byteCount
            val it = bitmaps.entries.iterator()
            while (cacheBytes > maxCacheBytes && it.hasNext()) {
                val e = it.next()
                if (e.value !== bmp) {
                    cacheBytes -= e.value.byteCount
                    it.remove()
                }
            }
        }
        onInvalidate()
    }

    private fun evictHalf() {
        synchronized(bitmaps) {
            var toDrop = bitmaps.size / 2
            val it = bitmaps.entries.iterator()
            while (toDrop-- > 0 && it.hasNext()) {
                cacheBytes -= it.next().value.byteCount
                it.remove()
            }
        }
    }

    /** Called from the app on low-memory callbacks: drops tile bitmaps, label bitmaps and the provider's grid caches. */
    fun trimMemory() {
        synchronized(bitmaps) {
            bitmaps.clear()
            cacheBytes = 0
        }
        labelBitmaps.clear()
        labelCacheBytes = 0
        measureCache.clear()
        provider.trimMemory()
        lastLayout = null
        lastLayoutKey = null
        onInvalidate()
    }

    private fun cached(key: TileKey, night: Boolean): Bitmap? = synchronized(bitmaps) { bitmaps[TileId(key, night, activePack)] }

    suspend fun inspect(wx: Double, wy: Double, zoom: Double): InspectResult? = provider.inspect(wx, wy, zoom, pixelRatio)

    fun close() {
        scope.cancel()
    }

    fun draw(canvas: Canvas, viewport: Viewport, overlays: MapOverlays, night: Boolean) {
        currentNight = night
        val t0 = System.nanoTime()
        canvas.drawColor(if (night) 0xFF14171C.toInt() else 0xFFC9D6B6.toInt())
        val z = viewport.tileZoom()
        val n = (1 shl z).toDouble()
        val size = viewport.tileScreenSize(z)
        val slots = viewport.visibleTileSlots(z)
        val flat = viewport.withBearing(0.0)
        canvas.save()
        if (viewport.bearingDeg != 0.0) canvas.rotate(-viewport.bearingDeg.toFloat(), viewport.widthPx / 2f, viewport.heightPx / 2f)
        var hash = z
        for (s in slots) {
            hash = hash * 31 + s.key.x * 7 + s.key.y
            val left = flat.worldToScreenX(s.rawX / n)
            val top = flat.worldToScreenY(s.key.y / n)
            dst.set(left.roundToInt(), top.roundToInt(), (left + size).roundToInt(), (top + size).roundToInt())
            val bmp = cached(s.key, night)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, dst, tilePaint)
            } else {
                provider.request(s.key, night)
                drawFallback(canvas, s.key, night)
            }
        }
        canvas.restore()
        if (hash != lastRetainHash) {
            lastRetainHash = hash
            retainRing(viewport, z, size)
        }
        val t1 = System.nanoTime()
        drawLabels(canvas, viewport, slots.map { it.key }, overlays)
        val t2 = System.nanoTime()
        drawOverlays(canvas, viewport, overlays)
        drawNs += System.nanoTime() - t0
        labelNs += t2 - t1
        tileNs += t1 - t0
        if (++drawFrames == 60) {
            Log.d(PERF_TAG, "draw[${profile.name}] mean=%.2f ms (tiles %.2f labels %.2f) over 60 frames".format(drawNs / 60e6, tileNs / 60e6, labelNs / 60e6))
            drawFrames = 0
            drawNs = 0
            tileNs = 0
            labelNs = 0
        }
    }

    private fun drawFallback(canvas: Canvas, key: TileKey, night: Boolean) {
        for (lvl in 1..MAX_FALLBACK_LEVELS) {
            if (key.z - lvl < 0) return
            val anc = key.ancestor(key.z - lvl)
            val bmp = cached(anc, night) ?: continue
            val part = TILE shr lvl
            val sx = (key.x - (anc.x shl lvl)) * part
            val sy = (key.y - (anc.y shl lvl)) * part
            src.set(sx, sy, sx + part, sy + part)
            canvas.drawBitmap(bmp, src, dst, tilePaint)
            return
        }
        if (key.z >= MAX_TILE_ZOOM) return
        val l = dst.left
        val t = dst.top
        val r = dst.right
        val b = dst.bottom
        val mx = (l + r) / 2
        val my = (t + b) / 2
        for (dy in 0..1) for (dx in 0..1) {
            val child = cached(TileKey(key.z + 1, key.x * 2 + dx, key.y * 2 + dy), night) ?: continue
            dst.set(if (dx == 0) l else mx, if (dy == 0) t else my, if (dx == 0) mx else r, if (dy == 0) my else b)
            canvas.drawBitmap(child, null, dst, tilePaint)
        }
        dst.set(l, t, r, b)
    }

    private fun retainRing(viewport: Viewport, z: Int, size: Double) {
        val pad = Math.ceil(size).toInt()
        val padded = Viewport(viewport.centerX, viewport.centerY, viewport.zoom, viewport.widthPx + 2 * pad, viewport.heightPx + 2 * pad, viewport.pixelRatio, viewport.bearingDeg)
        provider.retainOnly(padded.visibleTiles(z))
    }

    private fun fillPaint(kind: LabelKind): Paint = fillPaints.getOrPut(kind) {
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = engine.typeface
            textSize = baseTextDp(kind) * density * profile.textScale
            textAlign = Paint.Align.CENTER
            color = when (kind) {
                LabelKind.WATER -> 0xFFBFE3F2.toInt()
                LabelKind.ROAD, LabelKind.ROAD_MINOR -> 0xFFFFF2C0.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
        }
    }

    private fun strokePaint(kind: LabelKind): Paint = strokePaints.getOrPut(kind) {
        Paint(fillPaint(kind)).apply {
            style = Paint.Style.STROKE
            strokeWidth = 4f
            strokeJoin = Paint.Join.ROUND
            color = 0xE6101418.toInt()
        }
    }

    private fun baseTextDp(kind: LabelKind): Float = when (kind) {
        LabelKind.COUNTRY -> 16f
        LabelKind.STATE -> 14f
        LabelKind.CITY -> 16f
        LabelKind.TOWN -> 14f
        LabelKind.VILLAGE, LabelKind.ISLAND -> 12f
        LabelKind.HAMLET, LabelKind.SUBURB -> 11f
        LabelKind.QUARTER, LabelKind.NEIGHBOURHOOD -> 10f
        LabelKind.WATER -> 11f
        LabelKind.ROAD -> 10f
        LabelKind.ROAD_MINOR -> 9f
        LabelKind.POI_NAME -> 9f
    }

    private val measure: (String, LabelKind) -> Pair<Float, Float> = { text, kind ->
        measureCache.getOrPut("${kind.ordinal}|$text") {
            val p = fillPaint(kind)
            val fm = p.fontMetrics
            (p.measureText(text) + 4f) to (fm.descent - fm.ascent + 4f)
        }
    }

    private var layoutOriginX = 0.0
    private var layoutOriginY = 0.0

    private fun drawLabels(canvas: Canvas, viewport: Viewport, tiles: List<TileKey>, overlays: MapOverlays) {
        val bookmarks = overlays.bookmarks
        val markers = markerKey(overlays)
        val vts = tiles.map { provider.vectorTileKey(it) }.distinct().sortedWith(compareBy({ it.z }, { it.x }, { it.y }))
        val worldSize = viewport.worldSizePx
        val origin = viewport.worldToScreen(layoutOriginX, layoutOriginY)
        var shiftX = origin.x - viewport.widthPx / 2.0
        var shiftY = origin.y - viewport.heightPx / 2.0
        val key = LayoutKey(viewport.bearingDeg, 0.0, viewport.zoom, viewport.widthPx, viewport.heightPx, labelVersion.get(), showIcons, vts, bookmarks, activePack, markers)
        var layout = lastLayout
        if (key != lastLayoutKey || layout == null || Math.abs(shiftX) > LAYOUT_MARGIN_PX / 2 || Math.abs(shiftY) > LAYOUT_MARGIN_PX / 2) {
            val labels = ArrayList<Label>()
            val pois = ArrayList<Poi>()
            for (vt in vts) {
                val tl = provider.labels(vt) ?: continue
                labels.addAll(LabelFilter.apply(tl.labels, showStreetNames))
                if (showIcons) pois.addAll(tl.pois)
            }
            val m = LAYOUT_MARGIN_PX
            val expanded = Viewport(viewport.centerX, viewport.centerY, viewport.zoom, viewport.widthPx + 2 * m, viewport.heightPx + 2 * m, viewport.pixelRatio, viewport.bearingDeg)
            layout = LabelLayout.layout(labels, pois, expanded, measure, profile, 16f * iconFactor, bookmarkObstacles(expanded, bookmarks, viewport.zoom), markerObstacles(expanded, overlays))
            lastLayout = layout
            lastLayoutKey = key
            layoutOriginX = viewport.centerX
            layoutOriginY = viewport.centerY
            shiftX = 0.0
            shiftY = 0.0
        }
        canvas.save()
        canvas.translate((shiftX - LAYOUT_MARGIN_PX).toFloat(), (shiftY - LAYOUT_MARGIN_PX).toFloat())
        for (icon in layout.icons) {
            drawIcon(canvas, icon.poi.icon, icon.cx, icon.cy, icon.size)
            icon.name?.let { drawText(canvas, it.text, it.cx, it.cy, 0f, LabelKind.POI_NAME) }
        }
        for (l in layout.labels) drawText(canvas, l.label.text, l.cx, l.cy, l.angleDeg, l.label.kind)
        canvas.restore()
    }

    private data class LabelKey(val text: String, val kind: LabelKind, val night: Boolean, val scale: Float)

    private class LabelBitmap(val bitmap: Bitmap)

    private var labelCacheBytes = 0
    private val labelBitmaps = LinkedHashMap<LabelKey, LabelBitmap>(128, 0.75f, true)
    private val labelPaint = Paint().apply { isFilterBitmap = true }
    private var currentNight = false

    private fun labelBitmap(text: String, kind: LabelKind): Bitmap {
        val key = LabelKey(text, kind, currentNight, profile.textScale)
        labelBitmaps[key]?.let { return it.bitmap }
        val fill = fillPaint(kind)
        val fm = fill.fontMetrics
        val w = Math.ceil(fill.measureText(text).toDouble()).toInt() + 2 * LABEL_PAD
        val h = Math.ceil((fm.descent - fm.ascent).toDouble()).toInt() + 2 * LABEL_PAD
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val baseline = h / 2f - (fm.ascent + fm.descent) / 2f
        c.drawText(text, w / 2f, baseline, strokePaint(kind))
        c.drawText(text, w / 2f, baseline, fill)
        labelBitmaps[key] = LabelBitmap(bmp)
        labelCacheBytes += bmp.byteCount
        val it = labelBitmaps.entries.iterator()
        while (labelCacheBytes > LABEL_CACHE_BYTES && it.hasNext()) {
            val e = it.next()
            if (e.value.bitmap !== bmp) {
                labelCacheBytes -= e.value.bitmap.byteCount
                it.remove()
            }
        }
        return bmp
    }

    private fun drawText(canvas: Canvas, text: String, cx: Float, cy: Float, angle: Float, kind: LabelKind) {
        val bmp = labelBitmap(text, kind)
        canvas.save()
        canvas.translate(cx, cy)
        if (angle != 0f) canvas.rotate(angle)
        labelPaint.isFilterBitmap = angle != 0f
        canvas.drawBitmap(bmp, -bmp.width / 2f, -bmp.height / 2f, labelPaint)
        canvas.restore()
    }

    var arrowColor: Int = 0xFFFFFFFF.toInt()
        set(value) {
            if (field == value) return
            field = value
            onInvalidate()
        }

    private fun iconBitmap(name: String): Bitmap? {
        val tinted = name in atlas.tintableIcons
        val key = if (tinted) "$name#$arrowColor" else name
        iconBitmaps[key]?.let { return it }
        val px = atlas.icon(name) ?: return null
        val pixels = if (tinted) TextureAtlas.tintIcon(px, arrowColor) else px
        if (tinted) iconBitmaps.keys.removeAll { it.startsWith("$name#") }
        return Bitmap.createBitmap(pixels, 16, 16, Bitmap.Config.ARGB_8888).also { iconBitmaps[key] = it }
    }

    private val nameMinZoom: Int get() = if (profile == RenderProfile.CAR) 16 else 15
    private val shadowPaint = Paint().apply {
        color = 0x59000000
        isAntiAlias = true
    }
    private val oval = android.graphics.RectF()

    private fun resolveIcon(name: String): String = if (atlas.icon(name) != null) name else "pin"

    /** Icon rectangle (left, top, right, bottom) so that the icon's anchor pixel sits on screen point (x, y). */
    private fun bookmarkRect(icon: String, x: Float, y: Float, size: Float, out: FloatArray) {
        val a = atlas.anchor(resolveIcon(icon))
        val f = size / 16f
        out[0] = x - a.x * f
        out[1] = y - a.y * f
        out[2] = out[0] + size
        out[3] = out[1] + size
    }

    private fun markerKey(o: MapOverlays): List<Double> = buildList {
        o.player?.let { add(it.lat); add(it.lon) }
        o.searchPin?.let { add(it.lat); add(it.lng) }
    }

    /** The player arrow (plus a 4 dp pad) and the search pin are kept free of place, water and road labels. */
    private fun markerObstacles(expanded: Viewport, o: MapOverlays): List<Obstacle> {
        val size = 16f * iconFactor
        val pad = 4f * density
        val out = ArrayList<Obstacle>(2)
        o.player?.let { p ->
            val pt = expanded.worldToScreen(Mercator.lonToX(p.lon), Mercator.latToY(p.lat))
            val half = size / 2 + pad
            out.add(Obstacle(pt.x.toFloat() - half, pt.y.toFloat() - half, pt.x.toFloat() + half, pt.y.toFloat() + half))
        }
        o.searchPin?.let { p ->
            val pt = expanded.worldToScreen(Mercator.lonToX(p.lng), Mercator.latToY(p.lat))
            val r = FloatArray(4)
            bookmarkRect("pin", pt.x.toFloat(), pt.y.toFloat(), size, r)
            out.add(Obstacle(r[0] - pad, r[1] - pad, r[2] + pad, r[3] + pad))
        }
        return out
    }

    private fun bookmarkObstacles(expanded: Viewport, bookmarks: List<Bookmark>, zoom: Double): List<Obstacle> {
        val size = 16f * iconFactor
        val out = ArrayList<Obstacle>(bookmarks.size * 2)
        val r = FloatArray(4)
        for (b in bookmarks) {
            val pt = expanded.worldToScreen(Mercator.lonToX(b.lon), Mercator.latToY(b.lat))
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            bookmarkRect(b.icon, x, y, size, r)
            out.add(Obstacle(r[0], r[1], r[2], r[3]))
            if (zoom >= nameMinZoom && b.name.isNotBlank()) {
                val (w, h) = measure(b.name, LabelKind.POI_NAME)
                out.add(Obstacle(x - w / 2, r[3] + 2f, x + w / 2, r[3] + 2f + h))
            }
        }
        return out
    }

    private fun drawBookmarks(canvas: Canvas, viewport: Viewport, bookmarks: List<Bookmark>) {
        val size = 16f * iconFactor
        val r = FloatArray(4)
        for (b in bookmarks) {
            val pt = viewport.worldToScreen(Mercator.lonToX(b.lon), Mercator.latToY(b.lat))
            val x = pt.x.toFloat()
            val y = pt.y.toFloat()
            if (x < -size * 3 || y < -size * 3 || x > viewport.widthPx + size * 3 || y > viewport.heightPx + size * 3) continue
            val bmp = iconBitmap(resolveIcon(b.icon)) ?: continue
            bookmarkRect(b.icon, x, y, size, r)
            val baseY = if (atlas.anchor(resolveIcon(b.icon)) != Anchor(8, 8)) y else r[3] - size * 0.12f
            oval.set(x - size * 0.34f, baseY - size * 0.07f, x + size * 0.34f, baseY + size * 0.09f)
            canvas.drawOval(oval, shadowPaint)
            dst.set(r[0].roundToInt(), r[1].roundToInt(), r[2].roundToInt(), r[3].roundToInt())
            canvas.drawBitmap(bmp, null, dst, iconPaint)
            if (viewport.zoom >= nameMinZoom && b.name.isNotBlank()) {
                val bm = labelBitmap(b.name, LabelKind.POI_NAME)
                canvas.drawBitmap(bm, x - bm.width / 2f, r[3] + 2f - LABEL_PAD, labelPaint.apply { isFilterBitmap = false })
            }
        }
    }

    private fun drawOverlays(canvas: Canvas, viewport: Viewport, overlays: MapOverlays) {
        val size = 16f * iconFactor
        overlays.player?.let { p ->
            val pt = viewport.worldToScreen(Mercator.lonToX(p.lon), Mercator.latToY(p.lat))
            val px = pt.x.toFloat()
            val py = pt.y.toFloat()
            val mpp = Mercator.EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(p.lat)) / viewport.worldSizePx
            val radius = (p.accuracyM / mpp).toFloat()
            if (radius > 6f * density) {
                canvas.drawCircle(px, py, radius, accuracyFill)
                canvas.drawCircle(px, py, radius, accuracyStroke)
            }
        }
        drawBookmarks(canvas, viewport, overlays.bookmarks)
        overlays.searchPin?.let { drawPin(canvas, viewport, it.lat, it.lng, size) }
        overlays.player?.let { p ->
            val pt = viewport.worldToScreen(Mercator.lonToX(p.lon), Mercator.latToY(p.lat))
            val px = pt.x.toFloat()
            val py = pt.y.toFloat()
            iconBitmap("player")?.let { bmp ->
                canvas.save()
                if (p.speedMps > 1f) canvas.rotate(p.bearingDeg - viewport.bearingDeg.toFloat(), px, py) else if (viewport.bearingDeg != 0.0) canvas.rotate(-viewport.bearingDeg.toFloat(), px, py)
                val half = size / 2
                dst.set((px - half).roundToInt(), (py - half).roundToInt(), (px + half).roundToInt(), (py + half).roundToInt())
                canvas.drawBitmap(bmp, null, dst, iconPaint)
                canvas.restore()
            }
        }
    }

    private fun drawIcon(canvas: Canvas, name: String, x: Float, y: Float, size: Float) {
        val bmp = iconBitmap(name) ?: return
        val r = FloatArray(4)
        bookmarkRect(name, x, y, size, r)
        dst.set(r[0].roundToInt(), r[1].roundToInt(), r[2].roundToInt(), r[3].roundToInt())
        canvas.drawBitmap(bmp, null, dst, iconPaint)
    }

    private fun drawPin(canvas: Canvas, viewport: Viewport, lat: Double, lon: Double, size: Float) {
        val pt = viewport.worldToScreen(Mercator.lonToX(lon), Mercator.latToY(lat))
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()
        if (x < -size || y < -size || x > viewport.widthPx + size || y > viewport.heightPx + size) return
        drawIcon(canvas, "pin", x, y, size)
    }

    companion object {
        const val PERF_TAG = "MineMapsPerf"
        private const val TILE = 256
        private const val MAX_FALLBACK_LEVELS = 5
        private const val MAX_TILE_ZOOM = 20
        private const val LABEL_CACHE_BYTES = 4 * 1024 * 1024
        private const val LABEL_PAD = 4
        private const val LAYOUT_MARGIN_PX = 160
    }
}
