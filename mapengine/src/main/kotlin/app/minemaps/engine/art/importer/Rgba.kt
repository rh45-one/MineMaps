package app.minemaps.engine.art.importer

import kotlin.math.max
import kotlin.math.min

/** Non-premultiplied ARGB image with the handful of Pillow operations the importer needs (same rounding as Pillow). */
class Rgba(val w: Int, val h: Int, val px: IntArray = IntArray(w * h)) {
    init {
        require(px.size == w * h) { "pixel count ${px.size} != ${w}x$h" }
    }

    operator fun get(x: Int, y: Int): Int = px[y * w + x]

    operator fun set(x: Int, y: Int, v: Int) {
        px[y * w + x] = v
    }

    fun copy() = Rgba(w, h, px.copyOf())

    fun fill(argb: Int): Rgba = Rgba(w, h, IntArray(w * h) { argb })

    /** Pillow `crop`: areas outside the image are transparent. */
    fun crop(x0: Int, y0: Int, x1: Int, y1: Int): Rgba {
        val out = Rgba(x1 - x0, y1 - y0)
        for (y in y0 until y1) for (x in x0 until x1) if (x in 0 until w && y in 0 until h) out[x - x0, y - y0] = this[x, y]
        return out
    }

    /** Pillow `Image.NEAREST` resize. */
    fun resizeNearest(nw: Int, nh: Int): Rgba {
        val out = Rgba(nw, nh)
        val sx = w.toDouble() / nw
        val sy = h.toDouble() / nh
        for (y in 0 until nh) {
            val yi = min(h - 1, ((y + 0.5) * sy).toInt())
            for (x in 0 until nw) out[x, y] = this[min(w - 1, ((x + 0.5) * sx).toInt()), yi]
        }
        return out
    }

    /** Pillow `Image.BOX` resize: RGBA is premultiplied first, resampled separably in 8 bits, then un-premultiplied. */
    fun resizeBox(nw: Int, nh: Int): Rgba {
        var cur = premultiplied()
        if (nw != w) cur = resampleAxis(cur, nw, horizontal = true)
        if (nh != h) cur = resampleAxis(cur, nh, horizontal = false)
        return cur.unpremultiplied()
    }

    private fun premultiplied(): Rgba = Rgba(w, h, IntArray(px.size) { i ->
        val p = px[i]
        val a = p ushr 24
        fun m(c: Int): Int {
            val t = c * a + 128
            return ((t shr 8) + t) shr 8
        }
        (a shl 24) or (m((p shr 16) and 255) shl 16) or (m((p shr 8) and 255) shl 8) or m(p and 255)
    })

    private fun unpremultiplied(): Rgba = Rgba(w, h, IntArray(px.size) { i ->
        val p = px[i]
        val a = p ushr 24
        if (a == 255 || a == 0) {
            p
        } else {
            fun u(c: Int) = min(255, (255 * c) / a)
            (a shl 24) or (u((p shr 16) and 255) shl 16) or (u((p shr 8) and 255) shl 8) or u(p and 255)
        }
    })

    fun transposed(mode: Rotation): Rgba = when (mode) {
        Rotation.CCW90 -> Rgba(h, w).also { o -> for (y in 0 until o.h) for (x in 0 until o.w) o[x, y] = this[w - 1 - y, x] }
        Rotation.R180 -> Rgba(w, h).also { o -> for (y in 0 until h) for (x in 0 until w) o[x, y] = this[w - 1 - x, h - 1 - y] }
        Rotation.CW90 -> Rgba(h, w).also { o -> for (y in 0 until o.h) for (x in 0 until o.w) o[x, y] = this[y, h - 1 - x] }
        Rotation.FLIP_Y -> Rgba(w, h).also { o -> for (y in 0 until h) for (x in 0 until w) o[x, y] = this[x, h - 1 - y] }
    }

    /** Pillow `paste` without a mask: copies pixels (including alpha), clipped to this image. */
    fun paste(src: Rgba, dx: Int, dy: Int) {
        for (y in 0 until src.h) for (x in 0 until src.w) {
            val tx = x + dx
            val ty = y + dy
            if (tx in 0 until w && ty in 0 until h) this[tx, ty] = src[x, y]
        }
    }

