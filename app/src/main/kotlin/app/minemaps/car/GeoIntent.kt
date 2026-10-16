package app.minemaps.car

import java.net.URLDecoder

sealed interface GeoTarget {
    data class Place(val lat: Double, val lon: Double, val label: String?) : GeoTarget

    data class Query(val text: String) : GeoTarget
}

/** Parses `geo:` navigation intents: `geo:lat,lon`, `geo:0,0?q=lat,lon(label)` and `geo:0,0?q=text`. Anything malformed gives null. */
object GeoIntent {
    private const val MAX_INPUT = 2000
    private const val MAX_QUERY = 200
    private const val MAX_LABEL = 80
    private val AT_COORDS = Regex("""@(-?\d+\.\d+),(-?\d+\.\d+)""")
    private val BANG_COORDS = Regex("""!3d(-?\d+\.\d+)!4d(-?\d+\.\d+)""")
    private val PLAIN_COORDS = Regex("""(?<!\d)(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})(?!\d)""")

    /** Parses a shared text or URL from Google Maps (or a `geo:` URI) into a [GeoTarget]. */
    fun parseSharedText(input: String?): GeoTarget? {
        if (input == null || input.length > MAX_INPUT) return null
        val trimmed = input.trim()
        if (trimmed.startsWith("geo:", ignoreCase = true)) return parse(trimmed)
        BANG_COORDS.find(trimmed)?.let { m ->
            val c = checked(m.groupValues[1].toDoubleOrNull(), m.groupValues[2].toDoubleOrNull())
            if (c != null) return GeoTarget.Place(c.first, c.second, extractPlaceNameFromUrl(trimmed))
        }
        AT_COORDS.find(trimmed)?.let { m ->
            val c = checked(m.groupValues[1].toDoubleOrNull(), m.groupValues[2].toDoubleOrNull())
            if (c != null) return GeoTarget.Place(c.first, c.second, extractPlaceNameFromUrl(trimmed))
        }
        val qParam = Regex("""[?&](?:q|query|destination)=([^&#\s]+)""", RegexOption.IGNORE_CASE).find(trimmed)?.groupValues?.get(1)?.let { decode(it) }
        if (!qParam.isNullOrBlank()) {
            return parse("geo:0,0?q=$qParam")
        }
        PLAIN_COORDS.find(trimmed)?.let { m ->
            val c = checked(m.groupValues[1].toDoubleOrNull(), m.groupValues[2].toDoubleOrNull())
            if (c != null) return GeoTarget.Place(c.first, c.second, null)
        }
        // Fallback: if the user shared a multi-line "Place Name\nAddress\nhttps://maps.app.goo.gl/..." string from Google Maps, use the text before the URL as a search query.
        val withoutUrls = trimmed.replace(Regex("""https?://\S+"""), "").lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        val cleaned = cleanQuery(withoutUrls)
        return if (cleaned.length >= 2) GeoTarget.Query(cleaned) else null
    }

    private fun extractPlaceNameFromUrl(text: String): String? {
        val m = Regex("""/maps/place/([^/@?]+)""").find(text) ?: return null
        return decode(m.groupValues[1].replace('+', ' '))?.let { cleanLabel(it) }
    }

    fun parse(uri: String?): GeoTarget? {
        if (uri == null || uri.length > MAX_INPUT) return null
        if (!uri.startsWith("geo:", ignoreCase = true)) return null
        val rest = uri.substring(4)
        val coordPart = rest.substringBefore('?').substringBefore(';')
        val queryString = if ('?' in rest) rest.substringAfter('?') else ""
        val base = coordinates(coordPart)
        val q = queryString.split('&').firstOrNull { it.startsWith("q=", ignoreCase = true) }?.substring(2)?.let { decode(it) }
        if (q != null && q.isNotBlank()) {
            val inner = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*(?:\((.*)\))?\s*$""", RegexOption.DOT_MATCHES_ALL).matchEntire(q)
            if (inner != null) {
                val c = checked(inner.groupValues[1].toDoubleOrNull(), inner.groupValues[2].toDoubleOrNull()) ?: return null
                return GeoTarget.Place(c.first, c.second, inner.groups[3]?.value?.let { cleanLabel(it) })
            }
            val text = cleanQuery(q)
            if (text.isNotEmpty()) return GeoTarget.Query(text)
            return null
        }
        if (base == null) return null
        if (base.first == 0.0 && base.second == 0.0) return null
        return GeoTarget.Place(base.first, base.second, null)
    }

    private fun coordinates(s: String): Pair<Double, Double>? {
        val parts = s.split(',')
        if (parts.size < 2) return null
        return checked(parts[0].trim().toDoubleOrNull(), parts[1].trim().toDoubleOrNull())
    }

    private fun checked(lat: Double?, lon: Double?): Pair<Double, Double>? {
        if (lat == null || lon == null || !lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        return lat to lon
    }

    private fun decode(s: String): String? = try {
        URLDecoder.decode(s, "UTF-8")
    } catch (e: Exception) {
        null
    }

    private fun strip(s: String): String {
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            i += Character.charCount(cp)
            if (!Character.isISOControl(cp) && cp != 0x2028 && cp != 0x2029) sb.appendCodePoint(cp)
        }
        return sb.toString().trim()
    }

    private fun cleanQuery(s: String): String = strip(s).take(MAX_QUERY).trim()

    private fun cleanLabel(s: String): String? = strip(s).take(MAX_LABEL).trim().ifEmpty { null }
}
