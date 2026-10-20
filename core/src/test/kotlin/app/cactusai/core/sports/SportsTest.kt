package app.cactusai.core.sports

import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.web.SearchOutcome
import app.cactusai.core.web.WebFailure
import app.cactusai.core.web.WebResult
import app.cactusai.core.web.WebSearchProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every snippet below is SYNTHETIC: written to look like the way sports sites phrase results, not copied from any site and
 * not a real game. They test the parsing and verification rules. How well the parser reads real search results is only
 * known after trying it on a phone (docs/DEVICE_TEST_CHECKLIST.md).
 */
class SportsTest {
    private val detroit = ZoneId.of("America/Detroit")

    // Tuesday 20 October 2026, 3:42 PM in Detroit
    private val clock = Clock.fixed(Instant.parse("2026-10-20T19:42:00Z"), detroit)
    private val now = ZonedDateTime.now(clock)
    private val today = LocalDate.of(2026, 10, 20)

    private val tigers = TeamDirectory.all.first { it.fullName == "Detroit Tigers" }
    private val lions = TeamDirectory.all.first { it.fullName == "Detroit Lions" }
    private val redWings = TeamDirectory.all.first { it.fullName == "Detroit Red Wings" }
    private val michigan = TeamDirectory.all.first { it.fullName == "Michigan Wolverines" && it.league == League.COLLEGE_FOOTBALL }

    private fun r(host: String, title: String, content: String) = WebResult(title, "https://$host/page", content)

    private fun parse(team: Team, vararg results: WebResult) = SportsParser.parse(team, results.toList(), now)

    // ---- parsing finals ----

    @Test
    fun aScoreWrittenNextToTheTeamNamesIsRead() {
        val f = parse(tigers, r("espn.com", "Tigers vs. Royals - Game Recap - October 19, 2026", "Detroit Tigers 5, Kansas City Royals 3. Final.")).single()
        assertEquals(GameStatus.FINAL, f.status)
        assertEquals("Kansas City Royals", f.opponent!!.fullName)
        assertEquals(5, f.teamScore)
        assertEquals(3, f.opponentScore)
        assertEquals(LocalDate.of(2026, 10, 19), f.date)
        assertEquals("espn.com", f.host)
    }

    @Test
    fun theOrderOfTheTeamsDoesNotMixUpTheScores() {
        val facts = parse(tigers, r("espn.com", "Final: Royals 7, Tigers 2", "Kansas City Royals 7, Detroit Tigers 2. Final. Oct. 19, 2026."))
        assertTrue(facts.isNotEmpty())
        assertTrue(facts.all { it.teamScore == 2 && it.opponentScore == 7 })
    }

    @Test
    fun aBeatSentenceGivesTheWinnersScoreFirst() {
        val win = parse(tigers, r("mlb.com", "Tigers top Royals", "The Detroit Tigers defeated the Kansas City Royals, 5-3, on Monday, Oct. 19 at home.")).single()
        assertEquals(GameStatus.FINAL, win.status)
        assertEquals(5 to 3, win.teamScore to win.opponentScore)
        assertEquals(LocalDate.of(2026, 10, 19), win.date)

        val loss = parse(tigers, r("mlb.com", "Royals win", "The Kansas City Royals beat the Detroit Tigers 6-1 on Oct. 19.")).single()
        assertEquals(1 to 6, loss.teamScore to loss.opponentScore)

        val lostTo = parse(tigers, r("cbs.com", "Recap", "Detroit fell to the Kansas City Royals, 4-2, on Oct. 19.".replace("Detroit fell", "Detroit Tigers fell"))).single()
        assertEquals(2 to 4, lostTo.teamScore to lostTo.opponentScore)

        val wonOver = parse(tigers, r("cbs.com", "Recap", "The Kansas City Royals lost to the Detroit Tigers 3-2 on Oct. 19.")).single()
        assertEquals(3 to 2, wonOver.teamScore to wonOver.opponentScore)
    }

    @Test
    fun aLosingScoreWrittenFirstIsNotAWinSentence() {
        assertTrue(parse(tigers, r("x.com", "t", "The Detroit Tigers defeated the Kansas City Royals 3-5 on Oct. 19.")).isEmpty())
    }

