package app.minemaps.engine

import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.labels.LabelExtractor
import app.minemaps.engine.labels.NameResolver
import app.minemaps.engine.labels.NameResolver.Mode
import app.minemaps.engine.labels.Transliterator
import app.minemaps.engine.mvt.MvtDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Fixtures are real OpenFreeMap planet tiles (https://tiles.openfreemap.org/planet/20261011_080001_pt/{z}/{x}/{y}.pbf), fetched 2026-10-15. */
class NamesFixtureTest {
    private val fixtures = listOf(
        Triple("egypt_10_600_430", TileKey(10, 600, 430), "Cairo area"),
        Triple("cairo_region_6_37_26", TileKey(6, 37, 26), "Egypt/Libya region"),
        Triple("moscow_10_619_321", TileKey(10, 619, 321), "Moscow"),
        Triple("beijing_10_843_388", TileKey(10, 843, 388), "Beijing"),
    )

    @Test
    fun englishModeProducesOnlyLatinLabelsAndPoiNames() {
        var native = 0
        var transliterated = 0
        var total = 0
        val resolverCalls = Transliterator { "T(${it.length})" }
        for ((file, key, what) in fixtures) {
            val bytes = javaClass.getResourceAsStream("/names/$file.pbf")!!.readBytes()
            val tile = MvtDecoder.decode(bytes)
            val labels = LabelExtractor(NameResolver(Mode.ENGLISH, resolverCalls)).extract(tile, key)
            val withoutIcu = LabelExtractor(NameResolver(Mode.ENGLISH)).extract(tile, key)
            val texts = labels.labels.map { it.text } + labels.pois.map { it.name }.filter { it.isNotEmpty() }
            assertTrue(texts.isNotEmpty(), "$what: no labels")
            texts.filterNot { NameResolver.isLatin(it) }.also { assertEquals(emptyList<String>(), it, "$what has non-Latin labels") }
            val stillLocal = (withoutIcu.labels.map { it.text } + withoutIcu.pois.map { it.name }.filter { it.isNotEmpty() }).count { !NameResolver.isLatin(it) }
            total += texts.size
            transliterated += texts.count { it.startsWith("T(") }
            native += texts.size - texts.count { it.startsWith("T(") }
            println("NAMES $what: ${texts.size} labels/poi names, ${texts.count { it.startsWith("T(") }} transliterated, $stillLocal non-Latin without transliterator")
        }
        println("NAMES total=$total real-latin/english=$native transliterated=$transliterated")
    }

    @Test
    fun localModeKeepsLocalScript() {
        val bytes = javaClass.getResourceAsStream("/names/moscow_10_619_321.pbf")!!.readBytes()
        val labels = LabelExtractor(NameResolver(Mode.LOCAL)).extract(MvtDecoder.decode(bytes), TileKey(10, 619, 321))
        assertTrue(labels.labels.any { !NameResolver.isLatin(it.text) })
    }
}
