package app.cactusai.core.ask

import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.info.DeviceSnapshot
import app.cactusai.core.json.Json
import app.cactusai.core.steps.ResultAction
import app.cactusai.core.steps.StepEvent
import app.cactusai.core.steps.StepExecutor
import app.cactusai.core.steps.StepOutcome
import app.cactusai.core.steps.StepPolicy
import app.cactusai.core.steps.StepRunner
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.tools.ValidationResult
import app.cactusai.core.web.ReadMode
import app.cactusai.core.web.SearchOutcome
import app.cactusai.core.web.WebFailure
import app.cactusai.core.web.WebResult
import app.cactusai.core.web.WebSearchProvider
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Results here are SYNTHETIC. They test routing, offline behaviour and safety, not any real page. */
class InfoExecutorTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-20T19:42:00Z"), ZoneId.of("America/Detroit"))
    private val all = setOf(ToolGroup.PHONE, ToolGroup.SCREEN, ToolGroup.INFO, ToolGroup.ONLINE)

    private class FakeWeb(var outcome: SearchOutcome) : WebSearchProvider {
        override val name = "browser search"
        val queries = mutableListOf<String>()

        override suspend fun search(query: String, maxResults: Int): SearchOutcome {
            queries += query
            return outcome
        }
    }

    private fun r(host: String, title: String, content: String) = WebResult(title, "https://$host/page", content)
    private fun found(vararg results: WebResult) = SearchOutcome.Results(results.toList(), clock.millis(), pageUrl = "https://www.google.com/search?q=x")

    private val phone = DeviceSnapshot(batteryPercent = 82, charging = true, chargingSource = "USB", androidRelease = "14", sdkInt = 34, storageFreeBytes = 52_300_000_000L, storageTotalBytes = 128_000_000_000L, manufacturer = "Google", model = "Pixel 7")

    private fun call(tool: String, args: String = "{}"): ValidatedCall =
        (AssistantTools.full.validate(tool, Json.parse(args), all) as ValidationResult.Ok).call

    private fun executor(web: WebSearchProvider?, settings: InfoSettings = InfoSettings(), text: String = ""): InfoExecutor =
        InfoExecutor(clock, { Locale.US }, { phone }, { settings }, { web }).also { it.userText = text }

    private fun run(e: InfoExecutor, c: ValidatedCall) = runBlocking { e.execute(c) as StepOutcome.Success }

    // ---- local answers: no web, no network, no settings needed ----

    @Test
    fun localAnswersNeverTouchTheWebEvenWhenItIsAvailable() {
        val web = FakeWeb(found())
        val e = executor(web, text = "what time is it")
        assertEquals("It's 3:42 PM.", run(e, call("get_time")).message)
        assertEquals("Today is Tuesday, October 20, 2026.", run(e, call("get_date")).message)
        assertEquals("Yes, your phone is charging over USB. The battery is at 82%.", run(e, call("device_info", """{"topic":"charging"}""")).message)
        assertEquals("15 percent of 80 is 12.", run(e, call("calculate", """{"expression":"15 percent of 80"}""")).message)
        assertTrue(web.queries.isEmpty())
    }

    @Test
    fun localAnswersWorkWithNoProviderAndOnlineLookupsOff() {
        val e = executor(null, InfoSettings(onlineLookups = false), text = "what's the time in California")
        val time = run(e, call("get_time", """{"place":"California"}"""))
        assertTrue(time.message, time.message.startsWith("It's 12:42 PM in California"))
        assertEquals(AnswerOrigin.LOCAL, time.answer!!.origin)
        assertTrue(run(e, call("device_info", """{"topic":"storage"}""")).message.contains("52.3 GB"))
        assertEquals("Today is Tuesday, October 20, 2026.", run(e, call("get_date", """{"day":"today"}""")).message)
    }

    @Test
    fun theTwentyFourHourSettingIsHonoured() {
        val e = executor(null, InfoSettings(use24Hour = true))
        assertEquals("It's 15:42.", run(e, call("get_time")).message)
    }

    @Test
    fun theDayCountingStyleComesFromWhatTheUserSaid() {
        val e = executor(null, text = "How many days until Halloween?")
        assertEquals("11 days until Halloween (Saturday, October 31, 2026).", run(e, call("get_date", """{"day":"Halloween"}""")).message)
    }

    @Test
    fun aBadCalculationIsExplainedNotCrashed() {
        val e = executor(null)
        val out = run(e, call("calculate", """{"expression":"5 divided by 0"}"""))
        assertTrue(out.message.contains("divide by zero"))
        assertFalse(out.answer!!.verified)
    }

    // ---- questions that the model sent online but the phone can answer ----

    @Test
    fun aLocallyAnswerableQuestionIsNeverSentToTheWeb() {
        val web = FakeWeb(found(r("example.org", "Time", "Time zones are regions with the same standard time.")))
        for ((question, expected) in listOf(
            "What time is it?" to "It's 3:42 PM.",
            "What's today's date?" to "Today is Tuesday, October 20, 2026.",
            "What day of the week is Christmas?" to "Christmas is Friday, December 25, 2026, 66 days from today.",
            "What's my battery percentage?" to "Your phone's battery",
            "Is my phone charging?" to "Yes, your phone is charging",
            "what's 12 times 7" to "12 times 7 is 84.",
        )) {
            val out = run(executor(web, text = question), call("answer_question", """{"question":"$question"}"""))
            assertTrue("$question -> ${out.message}", out.message.startsWith(expected) || out.message.contains(expected.removePrefix("Your phone's ")))
            assertEquals(AnswerOrigin.LOCAL, out.answer!!.origin)
        }
        assertTrue("nothing was sent online", web.queries.isEmpty())
    }

    // ---- online answers ----

    private val telephone = arrayOf(
        r("example-encyclopedia.org", "Telephone", "The telephone was invented by Alexander Graham Bell, who received the first patent in 1876."),
        r("history.example.com", "Bell", "Alexander Graham Bell is credited with the telephone."),
    )

    @Test
    fun aFactualQuestionIsAnsweredFromTheResultsAndLabelledOnline() {
        val web = FakeWeb(found(*telephone))
        val e = executor(web, text = "Who invented the telephone?")
        val out = run(e, call("answer_question", """{"question":"Who invented the telephone?"}"""))
        assertTrue(out.message, out.message.startsWith("According to example-encyclopedia.org and history.example.com"))
        assertEquals(AnswerOrigin.ONLINE, out.answer!!.origin)
        assertFalse(out.answer!!.verified)
        assertEquals("who invented the telephone", web.queries.single().lowercase())
        // the sources are tappable results that only open web addresses
        assertTrue(out.results.isNotEmpty())
        assertTrue(out.results.all { it.action is ResultAction.OpenUri })
    }

    @Test
    fun theReadModeSettingChangesWhatIsSaid() {
        val web = FakeWeb(found(*telephone))
        val out = run(executor(web, InfoSettings(readMode = ReadMode.READ_RESULTS), "Who invented the telephone?"), call("answer_question", """{"question":"Who invented the telephone?"}"""))
        assertTrue(out.message, out.message.startsWith("Here are the first results."))
    }

    @Test
    fun withOnlineLookupsOffNothingIsSearchedAndTheUserIsTold() {
        val web = FakeWeb(found(*telephone))
        val e = executor(web, InfoSettings(onlineLookups = false), "Who invented the telephone?")
        for (c in listOf(
            call("answer_question", """{"question":"Who invented the telephone?"}"""),
            call("sports_info", """{"team":"Tigers"}"""),
            call("weather_info", """{"place":"Detroit"}"""),
        )) {
            val out = run(e, c)
            assertTrue(c.name + ": " + out.message, out.message.contains("turned off"))
            assertEquals(AnswerOrigin.LOCAL, out.answer!!.origin)
        }
        assertTrue(web.queries.isEmpty())
    }

    @Test
    fun aSportsQuestionGoesThroughTheVerifiedSportsPath() {
        val web = FakeWeb(found(
            r("espn.com", "Tigers vs. Royals - Game Recap - October 19, 2026", "Detroit Tigers 5, Kansas City Royals 3. Final."),
            r("mlb.com", "Tigers top Royals", "The Detroit Tigers defeated the Kansas City Royals, 5-3, on Monday, Oct. 19."),
        ))
        val e = executor(web, text = "Did the Tigers win yesterday?")
        val out = run(e, call("sports_info", """{"team":"Tigers","when":"yesterday"}"""))
        assertTrue(out.message, out.message.startsWith("Yes, the Tigers beat the Royals 5–3 yesterday."))
        assertTrue(out.answer!!.verified)
        assertEquals("Detroit Tigers final score result October 19 2026", web.queries.single())
    }

    @Test
    fun favouriteTeamsReachTheSportsLookup() {
        val web = FakeWeb(found(r("example.com", "News", "Nothing useful here.")))
        executor(web, InfoSettings(favoriteTeams = listOf("San Francisco Giants")), "Giants score").let { run(it, call("sports_info", """{"team":"Giants"}""")) }
        assertEquals(1, web.queries.size)
        assertTrue(web.queries.single().startsWith("San Francisco Giants"))
        val ambiguous = FakeWeb(found())
        val out = run(executor(ambiguous, text = "Giants score"), call("sports_info", """{"team":"Giants"}"""))
        assertTrue(out.message.startsWith("Which team do you mean"))
        assertTrue(ambiguous.queries.isEmpty())
    }

    @Test
    fun aSportsToolWithoutATeamIsAGeneralQuestion() {
        val web = FakeWeb(found(
            r("example-baseball.org", "World Series", "The 2025 World Series was won by the Los Angeles Dodgers over the Toronto Blue Jays."),
            r("example-sports.com", "Champions", "The Los Angeles Dodgers won the 2025 World Series."),
        ))
        val out = run(executor(web, text = "Who won the World Series?"), call("sports_info", "{}"))
        assertEquals("Who won the World Series", web.queries.single())
        assertTrue(out.message, out.message.contains("Dodgers"))
    }

    @Test
    fun weatherComesFromTheResults() {
        val web = FakeWeb(found(r("example-weather.com", "Detroit Weather", "Today will be mostly sunny with a high near 62°F and a low of 41°F.")))
        val out = run(executor(web, InfoSettings(tempUnit = app.cactusai.core.web.TempUnit.CELSIUS), "weather in Detroit"), call("weather_info", """{"place":"Detroit"}"""))
        assertTrue(out.message, out.message.contains("62°F (17°C)"))
    }

    @Test
    fun aFailedLookupIsExplainedAndTheStepStillEndsNormally() {
        val web = FakeWeb(SearchOutcome.Failed(WebFailure.ACCESSIBILITY_OFF, "I opened the results, but I can't read them aloud until you turn on the Accessibility service.", browserShowsResults = true))
        val out = run(executor(web, text = "Who invented the telephone?"), call("answer_question", """{"question":"Who invented the telephone?"}"""))
        assertTrue(out.message.contains("Accessibility service"))
        assertEquals("The search results are showing in your browser.", out.answer!!.note)
        assertTrue(out.results.isEmpty())
    }

    // ---- "search for ..." read aloud ----

    @Test
    fun aGoogleSearchIsReadBackWhenReadingIsOn() {
        val web = FakeWeb(found(*telephone))
        val answer = runBlocking { executor(web).searchAndRead("who invented the telephone", site = null) }
        assertNotNull(answer)
        assertTrue(answer!!.text.startsWith("According to"))
        assertEquals(1, web.queries.size)
    }

    @Test
    fun otherSitesOffSettingsAndMissingProvidersLeaveTheSearchAlone() {
        val web = FakeWeb(found(*telephone))
        assertNull(runBlocking { executor(web).searchAndRead("woodworking", site = "youtube") })
        assertNull(runBlocking { executor(web, InfoSettings(readSearchResults = false)).searchAndRead("x", site = null) })
        assertNull(runBlocking { executor(web, InfoSettings(onlineLookups = false)).searchAndRead("x", site = "google") })
        assertNull(runBlocking { executor(null).searchAndRead("x", site = null) })
        assertTrue(web.queries.isEmpty())
    }

    @Test
    fun aSearchThatCannotBeReadSaysSoAndKeepsTheResultsVisible() {
        val web = FakeWeb(SearchOutcome.Failed(WebFailure.NOT_READABLE, "The page does not let me read its results.", browserShowsResults = true))
        val answer = runBlocking { executor(web).searchAndRead("x", null) }!!
        assertTrue(answer.text.contains("does not let me read"))
        assertTrue(answer.note!!.contains("showing in your browser"))
    }

    // ---- through the step runner ----

    @Test
    fun theStepRunnerGivesSlowToolsTheirLongerTimeAndCarriesTheAnswer() {
        val web = FakeWeb(found(*telephone))
        val e = executor(web, text = "Who invented the telephone?")
        val runner = StepRunner(StepExecutor { c -> e.execute(c) }, StepPolicy(toolTimeoutMs = InfoExecutor.SLOW_TOOLS))
        val result = runBlocking { runner.run(listOf(call("answer_question", """{"question":"Who invented the telephone?"}"""))) }
        assertEquals(1, result.answers.size)
        assertEquals(AnswerOrigin.ONLINE, result.answers.single().origin)
        assertEquals(1, result.events.count { it is StepEvent.Finished })
        assertEquals(30_000L, InfoExecutor.SLOW_TOOLS.getValue("answer_question"))
        assertEquals(8_000L, StepPolicy().perStepTimeoutMs)
    }

    @Test
    fun everyInformationToolIsHandledAndNoOtherToolIs() {
        assertEquals(AssistantTools.information.map { it.name }.toSet(), InfoExecutor.TOOLS)
        val e = executor(null)
        assertTrue(e.handles("get_time"))
        assertFalse(e.handles("open_app"))
        assertFalse(e.handles("send_text"))
    }
}