    @Test
    fun recordsYearsAndTimesAreNotScores() {
        assertTrue(parse(tigers, r("espn.com", "Standings", "Detroit Tigers 88-74 Kansas City Royals 70-92. Final standings.")).isEmpty())
        assertTrue(parse(tigers, r("espn.com", "Schedule", "Detroit Tigers 2026 Kansas City Royals 7:10 PM ET")).none { it.teamScore != null })
        // a record in brackets before the verb must not hide the real score
        val withRecord = parse(tigers, r("espn.com", "Recap", "The Detroit Tigers (88-74) beat the Kansas City Royals 5-3 on Oct. 19.")).single()
        assertEquals(5 to 3, withRecord.teamScore to withRecord.opponentScore)
    }

    @Test
    fun aBareScoreWithoutAStatusIsUnclearNotFinal() {
        val f = parse(tigers, r("example.com", "Scores", "Detroit Tigers 5, Kansas City Royals 3")).single()
        assertEquals(GameStatus.UNKNOWN, f.status)
    }

    @Test
    fun theStatusInTheTitleSettlesABareScoreLine() {
        val f = parse(tigers, r("example.com", "FINAL: Tigers at Royals", "Detroit Tigers 5, Kansas City Royals 3")).single()
        assertEquals(GameStatus.FINAL, f.status)
    }

    // ---- live and upcoming ----

    @Test
    fun aGameInProgressIsRecognisedWithItsInning() {
        val f = parse(tigers, r("espn.com", "Live: Tigers vs Royals", "Live: Detroit Tigers 3, Kansas City Royals 2. Top 6th inning.")).single()
        assertEquals(GameStatus.IN_PROGRESS, f.status)
        assertEquals(3 to 2, f.teamScore to f.opponentScore)
        assertNotNull(f.detail)
        val football = parse(lions, r("nfl.com", "Live", "Live: Chicago Bears 10, Detroit Lions 14. 3rd quarter.")).single()
        assertEquals(GameStatus.IN_PROGRESS, football.status)
        assertEquals(14 to 10, football.teamScore to football.opponentScore)
    }

    @Test
    fun aScheduleLineGivesOpponentDateAndATimeInTheNamedZone() {
        val f = parse(redWings, r("nhl.com", "Red Wings schedule", "Detroit Red Wings at Boston Bruins, Thursday, Oct. 22, 7:00 PM ET.")).single()
        assertEquals(GameStatus.UPCOMING, f.status)
        assertEquals("Boston Bruins", f.opponent!!.fullName)
        assertEquals(LocalDate.of(2026, 10, 22), f.date)
        assertEquals(false, f.home)
        assertEquals(ZonedDateTime.of(2026, 10, 22, 19, 0, 0, 0, ZoneId.of("America/New_York")).toInstant(), f.start!!.toInstant())
    }

    @Test
    fun aTimeWithoutAZoneIsKeptAsWrittenAndNeverConverted() {
        val f = parse(redWings, r("nhl.com", "Schedule", "Detroit Red Wings vs Boston Bruins, Oct. 22, 7:00 PM")).single()
        assertNull(f.start)
        assertEquals("7:00 PM", f.timeText)
        assertEquals(true, f.home)
    }

    @Test
    fun collegeTeamsAreReadBySchoolName() {
        val f = parse(michigan, r("espn.com", "Recap", "Michigan beat Ohio State 31-24 on Saturday, Oct. 17 in Ann Arbor. Final.")).single()
        assertEquals("Ohio State", f.opponent!!.region)
        assertEquals(31 to 24, f.teamScore to f.opponentScore)
    }

    @Test
    fun unrelatedTextFindsNothing() {
        assertTrue(parse(tigers, r("example.com", "Weather", "It will be sunny in Detroit on Tuesday with a high of 62.")).isEmpty())
        assertTrue(parse(tigers, r("example.com", "Tigers", "The tiger is the largest living cat species.")).isEmpty())
    }

    // ---- verification ----

