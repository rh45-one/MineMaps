package app.minemaps.engine

import app.minemaps.engine.geo.Mercator
import kotlin.math.abs
import kotlin.math.floor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MercatorTest {
    private val eiffelLat = 48.8584
    private val eiffelLon = 2.2945

    @Test
    fun originMapsToCentre() {
        assertEquals(0.5, Mercator.lonToX(0.0), 1e-12)
        assertEquals(0.5, Mercator.latToY(0.0), 1e-12)
    }

    @Test
    fun eiffelTowerTilesAndWorldCoords() {
        val wx = Mercator.lonToX(eiffelLon)
        val wy = Mercator.latToY(eiffelLat)
        assertEquals(0.5063736, wx, 1e-7)
        assertEquals(0.3440208, wy, 1e-7)
        assertEquals(8296 to 5636, floor(wx * (1 shl 14)).toInt() to floor(wy * (1 shl 14)).toInt())
        assertEquals(33185 to 22545, floor(wx * (1 shl 16)).toInt() to floor(wy * (1 shl 16)).toInt())
    }

    @Test
    fun roundTrip() {
        for (lat in listOf(-80.0, -33.3, 0.0, 12.5, 48.8584, 84.0)) {
            for (lon in listOf(-179.9, -45.0, 0.0, 2.2945, 179.9)) {
                assertEquals(lat, Mercator.yToLat(Mercator.latToY(lat)), 1e-9)
                assertEquals(lon, Mercator.xToLon(Mercator.lonToX(lon)), 1e-9)
            }
        }
    }

    @Test
    fun metresPerBlock() {
        assertEquals(2.38866, Mercator.metersPerBlock(0.0), 1e-4)
        assertEquals(1.57155, Mercator.metersPerBlock(48.8584), 1e-4)
    }

    @Test
    fun latitudeIsClamped() {
        assertEquals(Mercator.latToY(Mercator.MAX_LAT), Mercator.latToY(89.9), 1e-12)
        assertEquals(Mercator.latToY(-Mercator.MAX_LAT), Mercator.latToY(-90.0), 1e-12)
        assertTrue(abs(Mercator.latToY(90.0)) < 1e-6)
        assertEquals(Mercator.MAX_LAT, Mercator.clampLat(100.0), 0.0)
        assertEquals(Mercator.MAX_LAT, Mercator.yToLat(0.0), 1e-6)
    }
}
