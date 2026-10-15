package app.minemaps

import android.content.Context
import androidx.car.app.validation.HostValidator
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.test.core.app.ApplicationProvider
import app.minemaps.car.MineMapsCarAppService
import app.minemaps.data.Camera
import app.minemaps.data.MapStateRepository
import app.minemaps.data.SavedPlaceNames
import app.minemaps.data.SavedPlacesFullException
import app.minemaps.data.SavedPlacesRepository
import app.minemaps.data.Settings
import app.minemaps.data.SettingsRepository
import app.minemaps.data.cameraStore
import app.minemaps.data.savedPlacesStore
import app.minemaps.data.settingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HardeningTest {
    private fun tempStore(): DataStore<Preferences> {
        val file = Files.createTempDirectory("ds").resolve("t.preferences_pb").toFile()
        return PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file }
    }

    @Test
    fun corruptPreferenceFilesFallBackToDefaults() = runBlocking<Unit> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (name in listOf("settings", "saved_places", "camera")) {
            val f = context.preferencesDataStoreFile(name)
            f.parentFile!!.mkdirs()
            f.writeBytes(ByteArray(200) { (it * 37 + 11).toByte() })
        }
        assertEquals(Settings(), SettingsRepository(context.settingsStore).settings.first())
        assertEquals(emptyList(), SavedPlacesRepository(context.savedPlacesStore).places.first())
        assertNull(MapStateRepository(context.cameraStore).load())
        val repo = SavedPlacesRepository(context.savedPlacesStore)
        repo.add("after corruption", 1.0, 2.0)
        assertEquals(1, repo.places.first().size)
    }

    @Test
    fun savedPlaceNamesAreCleaned() {
        assertEquals("Hello World", SavedPlaceNames.clean("  Hello\u0000 \u0007World\n\t  "))
        assertEquals("", SavedPlaceNames.clean("\u0001\u0002 "))
        assertEquals(80, SavedPlaceNames.clean("x".repeat(500)).length)
        val emoji = "\uD83D\uDE00".repeat(60)
        val cut = SavedPlaceNames.clean(emoji)
        assertTrue(cut.length <= 80)
        assertTrue(cut.all { !Character.isSurrogate(it) } || cut.codePointCount(0, cut.length) * 2 == cut.length)
        assertEquals("a b", SavedPlaceNames.clean("a b\u2028"))
    }

    @Test
    fun repositoryCleansNamesAndCapsCount() = runBlocking<Unit> {
        val repo = SavedPlacesRepository(tempStore())
        val p = repo.add("  Bad\u0000Name\n" + "y".repeat(200), 10.0, 20.0)
        assertEquals(80, p.name.length)
        assertTrue(p.name.none { Character.isISOControl(it) })
        assertEquals(SavedPlaceNames.DEFAULT, repo.add("\u0000\u0001", 1.0, 1.0).name)
        repo.update(p.id, "  renamed\u0000 ", "bed")
        assertEquals("renamed", repo.places.first().first { it.id == p.id }.name)
        assertFailsWith<IllegalArgumentException> { repo.add("nan", Double.NaN, 0.0) }
        val full = SavedPlacesRepository(tempStore())
        repeat(SavedPlacesRepository.MAX_PLACES) { full.add("p$it", 1.0, 1.0) }
        assertEquals(SavedPlacesRepository.MAX_PLACES, full.places.first().size)
        assertFailsWith<SavedPlacesFullException> { full.add("one too many", 1.0, 1.0) }
        assertEquals(SavedPlacesRepository.MAX_PLACES, full.places.first().size)
        full.delete(full.places.first().first().id)
        full.add("fits again", 1.0, 1.0)
    }

    @Test
    fun persistedGarbageCameraFallsBackToNull() = runBlocking<Unit> {
        val store = tempStore()
        val repo = MapStateRepository(store)
        for ((lat, lon, zoom) in listOf(
            Triple(Double.NaN, 0.0, 5.0), Triple(10.0, Double.POSITIVE_INFINITY, 5.0), Triple(10.0, 10.0, Double.NaN),
            Triple(95.0, 0.0, 5.0), Triple(10.0, 400.0, 5.0), Triple(10.0, 10.0, -3.0), Triple(10.0, 10.0, 99.0),
        )) {
            store.edit {
                it[doublePreferencesKey("lat")] = lat
                it[doublePreferencesKey("lon")] = lon
                it[doublePreferencesKey("zoom")] = zoom
            }
            assertNull(MapStateRepository(store).load(), "camera $lat,$lon,$zoom")
        }
        repo.persist(Camera(Double.NaN, 0.0, 3.0))
        assertNull(repo.current)
        store.edit {
            it[doublePreferencesKey("lat")] = 89.9
            it[doublePreferencesKey("lon")] = 10.0
            it[doublePreferencesKey("zoom")] = 12.0
        }
        assertEquals(Camera.MAX_LAT, assertNotNull(MapStateRepository(store).load()).lat, 1e-9)
    }

    @Test
    fun cameraSanitizeRules() {
        assertNull(Camera.sanitized(Double.NaN, 0.0, 1.0))
        assertNull(Camera.sanitized(0.0, 0.0, Double.POSITIVE_INFINITY))
        assertEquals(Camera(Camera.MAX_LAT, 5.0, 3.0), Camera.sanitized(90.0, 5.0, 3.0))
        assertEquals(Camera(-Camera.MAX_LAT, 5.0, 3.0), Camera.sanitized(-90.0, 5.0, 3.0))
        assertEquals(Camera(48.0, 2.0, 16.0), Camera.sanitized(48.0, 2.0, 16.0))
    }

    @Test
    fun releaseHostValidatorIsNotAllowAll() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertTrue(MineMapsCarAppService.hostValidator(context, debug = true) === HostValidator.ALLOW_ALL_HOSTS_VALIDATOR)
        assertTrue(MineMapsCarAppService.hostValidator(context, debug = false) !== HostValidator.ALLOW_ALL_HOSTS_VALIDATOR)
    }
}
