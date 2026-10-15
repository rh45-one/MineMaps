package app.minemaps.engine

import app.minemaps.engine.labels.LabelFilter
import app.minemaps.engine.labels.Label
import app.minemaps.engine.labels.LabelKind
import app.minemaps.engine.labels.NameResolver
import app.minemaps.engine.labels.NameResolver.Mode
import app.minemaps.engine.labels.Transliterator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NameResolverTest {
    private val calls = AtomicInteger()
    private val fake = Transliterator { calls.incrementAndGet(); "T(${it.length})" }
    private val en = NameResolver(Mode.ENGLISH, fake)

    @Test
    fun isLatinByScript() {
        assertTrue(NameResolver.isLatin("Müller-Straße 12"))
        assertTrue(NameResolver.isLatin("Łódź"))
        assertTrue(NameResolver.isLatin("123 - ()"))
        assertFalse(NameResolver.isLatin("القاهرة"))
        assertFalse(NameResolver.isLatin("Москва"))
        assertFalse(NameResolver.isLatin("北京 Beijing"))
    }

    @Test
    fun localModeUsesName() {
        assertEquals("القاهرة", NameResolver(Mode.LOCAL).resolve(mapOf("name" to "القاهرة", "name:en" to "Cairo")))
        assertNull(NameResolver(Mode.LOCAL).resolve(mapOf("name:en" to "Cairo")))
    }

    @Test
    fun englishFieldWins() {
        assertEquals("Cairo", en.resolve(mapOf("name" to "القاهرة", "name:en" to "Cairo", "name_en" to "Other")))
    }

    @Test
    fun nameEnUsedOnlyWhenLatin() {
        assertEquals("Zh", en.resolve(mapOf("name" to "Ж", "name_en" to "Zh")))
        assertEquals("T(7)", en.resolve(mapOf("name" to "الفيماء", "name_en" to "الفيماء", "name_int" to "الفيماء")))
    }

    @Test
    fun nameIntListIsCutForPlacesOnly() {
        val tags = mapOf("name" to "السلوم", "name_int" to "El Sellûm, Al Sellum, Al Sollum, Sellum, Sollum, Salum")
        assertEquals("El Sellûm", en.resolve(tags, isPlace = true))
        assertEquals("El Sellûm, Al Sellum, Al Sollum, Sellum, Sollum, Salum", en.resolve(tags, isPlace = false))
    }

    @Test
    fun latinNameThenLatinTagThenMixedThenTransliteration() {
        assertEquals("Paris", en.resolve(mapOf("name" to "Paris")))
        assertEquals("Nur", en.resolve(mapOf("name" to "نور", "name:latin" to "Nur")))
        assertEquals("Ajdabiya", en.resolve(mapOf("name" to "ⴰⵊⴷⴰⴱⵉⵢⴰ إجدابيا Ajdabiya")))
        assertEquals("Rue Foo", en.resolve(mapOf("name" to "شارع Rue Foo 12 بيت")))
        assertEquals("T(4)", en.resolve(mapOf("name" to "نور١")))
        assertEquals("نور", NameResolver(Mode.ENGLISH).resolve(mapOf("name" to "نور")))
    }

    @Test
    fun blanksAreSkippedAndMissingNameIsNull() {
        assertEquals("Cairo", en.resolve(mapOf("name:en" to "  ", "name_en" to "", "name" to "Cairo")))
        assertNull(en.resolve(mapOf("name:en" to " ")))
        assertNull(en.resolve(emptyMap()))
    }

    @Test
    fun transliterationIsCachedAndFailuresFallBack() {
        calls.set(0)
        repeat(5) { en.resolve(mapOf("name" to "بغداد")) }
        assertEquals(1, calls.get())
        val failing = NameResolver(Mode.ENGLISH) { throw IllegalStateException("icu") }
        assertEquals("بغداد", failing.resolve(mapOf("name" to "بغداد")))
        val nulls = NameResolver(Mode.ENGLISH) { null }
        assertEquals("بغداد", nulls.resolve(mapOf("name" to "بغداد")))
    }

    @Test
    fun streetNameFilterHidesOnlyRoads() {
        fun l(k: LabelKind) = Label("x", k, 1, 0.5, 0.5, 0.0, 10)
        val all = LabelKind.entries.map { l(it) }
        assertEquals(all.size, LabelFilter.apply(all, true).size)
        val hidden = LabelFilter.apply(all, false)
        assertEquals(all.size - 2, hidden.size)
        assertTrue(hidden.none { it.kind == LabelKind.ROAD || it.kind == LabelKind.ROAD_MINOR })
    }
}
