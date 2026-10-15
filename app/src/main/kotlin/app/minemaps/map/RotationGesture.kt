package app.minemaps.map

import kotlin.math.abs

/** Two-finger twist tracking: ignores the first [thresholdDeg] of accumulated twist, then follows the fingers 1:1. */
class RotationGesture(private val thresholdDeg: Double = THRESHOLD_DEG) {
    private var last = 0.0
    private var accumulated = 0.0
    var engaged = false
        private set

    fun start(angleDeg: Double) {
        last = angleDeg
        accumulated = 0.0
        engaged = false
    }

    /** Returns the clockwise rotation (degrees) to apply for the new pointer-pair angle. */
    fun update(angleDeg: Double): Double {
        val delta = angleDelta(last, angleDeg)
        last = angleDeg
        if (engaged) return delta
        accumulated += delta
        if (abs(accumulated) < thresholdDeg) return 0.0
        engaged = true
        return accumulated - Math.copySign(thresholdDeg, accumulated)
    }

    fun end() {
        engaged = false
        accumulated = 0.0
    }

    companion object {
        const val THRESHOLD_DEG = 12.0

        /** Shortest signed difference to - from, in (-180, 180]. */
        fun angleDelta(from: Double, to: Double): Double {
            var d = (to - from) % 360.0
            if (d > 180.0) d -= 360.0 else if (d <= -180.0) d += 360.0
            return d
        }
    }
}
