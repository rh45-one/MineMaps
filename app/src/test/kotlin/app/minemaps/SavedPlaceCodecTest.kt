package app.minemaps

import app.minemaps.data.SavedPlace
import app.minemaps.data.SavedPlaceCodec
import kotlin.test.Test
import kotlin.test.assertEquals

class SavedPlaceCodecTest {
    @Test
    fun roundTrip() {
        val places = listOf(
            SavedPlace("a", "Eiffel \"Tower\"", 48.8584, 2.2945, 1700000000000),
            SavedPlace("b", "Caf\u00e9 \u00e0 Lausanne", 46.5197, 6.6323, 1700000001000),
        )
        assertEquals(places, SavedPlaceCodec.decode(SavedPlaceCodec.encode(places)))
    }

    @Test
    fun badInputGivesEmptyList() {
        assertEquals(emptyList(), SavedPlaceCodec.decode(null))
        assertEquals(emptyList(), SavedPlaceCodec.decode(""))
        assertEquals(emptyList(), SavedPlaceCodec.decode("{not json"))
    }

    @Test
    fun ignoresUnknownFields() {
        val text = """[{"id":"x","name":"n","lat":1.0,"lon":2.0,"createdAt":3,"extra":true}]"""
        assertEquals(listOf(SavedPlace("x", "n", 1.0, 2.0, 3)), SavedPlaceCodec.decode(text))
    }

    @Test
    fun oldJsonWithoutIconDecodesToPin() {
        val old = """[{"id":"x","name":"n","lat":1.0,"lon":2.0,"createdAt":3}]"""
        assertEquals("pin", SavedPlaceCodec.decode(old).single().icon)
    }

    @Test
    fun iconRoundTrips() {
        val places = listOf(SavedPlace("a", "Home", 1.0, 2.0, 3, "bed"), SavedPlace("b", "Cake", 1.0, 2.0, 3, "cake"), SavedPlace("c", "Pin", 1.0, 2.0, 3))
        val back = SavedPlaceCodec.decode(SavedPlaceCodec.encode(places))
        assertEquals(places, back)
        assertEquals(listOf("bed", "cake", "pin"), back.map { it.icon })
    }
}
