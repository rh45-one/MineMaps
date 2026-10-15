package app.minemaps.engine.search

import app.minemaps.engine.data.TileRepository
import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.geo.TileKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.yield
import kotlin.math.cos

/** Feeds the [PlaceIndex] (through the repository's decode hook) from z12 tiles around a point and from z14 tiles already cached. */
class PlacePrefetcher(private val repo: TileRepository) {
    @Volatile
    private var lastCenter: DoubleArray? = null

    fun needsPrefetch(lat: Double, lon: Double): Boolean {
        val c = lastCenter ?: return true
        return PlaceIndex.meters(Mercator.lonToX(lon), Mercator.latToY(lat), Mercator.lonToX(c[1]), Mercator.latToY(c[0])) > RECENTER_METERS
    }

    suspend fun prefetch(lat: Double, lon: Double, isOnline: () -> Boolean, radiusKm: Double = RADIUS_KM, z: Int = PREFETCH_ZOOM) {
        lastCenter = doubleArrayOf(lat, lon)
        for (key in tilesAround(lat, lon, radiusKm, z)) {
            if (!isOnline()) return
            try {
                repo.get(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            yield()
        }
    }

    suspend fun warmFromCache(lat: Double, lon: Double, radiusKm: Double = RADIUS_KM, max: Int = MAX_WARM_TILES) {
        val cx = Mercator.lonToX(lon)
        val cy = Mercator.latToY(lat)
        val keys = repo.cachedKeys(WARM_ZOOM)
            .filter { k -> PlaceIndex.meters((k.x + 0.5) / (1 shl k.z), (k.y + 0.5) / (1 shl k.z), cx, cy) <= radiusKm * 1000 }
            .sortedBy { k -> PlaceIndex.meters((k.x + 0.5) / (1 shl k.z), (k.y + 0.5) / (1 shl k.z), cx, cy) }
            .take(max)
        for (key in keys) {
            try {
                repo.get(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
            }
            yield()
        }
    }

    companion object {
        const val RADIUS_KM = 20.0
        const val PREFETCH_ZOOM = 12
        const val WARM_ZOOM = 14
        const val MAX_WARM_TILES = 300
        const val RECENTER_METERS = 10_000.0

        fun tilesAround(lat: Double, lon: Double, radiusKm: Double, z: Int): List<TileKey> {
            val n = 1 shl z
            val dLat = radiusKm / 111.32
            val dLon = radiusKm / (111.32 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
            val x0 = Math.floor(Mercator.lonToX((lon - dLon).coerceAtLeast(-180.0)) * n).toInt().coerceIn(0, n - 1)
            val x1 = Math.floor(Mercator.lonToX((lon + dLon).coerceAtMost(180.0)) * n).toInt().coerceIn(0, n - 1)
            val y0 = Math.floor(Mercator.latToY((lat + dLat).coerceAtMost(Camera85)) * n).toInt().coerceIn(0, n - 1)
            val y1 = Math.floor(Mercator.latToY((lat - dLat).coerceAtLeast(-Camera85)) * n).toInt().coerceIn(0, n - 1)
            val cx = Mercator.lonToX(lon) * n
            val cy = Mercator.latToY(lat) * n
            val halfTileM = 40_075_016.0 * cos(Math.toRadians(lat)) / n / 2
            val wx = cx / n
            val wy = cy / n
            return buildList { for (y in y0..y1) for (x in x0..x1) add(TileKey(z, x, y)) }
                .filter { PlaceIndex.meters((it.x + 0.5) / n, (it.y + 0.5) / n, wx, wy) <= radiusKm * 1000 + halfTileM }
                .sortedBy { Math.hypot(it.x + 0.5 - cx, it.y + 0.5 - cy) }
        }

        private const val Camera85 = 85.0
    }
}
