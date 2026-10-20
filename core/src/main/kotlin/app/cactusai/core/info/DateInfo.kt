package app.cactusai.core.info

import app.cactusai.core.match.TextNorm
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** A day the user named, turned into a calendar date. [name] is set for a holiday or a spoken date ("Christmas"). */
data class ResolvedDay(val date: LocalDate, val name: String?)

/**
 * "What's today's date?", "What day is tomorrow?", "What day of the week is Christmas?", "How many days until
 * Halloween?". The system clock and `java.time` only, so it works with no connection. Holidays are computed (Easter with
 * the Gregorian computus; Thanksgiving, Memorial Day and the others by weekday rules), never looked up.
 */
object DateInfo {
    private val weekdays = DayOfWeek.entries.associateBy { it.name.lowercase() }
    private val months = Month.entries.flatMap { m ->
        listOf(m.name.lowercase() to m, m.name.lowercase().take(3) to m) + (if (m == Month.SEPTEMBER) listOf("sept" to m) else emptyList())
    }.toMap()
    private val smallNumbers = mapOf(
        "a" to 1, "an" to 1, "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5, "six" to 6, "seven" to 7,
        "eight" to 8, "nine" to 9, "ten" to 10, "eleven" to 11, "twelve" to 12, "fourteen" to 14, "thirty" to 30, "sixty" to 60, "ninety" to 90,
    )

    private class Holiday(val display: String, val on: (Int) -> LocalDate)

    private fun fixed(month: Month, day: Int): (Int) -> LocalDate = { y -> LocalDate.of(y, month, day) }
    private fun nth(month: Month, dow: DayOfWeek, n: Int): (Int) -> LocalDate =
        { y -> LocalDate.of(y, month, 1).with(TemporalAdjusters.dayOfWeekInMonth(n, dow)) }
    private fun lastOf(month: Month, dow: DayOfWeek): (Int) -> LocalDate =
        { y -> LocalDate.of(y, month, 1).with(TemporalAdjusters.lastInMonth(dow)) }

    /** Gregorian Easter Sunday by the anonymous (Meeus/Jones/Butcher) algorithm. */
    fun easter(year: Int): LocalDate {
        val a = year % 19
        val b = year / 100
        val c = year % 100
        val d = b / 4
        val e = b % 4
        val f = (b + 8) / 25
        val g = (b - f + 1) / 3
        val h = (19 * a + b - d - g + 15) % 30
        val i = c / 4
        val k = c % 4
        val l = (32 + 2 * e + 2 * i - h - k) % 7
        val m = (a + 11 * h + 22 * l) / 451
        val month = (h + l - 7 * m + 114) / 31
        val day = (h + l - 7 * m + 114) % 31 + 1
        return LocalDate.of(year, month, day)
    }

    private val holidays: Map<String, Holiday> = buildMap {
        fun add(display: String, on: (Int) -> LocalDate, vararg names: String) {
            val h = Holiday(display, on)
            names.forEach { put(it, h) }
        }
        add("Christmas", fixed(Month.DECEMBER, 25), "christmas", "christmas day", "xmas")
        add("Christmas Eve", fixed(Month.DECEMBER, 24), "christmas eve")
        add("New Year's Day", fixed(Month.JANUARY, 1), "new years day", "new year", "new years", "new year day")
        add("New Year's Eve", fixed(Month.DECEMBER, 31), "new years eve", "new year eve")
        add("Valentine's Day", fixed(Month.FEBRUARY, 14), "valentines day", "valentines", "valentine", "valentine s day")
        add("Halloween", fixed(Month.OCTOBER, 31), "halloween")
        add("Independence Day", fixed(Month.JULY, 4), "independence day", "fourth of july", "4th of july", "july fourth", "the fourth of july", "the 4th of july")
        add("St. Patrick's Day", fixed(Month.MARCH, 17), "st patricks day", "saint patricks day", "patricks day", "st patrick s day")
        add("Groundhog Day", fixed(Month.FEBRUARY, 2), "groundhog day")
        add("April Fools' Day", fixed(Month.APRIL, 1), "april fools day", "april fools", "april fool s day")
        add("Earth Day", fixed(Month.APRIL, 22), "earth day")
        add("Tax Day", fixed(Month.APRIL, 15), "tax day")
        add("Cinco de Mayo", fixed(Month.MAY, 5), "cinco de mayo")
        add("Juneteenth", fixed(Month.JUNE, 19), "juneteenth")
        add("Veterans Day", fixed(Month.NOVEMBER, 11), "veterans day")
        add("Thanksgiving", nth(Month.NOVEMBER, DayOfWeek.THURSDAY, 4), "thanksgiving", "thanksgiving day")
        add("Black Friday", { y -> nth(Month.NOVEMBER, DayOfWeek.THURSDAY, 4)(y).plusDays(1) }, "black friday")
        add("Cyber Monday", { y -> nth(Month.NOVEMBER, DayOfWeek.THURSDAY, 4)(y).plusDays(4) }, "cyber monday")
        add("Easter", ::easter, "easter", "easter sunday")
        add("Good Friday", { y -> easter(y).minusDays(2) }, "good friday")
        add("Memorial Day", lastOf(Month.MAY, DayOfWeek.MONDAY), "memorial day")
        add("Labor Day", nth(Month.SEPTEMBER, DayOfWeek.MONDAY, 1), "labor day")
        add("Mother's Day", nth(Month.MAY, DayOfWeek.SUNDAY, 2), "mothers day", "mother s day")
        add("Father's Day", nth(Month.JUNE, DayOfWeek.SUNDAY, 3), "fathers day", "father s day")
        add("Martin Luther King Jr. Day", nth(Month.JANUARY, DayOfWeek.MONDAY, 3), "martin luther king day", "martin luther king jr day", "mlk day", "mlk")
        add("Presidents' Day", nth(Month.FEBRUARY, DayOfWeek.MONDAY, 3), "presidents day", "president s day", "presidents")
        add("Columbus Day", nth(Month.OCTOBER, DayOfWeek.MONDAY, 2), "columbus day", "indigenous peoples day")
    }

