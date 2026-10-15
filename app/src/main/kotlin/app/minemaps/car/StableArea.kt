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
}
