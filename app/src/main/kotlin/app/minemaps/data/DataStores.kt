package app.minemaps.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

private fun corruptionHandler() = ReplaceFileCorruptionHandler<Preferences> { emptyPreferences() }

val Context.settingsStore by preferencesDataStore("settings", corruptionHandler = corruptionHandler())
val Context.savedPlacesStore by preferencesDataStore("saved_places", corruptionHandler = corruptionHandler())
val Context.cameraStore by preferencesDataStore("camera", corruptionHandler = corruptionHandler())

/** Preferences flow that turns read errors into empty (default) preferences instead of crashing collectors. */
fun DataStore<Preferences>.safeData(): Flow<Preferences> = data.catch { e ->
    if (e is IOException) emit(emptyPreferences()) else throw e
}
