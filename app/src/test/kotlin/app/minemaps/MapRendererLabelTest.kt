package app.minemaps

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.geo.Viewport
import app.minemaps.engine.layout.RenderProfile
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
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MapRendererLabelTest {

    private class Pb {
        val out = ByteArrayOutputStream()

        fun varint(v: Long) {
            var x = v
            while (x and 0x7fL.inv() != 0L) {
                out.write(((x and 0x7f) or 0x80).toInt())
                x = x ushr 7
            }
            out.write(x.toInt())
        }

        fun tag(field: Int, wire: Int) = varint(((field shl 3) or wire).toLong())

        fun uint(field: Int, v: Long) {
            tag(field, 0)
            varint(v)
        }

        fun bytes(field: Int, b: ByteArray) {
            tag(field, 2)
            varint(b.size.toLong())
            out.write(b)
        }

        fun str(field: Int, s: String) = bytes(field, s.toByteArray())

        fun packed(field: Int, values: List<Int>) {
            val p = Pb()
            values.forEach { p.varint(it.toLong() and 0xffffffffL) }
            bytes(field, p.out.toByteArray())
        }

        fun toBytes(): ByteArray = out.toByteArray()
    }

    private fun zig(v: Int) = (v shl 1) xor (v shr 31)
    private fun cmd(id: Int, count: Int) = (count shl 3) or id

    private data class CitySpec(val name: String, val wx: Double, val wy: Double, val rank: Int)

    private fun buildPlaceTiles(specs: List<CitySpec>, vtZoom: Int = 14): Map<TileKey, ByteArray> {
        val n = 1 shl vtZoom
        val grouped = specs.groupBy { s ->
            val tx = floor(s.wx * n).toInt().coerceIn(0, n - 1)
            val ty = floor(s.wy * n).toInt().coerceIn(0, n - 1)
            TileKey(vtZoom, tx, ty)
        }
        return grouped.mapValues { (vt, list) ->
            val l = Pb()
            l.uint(15, 2)
            l.str(1, "place")
            val keys = listOf("name", "class", "rank")
            val values = ArrayList<ByteArray>()
            val keyIndex = keys.withIndex().associate { it.value to it.index }
            var id = 1L
            for (s in list) {
                val px = ((s.wx * n - vt.x) * 4096.0).roundToInt()
                val py = ((s.wy * n - vt.y) * 4096.0).roundToInt()
                val nameIdx = values.size
                values.add(Pb().apply { str(1, s.name) }.toBytes())
                val clsIdx = values.size
                values.add(Pb().apply { str(1, "city") }.toBytes())
                val rankIdx = values.size
                values.add(Pb().apply { uint(4, s.rank.toLong()) }.toBytes())

                val f = Pb()
                f.uint(1, id++)
                f.packed(2, listOf(keyIndex.getValue("name"), nameIdx, keyIndex.getValue("class"), clsIdx, keyIndex.getValue("rank"), rankIdx))
                f.uint(3, 1L) // POINT
                f.packed(4, listOf(cmd(1, 1), zig(px), zig(py)))
                l.bytes(2, f.toBytes())
            }
            for (k in keys) l.str(3, k)
            for (v in values) l.bytes(4, v)
            l.uint(5, 4096)
            Pb().apply { bytes(3, l.toBytes()) }.toBytes()
        }
    }

    private data class DrawnLabel(val cx: Float, val cy: Float, val width: Int, val height: Int)

    private class RecordingCanvas(bmp: Bitmap) : Canvas(bmp) {
        val drawnLabels = ArrayList<DrawnLabel>()
        private val stack = ArrayList<Pair<Float, Float>>()
        private var tx = 0f
        private var ty = 0f

        override fun save(): Int {
            stack.add(tx to ty)
            return super.save()
        }

        override fun restore() {
            if (stack.isNotEmpty()) {
                val prev = stack.removeAt(stack.lastIndex)
                tx = prev.first
                ty = prev.second
            }
            super.restore()
        }

        override fun translate(dx: Float, dy: Float) {
            tx += dx
            ty += dy
            super.translate(dx, dy)
        }

        @Deprecated("Deprecated in Java")
        override fun drawBitmap(bitmap: Bitmap, left: Float, top: Float, paint: Paint?) {
            super.drawBitmap(bitmap, left, top, paint)
            drawnLabels.add(
                DrawnLabel(
                    tx + left + bitmap.width / 2f,
                    ty + top + bitmap.height / 2f,
                    bitmap.width,
                    bitmap.height,
                ),
            )
        }
    }

    private fun fakeClient(tiles: Map<TileKey, ByteArray>): OkHttpClient {
        val tilePattern = Regex("""/(\d+)/(\d+)/(\d+)\.pbf$""")
        return OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val req = chain.request()
                val path = req.url.encodedPath
                val m = tilePattern.find(path)
                if (m != null) {
                    val z = m.groupValues[1].toInt()
                    val x = m.groupValues[2].toInt()
                    val y = m.groupValues[3].toInt()
                    val bytes = tiles[TileKey(z, x, y)] ?: ByteArray(0)
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(bytes.toResponseBody("application/x-protobuf".toMediaType()))
                        .build()
                } else {
                    Response.Builder()
                        .request(req)
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("""{"tiles":["https://test.invalid/{z}/{x}/{y}.pbf"],"minzoom":0,"maxzoom":14}""".toResponseBody("application/json".toMediaType()))
                        .build()
                }
            })
            .build()
    }

    private fun awaitRenderedLabels(
        renderer: MapRenderer,
        vp: Viewport,
        invalidations: AtomicInteger,
        condition: (List<DrawnLabel>) -> Boolean,
    ): List<DrawnLabel> {
        val bmp = Bitmap.createBitmap(vp.widthPx, vp.heightPx, Bitmap.Config.ARGB_8888)
        try {
            val deadline = System.currentTimeMillis() + 5_000L
            var lastSeen = -1
            var drewAtZeroPending = false
            while (System.currentTimeMillis() < deadline) {
                val cur = invalidations.get()
                val pending = renderer.pendingLabelCount()
                if (cur != lastSeen || (pending == 0 && !drewAtZeroPending)) {
                    lastSeen = cur
                    val canvas = RecordingCanvas(bmp)
                    renderer.draw(canvas, vp, MapOverlays.EMPTY, night = false)
                    val stillPending = renderer.pendingLabelCount()
                    drewAtZeroPending = stillPending == 0
                    if (stillPending == 0 && condition(canvas.drawnLabels)) return canvas.drawnLabels
                } else if (pending > 0) {
                    drewAtZeroPending = false
                }
                Thread.sleep(15)
            }
            val finalCanvas = RecordingCanvas(bmp)
            renderer.draw(finalCanvas, vp, MapOverlays.EMPTY, night = false)
            return finalCanvas.drawnLabels
        } finally {
            bmp.recycle()
        }
    }

    @Test
    fun panBufferRetainsEnteringLabelWithinHalfMarginReuseWindow() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryClass(256)
        val tileCenter = (8192 + 0.5) / 16384.0
        val vp0 = Viewport(tileCenter, tileCenter, 17.0, 1280, 720, 1f, bearingDeg = 0.0, pitchDeg = 0f, tiltDensity = 1f)
        val anchorPt = vp0.screenToWorld(640.0, 360.0)
        val enteringPt = vp0.screenToWorld(-65.0, 360.0)
        val tiles = buildPlaceTiles(
            listOf(
                CitySpec("Anchor", anchorPt.x, anchorPt.y, rank = 1),
                CitySpec("CityX", enteringPt.x, enteringPt.y, rank = 2),
            ),
        )
        val invalidations = AtomicInteger(0)
        val engine = MapEngine(context, fakeClient(tiles))
        engine.clearCache()
        val renderer = MapRenderer(engine, RenderProfile.CAR, density = 1f, pixelRatioOverride = 1f) {
            invalidations.incrementAndGet()
        }
        try {
            val drawn0 = awaitRenderedLabels(renderer, vp0, invalidations) { labels ->
                labels.any { kotlin.math.abs(it.cx - 640f) < 5f && kotlin.math.abs(it.cy - 360f) < 5f }
            }
            assertTrue(drawn0.any { kotlin.math.abs(it.cx - 640f) < 5f }, "Anchor must draw at 640: $drawn0")
            assertFalse(drawn0.any { it.cx < 100f }, "Entering CityX at -65 must not draw on vp0: $drawn0")

            // Pan 80 px (within LAYOUT_MARGIN_PX / 2 = 80 px reuse window) so CityX enters at x = +15 px.
            val panCenter = vp0.screenToWorld(640.0 - 80.0, 360.0)
            val vp1 = Viewport(panCenter.x, panCenter.y, 17.0, 1280, 720, 1f, bearingDeg = 0.0, pitchDeg = 0f, tiltDensity = 1f)
            val bmp1 = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvas1 = RecordingCanvas(bmp1)
            renderer.draw(canvas1, vp1, MapOverlays.EMPTY, night = false)
            bmp1.recycle()

            assertTrue(
                canvas1.drawnLabels.any { kotlin.math.abs(it.cx - 15f) < 5f && kotlin.math.abs(it.cy - 360f) < 5f },
                "CityX must appear at x~=15 immediately during 80px pan reuse: ${canvas1.drawnLabels}",
            )
            assertTrue(
                canvas1.drawnLabels.any { kotlin.math.abs(it.cx - 720f) < 5f && kotlin.math.abs(it.cy - 360f) < 5f },
                "Anchor must pan to x~=720: ${canvas1.drawnLabels}",
            )
        } finally {
            renderer.close()
            engine.clearCache()
        }
    }

    @Test
    fun layoutKeyInvalidatesWhenPitchDegChanges() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryClass(256)
        val vpFlat = Viewport(0.5, 0.5, 17.0, 1280, 720, 1f, bearingDeg = 0.0, pitchDeg = 0f, tiltDensity = 1f)
        val vpTilted = Viewport(0.5, 0.5, 17.0, 1280, 720, 1f, bearingDeg = 0.0, pitchDeg = 38f, tiltDensity = 1f)

        val anchorPt = vpFlat.screenToWorld(640.0, 360.0)
        // Pre-tilt y = -100 is off-screen flat, but projects post-tilt to y ~= 177.2 inside [0..720]
        val horizonPt = vpFlat.screenToWorld(640.0, -100.0)
        val expectedTiltedHorizonY = vpTilted.projectTilt(640.0, -100.0).y.toFloat()

        val tiles = buildPlaceTiles(
            listOf(
                CitySpec("Anchor", anchorPt.x, anchorPt.y, rank = 1),
                CitySpec("Horizon", horizonPt.x, horizonPt.y, rank = 2),
            ),
        )
        val invalidations = AtomicInteger(0)
        val engine = MapEngine(context, fakeClient(tiles))
        engine.clearCache()
        val renderer = MapRenderer(engine, RenderProfile.CAR, density = 1f, pixelRatioOverride = 1f) {
            invalidations.incrementAndGet()
        }
        try {
            val drawnFlat = awaitRenderedLabels(renderer, vpFlat, invalidations) { labels ->
                labels.any { kotlin.math.abs(it.cx - 640f) < 5f && kotlin.math.abs(it.cy - 360f) < 5f }
            }
            assertFalse(drawnFlat.any { it.cy < 100f }, "Horizon must not draw flat: $drawnFlat")

            val bmpTilted = Bitmap.createBitmap(1280, 720, Bitmap.Config.ARGB_8888)
            val canvasTilted = RecordingCanvas(bmpTilted)
            renderer.draw(canvasTilted, vpTilted, MapOverlays.EMPTY, night = false)
            bmpTilted.recycle()

            assertTrue(
                canvasTilted.drawnLabels.any {
                    kotlin.math.abs(it.cx - 640f) < 5f && kotlin.math.abs(it.cy - expectedTiltedHorizonY) < 5f
                },
                "LayoutKey must invalidate on pitchDeg change so Horizon draws at y~=$expectedTiltedHorizonY: ${canvasTilted.drawnLabels}",
            )
        } finally {
            renderer.close()
            engine.clearCache()
        }
    }

    @Test
    fun splitScreenTiltedDrawLabelsClipsPostTiltAndPreservesCarBudget() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Shadows.shadowOf(context.getSystemService(ActivityManager::class.java)).setMemoryClass(256)
        val splitTilted = Viewport(
            0.5, 0.5, 17.0, 1280, 720, 1f, bearingDeg = 0.0, pitchDeg = 38f,
            focalX = 388.0, focalY = 388.0, visLeft = 16.0, visTop = 96.0, visRight = 760.0, visBottom = 680.0,
            tiltDensity = 1f,
        )
        val specs = ArrayList<CitySpec>()
        // 30 high-priority labels inside pre-tilt visRight=760 (755, 670) that spill > 760 post-tilt
        for (i in 0 until 30) {
            val p = splitTilted.screenToWorld(755.0, 670.0)
            specs.add(CitySpec("S$i", p.x, p.y, rank = i + 1))
        }
        // 30 high-priority labels in the post-tilt obscured margin [820..920]
        for (i in 0 until 30) {
            val pre = splitTilted.inverseTilt(820.0 + (i % 3) * 35.0, 140.0 + (i / 3) * 45.0)
            val p = splitTilted.screenToWorld(pre.x, pre.y)
            specs.add(CitySpec("M$i", p.x, p.y, rank = i + 40))
        }
        val visPt = splitTilted.screenToWorld(388.0, 388.0)
        val expectedVis = splitTilted.projectTilt(388.0, 388.0)
        specs.add(CitySpec("VisibleCity", visPt.x, visPt.y, rank = 500))

        val tiles = buildPlaceTiles(specs)
        val invalidations = AtomicInteger(0)
        val engine = MapEngine(context, fakeClient(tiles))
        engine.clearCache()
        val renderer = MapRenderer(engine, RenderProfile.CAR, density = 1f, pixelRatioOverride = 1f) {
            invalidations.incrementAndGet()
        }
        try {
            val drawn = awaitRenderedLabels(renderer, splitTilted, invalidations) { labels ->
                labels.isNotEmpty()
            }
            assertEquals(1, drawn.size, "only VisibleCity should render inside split-screen pane: $drawn")
            assertEquals(expectedVis.x.toFloat(), drawn.single().cx, 5f)
            assertEquals(expectedVis.y.toFloat(), drawn.single().cy, 5f)
            for (d in drawn) {
                assertTrue(d.cx + d.width / 2f >= 16f && d.cx - d.width / 2f <= 760f, "label spilled horizontally: $d")
                assertTrue(d.cy + d.height / 2f >= 96f && d.cy - d.height / 2f <= 680f, "label spilled vertically: $d")
            }
        } finally {
            renderer.close()
            engine.clearCache()
        }
    }
}
