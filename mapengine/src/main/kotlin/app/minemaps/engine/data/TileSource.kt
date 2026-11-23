package app.minemaps.engine.data

import app.minemaps.engine.geo.TileKey

interface TileSource {
    val maxZoom: Int get() = 14

    /** True if this source distinguishes live network responses from local cache hits via [onNetworkSuccess]. */
    val reportsNetworkSuccess: Boolean get() = false

    var onNetworkSuccess: (() -> Unit)?
        get() = null
        set(_) {}

    suspend fun fetch(key: TileKey): ByteArray?

    /** Tiles of zoom [z] already stored locally (cheap listing, no network). */
    fun cachedKeys(z: Int): List<TileKey> = emptyList()
}
