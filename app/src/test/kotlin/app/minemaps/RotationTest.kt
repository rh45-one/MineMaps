package app.minemaps

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import app.minemaps.data.Camera
import app.minemaps.data.MapStateRepository
import app.minemaps.map.RotationGesture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RotationTest {
    @Test
    fun angleDeltaWrapsAround() {
        assertEquals(20.0, RotationGesture.angleDelta(170.0, -170.0), 1e-9)
        assertEquals(-20.0, RotationGesture.angleDelta(-170.0, 170.0), 1e-9)
        assertEquals(10.0, RotationGesture.angleDelta(0.0, 10.0), 1e-9)
        assertEquals(180.0, RotationGesture.angleDelta(0.0, 180.0), 1e-9)
    }

    @Test
    fun twistBelowThresholdNeverRotates() {
        val g = RotationGesture()
        g.start(0.0)
        var total = 0.0
        for (a in listOf(3.0, 6.0, 9.0, 11.0, 5.0, -8.0, 11.5)) total += g.update(a)
        assertEquals(0.0, total, 1e-9)
        assertEquals(false, g.engaged)
    }

    @Test
    fun afterThresholdFollowsFingersOneToOne() {
        val g = RotationGesture()
        g.start(0.0)
        assertEquals(0.0, g.update(6.0), 1e-9)
        assertEquals(4.0, g.update(16.0), 1e-9)
        assertEquals(true, g.engaged)
        assertEquals(5.0, g.update(21.0), 1e-9)
        assertEquals(-3.0, g.update(18.0), 1e-9)
        g.end()
        g.start(100.0)
        assertEquals(0.0, g.update(105.0), 1e-9)
        val n = RotationGesture()
        n.start(0.0)
        assertEquals(-3.0, n.update(-15.0), 1e-9)
        val w = RotationGesture()
        w.start(175.0)
        assertEquals(0.0, w.update(-177.0), 1e-9)
        assertEquals(false, w.engaged)
        assertEquals(5.0, w.update(-168.0), 1e-9)
    }

    @Test
    fun cameraBearingIsSanitised() {
        assertEquals(10.0, Camera.sanitized(1.0, 2.0, 3.0, 370.0)!!.bearing, 1e-9)
        assertEquals(350.0, Camera.sanitized(1.0, 2.0, 3.0, -10.0)!!.bearing, 1e-9)
        assertNull(Camera.sanitized(1.0, 2.0, 3.0, Double.NaN))
        assertEquals(0.0, Camera.sanitized(1.0, 2.0, 3.0)!!.bearing, 1e-9)
    }

    @Test
    fun bearingPersistsAndOldDataLoadsAsNorthUp() = runBlocking<Unit> {
        val file = Files.createTempDirectory("ds").resolve("c.preferences_pb").toFile()
        val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) { file }
        store.edit {
            it[doublePreferencesKey("lat")] = 48.0
            it[doublePreferencesKey("lon")] = 2.0
            it[doublePreferencesKey("zoom")] = 12.0
        }
        assertEquals(0.0, MapStateRepository(store).load()!!.bearing, 1e-9)
        MapStateRepository(store).persist(Camera(48.0, 2.0, 12.0, 123.5))
        assertEquals(123.5, MapStateRepository(store).load()!!.bearing, 1e-9)
        store.edit { it[doublePreferencesKey("bearing")] = Double.NaN }
        assertNull(MapStateRepository(store).load())
    }
}
