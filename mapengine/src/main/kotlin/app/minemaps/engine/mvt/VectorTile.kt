package app.minemaps.engine.mvt

enum class GeomType { POINT, LINE, POLYGON }

class Feature(
    val id: Long,
    val type: GeomType,
    val tags: Map<String, Any>,
    val geometry: List<IntArray>,
) {
    @Volatile
    private var cachedBounds: IntArray? = null

    fun bounds(): IntArray {
        cachedBounds?.let { return it }
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (g in geometry) {
            var i = 0
            while (i + 1 < g.size) {
                val x = g[i]
                val y = g[i + 1]
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
                i += 2
            }
        }
        val b = intArrayOf(minX, minY, maxX, maxY)
        cachedBounds = b
        return b
    }
}

class Layer(val name: String, val extent: Int, val features: List<Feature>)

class VectorTile(val layers: Map<String, Layer>) {
    /** Rough heap footprint (object headers, tag maps, geometry arrays); used to bound caches by bytes instead of count. */
    val estimatedBytes: Long by lazy(LazyThreadSafetyMode.PUBLICATION) {
        var total = 64L
        for (layer in layers.values) {
            total += 64
            for (f in layer.features) {
                total += FEATURE_OVERHEAD + f.tags.size * TAG_BYTES + f.geometry.size * ARRAY_OVERHEAD
                for (g in f.geometry) total += g.size * 4L
            }
        }
        total
    }

    companion object {
        private const val FEATURE_OVERHEAD = 200L
        private const val TAG_BYTES = 110L
        private const val ARRAY_OVERHEAD = 32L
        val EMPTY = VectorTile(emptyMap())
    }
}