    private fun fact(host: String, a: Int?, b: Int?, status: GameStatus, date: LocalDate?, opp: String = "Kansas City Royals", start: ZonedDateTime? = null, home: Boolean? = null) =
        GameFact(TeamDirectory.all.first { it.fullName == opp && it.league == League.MLB }, a, b, status, date, start, null, home, null, host, "https://$host/p", "q")

    private val result = SportsIntent(SportsKind.RESULT, SportsDay.YESTERDAY)
    private val yesterday = today.minusDays(1)

    @Test
    fun aResultIsVerifiedOnlyWhenTwoSitesAgreeOnTheScoreAndTheDate() {
        val two = listOf(fact("espn.com", 5, 3, GameStatus.FINAL, yesterday), fact("mlb.com", 5, 3, GameStatus.FINAL, yesterday))
        assertEquals(Verification.VERIFIED, SportsVerifier.assess(two, result, today)!!.level)
        val one = SportsVerifier.assess(two.take(1), result, today)!!
        assertEquals(Verification.SINGLE_SOURCE, one.level)
        // the same site twice is still one source
        val same = listOf(fact("espn.com", 5, 3, GameStatus.FINAL, yesterday), fact("espn.com", 5, 3, GameStatus.FINAL, yesterday))
        assertEquals(Verification.SINGLE_SOURCE, SportsVerifier.assess(same, result, today)!!.level)
    }

    @Test
    fun sitesThatDisagreeOnTheScoreDoNotVerifyEachOther() {
        val split = listOf(fact("espn.com", 5, 3, GameStatus.FINAL, yesterday), fact("mlb.com", 5, 4, GameStatus.FINAL, yesterday))
        assertEquals(Verification.SINGLE_SOURCE, SportsVerifier.assess(split, result, today)!!.level)
    }

    @Test
    fun anOlderResultIsNotOfferedForYesterday() {
        val old = listOf(fact("espn.com", 5, 3, GameStatus.FINAL, today.minusDays(9)), fact("mlb.com", 5, 3, GameStatus.FINAL, today.minusDays(9)))
        assertNull(SportsVerifier.assess(old, result, today))
    }

    @Test
    fun aResultWithoutADateCannotBeVerified() {
        val undated = listOf(fact("espn.com", 5, 3, GameStatus.FINAL, null), fact("mlb.com", 5, 3, GameStatus.FINAL, null))
        assertEquals(Verification.SINGLE_SOURCE, SportsVerifier.assess(undated, result, today)!!.level)
    }

    @Test
    fun aLiveScoreFromSearchResultsIsNeverVerified() {
        val live = listOf(fact("espn.com", 3, 2, GameStatus.IN_PROGRESS, today), fact("mlb.com", 3, 2, GameStatus.IN_PROGRESS, today))
        val score = SportsIntent(SportsKind.SCORE, SportsDay.NOW)
        assertEquals(Verification.UNVERIFIED_LIVE, SportsVerifier.assess(live, score, today)!!.level)
        // only an official feed whose data is seconds old can call it verified
        assertEquals(Verification.VERIFIED, SportsVerifier.assess(live.take(1), score, today, official = true, fresh = true)!!.level)
        assertEquals(Verification.UNVERIFIED_LIVE, SportsVerifier.assess(live.take(1), score, today, official = true, fresh = false)!!.level)
    }

    @Test
    fun aVerifiedFinalBeatsAStaleLiveSnippetForTodaysScore() {
        val facts = listOf(
            fact("espn.com", 3, 2, GameStatus.IN_PROGRESS, today),
            fact("espn.com", 6, 2, GameStatus.FINAL, today), fact("mlb.com", 6, 2, GameStatus.FINAL, today),
        )
        val f = SportsVerifier.assess(facts, SportsIntent(SportsKind.SCORE, SportsDay.NOW), today)!!
        assertEquals(Verification.VERIFIED, f.level)
        assertEquals(6, f.fact.teamScore)
    }

