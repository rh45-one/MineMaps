package app.minemaps.map

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Two-finger twist tracking with Google-Maps-style initial breakaway resistance:
 * - Requires a deliberate twist of [thresholdDeg] before rotation engages.
 * - Requires the two fingers to be at least [minSpanDp] apart so close-finger pinches do not produce huge angular spikes.
 * - While zooming (span changing), requires the angular change to dominate the radial pinch change so a pinch-zoom does
 *   not accidentally rotate the map.
 * - Re-anchors the baseline angle when a third finger touches or lifts so pointer-index shifts do not jump the angle.
 */
class RotationGesture(
    private val thresholdDeg: Double = THRESHOLD_DEG,
    private val minSpanPx: Double = 0.0,
) {
    private var started = false
    private var lastAngle = 0.0
    private var lastSpan = 0.0
    private var accumulated = 0.0
    var engaged = false
        private set

    fun start(angleDeg: Double, spanPx: Double = Double.MAX_VALUE) {
        started = true
        lastAngle = angleDeg
        lastSpan = spanPx
        accumulated = 0.0
        engaged = false
    }

    /** Re-anchors the current pointer angle without resetting [engaged] (e.g. when a 3rd pointer goes down or up). */
    fun reanchor(angleDeg: Double, spanPx: Double = Double.MAX_VALUE) {
        started = true
        lastAngle = angleDeg
        lastSpan = spanPx
    }

    /** Returns the clockwise rotation (degrees) to apply for the new pointer-pair angle and span. */
    fun update(angleDeg: Double, spanPx: Double = Double.MAX_VALUE): Double {
        if (!started) {
            start(angleDeg, spanPx)
            return 0.0
        }
        val delta = angleDelta(lastAngle, angleDeg)
        val spanDelta = if (lastSpan < Double.MAX_VALUE / 2 && spanPx < Double.MAX_VALUE / 2) abs(spanPx - lastSpan) else 0.0
        val avgSpan = if (lastSpan < Double.MAX_VALUE / 2 && spanPx < Double.MAX_VALUE / 2) max((lastSpan + spanPx) * 0.5, 1.0) else Double.MAX_VALUE
        lastAngle = angleDeg
        lastSpan = spanPx

        // Ignore single-frame pointer-swap or touch-glitch jumps (> 45 degrees in one frame) and too-close fingers.
        if (abs(delta) > MAX_STEP_DEG || spanPx < minSpanPx) return 0.0

        if (engaged) return delta

        // Before rotation is engaged, compare tangential arc length against radial pinch movement:
        // if the user is primarily pinching in/out (radial > tangential), bleed off accumulated accidental twist.
        if (avgSpan < Double.MAX_VALUE / 2 && spanDelta > 0.0) {
            val arcPx = abs(Math.toRadians(delta)) * avgSpan
            if (spanDelta > arcPx * PINCH_DOMINANCE_RATIO) {
                accumulated *= 0.5
                return 0.0
            }
        }

        // If the user reverses twist direction before breaking through the resistance, reset accumulation toward the new direction.
        if (accumulated != 0.0 && delta != 0.0 && (accumulated > 0) != (delta > 0)) {
            accumulated = (accumulated + delta).let { if ((it > 0) != (accumulated > 0)) it else it * 0.7 }
        } else {
            accumulated += delta
        }

        if (abs(accumulated) < thresholdDeg) return 0.0
        engaged = true
        return accumulated - Math.copySign(thresholdDeg, accumulated)
    }

    fun end() {
        started = false
        engaged = false
        accumulated = 0.0
    }

    companion object {
        const val THRESHOLD_DEG = 22.0
        const val MIN_SPAN_DP = 72.0
        private const val MAX_STEP_DEG = 45.0
        private const val PINCH_DOMINANCE_RATIO = 1.25

        /** Shortest signed difference to - from, in (-180, 180]. */
        fun angleDelta(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180.0) d -= 360.0 else if (d <= -180.0) d += 360.0
            return d
        }

        fun span(x0: Float, y0: Float, x1: Float, y1: Float): Double =
            hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
    }
}
