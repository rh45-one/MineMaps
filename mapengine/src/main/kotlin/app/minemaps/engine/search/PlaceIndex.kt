package app.minemaps.engine.search

import app.minemaps.engine.geo.TileKey
import app.minemaps.engine.labels.Label
import app.minemaps.engine.labels.LabelExtractor
import app.minemaps.engine.labels.LabelKind
import app.minemaps.engine.labels.NameResolver
import app.minemaps.engine.labels.Poi
import app.minemaps.engine.mvt.VectorTile
import java.text.Normalizer
import kotlin.math.cos
import kotlin.math.hypot

enum class PlaceKind(val weight: Double, val label: String, val dedupMeters: Double) {
    COUNTRY(1.0, "Country", 5000.0),
    STATE(0.9, "Region", 5000.0),
    CITY(1.0, "City", 3000.0),
    TOWN(0.9, "Town", 3000.0),
    VILLAGE(0.75, "Village", 2000.0),
    HAMLET(0.6, "Hamlet", 1000.0),
    SUBURB(0.7, "Suburb", 1500.0),
    QUARTER(0.6, "Quarter", 1000.0),
    ISLAND(0.6, "Island", 3000.0),
    WATER(0.5, "Water", 1000.0),
    STREET(0.6, "Street", 300.0),
    POI(0.5, "Place", 300.0),
}

class PlaceHit(val name: String, val kindLabel: String, val kind: PlaceKind, val wx: Double, val wy: Double, val score: Double)

/** In-memory, bounded index of named features fed from decoded vector tiles; prefix search on normalised tokens. */
class PlaceIndex(private val maxEntries: Int = 50_000) {
    private class Entry(
        val en: String,
        val local: String?,
        val kind: PlaceKind,
        val kindLabel: String,
        val wx: Double,
        val wy: Double,
        val rank: Int,
        val enNorm: String,
        val enTokens: List<String>,
        val localNorm: String?,
        val localTokens: List<String>,
    )

    private val lock = Any()
    private var entries = ArrayList<Entry>()
    private val dedup = HashMap<String, MutableList<Entry>>()
    private var centerX = 0.5
    private var centerY = 0.5

    val size: Int get() = synchronized(lock) { entries.size }

    fun clear() = synchronized(lock) {
        entries = ArrayList()
        dedup.clear()
    }

    /** Entries closest to this world point survive when the index is full. */
    fun setCenter(wx: Double, wy: Double) = synchronized(lock) {
        centerX = wx
        centerY = wy
    }

    /** Index a decoded tile; [resolver] decides how names are resolved (normally English with transliteration); local names are kept too. */
    fun add(key: TileKey, tile: VectorTile, resolver: NameResolver) {
        val labels = LabelExtractor(resolver).extract(tile, key)
        add(labels.labels, labels.pois)
    }

    fun add(labels: List<Label>, pois: List<Poi>) {
        val entries = ArrayList<Entry>(labels.size + pois.size)
        for (l in labels) {
            val kind = when (l.kind) {
                LabelKind.COUNTRY -> PlaceKind.COUNTRY
                LabelKind.STATE -> PlaceKind.STATE
                LabelKind.CITY -> PlaceKind.CITY
                LabelKind.TOWN -> PlaceKind.TOWN
                LabelKind.VILLAGE -> PlaceKind.VILLAGE
                LabelKind.HAMLET -> PlaceKind.HAMLET
                LabelKind.SUBURB -> PlaceKind.SUBURB
                LabelKind.QUARTER, LabelKind.NEIGHBOURHOOD -> PlaceKind.QUARTER
                LabelKind.ISLAND -> PlaceKind.ISLAND
                LabelKind.WATER -> PlaceKind.WATER
                LabelKind.ROAD, LabelKind.ROAD_MINOR -> PlaceKind.STREET
                else -> continue
            }
            entries.add(entry(l.text, l.local, kind, kind.label, l.wx, l.wy, l.rank))
        }
        for (p in pois) if (p.name.isNotBlank()) entries.add(entry(p.name, p.local, PlaceKind.POI, p.kindLabel ?: PlaceKind.POI.label, p.wx, p.wy, p.rank))
        synchronized(lock) {
            for (e in entries) insert(e)
            if (this.entries.size > maxEntries) trim()
        }
    }

