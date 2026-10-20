package app.cactusai.core.web

import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.info.InfoAnswer
import app.cactusai.core.info.Source
import java.time.Clock
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

enum class TempUnit {
    AUTO, FAHRENHEIT, CELSIUS;

    /** AUTO follows the phone's region: Fahrenheit in the United States, Liberia and Myanmar, Celsius elsewhere. */
    fun resolve(locale: Locale): TempUnit = when (this) {
        AUTO -> if (locale.country in setOf("US", "LR", "MM", "BS", "BZ", "KY", "PW")) FAHRENHEIT else CELSIUS
        else -> this
    }

    companion object {
        fun fromKey(key: String?): TempUnit = entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: AUTO
    }
}

/** Adds the other unit beside a temperature written in the page's unit: "72°F" becomes "72°F (22°C)" for a Celsius user. */
object Temperatures {
    private val degrees = Regex("""(-?\d{1,3})\s?°\s?([FfCc])\b""")

    fun convertFor(text: String, preferred: TempUnit): String = degrees.replace(text) { m ->
        val value = m.groupValues[1].toInt()
        val unit = m.groupValues[2].uppercase()
        val original = "$value°$unit"
        when {
            preferred == TempUnit.CELSIUS && unit == "F" -> "$original (${((value - 32) * 5.0 / 9).roundToInt()}°C)"
            preferred == TempUnit.FAHRENHEIT && unit == "C" -> "$original (${(value * 9.0 / 5 + 32).roundToInt()}°F)"
            else -> original
        }
    }

    fun hasTemperature(text: String) = degrees.containsMatchIn(text)
}

/**
 * Weather from a web search, quoted as the page wrote it. This is a search result, not a weather service: it can be an hour
 * or a day old, and the answer says when it was fetched.
 */
class WeatherAnswerer(
    private val web: WebSearchProvider?,
    private val clock: Clock,
    private val locale: Locale,
    private val use24Hour: Boolean,
    private val unit: () -> TempUnit,
    private val homePlace: () -> String?,
) {
    private val weatherWords = Regex("""\b(sunny|cloudy|clear|rain|showers|snow|storm|thunder|wind|windy|humid|fog|overcast|drizzle|high|low|forecast|temperature)\b""", RegexOption.IGNORE_CASE)

    suspend fun answer(place: String?, whenKey: String?): InfoAnswer {
        val where = place?.trim().takeUnless { it.isNullOrBlank() } ?: homePlace()?.trim().takeUnless { it.isNullOrBlank() }
            ?: return InfoAnswer("Which place do you want the weather for? Say, for example, “weather in Detroit”, or set a home city in Settings.", verified = false)
        val search = web ?: return InfoAnswer("Searching is not available, so I can't look up the weather.", verified = false)
        val day = when (whenKey) {
            "tomorrow" -> "tomorrow"
            "weekend" -> "this weekend"
            else -> "today"
        }
        val preferred = unit().resolve(locale)
        val query = "weather forecast $where $day in ${if (preferred == TempUnit.FAHRENHEIT) "Fahrenheit" else "Celsius"}"
        val found = when (val r = search.search(query)) {
            is SearchOutcome.Failed -> return QuestionAnswerer.failureAnswer(r)
            is SearchOutcome.Results -> r
        }
        val time = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale).format(Instant.ofEpochMilli(found.retrievedAtMs).atZone(clock.zone))
        val sources = found.results.take(4).map { Source(it.title, it.url, it.content.take(160).ifBlank { null }) }

        for (r in found.results) {
            val sentence = (r.title + ". " + r.content).split(Regex("""(?<=[.!?])\s+""")).map { it.trim() }
                .firstOrNull { it.length in 15..260 && Temperatures.hasTemperature(it) && weatherWords.containsMatchIn(it) }
            if (sentence != null) {
                val shown = Temperatures.convertFor(sentence, preferred)
                return InfoAnswer(
                    text = "Weather for $where, $day. According to ${r.host}: “$shown”\nChecked $time from ${search.name}. This comes from search results, not a weather service, and may not be current.",
                    spoken = "Weather for $where, $day. According to ${r.host.replace(".", " dot ")}: ${UntrustedText.forSpeech(shown)}",
                    origin = AnswerOrigin.ONLINE,
                    sources = sources,
                    retrievedAtMs = found.retrievedAtMs,
                    note = "Quoted from the page; check the source for the current forecast.",
                    verified = false,
                )
            }
        }
        return InfoAnswer(
            text = "I couldn't find a clear forecast for $where, $day in the search results. The top results are listed below. Checked $time from ${search.name}.",
            origin = AnswerOrigin.ONLINE,
            sources = sources,
            retrievedAtMs = found.retrievedAtMs,
            verified = false,
        )
    }
}
