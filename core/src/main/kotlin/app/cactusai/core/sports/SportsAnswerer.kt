package app.cactusai.core.sports

import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.info.InfoAnswer
import app.cactusai.core.info.Source
import app.cactusai.core.web.SearchOutcome
import app.cactusai.core.web.WebResult
import app.cactusai.core.web.WebSearchProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class SportsKind { SCORE, RESULT, SCHEDULE }

enum class SportsDay { NOW, YESTERDAY, TOMORROW, NEXT, LAST }

data class SportsIntent(val kind: SportsKind, val day: SportsDay)

/** What the user asked, in the tool's own terms. [userText] is the whole spoken request. */
data class SportsQuestion(val team: String?, val league: League?, val whenKey: String?, val userText: String)

object SportsIntents {
    private val resultWords = Regex("""\b(did|does|do) .*\b(win|beat|lose|lost)\b|\b(who won|result|final score|how did)\b""")
    private val scheduleWords = Regex("""\b(when|what time|schedule|next game|play next|plays next|kick-?off|first pitch|tip-?off|puck drop|start)\b""")

    fun from(userText: String, whenKey: String?): SportsIntent {
        val t = userText.lowercase(Locale.ROOT).replace("'", "")
        val day = when (whenKey) {
            "yesterday" -> SportsDay.YESTERDAY
            "tomorrow" -> SportsDay.TOMORROW
            "next" -> SportsDay.NEXT
            "last" -> SportsDay.LAST
            "today" -> SportsDay.NOW
            else -> when {
                Regex("""\byesterday\b""").containsMatchIn(t) -> SportsDay.YESTERDAY
                Regex("""\btomorrow\b""").containsMatchIn(t) -> SportsDay.TOMORROW
                Regex("""\b(last game|last night)\b""").containsMatchIn(t) -> SportsDay.LAST
                scheduleWords.containsMatchIn(t) && !Regex("""\b(today|tonight|now)\b""").containsMatchIn(t) -> SportsDay.NEXT
                else -> SportsDay.NOW
            }
        }
        val kind = when {
            day == SportsDay.TOMORROW || day == SportsDay.NEXT -> SportsKind.SCHEDULE
            day == SportsDay.YESTERDAY || day == SportsDay.LAST -> SportsKind.RESULT
            resultWords.containsMatchIn(t) -> SportsKind.RESULT
            scheduleWords.containsMatchIn(t) -> SportsKind.SCHEDULE
            else -> SportsKind.SCORE
        }
        return SportsIntent(kind, day)
    }
}

enum class Verification {
    /** Two independent sites agree (or an official data service said so, freshly). */
    VERIFIED,

    /** One site said it. Shown as a report, not as a fact. */
    SINGLE_SOURCE,

    /** A game in progress found in a search result. A page cannot prove how fresh it is, so this is never verified. */
    UNVERIFIED_LIVE,

    /** A score or time with no status, or no date, to place it. */
    UNCLEAR,
}

data class Finding(val fact: GameFact, val level: Verification, val supporting: List<GameFact>, val timeConflict: Boolean = false) {
    val hosts: List<String> get() = supporting.map { it.host }.distinct()
}

/** Decides what, if anything, may be stated as confirmed. The rules are in docs/ASSISTANT_INFO.md. */
object SportsVerifier {
    /**
     * [official] is true for a dedicated sports data service (its facts count as confirmed on their own); [fresh] is true when
     * that service's data is only seconds old, which is what allows a game in progress to be called verified.
     */
    fun assess(facts: List<GameFact>, intent: SportsIntent, today: LocalDate, official: Boolean = false, fresh: Boolean = false): Finding? {
        val finals = facts.filter { it.status == GameStatus.FINAL && it.teamScore != null && it.opponentScore != null && it.opponent != null }
        val live = facts.filter { it.status == GameStatus.IN_PROGRESS && it.teamScore != null && it.opponentScore != null && it.opponent != null }
        val upcoming = facts.filter { it.status == GameStatus.UPCOMING && it.opponent != null && (it.date == null || !it.date.isBefore(today)) }
        val unclear = facts.filter { it.status == GameStatus.UNKNOWN && it.teamScore != null && it.opponent != null }

        return when (intent.kind) {
            SportsKind.RESULT -> finalFinding(finals, intent, today, official) ?: liveFinding(live, official, fresh) ?: unclearFinding(unclear)
            SportsKind.SCHEDULE -> upcomingFinding(upcoming, intent, today, official)
            SportsKind.SCORE -> finalFinding(finals, intent, today, official)?.takeIf { it.level == Verification.VERIFIED }
                ?: liveFinding(live, official, fresh)
                ?: upcomingFinding(upcoming.filter { it.date == null || it.date == today }, intent, today, official)
                ?: finalFinding(finals, intent, today, official)
                ?: unclearFinding(unclear)
        }
    }