    private fun entry(en: String, local: String?, kind: PlaceKind, label: String, wx: Double, wy: Double, rank: Int): Entry {
        val enNorm = normalize(en)
        val localNorm = local?.let { normalize(it) }?.takeIf { it.isNotEmpty() && it != enNorm }
        return Entry(en, local?.takeIf { localNorm != null }, kind, label, wx, wy, rank, enNorm, enNorm.split(' '), localNorm, localNorm?.split(' ') ?: emptyList())
    }

    private fun insert(e: Entry) {
        if (e.enNorm.isEmpty()) return
        val list = dedup.getOrPut(e.enNorm) { ArrayList(1) }
        for (o in list) if (o.kind == e.kind && meters(o.wx, o.wy, e.wx, e.wy) < e.kind.dedupMeters) return
        list.add(e)
        entries.add(e)
    }

    private fun trim() {
        val keep = (maxEntries * 9 / 10)
        val sorted = entries.sortedBy { hypot(it.wx - centerX, it.wy - centerY) }
        entries = ArrayList(sorted.subList(0, keep))
        dedup.clear()
        for (e in entries) dedup.getOrPut(e.enNorm) { ArrayList(1) }.add(e)
    }

    fun query(text: String, centerWx: Double, centerWy: Double, localNames: Boolean = false, limit: Int = 8): List<PlaceHit> {
        val q = normalize(text)
        if (q.isEmpty()) return emptyList()
        val tokens = q.split(' ')
        val hits = ArrayList<PlaceHit>()
        synchronized(lock) {
            for (e in entries) {
                val m = maxOf(match(q, tokens, e.enNorm, e.enTokens), if (e.localNorm != null) match(q, tokens, e.localNorm, e.localTokens) else 0.0)
                if (m <= 0.0) continue
                val km = meters(e.wx, e.wy, centerWx, centerWy) / 1000.0
                val proximity = 0.4 + 0.6 / (1.0 + km / 10.0)
                val rankBonus = if (e.rank in 0..200) 1.0 - e.rank / 400.0 else 0.9
                hits.add(PlaceHit(if (localNames && e.local != null) e.local else e.en, e.kindLabel, e.kind, e.wx, e.wy, m * e.kind.weight * proximity * rankBonus))
            }
        }
        return hits.sortedByDescending { it.score }.take(limit)
    }

    private fun match(q: String, tokens: List<String>, norm: String, nameTokens: List<String>): Double {
        if (norm == q) return 4.0
        if (norm.startsWith(q)) return 3.0
        for (t in tokens) if (nameTokens.none { it.startsWith(t) }) return 0.0
        return 2.0
    }

    companion object {
        private const val EARTH_M = 40_075_016.0

        fun normalize(s: String): String {
            val d = Normalizer.normalize(s, Normalizer.Form.NFD)
            val sb = StringBuilder(d.length)
            var space = true
            for (ch in d) {
                val type = Character.getType(ch)
                if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) continue
                if (Character.isLetterOrDigit(ch)) {
                    sb.append(Character.toLowerCase(ch))
                    space = false
                } else if (!space) {
                    sb.append(' ')
                    space = true
                }
            }
            return sb.toString().trim()
        }

        fun meters(ax: Double, ay: Double, bx: Double, by: Double): Double {
            val lat = Math.atan(Math.sinh(Math.PI * (1 - 2 * (ay + by) / 2)))
            val dx = (ax - bx).let { if (it > 0.5) it - 1 else if (it < -0.5) it + 1 else it } * EARTH_M * cos(lat)
            return hypot(dx, (ay - by) * EARTH_M)
        }
    }
}