    @Test
    fun anUpcomingGameNeedsTwoSitesToBeVerified() {
        val start = ZonedDateTime.of(2026, 10, 22, 19, 0, 0, 0, ZoneId.of("America/New_York"))
        val next = SportsIntent(SportsKind.SCHEDULE, SportsDay.NEXT)
        val two = listOf(fact("nhl.com", null, null, GameStatus.UPCOMING, LocalDate.of(2026, 10, 22), start = start), fact("espn.com", null, null, GameStatus.UPCOMING, LocalDate.of(2026, 10, 22), start = start))
        assertEquals(Verification.VERIFIED, SportsVerifier.assess(two, next, today)!!.level)
        assertEquals(Verification.SINGLE_SOURCE, SportsVerifier.assess(two.take(1), next, today)!!.level)
        val conflict = two.take(1) + fact("espn.com", null, null, GameStatus.UPCOMING, LocalDate.of(2026, 10, 22), start = start.plusHours(1))
        val c = SportsVerifier.assess(conflict, next, today)!!
        assertEquals(Verification.VERIFIED, c.level)
        assertTrue(c.timeConflict)
    }

    @Test
    fun theNextGameIsTheEarliestOneAndPastDatesAreIgnored() {
        val next = SportsIntent(SportsKind.SCHEDULE, SportsDay.NEXT)
        val facts = listOf(
            fact("nhl.com", null, null, GameStatus.UPCOMING, LocalDate.of(2026, 10, 25)),
            fact("nhl.com", null, null, GameStatus.UPCOMING, LocalDate.of(2026, 10, 22)),
            fact("nhl.com", null, null, GameStatus.UPCOMING, LocalDate.of(2026, 10, 12)),
        )
        assertEquals(LocalDate.of(2026, 10, 22), SportsVerifier.assess(facts, next, today)!!.fact.date)
        val tomorrow = SportsIntent(SportsKind.SCHEDULE, SportsDay.TOMORROW)
        assertNull("nothing is on tomorrow", SportsVerifier.assess(facts, tomorrow, today))
    }

    // ---- the whole answer ----

    private class FakeWeb(private val outcome: SearchOutcome) : WebSearchProvider {
        override val name = "Fake Web Search"
        val queries = mutableListOf<String>()

        override suspend fun search(query: String, maxResults: Int): SearchOutcome {
            queries += query
            return outcome
        }
    }

    private fun results(vararg r: WebResult) = SearchOutcome.Results(r.toList(), clock.millis())

    private fun answerer(web: WebSearchProvider?, favorites: List<String> = emptyList(), structured: List<SportsDataProvider> = emptyList(), use24: Boolean = false) =
        SportsAnswerer(web, clock, Locale.US, use24, { favorites }, structured)

    private fun ask(a: SportsAnswerer, team: String?, text: String, whenKey: String? = null, league: League? = null) =
        runBlocking { a.answer(SportsQuestion(team, league, whenKey, text)) }

    private val recapEspn = r("espn.com", "Tigers vs. Royals - Game Recap - October 19, 2026", "Detroit Tigers 5, Kansas City Royals 3. Final.")
    private val recapMlb = r("mlb.com", "Tigers top Royals", "The Detroit Tigers defeated the Kansas City Royals, 5-3, on Monday, Oct. 19.")

    @Test
    fun didTheTigersWinYesterdayIsAnsweredWhenTwoSourcesAgree() {
        val web = FakeWeb(results(recapEspn, recapMlb))
        val a = ask(answerer(web), "Tigers", "Did the Tigers win yesterday?", "yesterday")
        assertTrue(a.text, a.text.startsWith("Yes, the Tigers beat the Royals 5–3 yesterday."))
        assertTrue(a.text.contains("Confirmed by espn.com and mlb.com."))
        assertTrue(a.text.contains("Checked 3:42 PM from Fake Web Search."))
        assertTrue(a.verified)
        assertEquals(AnswerOrigin.ONLINE, a.origin)
        assertEquals(clock.millis(), a.retrievedAtMs)
        assertTrue(a.sources.any { it.host == "espn.com" })
        assertTrue(a.spoken.contains("5 to 3"))
        assertTrue(a.spoken.contains("espn dot com"))
        // the query names the team and the date, so the search can find that game
        assertEquals("Detroit Tigers final score result October 19 2026", web.queries.single())
    }

