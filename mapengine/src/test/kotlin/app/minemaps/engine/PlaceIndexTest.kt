package app.minemaps.engine

import app.minemaps.engine.geo.Mercator
import app.minemaps.engine.labels.Label
import app.minemaps.engine.labels.LabelKind
import app.minemaps.engine.labels.Poi
import app.minemaps.engine.search.PlaceIndex
import app.minemaps.engine.search.PlaceKind
import app.minemaps.engine.search.PlacePrefetcher
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaceIndexTest {
    private val px = Mercator.lonToX(2.3522)
    private val py = Mercator.latToY(48.8566)

    private fun label(text: String, kind: LabelKind, dxM: Double = 0.0, dyM: Double = 0.0, rank: Int = 10, local: String? = null) =
        Label(text, kind, rank, px + dxM / 40_075_016.0 / 0.658, py + dyM / 40_075_016.0, 0.0, 0, local = local)

    @Test
    fun normalisationStripsAccentsCaseAndPunctuation() {
        assertEquals("el sellum", PlaceIndex.normalize("El Sell\u00fbm"))
        assertEquals("rue de l eglise", PlaceIndex.normalize("  Rue de l'\u00c9glise!! "))
        assertEquals("zurich", PlaceIndex.normalize("Z\u00dcRICH".lowercase().replace("\u00fc", "u\u0308")))
        assertEquals("", PlaceIndex.normalize("---"))
    }

    @Test
    fun prefixMatchingNeedsEveryQueryTokenAndWorksOnEitherName() {
        val idx = PlaceIndex()
        idx.add(listOf(label("Rue de Rivoli", LabelKind.ROAD), label("Munich", LabelKind.CITY, local = "M\u00fcnchen"), label("Rue Saint-Antoine", LabelKind.ROAD)), emptyList())
        fun q(s: String, local: Boolean = false) = idx.query(s, px, py, local).map { it.name }
        assertEquals(listOf("Rue de Rivoli"), q("rue riv"))
        assertEquals(listOf("Rue de Rivoli"), q("RIVOLI r"))
        assertEquals(setOf("Rue de Rivoli", "Rue Saint-Antoine"), q("rue").toSet())
        assertEquals(emptyList(), q("rue xyz"))
        assertEquals(listOf("Munich"), q("mun"))
        assertEquals(listOf("M\u00fcnchen"), q("munc", local = true))
        assertEquals(listOf("Munich"), q("munch"))
    }

    @Test
    fun exactBeatsPrefixAndNearBeatsFarForEqualNames() {
        val idx = PlaceIndex()
        idx.add(
            listOf(
                label("Paris Road", LabelKind.ROAD, dxM = 100.0),
                label("Paris", LabelKind.CITY, dxM = 100.0),
                label("Rue Victor Hugo", LabelKind.ROAD, dxM = 200_000.0),
                label("Rue Victor Hugo", LabelKind.ROAD, dxM = 500.0, dyM = 3000.0),
            ),
            emptyList(),
        )
        assertEquals(listOf("Paris", "Paris Road"), idx.query("paris", px, py).map { it.name })
        val hugo = idx.query("victor hugo", px, py)
        assertEquals(2, hugo.size)
        assertTrue(hugo[0].wy - py < 0.001 && Math.abs(hugo[0].wx - px) < 0.001, "the nearby street must come first")
    }

    @Test
    fun streetsAreDeduplicatedWithin300MetersButNotFurther() {
        val idx = PlaceIndex()
        idx.add(listOf(label("Rue Nord", LabelKind.ROAD), label("Rue Nord", LabelKind.ROAD, dxM = 120.0), label("Rue Nord", LabelKind.ROAD, dyM = 250.0)), emptyList())
        assertEquals(1, idx.size)
        idx.add(listOf(label("Rue Nord", LabelKind.ROAD, dyM = 2000.0)), emptyList())
        assertEquals(2, idx.size)
        idx.add(emptyList(), listOf(Poi("meat", "Chez Paul", px, py, 16), Poi("meat", "", px, py, 16)))
        assertEquals(3, idx.size)
        assertEquals(PlaceKind.POI, idx.query("chez", px, py).single().kind)
    }

    @Test
    fun indexIsBoundedAndKeepsEntriesNearestTheCentre() {
        val idx = PlaceIndex(maxEntries = 1000)
        idx.setCenter(px, py)
        val labels = (0 until 3000).map { i -> label("Street $i", LabelKind.ROAD, dxM = (i * 50.0), dyM = 0.0) }
        idx.add(labels, emptyList())
        assertTrue(idx.size <= 1000, "size ${idx.size}")
        assertTrue(idx.query("street 5", px, py).isNotEmpty())
        assertTrue(idx.query("street 2999", px, py).isEmpty())
    }

    @Test
    fun queryOver50kEntriesIsFast() {
        val idx = PlaceIndex(maxEntries = 50_000)
        val rnd = java.util.Random(1)
        val words = listOf("rue", "avenue", "boulevard", "place", "chemin", "impasse", "quai", "route", "allee", "cours")
        val names = listOf("victor", "hugo", "jean", "jaures", "pasteur", "gambetta", "lafayette", "voltaire", "moliere", "rivoli", "saint", "antoine")
        idx.add((0 until 50_000).map { i -> label("${words[rnd.nextInt(words.size)]} ${names[rnd.nextInt(names.size)]} ${names[rnd.nextInt(names.size)]} $i", LabelKind.ROAD, dxM = rnd.nextDouble() * 30_000, dyM = rnd.nextDouble() * 30_000) }, emptyList())
        assertTrue(idx.size > 40_000, "indexed ${idx.size}")
        repeat(5) { idx.query("rue vic", px, py) }
        val t0 = System.nanoTime()
        repeat(20) { idx.query("avenue jean hug", px, py) }
        val ms = (System.nanoTime() - t0) / 20 / 1e6
        println("PlaceIndex query over ${idx.size} entries: %.1f ms".format(ms))
        assertTrue(ms < 100, "query took $ms ms")
    }

    @Test
    fun prefetchCoversTwentyKilometresWithAboutThirtyTiles() {
        val tiles = PlacePrefetcher.tilesAround(48.8566, 2.3522, 20.0, 12)
        println("z12 tiles for 20 km around Paris: ${tiles.size}")
        assertTrue(tiles.size in 25..40, "tiles ${tiles.size}")
        val first = tiles.first()
        assertEquals(12, first.z)
        assertTrue(tiles.all { it.z == 12 })
    }

    @Test
    fun landmarksAndImportantPoisOutrankNearbyGiftShops() {
        val idx = PlaceIndex()
        fun poi(name: String, label: String, dxM: Double, rank: Int) = Poi("compass", name, px + dxM / 40_075_016.0 / 0.658, py, 16, rank = rank, kindLabel = label)
        idx.add(
            emptyList(),
            listOf(
                poi("Eiffel shopping", "Gift", 20.0, 40),
                poi("Eiffel techno", "Gift", 30.0, Int.MAX_VALUE),
                poi("Eiffel Tower", "Monument", 900.0, 3),
                poi("Hotel Eiffel Blomet", "Hotel", 100.0, 20),
            ),
        )
        val names = idx.query("eiffel", px, py).map { it.name }
        assertEquals("Eiffel Tower", names.first())
        assertTrue(names.indexOf("Eiffel Tower") < names.indexOf("Hotel Eiffel Blomet"))
    }
}
