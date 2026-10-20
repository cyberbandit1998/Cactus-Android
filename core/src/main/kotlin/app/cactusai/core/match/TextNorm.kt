package app.cactusai.core.match

import java.text.Normalizer
import java.util.Locale

object TextNorm {
    private val marks = Regex("\\p{M}+")

    /** Lowercase, accent-free, letters and digits only, single spaces. "Café & Co." -> "cafe and co". */
    fun normalize(s: String): String {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFD).replace(marks, "").lowercase(Locale.ROOT)
        val sb = StringBuilder(folded.length)
        for (c in folded) {
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == '&' -> sb.append(" and ")
                else -> sb.append(' ')
            }
        }
        return sb.toString().trim().replace(Regex(" +"), " ")
    }

    fun tokens(s: String): List<String> = normalize(s).split(' ').filter { it.isNotEmpty() }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }

    /** 1.0 for equal strings, 0.0 for nothing in common. */
    fun similarity(a: String, b: String): Double {
        val longest = maxOf(a.length, b.length)
        return if (longest == 0) 1.0 else 1.0 - levenshtein(a, b).toDouble() / longest
    }
}
