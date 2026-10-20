package app.cactusai.core.web

import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.info.InfoAnswer
import app.cactusai.core.info.Source
import app.cactusai.core.match.TextNorm
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How search results are spoken. [SHORT_ANSWER] gives one sentence when it is reliably supported, otherwise the first results. */
enum class ReadMode {
    SHORT_ANSWER, READ_RESULTS;

    companion object {
        fun fromKey(key: String?): ReadMode = entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: SHORT_ANSWER
    }
}

/**
 * An optional component that writes an answer from search results, for questions that need reasoning or several sources
 * put together. **None is configured, none is built in, and Needle is not one:** it only chooses tools. The sources it is given
 * are untrusted web text and it must treat them as data. Whatever it returns is shown labelled with [name] and is never
 * called verified. It must run on the phone: the app adds no cloud AI service.
 */
interface AnswerGenerator {
    val name: String

    /** Returns the answer text, or null when it cannot answer from these sources. */
    suspend fun generate(question: String, sources: List<WebResult>): String?
}

/**
 * "Who invented the telephone?", "How far away is the moon?": the browser's search results, read back as either a short
 * answer (one sentence quoted word for word, only when at least two different sites support it) or the first few results.
 * Nothing is paraphrased, summarised or merged, and when no sentence is reliable the answer says so.
 */
class QuestionAnswerer(
    private val web: WebSearchProvider?,
    private val clock: Clock,
    private val locale: Locale,
    private val use24Hour: Boolean,
    private val mode: () -> ReadMode = { ReadMode.SHORT_ANSWER },
    private val generator: AnswerGenerator? = null,
) {
    suspend fun answer(question: String): InfoAnswer {
        val search = web ?: return InfoAnswer("Searching is not available, so I can't look that up.", verified = false)
        val query = QuestionText.searchQuery(question)
        if (query.isBlank()) return InfoAnswer("I didn't catch a question to look up.", verified = false)

        val found = when (val outcome = search.search(query)) {
            is SearchOutcome.Failed -> return failureAnswer(outcome)
            is SearchOutcome.Results -> outcome
        }
        val checked = "Checked ${clockText(found.retrievedAtMs)} from ${search.name}."
        val all = found.results
        if (all.isEmpty() && found.featured == null) {
            return InfoAnswer("I couldn't find any results I could read. $checked", origin = AnswerOrigin.ONLINE, retrievedAtMs = found.retrievedAtMs, verified = false)
        }

        generator?.let { g ->
            val written = g.generate(question, all)?.let { UntrustedText.clean(it, 600) }
            if (!written.isNullOrBlank()) {
                return InfoAnswer(
                    text = "$written\n$checked",
                    spoken = UntrustedText.forSpeech(written, 600),
                    origin = AnswerOrigin.ONLINE,
                    sources = SpokenResults.sources(all),
                    retrievedAtMs = found.retrievedAtMs,
                    note = "Written by ${g.name} from the results below. Check them: it can be wrong.",
                    verified = false,
                )
            }
        }

        val composed = SpokenResults.compose(mode(), question, found)
        return InfoAnswer(
            text = "${composed.text}\n$checked",
            spoken = composed.spoken,
            origin = AnswerOrigin.ONLINE,
            sources = composed.sources,
            retrievedAtMs = found.retrievedAtMs,
            note = if (composed.direct) "Quoted from the pages, not checked by me. Open a source to be sure." else null,
            verified = false,
        )
    }

    private fun clockText(ms: Long): String =
        DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale).format(Instant.ofEpochMilli(ms).atZone(clock.zone))

    companion object {
        /** A failed lookup, in words that also say when the results are still on screen. */
        fun failureAnswer(f: SearchOutcome.Failed): InfoAnswer = InfoAnswer(
            text = f.message,
            verified = false,
            note = if (f.browserShowsResults) "The search results are showing in your browser." else null,
        )
    }
}

data class QuotedSentence(val host: String, val sentence: String, val score: Double)

data class ReliableAnswer(val sentence: String, val hosts: List<String>, val score: Double)

/** Query building and sentence picking for general questions. No language model: word overlap, answer-type cues and agreement. */
object QuestionText {
    private val fillers = Regex("""^(?:hey |ok |okay |please |can you |could you |would you |tell me |i want to know |i would like to know |do you know |i wonder |let me know )+""", RegexOption.IGNORE_CASE)
    private val stop = setOf(
        "who", "what", "when", "where", "why", "how", "which", "is", "are", "was", "were", "do", "does", "did", "the", "a", "an", "of", "in", "on", "at", "to",
        "for", "from", "by", "with", "and", "or", "i", "you", "me", "my", "we", "it", "this", "that", "there", "many", "much", "far", "long", "old", "tall", "has", "have",
        "can", "will", "would", "could", "about", "s", "tell", "know", "current", "currently", "now", "today",
        // words that say what kind of answer is wanted rather than what it is about
        "away", "year", "date", "day", "time", "name", "number",
    )

