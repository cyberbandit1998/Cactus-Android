package app.cactusai.core.sports

import app.cactusai.core.web.WebResult
import java.text.Normalizer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.Month
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale

enum class GameStatus { UPCOMING, IN_PROGRESS, FINAL, UNKNOWN }

/**
 * One game as a single web page described it. [quote] is the sentence it came from, shown to the user as evidence. Nothing
 * here is trusted until [SportsVerifier] has compared it with other sources.
 */
data class GameFact(
    val opponent: Team?,
    val teamScore: Int?,
    val opponentScore: Int?,
    val status: GameStatus,
    /** Only a date the page wrote out ("Oct. 19"); words like "yesterday" or "tonight" are not dates a snippet can prove. */
    val date: LocalDate?,
    /** Start time when the page gave both a clock time and its time zone. */
    val start: ZonedDateTime?,
    /** A clock time the page gave without a time zone, shown as written and never converted. */
    val timeText: String?,
    /** True for "vs"/"hosts", false for "at"/"@", null when the page did not say. */
    val home: Boolean?,
    val detail: String?,
    val host: String,
    val url: String,
    val quote: String,
)

/**
 * Reads games for one team out of search-result text. It is deliberately conservative: a score is only taken when the two
 * numbers stand right next to the two team names (or in a "X beat Y 5-3" sentence) and are plausible for the league, so
 * records such as 88-74, years and times are not mistaken for scores. When in doubt it finds nothing, and the answer then
 * says so instead of guessing.
 */
object SportsParser {
    private const val WIN = "beat|defeated|edged|topped|downed|routed|blanked|shut out|outlasted|rallied past|held off|survived|thumped|overcame|stunned|nipped|clipped|handled|dominated|escaped"
    private const val LOSE = "lost to|fell to|dropped one to|were beaten by|fell against|lose to|losing to|lose against|were routed by|were edged by|fall to|dropped a game to"
    private const val RECORD = """(?:\s+\d{1,3}-\d{1,3}(?:-\d{1,3})?)?"""

