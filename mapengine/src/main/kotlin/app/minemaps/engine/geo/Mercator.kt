package app.minemaps.engine.geo

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

object Mercator {
    const val MAX_LAT = 85.05112878
    const val EARTH_CIRCUMFERENCE_M = 40075016.686
    const val BLOCK_ZOOM = 16
    const val WORLD_BLOCKS = 16777216.0

    fun clampLat(lat: Double): Double = lat.coerceIn(-MAX_LAT, MAX_LAT)

    fun lonToX(lon: Double): Double = (lon + 180.0) / 360.0

    fun latToY(lat: Double): Double {
        val r = Math.toRadians(clampLat(lat))
        return (1.0 - ln(tan(r) + 1.0 / cos(r)) / PI) / 2.0
    }

    fun xToLon(x: Double): Double = x * 360.0 - 180.0

    fun yToLat(y: Double): Double = Math.toDegrees(atan(sinh(PI * (1.0 - 2.0 * y))))

    fun metersPerBlock(lat: Double): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(clampLat(lat))) / WORLD_BLOCKS
}