    private fun expectedDate(intent: SportsIntent, today: LocalDate): LocalDate? = when (intent.day) {
        SportsDay.YESTERDAY -> today.minusDays(1)
        SportsDay.NOW -> today
        SportsDay.TOMORROW -> today.plusDays(1)
        else -> null
    }

    private fun dateFits(f: GameFact, intent: SportsIntent, today: LocalDate): Boolean {
        val d = f.date ?: return false
        expectedDate(intent, today)?.let { return d == it }
        return when (intent.day) {
            SportsDay.LAST -> !d.isAfter(today) && !d.isBefore(today.minusDays(30))
            else -> true
        }
    }

    private fun finalFinding(finals: List<GameFact>, intent: SportsIntent, today: LocalDate, official: Boolean): Finding? {
        if (finals.isEmpty()) return null
        val groups = finals.groupBy { "${it.opponent!!.id}|${it.teamScore}|${it.opponentScore}" }.values
        val best = groups.maxWithOrNull(compareBy({ g -> g.count { dateFits(it, intent, today) } > 0 }, { g -> g.map { it.host }.distinct().size }, { g -> g.maxOf { it.date ?: LocalDate.MIN } })) ?: return null
        val dated = best.filter { dateFits(it, intent, today) }
        val hosts = best.map { it.host }.distinct()
        val level = when {
            dated.isNotEmpty() && (official || hosts.size >= 2) -> Verification.VERIFIED
            else -> Verification.SINGLE_SOURCE
        }
        // A result that is not the game asked about (an older final) is not offered as the answer for "yesterday".
        if (dated.isEmpty() && expectedDate(intent, today) != null && best.all { it.date != null }) return null
        val chosen = dated.firstOrNull() ?: best.first()
        return Finding(chosen, level, best)
    }

    private fun liveFinding(live: List<GameFact>, official: Boolean, fresh: Boolean): Finding? {
        if (live.isEmpty()) return null
        val groups = live.groupBy { "${it.opponent!!.id}|${it.teamScore}|${it.opponentScore}" }.values
        val best = groups.maxByOrNull { g -> g.map { it.host }.distinct().size } ?: return null
        val level = if (official && fresh) Verification.VERIFIED else Verification.UNVERIFIED_LIVE
        return Finding(best.first(), level, best)
    }

    private fun upcomingFinding(upcoming: List<GameFact>, intent: SportsIntent, today: LocalDate, official: Boolean): Finding? {
        if (upcoming.isEmpty()) return null
        val wanted = if (intent.day == SportsDay.TOMORROW) upcoming.filter { it.date == today.plusDays(1) } else upcoming
        val pool = wanted.ifEmpty { return null }
        val groups = pool.groupBy { "${it.opponent!!.id}|${it.date}" }
        val ordered = groups.values.sortedWith(compareBy({ g -> g.first().date ?: LocalDate.MAX }, { g -> -g.map { it.host }.distinct().size }))
        val best = ordered.first()
        val hosts = best.map { it.host }.distinct()
        val dated = best.any { it.date != null }
        val level = when {
            !dated -> Verification.UNCLEAR
            official || hosts.size >= 2 -> Verification.VERIFIED
            else -> Verification.SINGLE_SOURCE
        }
        val starts = best.mapNotNull { it.start?.toInstant() }.distinct()
        val chosen = best.firstOrNull { it.start != null } ?: best.first()
        return Finding(chosen, level, best, timeConflict = starts.size > 1)
    }

    private fun unclearFinding(unclear: List<GameFact>): Finding? =
        unclear.firstOrNull()?.let { Finding(it, Verification.UNCLEAR, listOf(it)) }
}

