package app.cactusai.core.ask

import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.info.Calculator
import app.cactusai.core.info.CalcResult
import app.cactusai.core.info.DateInfo
import app.cactusai.core.info.DeviceAnswers
import app.cactusai.core.info.DeviceSnapshot
import app.cactusai.core.info.InfoAnswer
import app.cactusai.core.info.LocalGuard
import app.cactusai.core.info.LocalRoute
import app.cactusai.core.info.TimeAnswer
import app.cactusai.core.sports.League
import app.cactusai.core.sports.SportsAnswerer
import app.cactusai.core.sports.SportsDataProvider
import app.cactusai.core.sports.SportsQuestion
import app.cactusai.core.steps.ResultAction
import app.cactusai.core.steps.ResultItem
import app.cactusai.core.steps.StepOutcome
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolArgs
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.web.AnswerGenerator
import app.cactusai.core.web.QuestionAnswerer
import app.cactusai.core.web.ReadMode
import app.cactusai.core.web.SearchOutcome
import app.cactusai.core.web.SpokenResults
import app.cactusai.core.web.TempUnit
import app.cactusai.core.web.WeatherAnswerer
import app.cactusai.core.web.WebSearchProvider
import java.time.Clock
import java.time.LocalDate
import java.util.Locale

/** The settings the information tools read each time they run, so a change takes effect on the next command. */
data class InfoSettings(
    /** Lets sports, weather and general questions open the browser and read its results. Local answers never need it. */
    val onlineLookups: Boolean = true,
    /** Read the results of "search for ..." aloud as well as opening them. */
    val readSearchResults: Boolean = true,
    val readMode: ReadMode = ReadMode.SHORT_ANSWER,
    val tempUnit: TempUnit = TempUnit.AUTO,
    val favoriteTeams: List<String> = emptyList(),
    val homePlace: String? = null,
    val use24Hour: Boolean = false,
)

/**
 * Runs the information tools: time, date, device facts, arithmetic, sports, weather and general questions. Local tools use only
 * the clock and the phone's own values and never touch [web]. The online ones ask [web] (the user's browser, read through the
 * Accessibility service) and only when the user has allowed it; a question that can be answered on the phone is answered there
 * even if the model sent it to the online tool.
 *
 * What comes back from the web is turned into an [InfoAnswer] to show and speak. It never becomes a command: this class has no
 * path from web text to the tool registry or to an action.
 */