    /** Pillow `alpha_composite`: `src` over this (straight alpha, same integer arithmetic). */
    fun alphaComposite(src: Rgba) {
        require(src.w == w && src.h == h)
        for (i in px.indices) {
            val s = src.px[i]
            val sa = s ushr 24
            if (sa == 0) continue
            val d = px[i]
            val da = d ushr 24
            val blend = da * (255 - sa)
            val outa255 = sa * 255 + blend
            val coef1 = ((sa.toLong() * 255 * 255 * (1 shl PRECISION)) / outa255).toInt()
            val coef2 = 255 * (1 shl PRECISION) - coef1
            fun ch(shift: Int): Int {
                val t = (((s shr shift) and 255).toLong() * coef1 + ((d shr shift) and 255).toLong() * coef2).toInt()
                return shiftForDiv255(t + (0x80 shl PRECISION)) shr PRECISION
            }
            px[i] = (shiftForDiv255(outa255 + 0x80) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /** Bounding box (left, top, right, bottom exclusive) of pixels with non-zero alpha, or null. */
    fun alphaBBox(): IntArray? {
        var x0 = w
        var y0 = h
        var x1 = -1
        var y1 = -1
        for (y in 0 until h) for (x in 0 until w) if ((this[x, y] ushr 24) != 0) {
            x0 = min(x0, x)
            y0 = min(y0, y)
            x1 = max(x1, x)
            y1 = max(y1, y)
        }
        return if (x1 < 0) null else intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    enum class Rotation { CCW90, R180, CW90, FLIP_Y }

    private companion object {
        const val PRECISION = 7
        const val COEF_BITS = 22

        fun shiftForDiv255(a: Int) = ((a shr 8) + a) shr 8

        fun resampleAxis(src: Rgba, outSize: Int, horizontal: Boolean): Rgba {
            val inSize = if (horizontal) src.w else src.h
            val scale = inSize.toDouble() / outSize
            val filterScale = max(scale, 1.0)
            val support = 0.5 * filterScale
            val starts = IntArray(outSize)
            val coefs = Array(outSize) { IntArray(0) }
            for (xx in 0 until outSize) {
                val center = (xx + 0.5) * scale
                var xmin = (center - support + 0.5).toInt()
                if (xmin < 0) xmin = 0
                var xmax = (center + support + 0.5).toInt()
                if (xmax > inSize) xmax = inSize
                xmax -= xmin
                val k = DoubleArray(max(xmax, 0))
                var ww = 0.0
                val ss = 1.0 / filterScale
                for (x in 0 until xmax) {
                    val v = (x + xmin - center + 0.5) * ss
                    k[x] = if (v > -0.5 && v <= 0.5) 1.0 else 0.0
                    ww += k[x]
                }
                if (ww != 0.0) for (x in 0 until xmax) k[x] /= ww
                starts[xx] = xmin
                coefs[xx] = IntArray(xmax) { i -> if (k[i] < 0) (-0.5 + k[i] * (1 shl COEF_BITS)).toInt() else (0.5 + k[i] * (1 shl COEF_BITS)).toInt() }
            }
            val out = if (horizontal) Rgba(outSize, src.h) else Rgba(src.w, outSize)
            for (y in 0 until out.h) for (x in 0 until out.w) {
                val o = if (horizontal) x else y
                var a = 1 shl (COEF_BITS - 1)
                var r = a
                var g = a
                var b = a
                val ks = coefs[o]
                for (i in ks.indices) {
                    val p = if (horizontal) src[starts[o] + i, y] else src[x, starts[o] + i]
                    a += (p ushr 24) * ks[i]
                    r += ((p shr 16) and 255) * ks[i]
                    g += ((p shr 8) and 255) * ks[i]
                    b += (p and 255) * ks[i]
                }
                fun c(v: Int) = (v shr COEF_BITS).coerceIn(0, 255)
                out[x, y] = (c(a) shl 24) or (c(r) shl 16) or (c(g) shl 8) or c(b)
            }
            return out
        }
    }
}