    /** Past forms that a plain suffix rule cannot reduce to the word in the question ("sink" and "sank"). */
    private val irregular = mapOf(
        "sank" to "sink", "sunk" to "sink", "won" to "win", "wrote" to "write", "written" to "write", "built" to "build", "began" to "begin", "begun" to "begin",
        "led" to "lead", "bought" to "buy", "found" to "find", "made" to "make", "took" to "take", "gave" to "give", "ran" to "run", "flew" to "fly", "fell" to "fall",
        "became" to "become", "went" to "go", "spoke" to "speak", "drew" to "draw", "knew" to "know", "grew" to "grow", "held" to "hold", "said" to "say",
    )
    private val boilerplate = Regex("""cookie|sign in|log in|subscribe|privacy policy|advertisement|all rights reserved|click here|terms of use|javascript""", RegexOption.IGNORE_CASE)
    private val numberCue = Regex("""^(?:how (?:far|many|much|long|old|tall|big|fast|hot|cold|high|deep|heavy|large)|what(?:'s| is) the (?:temperature|distance|population|height|speed))""", RegexOption.IGNORE_CASE)
    private val whenCue = Regex("""^(?:when|what (?:year|date|day|century))""", RegexOption.IGNORE_CASE)
    private val monthNames = Regex("""\b(january|february|march|april|may|june|july|august|september|october|november|december)\b""", RegexOption.IGNORE_CASE)
    private val year = Regex("""\b(1\d{3}|20\d{2})\b""")
    private val commonCapitals = setOf("The", "A", "An", "In", "On", "It", "He", "She", "They", "This", "That", "These", "Those", "His", "Her", "Its", "Their", "And", "But", "Or", "Is", "Was", "Are", "Were")

    /** The words to search for: the question with greetings and politeness taken off, at most 200 characters. */
    fun searchQuery(question: String): String {
        val cleaned = UntrustedText.clean(question, 300).replace(fillers, "").trim().trimEnd('?', '.', '!', ' ')
        return cleaned.take(200)
    }

    private fun stem(w: String): String = irregular[w] ?: when {
        w.length > 5 && w.endsWith("ing") -> w.dropLast(3)
        w.length > 4 && w.endsWith("ed") -> w.dropLast(2)
        w.length > 4 && w.endsWith("es") -> w.dropLast(2)
        w.length > 3 && w.endsWith("s") -> w.dropLast(1)
        else -> w
    }

    fun contentWords(text: String): Set<String> = TextNorm.tokens(text.replace("'", "")).filter { it !in stop && it.length > 1 }.map(::stem).toSet()

    /** The best-matching sentence of each of the first few results, best first, at most [limit] and one per site. */
    fun bestSentences(question: String, results: List<WebResult>, minScore: Double = 0.65, limit: Int = 2): List<QuotedSentence> {
        val q = contentWords(question)
        if (q.isEmpty()) return emptyList()
        val wantsNumber = numberCue.containsMatchIn(question.trim())
        val wantsWhen = whenCue.containsMatchIn(question.trim())
        val scored = ArrayList<QuotedSentence>()
        results.forEachIndexed { rank, r ->
            var best: QuotedSentence? = null
            for (s in sentencesOf(r)) {
                if (s.length < 25 || s.length > 320 || boilerplate.containsMatchIn(s)) continue
                val words = contentWords(s)
                val matched = q.count { it in words }
                if (matched < minOf(2, q.size)) continue
                var score = matched.toDouble() / q.size
                if (wantsNumber && Regex("""\d""").containsMatchIn(s)) score += 0.2
                if (wantsWhen && (year.containsMatchIn(s) || monthNames.containsMatchIn(s))) score += 0.2
                if (rank == 0) score += 0.05
                if (best == null || score > best.score) best = QuotedSentence(r.host, s, score)
            }
            best?.takeIf { it.score >= minScore }?.let { scored += it }
        }
        return scored.sortedByDescending { it.score }.distinctBy { it.host }.take(limit)
    }

    private fun sentencesOf(r: WebResult): List<String> =
        (r.title + ". " + r.content).split(Regex("""(?<=[.!?])\s+(?=[A-Z0-9“"])""")).map { it.trim() }

