package app.minemaps.engine.geo

data class TileKey(val z: Int, val x: Int, val y: Int) {
    fun ancestor(zoom: Int): TileKey {
        require(zoom in 0..z) { "ancestor zoom $zoom out of range for z=$z" }
        val d = z - zoom
        return TileKey(zoom, x shr d, y shr d)
    }

    fun centerWorldX(): Double = (x + 0.5) / (1 shl z)

    fun centerWorldY(): Double = (y + 0.5) / (1 shl z)

    fun centerLat(): Double = Mercator.yToLat(centerWorldY())
}
