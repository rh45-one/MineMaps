package app.minemaps.data

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.settingsStore by preferencesDataStore("settings")
val Context.savedPlacesStore by preferencesDataStore("saved_places")
val Context.cameraStore by preferencesDataStore("camera")
