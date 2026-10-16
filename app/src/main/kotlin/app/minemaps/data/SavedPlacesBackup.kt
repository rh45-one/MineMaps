package app.minemaps.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.math.cos
import kotlin.math.hypot

class BackupFormatException(message: String) : Exception(message)

class ImportResult(val places: List<SavedPlace>, val imported: Int, val skipped: Int)

/** Export/import of saved places as JSON: {"app":"MineMaps","format":1,"exportedAt":...,"places":[{name,lat,lon,icon,createdAt}]}. */
object SavedPlacesBackup {
    const val MAX_BYTES = 2 * 1024 * 1024
    private const val FORMAT = 1
    private val json = Json { prettyPrint = true }
    private val parser = Json
    private val iconPattern = Regex("^[a-z0-9_]{1,32}$")

    fun export(places: List<SavedPlace>, now: Long = System.currentTimeMillis()): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("app", "MineMaps")
            put("format", FORMAT)
            put("exportedAt", now)
            put(
                "places",
                buildJsonArray {
                    for (p in places) add(
                        buildJsonObject {
                            put("name", p.name)
                            put("lat", p.lat)
                            put("lon", p.lon)
                            put("icon", p.icon)
                            put("createdAt", p.createdAt)
                        },
                    )
                },
            )
        },
    )

    fun merge(existing: List<SavedPlace>, text: String, now: Long = System.currentTimeMillis()): ImportResult {
        if (text.length > MAX_BYTES) throw BackupFormatException("The file is too large to be a MineMaps backup")
        val root = try {
            parser.parseToJsonElement(text) as? JsonObject
        } catch (e: Exception) {
            null
        } ?: throw BackupFormatException("This is not a MineMaps backup file")
        if ((root["app"] as? JsonPrimitive)?.contentOrNull != "MineMaps") throw BackupFormatException("This is not a MineMaps backup file")
        val format = (root["format"] as? JsonPrimitive)?.intOrNull
        if (format == null || format > FORMAT) throw BackupFormatException("This backup was made by a newer version of MineMaps")
        val items = root["places"] as? JsonArray ?: throw BackupFormatException("The backup contains no places list")
        val out = ArrayList(existing)
        var imported = 0
        var skipped = 0
        for (item in items) {
            val place = parsePlace(item, now)
            if (place == null || out.size >= SavedPlacesRepository.MAX_PLACES || out.any { duplicate(it, place) }) {
                skipped++
            } else {
                out.add(place)
                imported++
            }
        }
        return ImportResult(out, imported, skipped)
    }

    private fun parsePlace(e: JsonElement, now: Long): SavedPlace? {
        val o = e as? JsonObject ?: return null
        val lat = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lon = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: return null
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val name = SavedPlaceNames.clean((o["name"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "").ifBlank { SavedPlaceNames.DEFAULT }
        val icon = (o["icon"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { iconPattern.matches(it) } ?: SavedPlace.DEFAULT_ICON
        val created = (o["createdAt"] as? JsonPrimitive)?.longOrNull?.takeIf { it in 1..now + 86_400_000L } ?: now
        return SavedPlace(UUID.randomUUID().toString(), name, lat, lon, created, icon)
    }

    private fun duplicate(a: SavedPlace, b: SavedPlace): Boolean {
        if (a.name != b.name) return false
        val dx = (a.lon - b.lon) * 111_320.0 * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot(dx, (a.lat - b.lat) * 110_540.0) < 1.0
    }
}
