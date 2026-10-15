package app.minemaps.engine.labels

fun interface Transliterator {
    fun toLatin(text: String): String?
}

class NameResolver(val mode: Mode, private val transliterator: Transliterator? = null) {
    enum class Mode { ENGLISH, LOCAL }

    private val cache = object : LinkedHashMap<String, String?>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?): Boolean = size > CACHE_SIZE
    }

    fun resolve(tags: Map<String, Any?>, isPlace: Boolean = false): String? {
        fun tag(key: String) = (tags[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val name = tag("name")
        if (mode == Mode.LOCAL) return name
        tag("name:en")?.let { return it }
        tag("name_en")?.takeIf { isLatin(it) }?.let { return it }
        tag("name_int")?.takeIf { isLatin(it) }?.let { return if (isPlace && ", " in it) it.substringBefore(", ") else it }
        if (name != null && isLatin(name)) return name
        tag("name:latin")?.let { return it }
        if (name == null) return null
        latinPart(name)?.let { return it }
        return latinize(name) ?: name
    }

    fun latinize(text: String): String? {
        if (isLatin(text)) return text
        val t = transliterator ?: return null
        synchronized(cache) { if (cache.containsKey(text)) return cache[text] }
        val out = runCatching { t.toLatin(text) }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
        synchronized(cache) { cache[text] = out }
        return out
    }

    private fun latinPart(name: String): String? {
        var best: List<String> = emptyList()
        var bestLetters = 0
        var run = ArrayList<String>()
        var runLetters = 0
        fun flush() {
            if (runLetters > bestLetters) {
                best = run
                bestLetters = runLetters
            }
            run = ArrayList()
            runLetters = 0
        }
        for (w in name.split(Regex("\\s+"))) {
            val letters = w.count { it.isLetter() }
            if (letters > 0 && isLatin(w)) {
                run.add(w)
                runLetters += letters
            } else {
                flush()
            }
        }
        flush()
        return if (bestLetters >= 2) best.joinToString(" ") else null
    }

    companion object {
        private const val CACHE_SIZE = 2048

        fun isLatin(s: String): Boolean {
            var i = 0
            while (i < s.length) {
                val cp = s.codePointAt(i)
                i += Character.charCount(cp)
                if (!Character.isLetter(cp)) continue
                val script = Character.UnicodeScript.of(cp)
                if (script != Character.UnicodeScript.LATIN && script != Character.UnicodeScript.COMMON && script != Character.UnicodeScript.INHERITED) return false
            }
            return true
        }
    }
}
