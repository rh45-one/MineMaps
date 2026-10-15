package app.minemaps.map

import app.minemaps.engine.geo.LatLng

class PlayerMarker(
    val lat: Double,
    val lon: Double,
    val accuracyM: Float,
    val bearingDeg: Float,
    val speedMps: Float,
)

data class Bookmark(val lat: Double, val lon: Double, val icon: String, val name: String)

class MapOverlays(
    val player: PlayerMarker? = null,
    val bookmarks: List<Bookmark> = emptyList(),
    val searchPin: LatLng? = null,
) {
    companion object {
        val EMPTY = MapOverlays()
    }
}
