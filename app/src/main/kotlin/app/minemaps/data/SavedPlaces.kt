package app.minemaps.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
data class SavedPlace(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val createdAt: Long,
    val icon: String = DEFAULT_ICON,
) {
    companion object {
        const val DEFAULT_ICON = "pin"
    }
}

class SavedPlacesFullException : RuntimeException("Saved places limit reached")

object SavedPlaceNames {
    const val MAX_LENGTH = 80
    const val DEFAULT = "Saved place"

    /** Strips control characters, trims, caps at 80 characters (never splitting a surrogate pair). */
    fun clean(name: String): String {
        val sb = StringBuilder(name.length)
        var i = 0
        while (i < name.length) {
            val cp = name.codePointAt(i)
            i += Character.charCount(cp)
            if (Character.isISOControl(cp) || cp == 0x2028 || cp == 0x2029) continue
            sb.appendCodePoint(cp)
        }
        val trimmed = sb.toString().trim()
        val s = if (trimmed.length <= MAX_LENGTH) trimmed else {
            var end = MAX_LENGTH
            if (Character.isHighSurrogate(trimmed[end - 1])) end--
            trimmed.substring(0, end).trim()
        }
        return s
    }
}

object SavedPlaceCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedPlace.serializer())

    fun encode(places: List<SavedPlace>): String = json.encodeToString(serializer, places)

    fun decode(text: String?): List<SavedPlace> {
        if (text.isNullOrBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: Exception) {
            emptyList()
        }
    }
}

class SavedPlacesRepository(private val store: DataStore<Preferences>) {
    companion object {
        const val MAX_PLACES = 500
    }

    private val key = stringPreferencesKey("places_json")

    val places: Flow<List<SavedPlace>> = store.safeData().map { SavedPlaceCodec.decode(it[key]) }

    suspend fun add(name: String, lat: Double, lon: Double, icon: String = SavedPlace.DEFAULT_ICON, now: Long = System.currentTimeMillis()): SavedPlace {
        require(lat.isFinite() && lon.isFinite()) { "invalid coordinates" }
        val place = SavedPlace(UUID.randomUUID().toString(), SavedPlaceNames.clean(name).ifBlank { SavedPlaceNames.DEFAULT }, lat, lon, now, icon)
        update { if (it.size >= MAX_PLACES) throw SavedPlacesFullException() else it + place }
        return place
    }

    suspend fun update(id: String, name: String, icon: String) {
        update { list -> list.map { if (it.id == id) it.copy(name = SavedPlaceNames.clean(name).ifBlank { it.name }, icon = icon) else it } }
    }

    suspend fun delete(id: String) {
        update { list -> list.filterNot { it.id == id } }
    }

    private suspend fun update(transform: (List<SavedPlace>) -> List<SavedPlace>) {
        store.edit { prefs -> prefs[key] = SavedPlaceCodec.encode(transform(SavedPlaceCodec.decode(prefs[key]))) }
    }

    suspend fun exportJson(): String = SavedPlacesBackup.export(places.first())

    /** Merges a backup file into the saved places; returns "imported / skipped" counts. */
    suspend fun importJson(text: String): ImportResult {
        var result: ImportResult? = null
        store.edit { prefs ->
            val r = SavedPlacesBackup.merge(SavedPlaceCodec.decode(prefs[key]), text)
            prefs[key] = SavedPlaceCodec.encode(r.places)
            result = r
        }
        return result!!
    }
}
