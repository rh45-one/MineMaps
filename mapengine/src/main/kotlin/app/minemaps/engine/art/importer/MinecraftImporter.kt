package app.minemaps.engine.art.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.zip.ZipFile

fun interface PngDecoder {
    fun decode(bytes: ByteArray): Rgba
}

class ImportException(message: String) : Exception(message)

interface TextureSource {
    /** PNG bytes of `assets/minecraft/textures/<rel>.png`, or null. */
    fun read(rel: String): ByteArray?
}

/** A client jar or resource-pack zip; a pack may nest the assets folder inside one top-level folder. */
class ZipTextureSource(file: File) : TextureSource, AutoCloseable {
    private val zip = ZipFile(file)
    private val base: String

    init {
        val first = zip.entries().asSequence().map { it.name }.firstOrNull { PREFIX in it }
            ?: run {
                zip.close()
                throw ImportException("No $PREFIX inside: is this a Minecraft jar or resource pack?")
            }
        base = first.substring(0, first.indexOf(PREFIX))
    }

    override fun read(rel: String): ByteArray? {
        val name = (if (base.isNotEmpty()) base.trimEnd('/') + "/" + PREFIX + rel + ".png" else PREFIX + rel + ".png").trimStart('/')
        val entry = zip.getEntry(name) ?: return null
        if (entry.size > MAX_ENTRY_BYTES) return null
        return zip.getInputStream(entry).use { it.readNBytesCompat(MAX_ENTRY_BYTES) }
    }

    override fun close() = zip.close()

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (out.size() <= limit) {
            val n = read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    companion object {
        const val PREFIX = "assets/minecraft/textures/"
        private const val MAX_ENTRY_BYTES = 8 * 1024 * 1024
    }
}

class OriginalAtlas(val json: String, val atlas: Rgba)

class ImportOutput(val atlasPng: ByteArray, val atlasJson: String, val kept: List<String>)

/** Kotlin port of tools/art/import_minecraft.py (same recipes, same Pillow arithmetic). */
object MinecraftImporter {
    const val PINNED_VERSION = "26.3"
    const val PINNED_SHA1 = "e877b6a07acd633fb3bb475002175cec036e7b87"
    const val PINNED_SIZE = 41_483_720L
    const val IMPORTER_VERSION = 1
    const val PINNED_URL = "https://piston-data.mojang.com/v1/objects/$PINNED_SHA1/client.jar"

    private const val T = 16
    private const val COLS = 16
    private val WATER = intArrayOf(0x3F, 0x76, 0xE4)
    private val SWAMP_GRASS = intArrayOf(0x6A, 0x70, 0x39)

    private class Missing(val rel: String) : Exception(rel)

    private class Textures(val sources: List<TextureSource>, val decoder: PngDecoder) {
        fun has(rel: String) = sources.any { it.read(rel) != null }

        fun img(rel: String): Rgba {
            for (s in sources) {
                val data = s.read(rel) ?: continue
                var im = try {
                    decoder.decode(data)
                } catch (e: Exception) {
                    throw ImportException("Cannot decode $rel.png")
                }
                if ((rel.startsWith("block/") || rel.startsWith("item/")) && im.w != T) {
                    im = im.resizeBox(T, maxOf(T, im.h * T / im.w))
                }
                return im
            }
            throw Missing(rel)
        }
    }

    private fun frame(im: Rgba, i: Int = 0): Rgba {
        val n = maxOf(1, im.h / im.w)
        val k = Math.floorMod(i, n)
        return im.crop(0, k * im.w, im.w, (k + 1) * im.w).resizeNearest(T, T)
    }

    private fun tint(im: Rgba, rgb: IntArray, f: Double = 1.0): Rgba = Rgba(im.w, im.h, IntArray(im.px.size) { i ->
        val p = im.px[i]
        fun c(shift: Int, m: Int) = minOf(255, ((((p shr shift) and 255) * m) / 255.0 * f).toInt())
        (p and (0xFF shl 24)) or (c(16, rgb[0]) shl 16) or (c(8, rgb[1]) shl 8) or c(0, rgb[2])
    })

    private fun over(base: Rgba, top: Rgba): Rgba = frame(base).copy().also { it.alphaComposite(frame(top)) }

    private fun flatten(im: Rgba, bg: IntArray): Rgba {
        val out = im.fill((255 shl 24) or (bg[0] shl 16) or (bg[1] shl 8) or bg[2])
        out.alphaComposite(im)
        return out
    }

    private fun biome(colormap: Rgba, temp: Double, humidity: Double): IntArray {
        val hum = humidity * temp
        val p = colormap[((1 - temp) * (colormap.w - 1)).toInt(), ((1 - hum) * (colormap.h - 1)).toInt()]
        return intArrayOf((p shr 16) and 255, (p shr 8) and 255, p and 255)
    }

