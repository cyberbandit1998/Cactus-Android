package app.cactusai.core

import app.cactusai.core.needle.NeedleParse
import app.cactusai.core.needle.NeedleResponses
import app.cactusai.core.plan.Hint
import app.cactusai.core.plan.PlanOutcome
import app.cactusai.core.plan.Planner
import app.cactusai.core.plan.PlannerPolicy
import app.cactusai.core.plan.RefusalKind
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private val reg = AssistantTools.registry
    private val phone = PlannerPolicy(groups = setOf(ToolGroup.PHONE))
    private val both = PlannerPolicy(groups = setOf(ToolGroup.PHONE, ToolGroup.SCREEN))

    private fun response(calls: String, confidence: String = "0.93", extra: String = "") =
        (NeedleResponses.parse("""{"type":"call","success":true,"function_calls":$calls,"confidence":$confidence$extra}""") as NeedleParse.Parsed).response

    private fun plan(text: String, calls: String, policy: PlannerPolicy = phone, confidence: String = "0.93", extra: String = "") =
        Planner.plan(text, response(calls, confidence, extra), reg, policy)

    @Test
    fun confidentSafeCallRunsImmediately() {
        val out = plan("open spotify", """[{"name":"open_app","arguments":{"app_name":"Spotify"}}]""")
        assertTrue(out is PlanOutcome.Run)
        assertEquals("open_app", (out as PlanOutcome.Run).steps.single().name)
    }

    @Test
    fun lowConfidenceAsksFirst() {
        val out = plan("open spotify", """[{"name":"open_app","arguments":{"app_name":"Spotify"}}]""", confidence = "0.4")
        assertTrue(out is PlanOutcome.NeedsConfirmation)
        assertTrue((out as PlanOutcome.NeedsConfirmation).reasons.single().contains("40%"))
    }

    @Test
    fun thresholdIsInclusive() {
        val call = """[{"name":"open_app","arguments":{"app_name":"Spotify"}}]"""
        assertTrue(plan("x", call, confidence = "0.7") is PlanOutcome.Run)
        assertTrue(plan("x", call, confidence = "0.69") is PlanOutcome.NeedsConfirmation)
    }

    @Test
    fun missingConfidenceNeverRunsSilently() {
        val r = (NeedleResponses.parse("""{"success":true,"function_calls":[{"name":"open_app","arguments":{"app_name":"Maps"}}]}""") as NeedleParse.Parsed).response
        assertTrue(Planner.plan("open maps", r, reg, phone) is PlanOutcome.NeedsConfirmation)
    }

    @Test
    fun textAndCallsAreConfirmedByDefaultButTheUserCanRelaxThat() {
        val text = """[{"name":"send_text","arguments":{"recipient":"John","message":"I'm on my way"}}]"""
        assertTrue(plan("text john", text) is PlanOutcome.NeedsConfirmation)
        assertTrue(plan("text john", text, phone.copy(confirmCallsAndTexts = false)) is PlanOutcome.Run)
        val call = """[{"name":"call_contact","arguments":{"name":"Mom"}}]"""
        assertTrue(plan("call mom", call) is PlanOutcome.NeedsConfirmation)
    }

    @Test
    fun emptyCallsIsARefusal() {
        val out = plan("what is the meaning of life", "[]", confidence = "0.1")
        assertEquals(RefusalKind.NOTHING_MATCHED, (out as PlanOutcome.Refused).kind)
    }

    @Test
    fun suppressedCallIsOnlyOfferedForConfirmation() {
        val out = plan("open spotify", "[]", extra = ""","suppressed_calls":[{"name":"open_app","arguments":{"app_name":"Spotify"}}]""")
        assertTrue(out is PlanOutcome.NeedsConfirmation)
    }

    @Test
    fun invalidCallsAreRefusedWholeNotPartiallyRun() {
        val out = plan("do things", """[{"name":"open_app","arguments":{"app_name":"Maps"}},{"name":"run_shell","arguments":{"cmd":"id"}}]""")
        assertEquals(RefusalKind.INVALID_CALL, (out as PlanOutcome.Refused).kind)
    }

    @Test
    fun negatedRequestsDoNothing() {
        val out = plan("don't open spotify", """[{"name":"open_app","arguments":{"app_name":"Spotify"}}]""", extra = ""","validation":{"negation":true}""")
        assertEquals(RefusalKind.NEGATED, (out as PlanOutcome.Refused).kind)
    }

    @Test
    fun ungroundedValuesAreConfirmed() {
        val out = plan("open it", """[{"name":"open_app","arguments":{"app_name":"Spotify"}}]""", extra = ""","validation":{"ungrounded":["open_app.app_name"]}""")
        assertTrue((out as PlanOutcome.NeedsConfirmation).reasons.single().contains("open_app.app_name"))
    }

    @Test
    fun engineErrorIsReported() {
        val r = (NeedleResponses.parse("""{"success":false,"error":"oom","error_code":"memory","function_calls":[]}""") as NeedleParse.Parsed).response
        val out = Planner.plan("x", r, reg, phone)
        assertEquals(RefusalKind.ENGINE_ERROR, (out as PlanOutcome.Refused).kind)
    }

    @Test
    fun tooManyStepsRefused() {
        val calls = (1..5).joinToString(",", "[", "]") { """{"name":"check_battery","arguments":{}}""" }
        assertEquals(RefusalKind.TOO_MANY_STEPS, (plan("battery x5", calls) as PlanOutcome.Refused).kind)
    }

    @Test
    fun screenControlWithoutAccessibilityGetsAHint() {
        val refused = plan("scroll down", "[]") as PlanOutcome.Refused
        assertEquals(Hint.ENABLE_SCREEN_CONTROL, refused.hint)
        val invalid = plan("scroll down", """[{"name":"scroll","arguments":{"direction":"down"}}]""") as PlanOutcome.Refused
        assertEquals(Hint.ENABLE_SCREEN_CONTROL, invalid.hint)
        assertNull((plan("what time is it", "[]") as PlanOutcome.Refused).hint)
    }

    @Test
    fun screenCallsRunWhenEnabled() {
        val out = plan("scroll down", """[{"name":"scroll","arguments":{"direction":"down"}}]""", both)
        assertTrue(out is PlanOutcome.Run)
    }

    @Test
    fun openThenSearchOnTheSameSiteCollapsesToTheSearch() {
        val out = plan(
            "open youtube and search for woodworking",
            """[{"name":"open_app","arguments":{"app_name":"YouTube"}},{"name":"search_web","arguments":{"query":"woodworking","site":"youtube"}}]""",
        ) as PlanOutcome.Run
        assertEquals(listOf("search_web"), out.steps.map { it.name })
    }

    @Test
    fun openThenSearchOnADifferentSiteKeepsBoth() {
        val out = plan(
            "open spotify and search google for jazz",
            """[{"name":"open_app","arguments":{"app_name":"Spotify"}},{"name":"search_web","arguments":{"query":"jazz"}}]""",
        ) as PlanOutcome.Run
        assertEquals(2, out.steps.size)
        assertNotNull(out.steps.first())
    }
}
