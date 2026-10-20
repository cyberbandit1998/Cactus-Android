package app.cactusai.core.match

class Candidate<T>(val item: T, val label: String, val aliases: List<String> = emptyList())

data class Scored<T>(val candidate: Candidate<T>, val score: Double)

sealed interface MatchResult<out T> {
    data class Single<T>(val match: Scored<T>) : MatchResult<T>
    data class Ambiguous<T>(val matches: List<Scored<T>>) : MatchResult<T>
    data object None : MatchResult<Nothing>
}

/**
 * Picks an app or contact from what the user said. Never guesses between close matches:
 * when two or more are about equally good the caller gets [MatchResult.Ambiguous] and asks the user.
 */
object NameMatcher {
    const val MIN_SCORE = 0.62
    private const val CLOSE_BAND = 0.06
    private val fillerPrefix = Regex("^(?:my|the|a|an)\\s+")
    private val fillerSuffix = Regex("\\s+(?:app|application|please)$")

    /** "my Spotify app" -> "spotify". */
    fun cleanQuery(raw: String): String {
        var q = TextNorm.normalize(raw)
        var previous: String
        do {
            previous = q
            q = q.replace(fillerPrefix, "").replace(fillerSuffix, "")
        } while (q != previous)
        return q
    }

    fun <T> find(rawQuery: String, candidates: List<Candidate<T>>, maxAmbiguous: Int = 5): MatchResult<T> {
        val query = cleanQuery(rawQuery)
        if (query.isEmpty()) return MatchResult.None
        val scored = candidates
            .map { c -> Scored(c, (listOf(c.label) + c.aliases).maxOf { score(query, it) }) }
            .filter { it.score >= MIN_SCORE }
            .sortedWith(compareByDescending<Scored<T>> { it.score }.thenBy { it.candidate.label.lowercase() })
        if (scored.isEmpty()) return MatchResult.None

        val top = scored.first()
        val exact = scored.filter { it.score >= 0.999 }
        if (exact.size == 1) return MatchResult.Single(exact.first())
        if (exact.size > 1) return MatchResult.Ambiguous(exact.take(maxAmbiguous))

        val close = scored.filter { it.score >= top.score - CLOSE_BAND }
        return if (close.size == 1) MatchResult.Single(top) else MatchResult.Ambiguous(close.take(maxAmbiguous))
    }

    /** Score of an already-cleaned query against one label, 0.0 to 1.0. */
    fun score(query: String, rawLabel: String): Double {
        val label = TextNorm.normalize(rawLabel)
        if (label.isEmpty() || query.isEmpty()) return 0.0
        if (query == label) return 1.0
        if (query.length >= 3 && label.startsWith(query)) return 0.88
        val qTokens = query.split(' ')
        val lTokens = label.split(' ')
        if (qTokens.all { it in lTokens }) return 0.86
        if (query.length >= 4 && label.contains(query)) return 0.75
        val whole = TextNorm.similarity(query, label)
        val perToken = qTokens.map { q -> lTokens.maxOf { TextNorm.similarity(q, it) } }.average()
        val fuzzy = maxOf(whole, perToken)
        return if (fuzzy >= 0.75) fuzzy * 0.85 else 0.0
    }
}