    private val finalMark = Regex("""\b(final|final score|ended|full time|ft|postgame|recap|box score|walk-?off)\b""")
    private val liveMark = Regex(
        """\b(live|in progress|now playing|(top|bot|bottom|mid|middle|end) (?:of )?(?:the )?\d{1,2}(?:st|nd|rd|th)|\d{1,2}(?:st|nd|rd|th) (?:inning|quarter|period)""" +
            """|q[1-4]|halftime|half-?time|intermission|overtime|shootout|\d{1,2}:\d{2} (?:left|remaining|to go|in the \d))\b""",
    )
    private val upcomingMark = Regex("""\b(scheduled|upcoming|preview|will play|tonight|first pitch|puck drop|kickoff|kick-?off|tip-?off|game time|start time|starts at|pregame|next game|plays? (?:at|on|vs|against)|faces|hosts|visits)\b""")
    private val timeOfDay = Regex("""\b(\d{1,2})(?::(\d{2}))? ?(am|pm)\b(?: ?(et|est|edt|ct|cst|cdt|mt|mst|mdt|pt|pst|pdt|utc|gmt))?""")
    private val monthDay = Regex(
        """\b(jan|feb|mar|apr|may|jun|jul|aug|sep|sept|oct|nov|dec)(?:uary|ruary|ch|il|e|y|ust|tember|ober|ember)? (\d{1,2})(?:st|nd|rd|th)?(?:,? (\d{4}))?\b""",
    )
    private val numericDate = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""")
    private val weekdayWord = Regex("""\b(mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)(?:day|sday|nesday|rsday|urday)?\b""")

    private val months = mapOf(
        "jan" to Month.JANUARY, "feb" to Month.FEBRUARY, "mar" to Month.MARCH, "apr" to Month.APRIL, "may" to Month.MAY, "jun" to Month.JUNE,
        "jul" to Month.JULY, "aug" to Month.AUGUST, "sep" to Month.SEPTEMBER, "sept" to Month.SEPTEMBER, "oct" to Month.OCTOBER, "nov" to Month.NOVEMBER, "dec" to Month.DECEMBER,
    )
    private val zones = mapOf(
        "et" to "America/New_York", "est" to "America/New_York", "edt" to "America/New_York",
        "ct" to "America/Chicago", "cst" to "America/Chicago", "cdt" to "America/Chicago",
        "mt" to "America/Denver", "mst" to "America/Denver", "mdt" to "America/Denver",
        "pt" to "America/Los_Angeles", "pst" to "America/Los_Angeles", "pdt" to "America/Los_Angeles",
        "utc" to "UTC", "gmt" to "UTC",
    )

    /** Letters, digits and the few marks the patterns use; apostrophes and full stops vanish so "St. Louis" and "p.m." line up. */
    internal fun mini(s: String): String {
        val folded = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase(Locale.ROOT)
        val sb = StringBuilder(folded.length)
        for (c in folded) {
            when {
                c.isLetterOrDigit() -> sb.append(c)
                c == '-' || c == '–' || c == '—' || c == '−' -> sb.append('-')
                c == ':' || c == ',' || c == '/' || c == '@' -> sb.append(c)
                c == '\'' || c == '’' || c == '.' -> Unit
                c == '&' -> sb.append(" and ")
                else -> sb.append(' ')
            }
        }
        return sb.toString().replace(Regex(" +"), " ").trim()
    }

    private class NameSet(val own: String, val others: String, val byName: Map<String, Team>)

    private fun nameSet(team: Team): NameSet {
        val sameLeague = TeamDirectory.all.filter { it.league == team.league }
        // A spelling counts only when it names one team of the league ("Tigers" is unique in baseball, not in college football).
        val owners = HashMap<String, MutableSet<Team>>()
        fun add(name: String, t: Team) {
            val key = mini(name)
            if (key.length >= 3) owners.getOrPut(key) { mutableSetOf() }.add(t)
        }
        for (t in sameLeague) {
            add(t.fullName, t)
            add(t.nickname, t)
            t.aliases.forEach { add(it, t) }
            if (t.region.isNotBlank()) add(t.region, t)
        }
        val unique = owners.filterValues { it.size == 1 }.mapValues { it.value.single() }
        val own = unique.filterValues { it.id == team.id }.keys.sortedByDescending { it.length }
        val others = unique.filterValues { it.id != team.id && it.identity != team.identity }.keys.sortedByDescending { it.length }
        fun alt(names: List<String>) = names.joinToString("|") { Regex.escape(it) }
        return NameSet(alt(own), alt(others), unique)
    }

    private fun plausible(league: League, a: Int, b: Int): Boolean {
        val max = when (league) {
            League.MLB -> 30
            League.NHL -> 20
            League.NFL -> 80
            League.NBA, League.COLLEGE_BASKETBALL -> 200
            League.COLLEGE_FOOTBALL -> 120
        }
        return a in 0..max && b in 0..max
    }

    private val abbreviation = Regex("""\b(jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec|st|mt|vs|no|jr|sr|inc|ft|mon|tue|tues|wed|thu|thur|thurs|fri|sat|sun)\.""", RegexOption.IGNORE_CASE)
    private val clockDots = Regex("""\b([ap])\.m\.""", RegexOption.IGNORE_CASE)

    /** Splits at sentence ends but not after an abbreviation, so "Oct. 22" and "St. Louis" stay in one piece. */
    private fun sentences(text: String): List<String> {
        val guarded = clockDots.replace(abbreviation.replace(text) { "${it.groupValues[1]}\u0001" }) { "${it.groupValues[1]}\u0001m\u0001" }
        return guarded.split(Regex("""(?<=[.!?])\s+(?=[A-Z0-9])|\s[|•·]\s|\n"""))
            .map { it.replace('\u0001', '.').trim() }
            .filter { it.isNotEmpty() }
    }

    fun parse(team: Team, results: List<WebResult>, now: ZonedDateTime): List<GameFact> {
        val names = nameSet(team)
        if (names.own.isEmpty()) return emptyList()
        val facts = ArrayList<GameFact>()
        for (r in results) {
            val resultText = "${r.title}. ${r.content}"
            val topMini = mini(resultText.take(400))
            val pageDate = singleDate(topMini, now.toLocalDate())
            val parts = sentences(resultText)
            parts.forEachIndexed { i, sentence ->
                // a bare "Final." right before or after a score line belongs to it
                val neighbours = listOfNotNull(parts.getOrNull(i - 1), parts.getOrNull(i + 1)).filter { it.length <= 30 }.joinToString(" ") { mini(it) }
                parseSentence(team, names, sentence, r, pageDate, now, neighbours)?.let { facts += it }
            }
        }
        return facts
    }

    private fun parseSentence(team: Team, n: NameSet, original: String, r: WebResult, pageDate: LocalDate?, now: ZonedDateTime, neighbours: String): GameFact? {
        val s = mini(original)
        if (n.others.isEmpty() || !Regex("\\b(" + n.own + ")\\b").containsMatchIn(s)) return null
        val a = n.own
        val o = n.others
        val league = team.league

        var opp: Team? = null
        var ts: Int? = null
        var os: Int? = null
        var viaVerb = false

        fun take(m: MatchResult?, oppIdx: Int, tIdx: Int, oIdx: Int, requireWinnerFirst: Boolean = false, teamIsWinner: Boolean? = null): Boolean {
            m ?: return false
            val opponent = n.byName[m.groupValues[oppIdx]] ?: return false
            val x = m.groupValues[tIdx].toInt()
            val y = m.groupValues[oIdx].toInt()
            if (!plausible(league, x, y)) return false
            // "X beat Y 3-5" is not a result: in a win or loss sentence the first number written is the winner's, so it is the larger
            if (requireWinnerFirst && m.groupValues[3].toInt() < m.groupValues[4].toInt()) return false
            opp = opponent
            ts = x
            os = y
            if (teamIsWinner != null) viaVerb = true
            return true
        }

        // "Tigers 5, Royals 3" and "Royals 3, Tigers 5": each score stands right after its own team
        val r1 = Regex("""\b($a)\b ?:? ?(\d{1,3})\b ?[,;-]? ?\b($o)\b ?:? ?(\d{1,3})\b""").find(s)
        val r1r = Regex("""\b($o)\b ?:? ?(\d{1,3})\b ?[,;-]? ?\b($a)\b ?:? ?(\d{1,3})\b""").find(s)
        val winA = Regex("""\b($a)\b$RECORD (?:$WIN) (?:the )?\b($o)\b[^0-9]{0,25}?\b(\d{1,3}) ?- ?(\d{1,3})\b""").find(s)
        val winO = Regex("""\b($o)\b$RECORD (?:$WIN) (?:the )?\b($a)\b[^0-9]{0,25}?\b(\d{1,3}) ?- ?(\d{1,3})\b""").find(s)
        val loseA = Regex("""\b($a)\b$RECORD (?:$LOSE) (?:the )?\b($o)\b[^0-9]{0,25}?\b(\d{1,3}) ?- ?(\d{1,3})\b""").find(s)
        val loseO = Regex("""\b($o)\b$RECORD (?:$LOSE) (?:the )?\b($a)\b[^0-9]{0,25}?\b(\d{1,3}) ?- ?(\d{1,3})\b""").find(s)
        val dash = Regex("""\b($a)\b (\d{1,3}) ?- ?(\d{1,3}) (?:over |vs |against )?\b($o)\b""").find(s)
        val dashR = Regex("""\b($o)\b (\d{1,3}) ?- ?(\d{1,3}) (?:over |vs |against )?\b($a)\b""").find(s)

        val matched = when {
            take(winA, 2, 3, 4, requireWinnerFirst = true, teamIsWinner = true) -> true
            take(winO, 1, 4, 3, requireWinnerFirst = true, teamIsWinner = false) -> true
            // "X lost to Y 5-3": the winner's score is written first, so the opponent holds the first number
            take(loseA, 2, 4, 3, requireWinnerFirst = true, teamIsWinner = false) -> true
            take(loseO, 1, 3, 4, requireWinnerFirst = true, teamIsWinner = true) -> true
            take(r1, 3, 2, 4) -> true
            take(r1r, 1, 4, 2) -> true
            else -> false
        } || run {
            // "Tigers 5-3 Royals": the first number belongs to the first team named; only trusted with a status word
            val hasStatus = finalMark.containsMatchIn(s) || liveMark.containsMatchIn(s)
            hasStatus && (take(dash, 4, 2, 3) || take(dashR, 1, 3, 2))
        }

        val timeInfo = timeOfDay.find(s)
        val home = homeAway(s, a, o)

        val status: GameStatus
        if (matched) {
            status = when {
                finalMark.containsMatchIn(s) || viaVerb -> GameStatus.FINAL
                liveMark.containsMatchIn(s) -> GameStatus.IN_PROGRESS
                else -> GameStatus.UNKNOWN
            }
            // A status word elsewhere in the same result ("Final" in its title) settles a bare score line.
            val resolved = if (status == GameStatus.UNKNOWN) {
                val title = mini(r.title) + " " + neighbours
                when {
                    finalMark.containsMatchIn(title) -> GameStatus.FINAL
                    liveMark.containsMatchIn(title) -> GameStatus.IN_PROGRESS
                    else -> GameStatus.UNKNOWN
                }
            } else {
                status
            }
            val date = dateIn(s, now.toLocalDate(), resolved) ?: pageDate
            return GameFact(
                opponent = opp, teamScore = ts, opponentScore = os, status = resolved, date = date, start = null, timeText = null,
                home = home, detail = liveDetail(s), host = r.host, url = r.url, quote = original.take(240),
            )
        }

        // No score: maybe a schedule line, "Tigers vs Royals, Oct 22, 7:10 PM ET"
        val oppMention = Regex("""\b($o)\b""").find(s) ?: return null
        val opponent = n.byName[oppMention.groupValues[1]] ?: return null
        if (!upcomingMark.containsMatchIn(s) && timeInfo == null) return null
        if (finalMark.containsMatchIn(s)) return null
        val date = dateIn(s, now.toLocalDate(), GameStatus.UPCOMING) ?: pageDate
        var start: ZonedDateTime? = null
        var timeText: String? = null
        if (timeInfo != null) {
            val h12 = timeInfo.groupValues[1].toInt()
            val min = timeInfo.groupValues[2].ifEmpty { "0" }.toInt()
            if (h12 in 1..12 && min in 0..59) {
                val h24 = (h12 % 12) + if (timeInfo.groupValues[3] == "pm") 12 else 0
                val zoneId = zones[timeInfo.groupValues[4]]
                if (date != null && zoneId != null) {
                    start = ZonedDateTime.of(date, LocalTime.of(h24, min), ZoneId.of(zoneId))
                } else {
                    timeText = "$h12:${min.toString().padStart(2, '0')} ${timeInfo.groupValues[3].uppercase()}"
                }
            }
        }
        return GameFact(
            opponent = opponent, teamScore = null, opponentScore = null, status = GameStatus.UPCOMING, date = date, start = start, timeText = timeText,
            home = home, detail = null, host = r.host, url = r.url, quote = original.take(240),
        )
    }

    private fun homeAway(s: String, a: String, o: String): Boolean? = when {
        Regex("""\b($a)\b[^0-9]{0,30}?\b(?:at|@) (?:the )?\b($o)\b""").containsMatchIn(s) -> false
        Regex("""\b($a)\b[^0-9]{0,30}?\b(?:vs|versus|hosts|host|against|v) (?:the )?\b($o)\b""").containsMatchIn(s) -> true
        Regex("""\b($o)\b[^0-9]{0,30}?\b(?:at|@) (?:the )?\b($a)\b""").containsMatchIn(s) -> true
        else -> null
    }

    private fun liveDetail(s: String): String? =
        liveMark.find(s)?.value?.replaceFirstChar { it.uppercase() }

    /** A date written out in [s]. Without a year the date nearest to [today] is taken, looking a little further back for finals. */
    private fun dateIn(s: String, today: LocalDate, status: GameStatus): LocalDate? {
        monthDay.find(s)?.let { m ->
            val month = months[m.groupValues[1]] ?: return@let
            val day = m.groupValues[2].toInt()
            val year = m.groupValues[3].toIntOrNull()
            return build(year, month, day, today)
        }
        numericDate.find(s)?.let { m ->
            val mo = m.groupValues[1].toInt()
            val d = m.groupValues[2].toInt()
            if (mo !in 1..12) return@let
            val y = m.groupValues[3].toIntOrNull()?.let { if (it < 100) 2000 + it else it }
            return build(y, Month.of(mo), d, today)
        }
        // "Mon" alone is a weekday of the last/next week: close enough to compare, never to prove a date
        weekdayWord.find(s)?.let { m ->
            val dow = when (m.groupValues[1]) {
                "mon" -> DayOfWeek.MONDAY
                "tue", "tues" -> DayOfWeek.TUESDAY
                "wed" -> DayOfWeek.WEDNESDAY
                "thu", "thur", "thurs" -> DayOfWeek.THURSDAY
                "fri" -> DayOfWeek.FRIDAY
                "sat" -> DayOfWeek.SATURDAY
                else -> DayOfWeek.SUNDAY
            }
            return if (status == GameStatus.UPCOMING) today.with(java.time.temporal.TemporalAdjusters.nextOrSame(dow)) else today.with(java.time.temporal.TemporalAdjusters.previousOrSame(dow))
        }
        return null
    }

    /** A page-level date, used only when the top of the result names exactly one. */
    private fun singleDate(topMini: String, today: LocalDate): LocalDate? {
        val found = monthDay.findAll(topMini).mapNotNull { m ->
            val month = months[m.groupValues[1]] ?: return@mapNotNull null
            build(m.groupValues[3].toIntOrNull(), month, m.groupValues[2].toInt(), today)
        }.toSet()
        return found.singleOrNull()
    }

    private fun build(year: Int?, month: Month, day: Int, today: LocalDate): LocalDate? {
        if (day !in 1..31) return null
        fun make(y: Int) = try { LocalDate.of(y, month, day) } catch (_: java.time.DateTimeException) { null }
        if (year != null) return make(year)
        val candidates = listOf(today.year - 1, today.year, today.year + 1).mapNotNull(::make)
        return candidates.minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(today, it)) }?.takeIf { kotlin.math.abs(ChronoUnit.DAYS.between(today, it)) <= 200 }
    }
}
