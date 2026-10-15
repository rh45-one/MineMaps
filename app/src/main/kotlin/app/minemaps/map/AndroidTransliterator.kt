package app.minemaps.map

import android.os.Build
import androidx.annotation.RequiresApi
import app.minemaps.engine.labels.Transliterator

/** ICU "Any-Latin; Latin-ASCII" (Android 10+); returns null on older releases, where names stay in their local script. */
class AndroidTransliterator : Transliterator {
    private val local = ThreadLocal<android.icu.text.Transliterator>()

    override fun toLatin(text: String): String? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) transliterate(text) else null

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun transliterate(text: String): String {
        val t = local.get() ?: android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII").also { local.set(it) }
        return titleCase(t.transliterate(text))
    }

    companion object {
        fun titleCase(s: String): String = s.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.titlecase() } }
    }
}