    /** What a sentence is "about": its numbers and its capitalised names, other than the words of the question. */
    fun keyTokens(sentence: String, question: String): Set<String> {
        val asked = TextNorm.tokens(question).toSet()
        val tokens = LinkedHashSet<String>()
        Regex("""\d[\d,.]*\d|\d""").findAll(sentence).forEach { tokens += it.value.trimEnd('.', ',') }
        Regex("""\b[A-Z][a-zA-Z'’-]{2,}\b""").findAll(sentence).forEach { m ->
            if (m.range.first == 0 || m.value in commonCapitals) return@forEach
            if (m.value.lowercase() !in asked) tokens += m.value
        }
        return tokens
    }

    /**
     * A short answer that is allowed to be spoken: the best matching sentence, but only when another site (or the page's
     * highlighted answer plus an ordinary result) contains one of its names or numbers. One page saying so is not enough.
     */
    fun reliable(question: String, featured: String?, results: List<WebResult>, pageUrl: String? = null): ReliableAnswer? {
        val pool = ArrayList<WebResult>()
        featured?.let { pool += WebResult("Highlighted answer", pageUrl ?: "https://www.google.com/search", it) }
        pool += results
        val best = bestSentences(question, pool, limit = 1).firstOrNull() ?: return null
        val keys = keyTokens(best.sentence, question)
        if (keys.isEmpty()) return null
        val supporters = pool.filter { it.host != best.host }.filter { r ->
            val text = r.title + " " + r.content
            keys.any { k -> Regex("""(?<![\w])${Regex.escape(k)}(?![\w])""", RegexOption.IGNORE_CASE).containsMatchIn(text) }
        }.map { it.host }.distinct()
        if (supporters.isEmpty()) return null
        return ReliableAnswer(best.sentence, (listOf(best.host) + supporters).take(3), best.score)
    }
}

/** Composes what is shown and what is spoken for a page of results. */
object SpokenResults {
    data class Composed(val text: String, val spoken: String, val sources: List<Source>, val direct: Boolean)

    fun sources(results: List<WebResult>): List<Source> = results.take(5).map { Source(it.title, it.url, it.content.take(160).ifBlank { null }) }

    /** The first results that share a word with the question, up to three, so an unrelated block is not read out. */
    fun relevant(question: String, results: List<WebResult>, limit: Int = 3): List<WebResult> {
        val q = QuestionText.contentWords(question)
        val related = if (q.isEmpty()) results else results.filter { r -> QuestionText.contentWords(r.title + " " + r.content).any { it in q } }
        return related.ifEmpty { results.take(1) }.take(limit)
    }

    private fun hostSpoken(host: String) = host.replace(".", " dot ")

    private fun hostsText(hosts: List<String>) = when (hosts.size) {
        1 -> hosts[0]
        2 -> "${hosts[0]} and ${hosts[1]}"
        else -> hosts.dropLast(1).joinToString(", ") + " and ${hosts.last()}"
    }

    fun compose(mode: ReadMode, question: String, page: SearchOutcome.Results): Composed {
        if (mode == ReadMode.SHORT_ANSWER) {
            QuestionText.reliable(question, page.featured, page.results, page.pageUrl)?.let { r ->
                val text = "According to ${hostsText(r.hosts)}: “${r.sentence}”"
                val spoken = "According to ${hostsText(r.hosts.map(::hostSpoken))}: ${UntrustedText.forSpeech(r.sentence)}"
                val cited = r.hosts.mapNotNull { h -> page.results.firstOrNull { it.host == h } }
                return Composed(text, spoken, (cited.map { Source(it.title, it.url, it.content.take(160).ifBlank { null }) } + sources(page.results)).distinctBy { it.url }.take(5), direct = true)
            }
        }
        val picked = relevant(question, page.results)
        if (picked.isEmpty()) {
            return Composed("I couldn't find any results I could read.", "I couldn't find any results I could read.", emptyList(), direct = false)
        }
        val lead = if (mode == ReadMode.SHORT_ANSWER) "I couldn't find a reliable short answer, so here are the first results." else "Here are the first results."
        val display = StringBuilder(lead)
        val spoken = StringBuilder(lead)
        val numbers = listOf("First", "Second", "Third")
        picked.forEachIndexed { i, r ->
            val detail = UntrustedText.forSpeech(r.content, 170).let { if (it.isBlank()) "" else " $it" }
            display.append("\n${i + 1}. ${r.title} (${r.host})${if (r.content.isBlank()) "" else ": ${UntrustedText.clean(r.content, 170)}"}")
            spoken.append(" ${numbers[i]}: ${UntrustedText.forSpeech(r.title, 120)}.$detail")
        }
        return Composed(display.toString(), spoken.toString().replace(Regex(" +"), " "), sources(page.results), direct = false)
    }
}