/** A dedicated sports data service (a league or data provider API). None is built in; the app asks these before the web. */
interface SportsDataProvider {
    val name: String

    /** Facts for [team], or null when the service has nothing for it or is not available right now. */
    suspend fun lookup(team: Team, intent: SportsIntent, now: ZonedDateTime): StructuredGames?
}

data class StructuredGames(val facts: List<GameFact>, val asOfMs: Long)

/**
 * Answers sports questions. Order of preference: a structured data service (if one is configured), then web search. Whatever
 * the source, nothing is stated as confirmed unless [SportsVerifier] allows it, and every answer says when it was checked.
 */
class SportsAnswerer(
    private val web: WebSearchProvider?,
    private val clock: Clock,
    private val locale: Locale,
    private val use24Hour: Boolean,
    private val favorites: () -> List<String> = { emptyList() },
    private val structured: List<SportsDataProvider> = emptyList(),
) {
    suspend fun answer(q: SportsQuestion): InfoAnswer {
        val now = ZonedDateTime.now(clock)
        val today = now.toLocalDate()
        val intent = SportsIntents.from(q.userText, q.whenKey)

        val resolution = TeamResolver.resolve(q.team, q.userText, q.league, favorites(), today)
        return when (resolution) {
            is TeamResolution.Ambiguous -> clarify(resolution)
            is TeamResolution.Unknown -> unknownTeam(q, intent, now)
            is TeamResolution.Found -> known(resolution, intent, now, q.userText)
        }
    }

    // ---- ambiguous or unknown teams ----

    private fun clarify(r: TeamResolution.Ambiguous): InfoAnswer {
        val options = r.options.distinctBy { it.id }.take(4)
        val list = options.joinToString(", ", limit = 4) { "the ${it.fullName} (${it.league.display})" }
        val text = "Which team do you mean: $list? Say it again with the city or the sport, or add the team to your favorites in Settings."
        return InfoAnswer(text = text, verified = false, note = "No online request was made.")
    }

    private suspend fun unknownTeam(q: SportsQuestion, intent: SportsIntent, now: ZonedDateTime): InfoAnswer {
        val said = q.team?.trim().orEmpty()
        val search = web ?: return noWeb()
        if (said.isBlank() && q.league == null) {
            return InfoAnswer("Tell me which team you mean, for example the Tigers or the Lions.", verified = false)
        }
        val subject = said.ifBlank { q.league!!.display }
        val query = "$subject ${intentWords(intent)} ${dateWords(intent, now.toLocalDate())}".trim()
        return when (val r = search.search(query)) {
            is SearchOutcome.Failed -> failure(r.message)
            is SearchOutcome.Results -> {
                val text = "I did not recognise “$subject” as a team in the NFL, MLB, NHL, NBA or college football and basketball lists, so I can't check a score for it. " +
                    "A web search for exactly what you said found the results below."
                InfoAnswer(
                    text = text, origin = AnswerOrigin.ONLINE, sources = sourcesOf(r.results), retrievedAtMs = r.retrievedAtMs, verified = false,
                    note = "Not checked: team not recognised.",
                )
            }
        }
    }

    // ---- a recognised team ----

    private suspend fun known(r: TeamResolution.Found, intent: SportsIntent, now: ZonedDateTime, userText: String): InfoAnswer {
        val team = r.team
        val today = now.toLocalDate()

        for (provider in structured) {
            val data = provider.lookup(team, intent, now) ?: continue
            if (data.facts.isEmpty()) continue
            val fresh = now.toInstant().toEpochMilli() - data.asOfMs <= 120_000
            SportsVerifier.assess(data.facts, intent, today, official = true, fresh = fresh)?.let { f ->
                return compose(team, r.assumed, intent, userText, f, now, data.asOfMs, provider.name, emptyList())
            }
        }

        val search = web ?: return noWeb()
        val query = buildQuery(team, intent, today)
        val first = search.search(query)
        val results = when (first) {
            is SearchOutcome.Failed -> return failure(first.message)
            is SearchOutcome.Results -> first
        }
        val onPage = pageText(results)
        val facts = SportsParser.parse(team, results.results + onPage, now)
        val finding = SportsVerifier.assess(facts, intent, today)
        return compose(team, r.assumed, intent, userText, finding, now, results.retrievedAtMs, search.name, results.results)
    }

    /** The text above the first result and the highlighted answer, read as two more sources from the search page itself. */
    private fun pageText(r: SearchOutcome.Results): List<WebResult> {
        val url = r.pageUrl ?: "https://www.google.com/search"
        return listOfNotNull(r.header, r.featured).map { WebResult("Search page", url, it) }
    }

    fun buildQuery(team: Team, intent: SportsIntent, today: LocalDate): String {
        val name = team.fullName
        val date = dateWords(intent, today)
        return "$name ${intentWords(intent)} $date".replace(Regex(" +"), " ").trim()
    }

    private fun intentWords(intent: SportsIntent) = when (intent.kind) {
        SportsKind.SCORE -> "score"
        SportsKind.RESULT -> "final score result"
        SportsKind.SCHEDULE -> "next game schedule"
    }

    private fun dateWords(intent: SportsIntent, today: LocalDate): String {
        val f = DateTimeFormatter.ofPattern("MMMM d yyyy", Locale.US)
        return when (intent.day) {
            SportsDay.NOW -> "today ${f.format(today)}"
            SportsDay.YESTERDAY -> f.format(today.minusDays(1))
            SportsDay.TOMORROW -> f.format(today.plusDays(1))
            SportsDay.NEXT -> f.format(today)
            SportsDay.LAST -> f.format(today)
        }
    }

    // ---- wording ----

    private fun name(t: Team) = if (t.league.isCollege) t.region else t.nickname

    private fun noWeb() = InfoAnswer("Online search is not available, so I can't look up sports.", verified = false)

    private fun failure(message: String) = InfoAnswer(message, verified = false)

    private fun sourcesOf(results: List<WebResult>) = results.take(4).map { Source(it.title, it.url, it.content.take(160).ifBlank { null }) }

    private fun clock(ms: Long): String {
        val fmt = DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm" else "h:mm a", locale)
        return fmt.format(Instant.ofEpochMilli(ms).atZone(clock.zone))
    }

    /** "today", "yesterday", "tomorrow" or "on Thursday, October 22", ready to follow a verb. */
    private fun dateText(d: LocalDate, today: LocalDate): String = when (d) {
        today -> "today"
        today.minusDays(1) -> "yesterday"
        today.plusDays(1) -> "tomorrow"
        else -> "on " + DateTimeFormatter.ofPattern(if (d.year == today.year) "EEEE, MMMM d" else "EEEE, MMMM d, yyyy", locale).format(d)
    }

    private fun startText(f: GameFact): String? = when {
        f.start != null -> {
            val local = f.start.withZoneSameInstant(clock.zone)
            DateTimeFormatter.ofPattern(if (use24Hour) "HH:mm z" else "h:mm a z", locale).format(local)
        }
        f.timeText != null -> "${f.timeText} (the page did not say which time zone)"
        else -> null
    }

    private fun hostsText(hosts: List<String>): String = when (hosts.size) {
        0 -> ""
        1 -> hosts[0]
        2 -> "${hosts[0]} and ${hosts[1]}"
        else -> hosts.take(2).joinToString(", ") + " and ${hosts[2]}"
    }

    private fun spoken(text: String) = text.replace("–", " to ").replace(Regex("""\.(com|org|net|tv)\b"""), " dot $1")

    private fun compose(
        team: Team,
        assumed: String?,
        intent: SportsIntent,
        userText: String,
        finding: Finding?,
        now: ZonedDateTime,
        retrievedAtMs: Long,
        sourceName: String,
        allResults: List<WebResult>,
    ): InfoAnswer {
        val today = now.toLocalDate()
        val me = name(team)
        val checked = "Checked ${clock(retrievedAtMs)} from $sourceName."
        val sources = if (finding != null) {
            val fromFacts = finding.supporting.map { Source(it.host, it.url, it.quote) }.distinctBy { it.url }
            (fromFacts + sourcesOf(allResults)).distinctBy { it.url }.take(5)
        } else {
            sourcesOf(allResults)
        }
        val assumedNote = assumed?.let { "I assumed the ${team.fullName}: $it." }
        val offSeason = if (!team.league.inSeason(today) && intent.kind != SportsKind.RESULT) "The ${team.league.display} is not in season right now." else null

        if (finding == null) {
            val kindText = if (intent.kind == SportsKind.SCHEDULE) "schedule" else "score"
            val text = buildString {
                append("I couldn't find a $kindText for the $me that I could verify. ")
                offSeason?.let { append(it).append(' ') }
                append("The top results are listed below. ").append(checked)
            }
            return InfoAnswer(text = text.trim(), spoken = spoken(text.trim()), origin = AnswerOrigin.ONLINE, sources = sources, retrievedAtMs = retrievedAtMs, note = assumedNote, verified = false)
        }

        val f = finding.fact
        val opp = f.opponent?.let(::name) ?: "their opponent"
        val hosts = hostsText(finding.hosts)
        val a = f.teamScore
        val b = f.opponentScore
        val dateWhen = f.date?.let { dateText(it, today) }
        val text: String
        val verified = finding.level == Verification.VERIFIED

        when (f.status) {
            GameStatus.FINAL -> {
                val score = if (a!! >= b!!) "$a–$b" else "$b–$a"
                val sentence = when {
                    a > b -> "the $me beat the $opp $score"
                    a < b -> "the $me lost to the $opp $score"
                    else -> "the $me and the $opp tied $a–$b"
                }
                val yesNo = Regex("""\b(did|does|do)\b.*\b(win|beat|lose|lost)\b""").containsMatchIn(userText.lowercase(Locale.ROOT))
                val lead = if (yesNo) (if (a > b) "Yes, " else "No, ") else ""
                val whenPart = dateWhen?.let { " $it" }.orEmpty()
                text = when (finding.level) {
                    Verification.VERIFIED -> "$lead$sentence$whenPart.".replaceFirstChar { it.uppercase() } + " Confirmed by $hosts. $checked"
                    else -> "$hosts reports $sentence${dateWhen?.let { " $it" } ?: ""}. I could not confirm that with a second source${if (f.date == null) " or find its date" else ""}, so treat it as unconfirmed. $checked"
                }
            }
            GameStatus.IN_PROGRESS -> {
                val detail = f.detail?.let { " ($it)" }.orEmpty()
                text = if (verified) {
                    "Live: the $me $a, the $opp $b$detail. Updated ${clock(retrievedAtMs)} by $sourceName."
                } else {
                    "A search result from $hosts shows the $me $a, the $opp $b$detail, but I can't verify that it is live right now: search results can lag behind the game. " +
                        "$checked Check $hosts for the current score."
                }
            }
            GameStatus.UPCOMING -> {
                val verb = when (f.home) {
                    true -> "host the $opp"
                    false -> "play at the $opp"
                    null -> "play the $opp"
                }
                val whenPart = dateWhen?.let { " $it" }.orEmpty()
                val startPart = startText(f)?.let { " at $it" } ?: if (f.date != null) ". The start time isn't confirmed" else ""
                val core = (if (intent.day == SportsDay.NEXT) "The $me next $verb" else "The $me $verb") + whenPart + startPart
                text = when (finding.level) {
                    Verification.VERIFIED -> "$core. Confirmed by $hosts.${if (finding.timeConflict) " The sources disagree about the start time." else ""} $checked"
                    Verification.SINGLE_SOURCE -> "$hosts lists: $core. I could not confirm that with a second source, so treat it as unconfirmed. $checked"
                    else -> "$hosts lists: $core. I could not tell the date for certain, so treat it as unconfirmed. $checked"
                }
            }
            GameStatus.UNKNOWN -> {
                text = "$hosts shows the $me $a, the $opp $b, but it does not say whether the game is over or still going, so I can't confirm it. $checked"
            }
        }
        val note = listOfNotNull(assumedNote, offSeason).joinToString(" ").ifBlank { null }
        return InfoAnswer(
            text = text.replace(Regex(" +"), " ").trim(),
            spoken = spoken(text).replace(Regex(" +"), " ").trim(),
            origin = AnswerOrigin.ONLINE,
            sources = sources,
            retrievedAtMs = retrievedAtMs,
            note = note,
            verified = verified,
        )
    }
}