    /**
     * Understands what people say after "what day is", "when is" and "days until". Returns null when it does not recognise
     * the phrase, so the caller can say so instead of guessing. [preferPast] picks the most recent occurrence of a holiday
     * or a month-and-day instead of the next one.
     */
    fun resolve(rawDay: String?, today: LocalDate, preferPast: Boolean = false): ResolvedDay? {
        val norm = TextNorm.normalize((rawDay ?: "").replace("'", "").replace("’", ""))
            .removePrefix("the ").removePrefix("on ").trim()
        when (norm) {
            "", "today", "now", "date", "todays date", "current date", "the date", "this day" -> return ResolvedDay(today, null)
            "tomorrow", "tomorrows date" -> return ResolvedDay(today.plusDays(1), null)
            "yesterday", "yesterdays date" -> return ResolvedDay(today.minusDays(1), null)
            "day after tomorrow" -> return ResolvedDay(today.plusDays(2), null)
            "day before yesterday" -> return ResolvedDay(today.minusDays(2), null)
        }

        holidays[norm]?.let { h -> return occurrence(today, preferPast, h.on)?.let { ResolvedDay(it, h.display) } }

        Regex("""(?:in )?(\d+|[a-z]+) (day|days|week|weeks|month|months)(?: (from now|from today|ago))?""").matchEntire(norm)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: smallNumbers[m.groupValues[1]] ?: return@let
            if (n > 3650) return@let
            val ago = m.groupValues[3] == "ago"
            val unit = m.groupValues[2].removeSuffix("s")
            val date = when (unit) {
                "day" -> if (ago) today.minusDays(n.toLong()) else today.plusDays(n.toLong())
                "week" -> if (ago) today.minusWeeks(n.toLong()) else today.plusWeeks(n.toLong())
                else -> if (ago) today.minusMonths(n.toLong()) else today.plusMonths(n.toLong())
            }
            return ResolvedDay(date, null)
        }

        Regex("""(next|this|last)? ?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)""").matchEntire(norm)?.let { m ->
            val dow = weekdays.getValue(m.groupValues[2])
            val date = when (m.groupValues[1]) {
                "next" -> today.with(TemporalAdjusters.next(dow))
                "last" -> today.with(TemporalAdjusters.previous(dow))
                else -> today.with(TemporalAdjusters.nextOrSame(dow))
            }
            return ResolvedDay(date, null)
        }