    private fun rotations(im0: Rgba, n: Int = 4): List<Rgba> {
        val im = frame(im0)
        return listOf(im, im.transposed(Rgba.Rotation.CCW90), im.transposed(Rgba.Rotation.R180), im.transposed(Rgba.Rotation.CW90)).take(n)
    }

    private fun scaleIcon(im0: Rgba): Rgba {
        var im = im0
        if (im.h > im.w) im = frame(im)
        if (im.w == T && im.h == T) return im
        if (im.w < T) {
            val k = T / im.w
            val big = im.resizeNearest(im.w * k, im.h * k)
            val out = Rgba(T, T)
            out.paste(big, (T - big.w) / 2, (T - big.h) / 2)
            return out
        }
        return im.resizeBox(T, T)
    }

    private fun centerX(im: Rgba): Rgba {
        val box = im.alphaBBox() ?: return im
        val shift = Math.rint(T / 2.0 - (box[0] + box[2]) / 2.0).toInt()
        val out = Rgba(im.w, im.h)
        out.paste(im, shift, 0)
        return out
    }

    fun import(sources: List<TextureSource>, original: OriginalAtlas, decoder: PngDecoder, onProgress: (Int) -> Unit = {}): ImportOutput {
        val tx = Textures(sources, decoder)
        val item = { n: String -> tx.img("item/$n") }
        val grassC = { biome(tx.img("colormap/grass"), 0.8, 0.4) }
        val parkC = { biome(tx.img("colormap/grass"), 0.7, 0.8) }
        val leafC = { biome(tx.img("colormap/foliage"), 0.7, 0.8) }
        val grass = { tint(frame(tx.img("block/grass_block_top")), grassC()) }
        val park = { tint(frame(tx.img("block/grass_block_top")), parkC()) }
        val leaves = {
            val c = leafC()
            flatten(tint(frame(tx.img("block/oak_leaves")), c), intArrayOf((c[0] * 0.35).toInt(), (c[1] * 0.35).toInt(), (c[2] * 0.35).toInt()))
        }
        val grave = {
            val g = grass().copy()
            g.paste(frame(tx.img("block/stone_bricks")).crop(0, 0, 4, 6), 6, 5)
            g
        }
        val roofStone = { tx.img(if (tx.has("block/polished_andesite")) "block/polished_andesite" else "block/stone") }
        val textures: Map<String, () -> List<Rgba>> = mapOf(
            "grass" to { rotations(grass()) },
            "park" to { rotations(park(), 2) + listOf(over(park(), tx.img("block/poppy")), over(park(), tx.img("block/dandelion"))) },
            "leaves" to { rotations(leaves(), 3) },
            "water" to { listOf(0, 5, 11).map { tint(frame(tx.img("block/water_still"), it), WATER) } },
            "sand" to { rotations(tx.img("block/sand"), 2) },
            "gravel" to { rotations(tx.img("block/gravel"), 2) },
            "stone" to { rotations(tx.img("block/smooth_stone"), 2) },
            "cobble" to { rotations(tx.img("block/cobblestone"), 2) },
            "asphalt" to { rotations(tx.img("block/gray_concrete"), 2) },
            "stone_bricks" to { listOf(frame(tx.img("block/stone_bricks")), frame(tx.img("block/cracked_stone_bricks"))) },
            "dirt_path" to { rotations(tx.img("block/dirt_path_top"), 2) },
            "farmland" to { listOf(over(tx.img("block/farmland_moist"), tx.img("block/wheat_stage3")), over(tx.img("block/farmland_moist"), tx.img("block/wheat_stage7"))) },
            "snow" to { rotations(tx.img("block/snow"), 2) },
            "wetland" to { listOf(frame(tx.img("block/mud")), tint(frame(tx.img("block/grass_block_top")), SWAMP_GRASS)) },
            "roof_brick" to { listOf(frame(tx.img("block/bricks"))) },
            "roof_oak" to { listOf(frame(tx.img("block/oak_planks"))) },
            "roof_spruce" to { listOf(frame(tx.img("block/spruce_planks"))) },
            "roof_stone" to { listOf(frame(roofStone())) },
            "roof_concrete" to { listOf(frame(tx.img("block/white_concrete"))) },
            "roof_terracotta" to { listOf(frame(tx.img("block/terracotta"))) },
            "rail" to { listOf(over(tx.img("block/gravel"), tx.img("block/rail"))) },
            "grave" to { listOf(grave()) },
        )
        val icons: Map<String, () -> Rgba> = mapOf(
            "apple" to { item("apple") }, "bread" to { item("bread") }, "meat" to { item("cooked_beef") },
            "emerald" to { item("emerald") }, "gold_ingot" to { item("gold_ingot") }, "coal" to { item("coal") },
            "potion" to { over(tint(item("potion_overlay"), intArrayOf(0xF8, 0x24, 0x23)), item("potion")) },
            "book" to { item("book") }, "sapling" to { tx.img("block/oak_sapling") },
            "minecart" to { item("minecart") }, "mug" to { item("honey_bottle") },
            "pickaxe" to { item("iron_pickaxe") }, "compass" to { item("compass_00") }, "bell" to { item("bell") },
            "fish" to { item("cod") }, "pin" to { tx.img("map/decorations/red_banner") },
            "player" to { centerX(scaleIcon(tx.img("map/decorations/player"))) }, "diamond" to { item("diamond") },
            "iron_ingot" to { item("iron_ingot") }, "redstone" to { item("redstone") },
            "golden_apple" to { item("golden_apple") }, "cake" to { item("cake") }, "cookie" to { item("cookie") },
            "map" to { if (tx.has("item/filled_map")) item("filled_map") else item("map") },
            "clock" to { item("clock_00") }, "sword" to { item("iron_sword") }, "axe" to { item("iron_axe") },
            "shovel" to { item("iron_shovel") }, "bow" to { item("bow") }, "torch" to { tx.img("block/torch") },
            "lantern" to { item("lantern") }, "poppy" to { tx.img("block/poppy") }, "boat" to { item("oak_boat") },
            "bucket" to { item("bucket") }, "star" to { item("nether_star") },
        )

        val orig = Json.parseToJsonElement(original.json).jsonObject
        fun origCell(c: JsonArray) = original.atlas.crop(c[0].jsonPrimitive.int * T, c[1].jsonPrimitive.int * T, c[0].jsonPrimitive.int * T + T, c[1].jsonPrimitive.int * T + T)
        val kept = ArrayList<String>()
        val cells = ArrayList<Rgba>()
        fun add(im: Rgba): JsonElement {
            cells.add(im)
            val i = cells.size - 1
            return buildJsonArray {
                add(JsonPrimitive(i % COLS))
                add(JsonPrimitive(i / COLS))
            }
        }

        val outTextures = LinkedHashMap<String, JsonElement>()
        val origTextures = orig["textures"]?.jsonObject ?: JsonObject(emptyMap())
        var step = 0
        val total = origTextures.size + (orig["icons"]?.jsonObject?.size ?: 0)
        for ((name, origCells) in origTextures) {
            val variants = try {
                (textures[name]?.invoke() ?: throw Missing("no recipe")).map { frame(it) }
            } catch (e: Missing) {
                kept.add("$name (${e.rel})")
                origCells.jsonArray.map { origCell(it.jsonArray) }
            }
            outTextures[name] = JsonArray(variants.map { add(it) })
            onProgress(++step * 100 / total)
        }
        while (cells.size % COLS != 0) cells.add(Rgba(T, T))
        val outIcons = LinkedHashMap<String, JsonElement>()
        val outAnchors = LinkedHashMap<String, JsonElement>()
        val origAnchors = orig["anchors"]?.jsonObject ?: JsonObject(emptyMap())
        for ((name, cell) in orig["icons"]?.jsonObject ?: JsonObject(emptyMap())) {
            var im: Rgba? = try {
                icons[name]?.let { scaleIcon(it()) }
            } catch (e: Missing) {
                kept.add("$name (${e.rel})")
                null
            }
            if (im == null) {
                im = origCell(cell.jsonArray)
                origAnchors[name]?.let { outAnchors[name] = it }
                if (name !in icons) kept.add("$name (no 2D texture in the game)")
            } else if (name == "pin") {
                outAnchors["pin"] = buildJsonArray {
                    add(JsonPrimitive(8))
                    add(JsonPrimitive(16))
                }
            }
            outIcons[name] = add(im)
            onProgress(++step * 100 / total)
        }

        val atlas = Rgba(COLS * T, ((cells.size + COLS - 1) / COLS) * T)
        cells.forEachIndexed { i, c -> atlas.paste(c, (i % COLS) * T, (i / COLS) * T) }
        val meta = LinkedHashMap<String, JsonElement>()
        for ((k, v) in orig) if (k != "textures" && k != "icons" && k != "anchors") meta[k] = v
        meta["textures"] = JsonObject(outTextures)
        meta["icons"] = JsonObject(outIcons)
        meta["anchors"] = JsonObject(outAnchors)
        return ImportOutput(PngWriter.encode(atlas), Json { prettyPrint = true; prettyPrintIndent = " " }.encodeToString(JsonObject.serializer(), JsonObject(meta)), kept)
    }

    fun sourceText(desc: List<String>, kept: List<String>): String =
        "Imported from: " + desc.joinToString("; ") + "\n" +
            "Minecraft textures (c) Mojang Studios. Personal use only: do not commit or distribute this folder or any APK built with it.\n" +
            "Kept MineMaps art for: " + (kept.joinToString(", ").ifEmpty { "none" }) + "\n"
}