    @Test
    fun aLossIsAnsweredNo() {
        val web = FakeWeb(results(
            r("espn.com", "Recap - October 19, 2026", "Kansas City Royals 7, Detroit Tigers 2. Final."),
            r("mlb.com", "Royals win", "The Kansas City Royals beat the Detroit Tigers 7-2 on Monday, Oct. 19."),
        ))
        val a = ask(answerer(web), "Tigers", "Did the Tigers win yesterday?", "yesterday")
        assertTrue(a.text, a.text.startsWith("No, the Tigers lost to the Royals 7–2 yesterday."))
    }

    @Test
    fun oneSourceIsReportedAsUnconfirmed() {
        val a = ask(answerer(FakeWeb(results(recapEspn))), "Tigers", "Did the Tigers win yesterday?", "yesterday")
        assertFalse(a.verified)
        assertTrue(a.text, a.text.contains("could not confirm that with a second source"))
        assertFalse(a.text.startsWith("Yes"))
    }

    @Test
    fun aLiveScoreIsPresentedAsUnverifiedWithItsFreshness() {
        val live = r("espn.com", "Live: Tigers vs Royals", "Live: Detroit Tigers 3, Kansas City Royals 2. Top 6th inning.")
        val a = ask(answerer(FakeWeb(results(live))), "Detroit Tigers", "What's the Detroit Tigers score?")
        assertFalse(a.verified)
        assertTrue(a.text, a.text.contains("can't verify that it is live right now"))
        assertTrue(a.text.contains("Checked 3:42 PM"))
        assertTrue(a.text.contains("3, the Royals 2"))
    }

    @Test
    fun theNextGameIsGivenInThePhonesTimeZone() {
        val web = FakeWeb(results(
            r("nhl.com", "Red Wings schedule", "Detroit Red Wings at Boston Bruins, Thursday, Oct. 22, 7:00 PM ET."),
            r("espn.com", "Red Wings", "Next game: Red Wings at Bruins, Oct. 22, 7 PM ET."),
        ))
        val a = ask(answerer(web), "Red Wings", "When do the Red Wings play next?", "next")
        assertTrue(a.text, a.text.startsWith("The Red Wings next play at the Bruins on Thursday, October 22 at 7:00 PM EDT."))
        assertTrue(a.text.contains("Confirmed by nhl.com and espn.com."))
        assertTrue(a.verified)
        assertEquals("Detroit Red Wings next game schedule October 20 2026", web.queries.single())
        // the same game shown to someone in California is three hours earlier
        val pacific = SportsAnswerer(web, Clock.fixed(clock.instant(), ZoneId.of("America/Los_Angeles")), Locale.US, false, { emptyList() })
        val west = runBlocking { pacific.answer(SportsQuestion("Red Wings", null, "next", "When do the Red Wings play next?")) }
        assertTrue(west.text, west.text.contains("at 4:00 PM PDT"))
        // and with the 24-hour clock
        val h24 = ask(answerer(web, use24 = true), "Red Wings", "When do the Red Wings play next?", "next")
        assertTrue(h24.text, h24.text.contains("at 19:00 EDT"))
    }

    @Test
    fun aGameThatIsTonightIsSaidToBeTonight() {
        val web = FakeWeb(results(
            r("mlb.com", "Schedule", "Detroit Tigers vs Kansas City Royals, Oct. 20, 7:10 PM ET."),
            r("espn.com", "Schedule", "Tigers vs Royals, Oct. 20, 7:10 PM ET. Tonight."),
        ))
        val a = ask(answerer(web), "Tigers", "What time is the Tigers game?")
        assertTrue(a.text, a.text.contains("today at 7:10 PM EDT"))
    }

    @Test
    fun nothingParsableMeansNoClaimIsMade() {
        val web = FakeWeb(results(r("example.com", "Tigers news", "The team announced a new coach and fans are excited.")))
        val a = ask(answerer(web), "Tigers", "What's the Tigers score?")
        assertFalse(a.verified)
        assertTrue(a.text, a.text.startsWith("I couldn't find a score for the Tigers that I could verify."))
        assertEquals(1, a.sources.size)
        assertFalse(a.text.contains("beat") || a.text.contains("won"))
    }

