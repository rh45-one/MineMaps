package app.minemaps.engine.data

import app.minemaps.engine.geo.TileKey

interface TileSource {
    val maxZoom: Int get() = 14

    suspend fun fetch(key: TileKey): ByteArray?

    /** Tiles of zoom [z] already stored locally (cheap listing, no network). */
    fun cachedKeys(z: Int): List<TileKey> = emptyList()
}
