package app.minemaps.engine.style

enum class Block(val id: Int, val texture: String) {
    GRASS(0, "grass"),
    WATER(1, "water"),
    SAND(2, "sand"),
    FOREST(3, "leaves"),
    PARK(4, "park"),
    FARMLAND(5, "farmland"),
    WETLAND(6, "wetland"),
    SNOW(7, "snow"),
    ROCK(8, "stone"),
    INDUSTRIAL(9, "gravel"),
    CEMETERY(10, "grave"),
    ROAD_MAJOR(11, "asphalt"),
    ROAD(12, "stone"),
    ROAD_MINOR(13, "cobble"),
    PATH(14, "dirt_path"),
    PEDESTRIAN(15, "stone_bricks"),
    RAIL(16, "rail"),
    BUILDING(17, "roof_brick"),
    RUNWAY(18, "asphalt"),
    RESIDENTIAL(19, "grass");

    val byteId: Byte get() = id.toByte()

    companion object {
        private val byId = entries.toTypedArray()

        fun fromId(id: Int): Block = byId.getOrElse(id) { GRASS }

        val BUILDING_MATERIALS = listOf(
            "roof_brick", "roof_oak", "roof_spruce", "roof_stone", "roof_concrete", "roof_terracotta",
        )

        const val AUX_MATERIAL_MASK = 0x0f
        const val AUX_HEIGHT_SHIFT = 4

        fun auxMaterial(aux: Int): Int = aux and AUX_MATERIAL_MASK

        fun auxHeightBucket(aux: Int): Int = (aux ushr AUX_HEIGHT_SHIFT) and 0x0f
    }
}
