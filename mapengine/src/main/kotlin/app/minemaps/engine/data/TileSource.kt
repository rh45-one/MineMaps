package app.minemaps.engine.data

import app.minemaps.engine.geo.TileKey

interface TileSource {
    val maxZoom: Int get() = 14

    suspend fun fetch(key: TileKey): ByteArray?
}