    @Test
    fun anEmptySearchIsReportedHonestly() {
        val a = ask(answerer(FakeWeb(results())), "Lions", "What's the Lions score?")
        assertFalse(a.verified)
        assertTrue(a.sources.isEmpty())
        assertTrue(a.text.contains("couldn't find"))
    }

    @Test
    fun aFailedSearchIsPassedOnInPlainWords() {
        val web = FakeWeb(SearchOutcome.Failed(WebFailure.NOT_READABLE, "The page does not let me read its results."))
        val a = ask(answerer(web), "Tigers", "Tigers score")
        assertEquals("The page does not let me read its results.", a.text)
        assertEquals(AnswerOrigin.LOCAL, a.origin)
        assertFalse(a.verified)
    }

    @Test
    fun withNoSearchProviderNothingIsClaimed() {
        val a = ask(answerer(null), "Tigers", "Tigers score")
        assertTrue(a.text.contains("not available"))
        assertFalse(a.verified)
    }

    @Test
    fun aSharedNameAsksInsteadOfGuessing() {
        val web = FakeWeb(results(recapEspn))
        val a = ask(answerer(web), "Giants", "What's the Giants score?")
        assertTrue(a.text, a.text.startsWith("Which team do you mean"))
        assertTrue(a.text.contains("New York Giants") && a.text.contains("San Francisco Giants"))
        assertTrue("no online request is made to find out", web.queries.isEmpty())
        // a favourite settles it
        val fav = ask(answerer(FakeWeb(results(recapEspn)), favorites = listOf("San Francisco Giants")), "Giants", "What's the Giants score?")
        assertFalse(fav.text.startsWith("Which team"))
    }

    @Test
    fun anAssumedTeamIsSaidSo() {
        val a = ask(answerer(FakeWeb(results(recapEspn, recapMlb))), "Tigers", "Did the Tigers win yesterday?", "yesterday")
        assertNotNull(a.note)
        assertTrue(a.note!!.contains("Detroit Tigers"))
    }

    @Test
    fun aTeamThatIsNotRecognisedIsSearchedForAsSaidAndNotChecked() {
        val web = FakeWeb(results(r("example.com", "Flying Pigs", "The Flying Pigs won 3-2.")))
        val a = ask(answerer(web), "Flying Pigs", "What's the Flying Pigs score?")
        assertFalse(a.verified)
        assertTrue(a.text.contains("did not recognise"))
        assertTrue(web.queries.single().startsWith("Flying Pigs"))
        assertFalse("must not repeat the unchecked score as fact", a.text.contains("3-2") || a.text.contains("3–2"))
    }

    @Test
    fun offSeasonIsMentioned() {
        val summer = Clock.fixed(Instant.parse("2026-07-01T16:00:00Z"), detroit)
        val web = FakeWeb(results(r("example.com", "Lions", "Training camp opens soon.")))
        val a = runBlocking { SportsAnswerer(web, summer, Locale.US, false).answer(SportsQuestion("Lions", null, null, "What's the Lions score?")) }
        assertTrue(a.text, a.text.contains("NFL is not in season"))
    }

    @Test
    fun theScoreboardAtTheTopOfThePageCountsAsASource() {
        val page = SearchOutcome.Results(
            listOf(r("espn.com", "Tigers - ESPN", "Detroit Tigers 5, Kansas City Royals 3. Final. Oct. 19, 2026.")),
            clock.millis(), header = "Detroit Tigers 5 Kansas City Royals 3 Final Oct 19, 2026", pageUrl = "https://www.google.com/search?q=x",
        )
        val a = ask(answerer(FakeWeb(page)), "Tigers", "Did the Tigers win yesterday?", "yesterday")
        assertTrue(a.text, a.verified)
        assertTrue(a.text.contains("Confirmed by"))
    }

    // ---- a dedicated structured provider is preferred ----