class InfoExecutor(
    private val clock: Clock,
    private val locale: () -> Locale,
    private val device: () -> DeviceSnapshot,
    private val settings: () -> InfoSettings,
    private val web: () -> WebSearchProvider?,
    private val structuredSports: List<SportsDataProvider> = emptyList(),
    private val generator: AnswerGenerator? = null,
) {
    /** The whole spoken or typed request for the command being run. Set by the caller before each command. */
    @Volatile
    var userText: String = ""

    /** The information tools, plus the older battery tool, which is answered from the same phone facts so it can be spoken too. */
    fun handles(tool: String): Boolean = tool in TOOLS || tool == AssistantTools.CHECK_BATTERY

    suspend fun execute(call: ValidatedCall): StepOutcome = outcomeOf(answer(call))

    /** An answer as a finished step: its text for the screen, its sources as rows to open, and the answer itself for the speaker. */
    fun outcomeOf(answer: InfoAnswer): StepOutcome.Success = StepOutcome.Success(answer.text, sourceItems(answer), answer)

    private fun sourceItems(a: InfoAnswer): List<ResultItem> =
        a.sources.map { ResultItem(it.title, listOfNotNull(it.host, it.snippet).joinToString(" · "), ResultAction.OpenUri(it.url)) }

    suspend fun answer(call: ValidatedCall): InfoAnswer {
        val a = call.args
        val s = settings()
        val loc = locale()
        return when (call.name) {
            AssistantTools.GET_TIME -> TimeAnswer.answer(clock, loc, s.use24Hour, a.string("place"))
            AssistantTools.GET_DATE -> DateInfo.answer(a.string("day"), userText, clock, loc)
            AssistantTools.DEVICE_INFO -> DeviceAnswers.describe(a.string("topic"), device(), loc)
            AssistantTools.CHECK_BATTERY -> DeviceAnswers.battery(device())
            AssistantTools.CALCULATE -> calculate(a.string("expression").orEmpty())
            AssistantTools.SPORTS_INFO -> sports(a, s, loc)
            AssistantTools.WEATHER_INFO -> {
                if (!s.onlineLookups) offline() else WeatherAnswerer(web(), clock, loc, s.use24Hour, { s.tempUnit }, { s.homePlace }).answer(a.string("place"), a.string("when"))
            }
            AssistantTools.ANSWER_QUESTION -> question(a.string("question").orEmpty(), s, loc)
            else -> InfoAnswer("I don't know how to answer that.", verified = false)
        }
    }

    private fun calculate(expression: String): InfoAnswer = when (val r = Calculator.evaluate(expression)) {
        is CalcResult.Value -> InfoAnswer(r.shown, Calculator.spokenForm(r.shown))
        is CalcResult.Error -> InfoAnswer(r.message, verified = false)
    }

    private fun offline() = InfoAnswer(
        "Looking things up online is turned off. You can turn it on in Settings. Time, date, battery and calculations still work without it.",
        verified = false,
    )

    private suspend fun sports(a: ToolArgs, s: InfoSettings, loc: Locale): InfoAnswer {
        val team = a.string("team")
        val league = League.fromKey(a.string("league"))
        // "Who won the World Series?" names no team: it is a question for the web, not a team lookup.
        if (team == null && league == null) return question(userText, s, loc)
        if (!s.onlineLookups) return offline()
        val answerer = SportsAnswerer(web(), clock, loc, s.use24Hour, { s.favoriteTeams }, structuredSports)
        return answerer.answer(SportsQuestion(team, league, a.string("when"), userText.ifBlank { team.orEmpty() }))
    }

    private suspend fun question(question: String, s: InfoSettings, loc: Locale): InfoAnswer {
        val asked = question.ifBlank { userText }
        reroute(asked)?.let { return answerLocally(it, loc) }
        if (!s.onlineLookups) return offline()
        return QuestionAnswerer(web(), clock, loc, s.use24Hour, { s.readMode }, generator).answer(asked)
    }

    /** A plain time, date, battery, device or arithmetic question, wherever the model sent it. */
    private fun reroute(question: String): LocalRoute? = LocalGuard.reroute(question, LocalDate.now(clock))

    private fun answerLocally(route: LocalRoute, loc: Locale): InfoAnswer {
        val s = settings()
        return when (route.tool) {
            AssistantTools.GET_TIME -> TimeAnswer.answer(clock, loc, s.use24Hour, route.args["place"])
            AssistantTools.GET_DATE -> DateInfo.answer(route.args["day"], userText, clock, loc)
            AssistantTools.DEVICE_INFO -> DeviceAnswers.describe(route.args["topic"], device(), loc)
            AssistantTools.CHECK_BATTERY -> DeviceAnswers.battery(device())
            AssistantTools.CALCULATE -> calculate(route.args["expression"].orEmpty())
            else -> InfoAnswer("I don't know how to answer that.", verified = false)
        }
    }

    /**
     * "Search Google for ..." with reading switched on: the browser is opened on the results and, when the Accessibility service can
     * read the page, the answer or the first results are returned to be spoken. Returns null when this does not apply (reading is
     * off, another site was asked for, no provider), and the caller then just opens the search as before.
     */
    suspend fun searchAndRead(query: String, site: String?): InfoAnswer? {
        val s = settings()
        if (!s.onlineLookups || !s.readSearchResults) return null
        if (site != null && site != "google") return null
        val provider = web() ?: return null
        val found = when (val r = provider.search(query)) {
            is SearchOutcome.Failed -> return QuestionAnswerer.failureAnswer(r)
            is SearchOutcome.Results -> r
        }
        val composed = SpokenResults.compose(s.readMode, query, found)
        return InfoAnswer(
            text = composed.text,
            spoken = composed.spoken,
            origin = AnswerOrigin.ONLINE,
            sources = composed.sources,
            retrievedAtMs = found.retrievedAtMs,
            note = if (composed.direct) "Quoted from the pages, not checked by me. Open a source to be sure." else null,
            verified = false,
        )
    }

    companion object {
        val TOOLS: Set<String> = setOf(
            AssistantTools.GET_TIME, AssistantTools.GET_DATE, AssistantTools.DEVICE_INFO, AssistantTools.CALCULATE,
            AssistantTools.SPORTS_INFO, AssistantTools.WEATHER_INFO, AssistantTools.ANSWER_QUESTION,
        )

        /** Tools that open a page and wait for it: they get a longer step timeout than the 8 seconds of ordinary actions. */
        val SLOW_TOOLS: Map<String, Long> = mapOf(
            AssistantTools.SPORTS_INFO to 30_000L, AssistantTools.WEATHER_INFO to 30_000L, AssistantTools.ANSWER_QUESTION to 30_000L, AssistantTools.SEARCH_WEB to 30_000L,
        )
    }
}
