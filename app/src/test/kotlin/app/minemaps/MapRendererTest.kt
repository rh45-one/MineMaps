package app.minemaps

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.core.app.ApplicationProvider
import app.minemaps.engine.geo.LatLng
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.Viewport
import app.minemaps.engine.layout.RenderProfile
import app.minemaps.engine.routing.RoadAlert
import app.minemaps.engine.routing.RoadAlertKind
import app.minemaps.map.Bookmark
import app.minemaps.map.MapEngine
import app.minemaps.map.MapOverlays
import app.minemaps.map.MapRenderer
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapRendererTest {

    private class OverlayRecordingCanvas(bmp: Bitmap) : Canvas(bmp) {
        val drawnBounds = ArrayList<RectF>()

        override fun drawBitmap(bitmap: Bitmap, src: Rect?, dst: Rect, paint: Paint?) {
            super.drawBitmap(bitmap, src, dst, paint)
            drawnBounds.add(RectF(dst))
        }

        @Deprecated("Deprecated in Java")
        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            super.drawBitmap(bitmap, left, top, paint)
            drawnBounds.add(RectF(left, top, left + bitmap.width, top + bitmap.height))
        }

        override fun drawOval(oval: RectF, paint: Paint) {
            super.drawOval(oval, paint)
            drawnBounds.add(RectF(oval))
        }
    }

    private fun jsonResponse(request: okhttp3.Request, body: String): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(body.toResponseBody("application/json".toMediaType()))
            .build()

    private fun pbfResponse(request: okhttp3.Request, bytes: ByteArray): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(200)
            .message("OK")
            .body(bytes.toResponseBody("application/x-protobuf".toMediaType()))
            .build()

    @Test
    fun drawRetainsAllTiltedHorizonTilesRequestedByDraw() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryClass(256)

        val cases = listOf(
            // 1280x720 LHD split screen with bearing
            Viewport(
                0.5063736, 0.3440208, 17.0, 1280, 720, 1f, bearingDeg = 45.0, pitchDeg = 38f,
                focalX = 388.0, focalY = 388.0, visLeft = 16.0, visTop = 96.0, visRight = 760.0, visBottom = 680.0,
                tiltDensity = 1f,
            ),
            // 1920x1080 full screen
            Viewport(
                0.5063736, 0.3440208, 17.0, 1920, 1080, 1f, bearingDeg = 0.0, pitchDeg = 38f,
                focalX = 960.0, focalY = 540.0, visLeft = 0.0, visTop = 0.0, visRight = 1920.0, visBottom = 1080.0,
                tiltDensity = 1f,
            ),
            // 2400x960 LHD split screen
            Viewport(
                0.5063736, 0.3440208, 17.0, 2400, 960, 1f, bearingDeg = 0.0, pitchDeg = 38f,
                focalX = 750.0, focalY = 480.0, visLeft = 0.0, visTop = 0.0, visRight = 1500.0, visBottom = 960.0,
                tiltDensity = 1f,
            ),
        )

        for (vp in cases) {
            val pbfGate = CountDownLatch(1)
            val invalidations = AtomicInteger(0)
            val client = OkHttpClient.Builder()
                .addInterceptor(Interceptor { chain ->
                    val req = chain.request()
                    if (req.url.encodedPath.endsWith(".pbf")) {
                        pbfGate.await(5, TimeUnit.SECONDS)
                        pbfResponse(req, ByteArray(0))
                    } else {
                        jsonResponse(req, """{"tiles":["https://test.invalid/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14}""")
                    }
                })
                .build()

            val engine = MapEngine(context, client)
            engine.clearCache()
            val renderer = MapRenderer(engine, RenderProfile.CAR, density = vp.tiltDensity, pixelRatioOverride = vp.pixelRatio) {
                invalidations.incrementAndGet()
            }
            try {
                val z = vp.tileZoom()
                val expectedKeys = vp.visibleTileSlots(z).map { it.key }.toSet()
                val retainedKeys = renderer.paddedRetainViewport(vp, vp.tileScreenSize(z)).visibleTiles(z).toSet()
                assertTrue(
                    retainedKeys.containsAll(expectedKeys),
                    "paddedRetainViewport dropped ${expectedKeys - retainedKeys} for ${vp.widthPx}x${vp.heightPx}",
                )

                val bmp = Bitmap.createBitmap(vp.widthPx, vp.heightPx, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bmp)
                renderer.draw(canvas, vp, MapOverlays.EMPTY, night = false)

                assertEquals(
                    expectedKeys.size,
                    renderer.pendingTileCount(),
                    "retainRing dropped tilted horizon tiles for ${vp.widthPx}x${vp.heightPx}",
                )

                pbfGate.countDown()
                val deadline = System.currentTimeMillis() + 5_000L
                while (renderer.pendingTileCount() > 0 && System.currentTimeMillis() < deadline) {
                    Thread.sleep(15)
                }
                assertEquals(0, renderer.pendingTileCount(), "pending tiles did not drain for ${vp.widthPx}x${vp.heightPx}")
                assertTrue(invalidations.get() >= expectedKeys.size)

                // Second draw finds all horizon tiles cached and requests none
                renderer.draw(canvas, vp, MapOverlays.EMPTY, night = false)
                assertEquals(0, renderer.pendingTileCount())
                bmp.recycle()
            } finally {
                pbfGate.countDown()
                renderer.close()
                engine.clearCache()
            }
        }
    }

    @Test
    fun splitScreenCullsObscuredBookmarksPinAndRoadAlertsAndSuppressesCameraFlash() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryClass(256)
        val invalidations = AtomicInteger(0)
        val offlineClient = OkHttpClient.Builder()
            .addInterceptor(Interceptor { throw IOException("offline") })
            .build()
        val engine = MapEngine(context, offlineClient)
        engine.clearCache()
        val renderer = MapRenderer(engine, RenderProfile.CAR, density = 2f, pixelRatioOverride = 2f) {
            invalidations.incrementAndGet()
        }

        val vp = Viewport(
            centerX = 0.5063736, centerY = 0.3440208, zoom = 17.0,
            widthPx = 1280, heightPx = 720, pixelRatio = 2f,
            bearingDeg = 35.0, pitchDeg = 38f,
            focalX = 380.0, focalY = 360.0,
            visLeft = 0.0, visTop = 0.0, visRight = 760.0, visBottom = 720.0,
            tiltDensity = 2f,
        )

        fun latLngAtPostTiltScreen(sx: Double, sy: Double): LatLng {
            val pre = vp.inverseTilt(sx, sy)
            val w = vp.screenToWorld(pre.x, pre.y)
            return LatLng(Mercator.yToLat(w.y), Mercator.xToLon(w.x))
        }

        val bmp = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)

        try {
            // Case A: Overlays only in obscured region [760..1280]
            val obs1 = latLngAtPostTiltScreen(900.0, 360.0)
            val obs2 = latLngAtPostTiltScreen(1050.0, 360.0)
            val obsPin = latLngAtPostTiltScreen(1000.0, 360.0)
            val obsCam = latLngAtPostTiltScreen(1000.0, 460.0)
            val obscuredOnly = MapOverlays(
                bookmarks = listOf(
                    Bookmark(obs1.lat, obs1.lng, "home", ""),
                    Bookmark(obs2.lat, obs2.lng, "cafe", "Cafe"),
                ),
                searchPin = obsPin,
                roadAlerts = listOf(RoadAlert("cam-obs", obsCam.lat, obsCam.lng, RoadAlertKind.SPEED_CAMERA, "Speed camera")),
            )

            val canvasA = OverlayRecordingCanvas(bmp)
            renderer.draw(canvasA, vp, obscuredOnly, night = false)

            assertTrue(
                canvasA.drawnBounds.isEmpty(),
                "expected zero overlay draws for obscured split-screen overlays, got ${canvasA.drawnBounds}",
            )
            assertFalse(renderer.isCameraFlashActive(), "cameraFlashJob must not arm for obscured speed camera")
            Thread.sleep(600)
            assertEquals(0, invalidations.get(), "obscured speed camera must not schedule 460 ms flash redraws")

            // Case B: Visible-pane overlays render inside [0..760] and arm flash timer
            val visBm = latLngAtPostTiltScreen(250.0, 360.0)
            val visPin = latLngAtPostTiltScreen(380.0, 360.0)
            val visCam = latLngAtPostTiltScreen(500.0, 360.0)
            val visibleOverlays = MapOverlays(
                bookmarks = listOf(Bookmark(visBm.lat, visBm.lng, "home", "Home")),
                searchPin = visPin,
                roadAlerts = listOf(RoadAlert("cam-vis", visCam.lat, visCam.lng, RoadAlertKind.SPEED_CAMERA, "Speed camera")),
            )
            val canvasB = OverlayRecordingCanvas(bmp)
            renderer.draw(canvasB, vp, visibleOverlays, night = false)

            assertTrue(canvasB.drawnBounds.any { it.contains(250f, 360f) }, "expected visible bookmark draw at (250,360): ${canvasB.drawnBounds}")
            assertTrue(canvasB.drawnBounds.any { kotlin.math.abs(it.centerX() - 380f) < 5f && it.bottom >= 355f }, "expected visible pin draw at (380,360): ${canvasB.drawnBounds}")
            assertTrue(canvasB.drawnBounds.any { it.contains(500f, 360f) }, "expected visible speed camera draw at (500,360): ${canvasB.drawnBounds}")
            assertTrue(canvasB.drawnBounds.all { it.right <= 760f }, "visible overlays must stay out of obscured pane: ${canvasB.drawnBounds}")
            assertTrue(renderer.isCameraFlashActive(), "cameraFlashJob must arm when speed camera is visible")

            val deadline = System.currentTimeMillis() + 1_500L
            while (invalidations.get() == 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(25)
            }
            assertTrue(invalidations.get() > 0, "expected cameraFlashJob to trigger invalidation")

            // Case C: Moving camera off-pane or clearing alerts cancels cameraFlashJob immediately
            val canvasC = OverlayRecordingCanvas(bmp)
            renderer.draw(canvasC, vp, obscuredOnly, night = false)
            assertTrue(canvasC.drawnBounds.isEmpty())
            assertFalse(renderer.isCameraFlashActive(), "cameraFlashJob must cancel when speed camera leaves visible pane")

            renderer.draw(OverlayRecordingCanvas(bmp), vp, visibleOverlays, night = false)
            assertTrue(renderer.isCameraFlashActive())
            renderer.draw(OverlayRecordingCanvas(bmp), vp, MapOverlays.EMPTY, night = false)
            assertFalse(renderer.isCameraFlashActive(), "cameraFlashJob must cancel when roadAlerts becomes empty")
        } finally {
            bmp.recycle()
            renderer.close()
            engine.clearCache()
        }
    }
}
