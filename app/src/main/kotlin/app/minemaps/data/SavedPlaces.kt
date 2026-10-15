package app.minemaps.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
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
    private val key = stringPreferencesKey("places_json")

    val places: Flow<List<SavedPlace>> = store.data.map { SavedPlaceCodec.decode(it[key]) }

    suspend fun add(name: String, lat: Double, lon: Double, icon: String = SavedPlace.DEFAULT_ICON, now: Long = System.currentTimeMillis()): SavedPlace {
        val place = SavedPlace(UUID.randomUUID().toString(), name.ifBlank { "Saved place" }, lat, lon, now, icon)
        update { it + place }
        return place
    }

    suspend fun update(id: String, name: String, icon: String) {
        update { list -> list.map { if (it.id == id) it.copy(name = name.ifBlank { it.name }, icon = icon) else it } }
    }

    suspend fun delete(id: String) {
        update { list -> list.filterNot { it.id == id } }
    }

    private suspend fun update(transform: (List<SavedPlace>) -> List<SavedPlace>) {
        store.edit { prefs -> prefs[key] = SavedPlaceCodec.encode(transform(SavedPlaceCodec.decode(prefs[key]))) }
    }
}
