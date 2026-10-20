package app.cactusai.core.info

import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs

/** "What time is it?" and "What's the time in California?", from the system clock and the IANA zone database. Offline. */
object TimeAnswer {
    fun answer(clock: Clock, locale: Locale, use24Hour: Boolean, place: String?): InfoAnswer {
        val now = clock.instant()
        val here = ZonedDateTime.ofInstant(now, clock.zone)
        val pattern = if (use24Hour) "HH:mm" else "h:mm a"
        val format = DateTimeFormatter.ofPattern(pattern, locale)

        if (place.isNullOrBlank()) {
            val text = "It's ${here.format(format)}."
            return InfoAnswer(text)
        }

        val resolved = ZoneResolver.resolve(place)
            ?: return InfoAnswer(
                text = "I don't know which time zone “${place.trim().take(40)}” is in. Try a city, state or country, or a zone like Eastern time.",
                verified = false,
            )
        val there = ZonedDateTime.ofInstant(now, resolved.zone)
        val day = when (there.toLocalDate().compareTo(here.toLocalDate())) {
            1 -> " tomorrow"
            -1 -> " yesterday"
            else -> ""
        }
        val zoneName = resolved.zone.getDisplayName(TextStyle.FULL, locale)
        val gap = difference(clock.zone, resolved.zone, now.toEpochMilli())
        val text = buildString {
            append("It's ${there.format(format)}$day in ${resolved.label} ($zoneName). $gap")
            resolved.note?.let { append(" ").append(it) }
        }
        val spoken = buildString {
            append("It's ${there.format(format)}$day in ${resolved.label}. $gap")
            resolved.note?.let { append(" ").append(it) }
        }
        return InfoAnswer(text, spoken)
    }

    private fun difference(here: ZoneId, there: ZoneId, epochMs: Long): String {
        val instant = java.time.Instant.ofEpochMilli(epochMs)
        val seconds = there.rules.getOffset(instant).totalSeconds - here.rules.getOffset(instant).totalSeconds
        if (seconds == 0) return "That's the same time as here."
        val d = Duration.ofSeconds(abs(seconds).toLong())
        val h = d.toHours()
        val m = d.toMinutes() % 60
        val amount = buildList {
            if (h > 0) add("$h hour${if (h == 1L) "" else "s"}")
            if (m > 0) add("$m minute${if (m == 1L) "" else "s"}")
        }.joinToString(" ")
        return "That's $amount ${if (seconds < 0) "behind" else "ahead of"} you."
    }
}
