package app.minemaps.car

import android.graphics.Rect
import android.os.Handler
import android.util.Log
import android.os.Looper
import android.view.Choreographer
import androidx.car.app.CarContext
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import app.minemaps.AppContainer
import app.minemaps.data.Camera
import app.minemaps.data.SavedPlace
import app.minemaps.engine.data.NetState
import app.minemaps.engine.geo.LatLng
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.Viewport
import app.minemaps.engine.layout.RenderProfile
import app.minemaps.location.LocationState
import app.minemaps.location.LocationStatus
import app.minemaps.engine.labels.NameResolver
import app.minemaps.map.Bookmark
import app.minemaps.map.MapOverlays
import app.minemaps.map.MapRenderer
import app.minemaps.map.PlayerMarker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow

interface CarMapActions {
    val following: StateFlow<Boolean>
    val networkState: StateFlow<NetState>
    val streetNames: StateFlow<Boolean>

    fun zoomIn()

    fun zoomOut()

    fun recenter()

    fun flyTo(lat: Double, lon: Double)

    fun toggleStreetNames()

    /** Centres the map on a place and marks it with the search pin (and starts routing if enabled). */
    fun showPlace(lat: Double, lon: Double, title: String = "Destination")
}

class CarMapController(
    private val carContext: CarContext,
    private val container: AppContainer,
    private val scope: CoroutineScope,
) : SurfaceCallback, CarMapActions {
    private class Anim(
        val startNs: Long,
        val durNs: Long,
        val fromX: Double,
        val fromY: Double,
        val fromZ: Double,
        val toX: Double,
        val toY: Double,
        val toZ: Double,
    )

    override val following = MutableStateFlow(false)
    override val networkState = MutableStateFlow(NetState.OK)
    override val streetNames = MutableStateFlow(true)
    private var searchPin: LatLng? = null
    private var activeRoute: app.minemaps.engine.routing.RoutePlan? = null
    private var roadAlerts: List<app.minemaps.engine.routing.RoadAlert> = emptyList()

    private val main = Handler(Looper.getMainLooper())
    private var renderer: MapRenderer? = null
    private var surface: SurfaceContainer? = null
    private var visibleArea: Rect? = null
    private var stableArea: Rect? = null

    private var cx = Mercator.lonToX(0.0)
    private var cy = Mercator.latToY(30.0)
    private var zoom = 17.0

    private var anim: Anim? = null
    private var flingVx = 0.0
    private var flingVy = 0.0
    private var lastFrameNs = 0L

    private var location = LocationState(null, LocationStatus.SEARCHING)
    private var savedPlaces: List<SavedPlace> = emptyList()
    private var night = carContext.isDarkMode
    private var released = false
    private var frameQueued = false
    private var collectJob: Job? = null
    private var persistJob: Job? = null
    private var lastPersisted: Camera? = null
    @Volatile
    private var texturePack: app.minemaps.engine.art.TexturePack = app.minemaps.engine.art.TexturePack.ORIGINAL

    @Volatile
    private var nameMode = NameResolver.Mode.ENGLISH

    @Volatile
    private var showStreetNames = true

    private fun names(s: app.minemaps.data.Settings) = if (s.labelNames == app.minemaps.data.LabelNames.LOCAL) NameResolver.Mode.LOCAL else NameResolver.Mode.ENGLISH

    @Volatile
    private var arrowColor: Int = app.minemaps.data.ArrowColors.DEFAULT
    private var gotFix = false
    private var cameraLoaded = false
    private var dynamicZoom = true
    private var showTraffic = true
    private var showRoadAlerts = true
    private var lastManualRecenterMs = 0L
    private var lastRoadProbeMs = 0L
    private var detectedRoadClass: String? = null

    private val frameCallback = Choreographer.FrameCallback { frameNs ->
        frameQueued = false
        if (!released) renderFrame(frameNs)
    }

    init {
        following.value = container.location.hasPermission()
        scope.launch {
            val saved = withContext(Dispatchers.IO) { container.mapState.current ?: container.mapState.load() }?.let { Camera.sanitized(it.lat, it.lon, it.zoom) }
            if (!cameraLoaded && saved != null) {
                cx = Mercator.lonToX(saved.lon)
                cy = Mercator.latToY(saved.lat)
                zoom = saved.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
            }
            cameraLoaded = true
            lastPersisted = camera()
            requestRender()
        }
    }

    private var creatingRenderer = false

    private fun ensureRenderer() {
        if (renderer != null || creatingRenderer || released) return
        val dpi = surface?.dpi ?: return
        creatingRenderer = true
        scope.launch {
            val engine = container.engine.await()
            if (released) return@launch
            val density = dpi / 160f
            val r = MapRenderer(engine, RenderProfile.CAR, density, max(1f, density), texturePack) { requestRender() }
            r.arrowColor = arrowColor
            r.nameMode = nameMode
            r.showStreetNames = showStreetNames
            container.register(r)
            renderer = r
            launch { r.networkState.collect { networkState.value = it } }
            launch { engine.packRevision.collect { r.pack = texturePack } }
            requestRender()
        }
    }

    fun onSessionStarted() {
        collectJob?.cancel()
        collectJob = scope.launch {
            launch {
                container.location.state.collect { onLocation(it) }
            }
            launch {
                container.settings.settings.collect {
                    arrowColor = it.arrowColor
                    texturePack = it.texturePack
                    renderer?.pack = it.texturePack
                    nameMode = names(it)
                    showStreetNames = it.showStreetNames
                    dynamicZoom = it.dynamicDrivingZoom
                    showTraffic = it.showTraffic
                    showRoadAlerts = it.showRoadAlerts
                    streetNames.value = it.showStreetNames
                    renderer?.nameMode = names(it)
                    renderer?.showStreetNames = it.showStreetNames
                    renderer?.arrowColor = it.arrowColor
                    requestRender()
                }
            }
            launch {
                container.savedPlaces.places.collect {
                    savedPlaces = it
                    requestRender()
                }
            }
            launch {
                container.navigation.activePlan.collect {
                    activeRoute = it
                    requestRender()
                }
            }
            launch {
                container.roadAlerts.collect {
                    roadAlerts = it
                    requestRender()
                }
            }
        }
        requestRender()
    }

    fun onSessionStopped() {
        collectJob?.cancel()
        collectJob = null
    }

    fun onNightChanged(dark: Boolean) {
        night = dark
        requestRender()
    }

    fun release() {
        Log.d(TAG, "release")
        released = true
        collectJob?.cancel()
        persistJob?.cancel()
        surface = null
        renderer?.close()
        renderer = null
    }

    private fun onLocation(state: LocationState) {
        val first = !gotFix && state.location != null
        location = state
        val l = state.location?.takeIf { it.latitude.isFinite() && it.longitude.isFinite() }
        if (l != null) {
            gotFix = true
            if (following.value) {
                val now = System.currentTimeMillis()
                var targetZ = if (first && zoom < 14.0) FOLLOW_ZOOM else zoom
                if (dynamicZoom && l.hasSpeed() && l.speed >= 1.5f && now - lastManualRecenterMs > 12_000L) {
                    if (now - lastRoadProbeMs > 5_000L) {
                        lastRoadProbeMs = now
                        val wx = Mercator.lonToX(l.longitude)
                        val wy = Mercator.latToY(l.latitude)
                        scope.launch {
                            detectedRoadClass = runCatching { renderer?.inspect(wx - Math.floor(wx), wy, 17.0)?.roadClass }.getOrNull()
                        }
                    }
                    app.minemaps.engine.routing.RouteTracker.targetDrivingZoom(l.speed, detectedRoadClass)?.let { dynZ ->
                        if (kotlin.math.abs(zoom - dynZ) >= 0.35) targetZ = dynZ
                    }
                }
                animateTo(Mercator.lonToX(l.longitude), Mercator.latToY(l.latitude), targetZ)
            }
        }
        requestRender()
    }

    private fun camera() = Camera(Mercator.yToLat(cy), Mercator.xToLon(cx), zoom, container.mapState.current?.bearing ?: 0.0)

    private fun wrap(x: Double) = x - Math.floor(x)

    private fun animateTo(tx: Double, ty: Double, tz: Double, durationMs: Long = 500L) {
        if (!tx.isFinite() || !ty.isFinite() || !tz.isFinite()) return
        var dx = tx - cx
        if (dx > 0.5) dx -= 1.0 else if (dx < -0.5) dx += 1.0
        anim = Anim(System.nanoTime(), durationMs * 1_000_000L, cx, cy, zoom, cx + dx, ty.coerceIn(0.0, 1.0), tz.coerceIn(MIN_ZOOM, MAX_ZOOM))
        requestRender()
    }

    override fun zoomIn() = zoomBy(1.0)

    override fun zoomOut() = zoomBy(-1.0)

    private fun zoomBy(delta: Double) {
        val target = (anim?.toZ ?: zoom) + delta
        animateTo(anim?.toX ?: cx, anim?.toY ?: cy, target, 300L)
    }

    override fun recenter() {
        val wasFollowing = following.value
        following.value = true
        val l = location.location
        val targetZ = if (wasFollowing) {
            lastManualRecenterMs = System.currentTimeMillis()
            app.minemaps.engine.routing.RouteTracker.nextRecenterZoom(zoom)
        } else {
            if (zoom < 14.0) FOLLOW_ZOOM else zoom
        }
        if (l != null) {
            animateTo(Mercator.lonToX(l.longitude), Mercator.latToY(l.latitude), targetZ)
        } else if (wasFollowing) {
            animateTo(cx, cy, targetZ, 300L)
        }
        requestRender()
    }

    override fun toggleStreetNames() {
        scope.launch { container.settings.setShowStreetNames(!showStreetNames) }
    }

    override fun showPlace(lat: Double, lon: Double, title: String) {
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return
        searchPin = LatLng(lat, lon)
        following.value = false
        animateTo(Mercator.lonToX(lon), Mercator.latToY(lat), FOLLOW_ZOOM, 700L)
        container.navigation.startNavigation(lat, lon, title)
    }

    override fun flyTo(lat: Double, lon: Double) {
        if (!lat.isFinite() || !lon.isFinite()) return
        following.value = false
        animateTo(Mercator.lonToX(lon), Mercator.latToY(lat), FOLLOW_ZOOM, 700L)
        container.navigation.startNavigation(lat, lon, "Saved place")
    }

    // --- SurfaceCallback ---

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        Log.d(TAG, "onSurfaceAvailable ${surfaceContainer.width}x${surfaceContainer.height} dpi=${surfaceContainer.dpi}")
        surface = surfaceContainer
        ensureRenderer()
        requestRender()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.d(TAG, "onSurfaceDestroyed")
        surface = null
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "visibleArea=$visibleArea surface=${surface?.width}x${surface?.height}")
        this.visibleArea = Rect(visibleArea)
        requestRender()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "stableArea=$stableArea surface=${surface?.width}x${surface?.height}")
        this.stableArea = Rect(stableArea)
        requestRender()
    }

    private fun stopMotion() {
        anim = null
        flingVx = 0.0
        flingVy = 0.0
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (!distanceX.isFinite() || !distanceY.isFinite()) return
        following.value = false
        stopMotion()
        val size = worldSize()
        cx = wrap(cx + distanceX / size)
        cy = (cy + distanceY / size).coerceIn(0.0, 1.0)
        requestRender()
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        if (!velocityX.isFinite() || !velocityY.isFinite()) return
        following.value = false
        anim = null
        flingVx = -velocityX.toDouble()
        flingVy = -velocityY.toDouble()
        requestRender()
    }

    override fun onScale(focusX: Float, focusY: Float, scaleFactor: Float) {
        if (!scaleFactor.isFinite() || scaleFactor <= 0f || focusX.isNaN() || focusY.isNaN()) return
        anim = null
        val newZoom = (zoom + ln(scaleFactor.toDouble()) / ln(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
        if (focusX < 0f || focusY < 0f) {
            zoom = newZoom
        } else {
            following.value = false
            val vp = viewport() ?: return
            val w = vp.screenToWorld(focusX.toDouble(), focusY.toDouble())
            zoom = newZoom
            val vp2 = viewport() ?: return
            val shiftX = (w.x - vp2.screenToWorldX(focusX.toDouble()))
            val shiftY = (w.y - vp2.screenToWorldY(focusY.toDouble()))
            cx = wrap(cx + shiftX)
            cy = (cy + shiftY).coerceIn(0.0, 1.0)
        }
        requestRender()
    }

    override fun onClick(x: Float, y: Float) {}

    // --- rendering ---

    private fun worldSize(): Double = 256.0 * max(1f, (surface?.dpi ?: DEFAULT_DPI) / 160f) * 2.0.pow(zoom)

    private fun viewport(): Viewport? {
        val s = surface ?: return null
        val w = s.width
        val h = s.height
        if (w <= 0 || h <= 0) return null
        val pr = max(1f, s.dpi / 160f)
        val size = 256.0 * pr * 2.0.pow(zoom)
        val area = stableArea?.takeIf { !it.isEmpty } ?: visibleArea?.takeIf { !it.isEmpty }
        val off = if (area != null) StableArea.worldOffset(w, h, area.left, area.top, area.right, area.bottom, size) else doubleArrayOf(0.0, 0.0)
        return Viewport(cx + off[0], (cy + off[1]), zoom, w, h, pr)
    }

    fun requestRender() {
        if (released) return
        if (Looper.myLooper() == Looper.getMainLooper()) postFrame() else main.post { postFrame() }
    }

    private fun postFrame() {
        if (frameQueued || released) return
        frameQueued = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
    }

    private fun renderFrame(frameNs: Long) {
        val dt = if (lastFrameNs == 0L) 0.016 else ((frameNs - lastFrameNs) / 1e9).coerceIn(0.0, 0.1)
        lastFrameNs = frameNs
        var more = false
        anim?.let { a ->
            val t = ((frameNs - a.startNs).toDouble() / a.durNs).coerceIn(0.0, 1.0)
            val e = 1.0 - (1.0 - t).pow(3)
            cx = wrap(a.fromX + (a.toX - a.fromX) * e)
            cy = a.fromY + (a.toY - a.fromY) * e
            zoom = a.fromZ + (a.toZ - a.fromZ) * e
            if (t >= 1.0) anim = null else more = true
        }
        if (flingVx * flingVx + flingVy * flingVy > 400.0) {
            val size = worldSize()
            cx = wrap(cx + flingVx * dt / size)
            cy = (cy + flingVy * dt / size).coerceIn(0.0, 1.0)
            val decay = exp(-dt * 3.5)
            flingVx *= decay
            flingVy *= decay
            more = true
        } else {
            flingVx = 0.0
            flingVy = 0.0
        }
        draw()
        if (more) {
            lastFrameNs = frameNs
            postFrame()
        } else {
            lastFrameNs = 0L
            scheduleSave()
        }
    }

    private fun overlays(): MapOverlays {
        val l = location.location
        val player = l?.let {
            PlayerMarker(it.latitude, it.longitude, if (it.hasAccuracy()) it.accuracy else 0f, it.bearing, if (it.hasSpeed()) it.speed else 0f)
        }
        return MapOverlays(
            player = player,
            bookmarks = savedPlaces.map { Bookmark(it.lat, it.lon, it.icon, it.name) },
            searchPin = searchPin,
            route = activeRoute,
            showTraffic = showTraffic,
            roadAlerts = if (showRoadAlerts) roadAlerts else emptyList(),
        )
    }

    private fun draw() {
        val r = renderer ?: return
        val container = surface ?: return
        val s = container.surface ?: return
        if (!s.isValid) return
        val vp = viewport() ?: return
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: IllegalStateException) {
            logDrawFailure(e)
            return
        } catch (e: IllegalArgumentException) {
            logDrawFailure(e)
            return
        }
        try {
            r.draw(canvas, vp, overlays(), night)
        } finally {
            try {
                s.unlockCanvasAndPost(canvas)
            } catch (e: IllegalStateException) {
            } catch (e: IllegalArgumentException) {
            }
        }
    }

    private var lastFailureLogNs = 0L

    private fun logDrawFailure(e: Exception) {
        val now = System.nanoTime()
        if (now - lastFailureLogNs > 5_000_000_000L) {
            lastFailureLogNs = now
            Log.w(TAG, "lockCanvas failed: ${e.javaClass.simpleName}")
        }
    }

    private fun scheduleSave() {
        val cam = camera()
        if (cam == lastPersisted || !cameraLoaded) return
        persistJob?.cancel()
        persistJob = scope.launch {
            delay(1000)
            lastPersisted = cam
            withContext(Dispatchers.IO) { container.mapState.persist(cam) }
        }
    }

    companion object {
        const val MIN_ZOOM = 10.0
        const val MAX_ZOOM = 19.0
        const val FOLLOW_ZOOM = 17.0
        private const val DEFAULT_DPI = 160
        private const val TAG = "MineMapsCar"
    }
}
