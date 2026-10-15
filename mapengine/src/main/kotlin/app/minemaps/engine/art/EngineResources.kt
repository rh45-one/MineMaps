package app.minemaps.engine.art

import java.io.InputStream

enum class TexturePack(val resourceDir: String) {
    ORIGINAL(""),
    VANILLA("packs/vanilla/"),
}

object EngineResources {
    private const val BASE = "minemaps/"

    private fun classLoader() = EngineResources::class.java.classLoader

    fun open(path: String): InputStream =
        classLoader().getResourceAsStream(BASE + path) ?: error("missing engine resource $BASE$path")

    fun isAvailable(pack: TexturePack): Boolean =
        classLoader().getResource(BASE + pack.resourceDir + "atlas.json") != null &&
            classLoader().getResource(BASE + pack.resourceDir + "atlas.png") != null

    fun atlasJson(pack: TexturePack = TexturePack.ORIGINAL): String =
        open(pack.resourceDir + "atlas.json").use { String(it.readBytes(), Charsets.UTF_8) }

    fun atlasPng(pack: TexturePack = TexturePack.ORIGINAL): ByteArray =
        open(pack.resourceDir + "atlas.png").use { it.readBytes() }

    fun fontStream(): InputStream = open("fonts/Monocraft.ttf")
}
