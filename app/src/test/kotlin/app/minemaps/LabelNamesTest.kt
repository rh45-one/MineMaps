package app.minemaps

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import app.minemaps.data.LabelNames
import app.minemaps.data.SettingsRepository
import app.minemaps.map.AndroidTransliterator
import app.minemaps.search.PhotonParser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LabelNamesTest {
    private fun repo(): Pair<SettingsRepository, suspend (String?) -> Unit> {
        val file = Files.createTempDirectory("ds").resolve("s.preferences_pb").toFile()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file }
        return SettingsRepository(store) to { v -> store.edit { if (v == null) it.remove(stringPreferencesKey("label_names")) else it[stringPreferencesKey("label_names")] = v } }
    }

    @Test
    fun legacyAndUnknownValuesMapToEnglish() = runBlocking<Unit> {
        val (r, set) = repo()
        assertEquals(LabelNames.ENGLISH, r.settings.first().labelNames)
        for (v in listOf("LATIN", "garbage", "ENGLISH")) {
            set(v)
            assertEquals(LabelNames.ENGLISH, r.settings.first().labelNames, v)
        }
        set("LOCAL")
        assertEquals(LabelNames.LOCAL, r.settings.first().labelNames)
        r.setLabelNames(LabelNames.ENGLISH)
        assertEquals(LabelNames.ENGLISH, r.settings.first().labelNames)
    }

    @Test
    fun streetNamesSettingDefaultsToTrueAndPersists() = runBlocking<Unit> {
        val (r, _) = repo()
        assertTrue(r.settings.first().showStreetNames)
        r.setShowStreetNames(false)
        assertEquals(false, r.settings.first().showStreetNames)
    }

    @Test
    fun titleCaseCapitalisesEachWord() {
        assertEquals("Al Qahirah Al Jadidah", AndroidTransliterator.titleCase("al qahirah al jadidah"))
        assertEquals("", AndroidTransliterator.titleCase(""))
    }

    @Test
    fun photonResultsAreLatinizedInEnglishMode() {
        val json = """{"features":[{"geometry":{"coordinates":[31.2,30.0]},"properties":{"name":"القاهرة","city":"القاهرة","country":"مصر","state":"Cairo Governorate","type":"city","osm_value":"city"}}]}"""
        val r = PhotonParser.parse(json) { if (it.any { c -> c.code > 0x600 }) "L:${it.length}" else it }.single()
        assertEquals("L:7", r.name)
        assertEquals("Cairo Governorate, L:3", r.address)
    }
}
