package app.minemaps.car

object StableArea {
    /**
     * Offset, in world units, to add to the camera centre so that the camera target is drawn at the
     * centre of the stable area instead of the surface centre.
     */
    fun worldOffset(
        surfaceWidth: Int,
        surfaceHeight: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int,
        worldSizePx: Double,
    ): DoubleArray {
        val dx = surfaceWidth / 2.0 - (left + right) / 2.0
        val dy = surfaceHeight / 2.0 - (top + bottom) / 2.0
        return doubleArrayOf(dx / worldSizePx, dy / worldSizePx)
    }

    /**
     * Computes the active unobscured map pane by intersecting [visibleArea] and [stableArea] when both are non-empty.
     * Falls back to [visibleArea] when the intersection is empty (e.g., stale [stableArea] during split-screen transition),
     * or to whichever rect is non-empty.
     */
    fun activeArea(visibleArea: android.graphics.Rect?, stableArea: android.graphics.Rect?): android.graphics.Rect? {
        val vis = visibleArea?.takeIf { !it.isEmpty }
        val stab = stableArea?.takeIf { !it.isEmpty }
        if (vis != null && stab != null) {
            val inter = android.graphics.Rect(vis)
            return if (inter.intersect(stab)) inter else vis
        }
        return vis ?: stab
    }
}