        return explicitDate(norm, today, preferPast)
    }

    private fun explicitDate(norm: String, today: LocalDate, preferPast: Boolean): ResolvedDay? {
        val monthNames = months.keys.sortedByDescending { it.length }.joinToString("|")
        // "december 25", "dec 25th", "december 25 2027"
        Regex("""($monthNames) (\d{1,2})(?:st|nd|rd|th)?(?: (\d{4}))?""").matchEntire(norm)?.let { m ->
            return build(months.getValue(m.groupValues[1]), m.groupValues[2].toInt(), m.groupValues[3].toIntOrNull(), today, preferPast)
        }
        // "25 december", "25th of december 2027"
        Regex("""(\d{1,2})(?:st|nd|rd|th)?(?: of)? ($monthNames)(?: (\d{4}))?""").matchEntire(norm)?.let { m ->
            return build(months.getValue(m.groupValues[2]), m.groupValues[1].toInt(), m.groupValues[3].toIntOrNull(), today, preferPast)
        }
        // "12 25" and "12 25 2027" (what "12/25" and "12/25/2027" become after normalising; month first, as in the US)
        Regex("""(\d{1,2}) (\d{1,2})(?: (\d{2}|\d{4}))?""").matchEntire(norm)?.let { m ->
            val year = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            val month = m.groupValues[1].toInt()
            if (month !in 1..12) return null
            return build(Month.of(month), m.groupValues[2].toInt(), year, today, preferPast)
        }
        // ISO "2027 03 03"
        Regex("""(\d{4}) (\d{1,2}) (\d{1,2})""").matchEntire(norm)?.let { m ->
            val month = m.groupValues[2].toInt()
            if (month !in 1..12) return null
            return build(Month.of(month), m.groupValues[3].toInt(), m.groupValues[1].toInt(), today, preferPast, fixedYear = true)
        }
        return null
    }

    private fun build(month: Month, day: Int, year: Int?, today: LocalDate, preferPast: Boolean, fixedYear: Boolean = false): ResolvedDay? {
        if (day !in 1..31) return null
        val label = "${month.getDisplayName(java.time.format.TextStyle.FULL, Locale.US)} $day"
        if (year != null || fixedYear) {
            val y = year ?: return null
            return try {
                ResolvedDay(LocalDate.of(y, month, day), label)
            } catch (_: java.time.DateTimeException) {
                null
            }
        }
        val found = occurrence(today, preferPast) { y -> LocalDate.of(y, month, day) } ?: return null
        return ResolvedDay(found, label)
    }

    /** The next (or, with [preferPast], most recent) date on which [on] falls, today included. */
    private fun occurrence(today: LocalDate, preferPast: Boolean, on: (Int) -> LocalDate): LocalDate? {
        val years = if (preferPast) (today.year downTo today.year - 8) else (today.year..today.year + 8)
        for (y in years) {
            val d = try {
                on(y)
            } catch (_: java.time.DateTimeException) {
                continue // February 29 in a year without one
            }
            if (if (preferPast) !d.isAfter(today) else !d.isBefore(today)) return d
        }
        return null
    }

    private val countStyle = Regex("""\b(how many days|days (until|till|to go|left|since|ago)|how long (until|till|since)|countdown)\b""")
    private val pastWords = Regex("""\b(since|ago|was|last)\b""")

    fun answer(rawDay: String?, userText: String, clock: Clock, locale: Locale): InfoAnswer {
        val today = LocalDate.now(clock)
        val lowered = userText.lowercase(Locale.ROOT)
        val counting = countStyle.containsMatchIn(lowered)
        val preferPast = pastWords.containsMatchIn(lowered)
        val resolved = resolve(rawDay, today, preferPast)
            ?: return InfoAnswer(
                "I don't know which day “${rawDay.orEmpty().trim().take(40)}” is. Try a weekday, a holiday such as Christmas, or a date like December 25.",
                verified = false,
            )

        val full = DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale).format(resolved.date)
        val days = ChronoUnit.DAYS.between(today, resolved.date)
        val away = distance(days)
        val name = resolved.name

        val text = when {
            counting && days > 0 -> "${plural(days)} until ${name ?: full}${if (name != null) " ($full)" else ""}."
            counting && days < 0 -> "${plural(-days)} since ${name ?: full}${if (name != null) " ($full)" else ""}."
            counting -> "${name ?: full} is today${if (name != null) " ($full)" else ""}."
            days == 0L -> if (name != null) "$name is today, $full." else "Today is $full."
            days == 1L -> if (name != null) "$name is tomorrow, $full." else "Tomorrow is $full."
            days == -1L -> if (name != null) "$name was yesterday, $full." else "Yesterday was $full."
            days > 0 -> if (name != null) "$name is $full, $away." else "$full is $away."
            else -> if (name != null) "$name was $full, $away." else "$full was $away."
        }
        return InfoAnswer(text)
    }

    private fun plural(n: Long) = if (n == 1L) "1 day" else "$n days"

    private fun distance(days: Long): String = if (days >= 0) "${plural(days)} from today" else "${plural(-days)} ago"
}