    private class FakeStructured(val data: StructuredGames?) : SportsDataProvider {
        override val name = "Fake Stats Feed"
        var calls = 0

        override suspend fun lookup(team: Team, intent: SportsIntent, now: ZonedDateTime): StructuredGames? {
            calls++
            return data
        }
    }

    @Test
    fun aFreshStructuredFeedCanVerifyALiveScoreAndSkipsTheWeb() {
        val live = fact("stats.example", 3, 2, GameStatus.IN_PROGRESS, today).copy(detail = "Top 6th")
        val feed = FakeStructured(StructuredGames(listOf(live), clock.millis() - 30_000))
        val web = FakeWeb(results(recapEspn))
        val a = ask(answerer(web, structured = listOf(feed)), "Tigers", "What's the Tigers score?")
        assertTrue(a.text, a.text.startsWith("Live: the Tigers 3, the Royals 2 (Top 6th)."))
        assertTrue(a.verified)
        assertTrue(web.queries.isEmpty())
    }

    @Test
    fun aStaleStructuredFeedIsNotCalledLive() {
        val live = fact("stats.example", 3, 2, GameStatus.IN_PROGRESS, today)
        val feed = FakeStructured(StructuredGames(listOf(live), clock.millis() - 10 * 60_000))
        val a = ask(answerer(FakeWeb(results()), structured = listOf(feed)), "Tigers", "What's the Tigers score?")
        assertFalse(a.verified)
        assertTrue(a.text.contains("can't verify"))
    }

    @Test
    fun whenTheFeedHasNothingTheWebIsUsed() {
        val feed = FakeStructured(null)
        val web = FakeWeb(results(recapEspn, recapMlb))
        val a = ask(answerer(web, structured = listOf(feed)), "Tigers", "Did the Tigers win yesterday?", "yesterday")
        assertEquals(1, feed.calls)
        assertTrue(a.verified)
        assertEquals(1, web.queries.size)
    }

    // ---- reading the question ----

    @Test
    fun theQuestionIsUnderstoodFromTheToolArgumentsAndTheWords() {
        fun intent(text: String, w: String? = null) = SportsIntents.from(text, w)
        assertEquals(SportsIntent(SportsKind.RESULT, SportsDay.YESTERDAY), intent("Did the Lions win yesterday?"))
        assertEquals(SportsIntent(SportsKind.SCHEDULE, SportsDay.NEXT), intent("When do the Red Wings play next?"))
        assertEquals(SportsIntent(SportsKind.SCHEDULE, SportsDay.NEXT), intent("What time is the Michigan football game?"))
        assertEquals(SportsIntent(SportsKind.SCORE, SportsDay.NOW), intent("What's the Detroit Tigers score?"))
        assertEquals(SportsIntent(SportsKind.SCORE, SportsDay.NOW), intent("What's the score of the Pistons game?"))
        assertEquals(SportsIntent(SportsKind.SCHEDULE, SportsDay.TOMORROW), intent("Do the Tigers play tomorrow?"))
        assertEquals(SportsIntent(SportsKind.RESULT, SportsDay.LAST), intent("how did the lions do", "last"))
        assertEquals(SportsIntent(SportsKind.SCORE, SportsDay.NOW), intent("what time is the game tonight", "today").copy(kind = SportsKind.SCORE))
    }

    @Test
    fun theSearchQueryNamesTheTeamAndThePlaceInTime() {
        val a = answerer(null)
        assertEquals("Detroit Tigers score today October 20 2026", a.buildQuery(tigers, SportsIntent(SportsKind.SCORE, SportsDay.NOW), today))
        assertEquals("Detroit Lions final score result October 19 2026", a.buildQuery(lions, SportsIntent(SportsKind.RESULT, SportsDay.YESTERDAY), today))
        assertEquals("Detroit Red Wings next game schedule October 20 2026", a.buildQuery(redWings, SportsIntent(SportsKind.SCHEDULE, SportsDay.NEXT), today))
        assertEquals("Detroit Tigers next game schedule October 21 2026", a.buildQuery(tigers, SportsIntent(SportsKind.SCHEDULE, SportsDay.TOMORROW), today))
    }
}
