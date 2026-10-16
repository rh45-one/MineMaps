package app.minemaps

import app.minemaps.data.BackupFormatException
import app.minemaps.data.SavedPlace
import app.minemaps.data.SavedPlacesBackup
import app.minemaps.data.SavedPlacesRepository
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackupTest {
    private fun place(name: String, lat: Double = 48.0, lon: Double = 2.0, icon: String = "pin") = SavedPlace("id-$name", name, lat, lon, 1_700_000_000_000L, icon)

    @Test
    fun roundTripKeepsPlaces() {
        val src = listOf(place("Home", icon = "bed"), place("Tower", 48.8584, 2.2945, "compass"))
        val text = SavedPlacesBackup.export(src, now = 1_800_000_000_000L)
        val r = SavedPlacesBackup.merge(emptyList(), text)
        assertEquals(2, r.imported)
        assertEquals(0, r.skipped)
        assertEquals(listOf("Home", "Tower"), r.places.map { it.name })
        assertEquals(listOf("bed", "compass"), r.places.map { it.icon })
        assertEquals(48.8584, r.places[1].lat, 1e-12)
        assertEquals(1_700_000_000_000L, r.places[0].createdAt)
        assertTrue(text.contains("\"app\": \"MineMaps\"") && text.contains("\"format\": 1"))
    }

    @Test
    fun duplicatesWithinOneMeterAreSkippedAndFurtherOnesKept() {
        val existing = listOf(place("Home"))
        val text = SavedPlacesBackup.export(listOf(place("Home", 48.0000001), place("Home", 48.001), place("Other")))
        val r = SavedPlacesBackup.merge(existing, text)
        assertEquals(2, r.imported)
        assertEquals(1, r.skipped)
        assertEquals(3, r.places.size)
        assertEquals(1, SavedPlacesBackup.merge(emptyList(), SavedPlacesBackup.export(listOf(place("A"), place("A")))).skipped)
    }

    @Test
    fun capsAtFiveHundred() {
        val existing = (0 until 498).map { place("P$it", 10.0 + it * 0.01) }
        val r = SavedPlacesBackup.merge(existing, SavedPlacesBackup.export((0 until 5).map { place("N$it", 20.0 + it) }))
        assertEquals(2, r.imported)
        assertEquals(3, r.skipped)
        assertEquals(SavedPlacesRepository.MAX_PLACES, r.places.size)
    }

    @Test
    fun malformedFilesGiveFriendlyErrors() {
        for (bad in listOf("", "garbage", "[]", "{}", """{"app":"Other","format":1,"places":[]}""", """{"app":"MineMaps","format":1}""", """{"app":"MineMaps","format":"x","places":[]}""", """{"app":"MineMaps","format":99,"places":[]}""")) {
            assertFailsWith<BackupFormatException>(bad) { SavedPlacesBackup.merge(emptyList(), bad) }
        }
        assertFailsWith<BackupFormatException> { SavedPlacesBackup.merge(emptyList(), " ".repeat(SavedPlacesBackup.MAX_BYTES + 1)) }
    }

    @Test
    fun hostileEntriesAreSanitisedOrSkipped() {
        val text = """{"app":"MineMaps","format":1,"places":[
            {"name":"  Ok\u0000\n place  ","lat":1.0,"lon":2.0,"icon":"../../etc","createdAt":-5},
            {"name":"NaN","lat":"NaN","lon":2.0},
            {"name":"Far","lat":91.0,"lon":2.0},
            {"name":"Far2","lat":1.0,"lon":181.0},
            {"name":"NoCoords"},
            "string", 5, null,
            {"name":"${"x".repeat(500)}","lat":3.0,"lon":4.0,"icon":"bell"},
            {"lat":5.0,"lon":6.0}
        ]}"""
        val r = SavedPlacesBackup.merge(emptyList(), text, now = 1_800_000_000_000L)
        assertEquals(3, r.imported)
        assertEquals(7, r.skipped)
        assertEquals("Ok place", r.places[0].name)
        assertEquals("pin", r.places[0].icon)
        assertEquals(1_800_000_000_000L, r.places[0].createdAt)
        assertEquals(80, r.places[1].name.length)
        assertEquals("bell", r.places[1].icon)
        assertEquals("Saved place", r.places[2].name)
    }
}
