package app.minemaps.map

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import app.minemaps.data.Camera
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.Viewport
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min

class MapView(context: Context, val renderer: MapRenderer) : View(context) {
    var centerX = 0.5
        private set
    var centerY = 0.5
        private set
    var zoom = 3.0
        private set

    var overlays: MapOverlays = MapOverlays.EMPTY
        set(value) {
            field = value
            invalidate()
        }
    var night: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                invalidate()
            }
        }

    var onCameraChanged: ((Camera) -> Unit)? = null
    var onUserMoved: (() -> Unit)? = null
    var onTap: ((Viewport, Float, Float) -> Unit)? = null
    var onLongPress: ((Viewport, Float, Float) -> Unit)? = null

    private val scroller = OverScroller(context)
    private var lastFlingX = 0
    private var lastFlingY = 0
    private var animator: ValueAnimator? = null
    private var scaling = false
    private var lastFocusX = 0f
    private var lastFocusY = 0f

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                scroller.forceFinished(true)
                cancelAnimation()
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                if (scaling) return false
                onUserMoved?.invoke()
                panBy(dx.toDouble(), dy.toDouble())
                return true
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (scaling) return false
                lastFlingX = 0
                lastFlingY = 0
                scroller.fling(0, 0, -vx.toInt(), -vy.toInt(), -FLING_RANGE, FLING_RANGE, -FLING_RANGE, FLING_RANGE)
                postInvalidateOnAnimation()
                return true
            }

            override fun onDoubleTap(e: MotionEvent): Boolean {
                onUserMoved?.invoke()
                zoomAround(e.x, e.y, zoom + 1.0)
                return true
            }

            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                onTap?.invoke(viewport(), e.x, e.y)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                onLongPress?.invoke(viewport(), e.x, e.y)
            }
        },
    )

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                scaling = true
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                scroller.forceFinished(true)
                cancelAnimation()
                onUserMoved?.invoke()
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val before = viewport().screenToWorld(lastFocusX.toDouble(), lastFocusY.toDouble())
                zoom = (zoom + ln(detector.scaleFactor.toDouble()) / ln(2.0)).coerceIn(MIN_ZOOM, MAX_ZOOM)
                placeWorldAt(before.x, before.y, detector.focusX.toDouble(), detector.focusY.toDouble())
                lastFocusX = detector.focusX
                lastFocusY = detector.focusY
                cameraChanged()
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                scaling = false
            }
        },
    ).apply { isQuickScaleEnabled = false }

    fun viewport(): Viewport = Viewport(centerX, centerY, zoom, max(width, 1), max(height, 1), renderer.pixelRatio)

    fun setCamera(camera: Camera) {
        cancelAnimation()
        centerX = Mercator.lonToX(camera.lon)
        centerY = Mercator.latToY(camera.lat)
        zoom = camera.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        cameraChanged()
    }

    fun camera(): Camera = Camera(Mercator.yToLat(centerY), Mercator.xToLon(centerX), zoom)

    fun requestRedraw() = postInvalidateOnAnimation()

    private fun placeWorldAt(wx: Double, wy: Double, sx: Double, sy: Double) {
        val size = Viewport(0.0, 0.0, zoom, 1, 1, renderer.pixelRatio).worldSizePx
        centerX = wrap(wx - (sx - width / 2.0) / size)
        centerY = (wy - (sy - height / 2.0) / size).coerceIn(0.0, 1.0)
    }

    private fun wrap(x: Double): Double = x - Math.floor(x)

    private fun panBy(dxPx: Double, dyPx: Double) {
        val size = viewport().worldSizePx
        centerX = wrap(centerX + dxPx / size)
        centerY = (centerY + dyPx / size).coerceIn(0.0, 1.0)
        cameraChanged()
    }

    private fun cameraChanged() {
        invalidate()
        onCameraChanged?.invoke(camera())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val dx = scroller.currX - lastFlingX
            val dy = scroller.currY - lastFlingY
            lastFlingX = scroller.currX
            lastFlingY = scroller.currY
            panBy(dx.toDouble(), dy.toDouble())
            postInvalidateOnAnimation()
        }
    }

    fun zoomBy(delta: Double) {
        animateZoomAround(width / 2f, height / 2f, zoom + delta)
    }

    fun zoomAround(sx: Float, sy: Float, target: Double) = animateZoomAround(sx, sy, target)

    private fun animateZoomAround(sx: Float, sy: Float, target: Double) {
        cancelAnimation()
        val z0 = zoom
        val z1 = target.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val focus = viewport().screenToWorld(sx.toDouble(), sy.toDouble())
        runAnimation(250L) { f ->
            zoom = z0 + (z1 - z0) * f
            placeWorldAt(focus.x, focus.y, sx.toDouble(), sy.toDouble())
        }
    }

    fun animateTo(lat: Double, lon: Double, targetZoom: Double = zoom, durationMs: Long = 700L) {
        cancelAnimation()
        val x0 = centerX
        val y0 = centerY
        val z0 = zoom
        var dx = Mercator.lonToX(lon) - x0
        if (dx > 0.5) dx -= 1.0 else if (dx < -0.5) dx += 1.0
        val dy = Mercator.latToY(lat) - y0
        val z1 = targetZoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
        runAnimation(durationMs) { f ->
            centerX = wrap(x0 + dx * f)
            centerY = (y0 + dy * f).coerceIn(0.0, 1.0)
            zoom = z0 + (z1 - z0) * f
        }
    }

    fun follow(lat: Double, lon: Double) {
        if (width == 0 || animator != null) return
        val target = Viewport(Mercator.lonToX(lon), Mercator.latToY(lat), zoom, width, height, renderer.pixelRatio)
        val screen = viewport().worldToScreen(target.centerX, target.centerY)
        val off = max(abs(screen.x - width / 2.0), abs(screen.y - height / 2.0))
        if (off < 2.0) return
        if (off > max(width, height)) setCamera(Camera(lat, lon, zoom)) else animateTo(lat, lon, zoom, 400L)
    }

    fun fitBounds(minLat: Double, minLon: Double, maxLat: Double, maxLon: Double, maxZoom: Double = 19.0) {
        val x0 = Mercator.lonToX(minLon)
        val x1 = Mercator.lonToX(maxLon)
        val y0 = Mercator.latToY(maxLat)
        val y1 = Mercator.latToY(minLat)
        val spanX = max(abs(x1 - x0), 1e-9)
        val spanY = max(abs(y1 - y0), 1e-9)
        val px = 256.0 * renderer.pixelRatio
        val zx = ln(width * 0.8 / (px * spanX)) / ln(2.0)
        val zy = ln(height * 0.8 / (px * spanY)) / ln(2.0)
        val z = min(min(zx, zy), maxZoom)
        animateTo(Mercator.yToLat((y0 + y1) / 2), Mercator.xToLon((x0 + x1) / 2), z)
    }

    private fun runAnimation(durationMs: Long, step: (Float) -> Unit) {
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = durationMs
        a.interpolator = DecelerateInterpolator(1.5f)
        a.addUpdateListener {
            step(it.animatedValue as Float)
            cameraChanged()
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                if (animator === animation) animator = null
            }
        })
        animator = a
        a.start()
    }

    private fun cancelAnimation() {
        animator?.let {
            animator = null
            it.cancel()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cameraChanged()
    }

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, viewport(), overlays, night)
    }

    override fun onDetachedFromWindow() {
        cancelAnimation()
        super.onDetachedFromWindow()
    }

    companion object {
        const val MIN_ZOOM = 3.0
        const val MAX_ZOOM = 21.5
        private const val FLING_RANGE = 100_000_000
    }
}
