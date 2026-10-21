package app.cactusai.core.exec

import app.cactusai.core.needle.NeedleParse
import app.cactusai.core.needle.NeedleResponse
import app.cactusai.core.needle.NeedleResponses
import app.cactusai.core.plan.RefusalKind
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolArgs
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ValidatedCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionPolicyTest {
    private val reg = AssistantTools.full
    private val all = setOf(ToolGroup.PHONE, ToolGroup.SCREEN, ToolGroup.INFO, ToolGroup.ONLINE)
    private val config = ExecutionConfig(groups = all)

    private fun response(calls: String, confidence: String? = "0.93", extra: String = "", key: String = "function_calls"): NeedleResponse {
        val conf = confidence?.let { ""","confidence":$it""" }.orEmpty()
        val json = """{"type":"call","success":true,"$key":$calls$conf$extra}"""
        return (NeedleResponses.parse(json) as NeedleParse.Parsed).response
    }

    private fun decide(text: String, calls: String, confidence: String? = "0.93", cfg: ExecutionConfig = config, extra: String = "") =
        ExecutionPolicy.decide(text, response(calls, confidence, extra), reg, cfg)

    private fun call(name: String, args: String = "{}") = """{"name":"$name","arguments":$args}"""

    private val openSpotify = "[" + call("open_app", """{"app_name":"Spotify"}""") + "]"

    @Test
    fun aConfidentLowRiskCommandRunsWithoutAsking() {
        val d = decide("open spotify", openSpotify)
        assertTrue(d is Decision.RunNow)
        assertEquals(0.93, d.confidence!!, 1e-9)
    }

    @Test
    fun theThresholdIsInclusiveAndUsesTheRealConfidence() {
        assertTrue(decide("open spotify", openSpotify, "0.70") is Decision.RunNow)
        val below = decide("open spotify", openSpotify, "0.69")
        assertTrue(below is Decision.AskFirst)
        assertTrue((below as Decision.AskFirst).reasons.single().contains("69%"))
        assertEquals(0.69, below.confidence!!, 1e-9)
    }

    @Test
    fun aSixtyPercentThresholdCanBeTried() {
        val sixty = config.copy(thresholdPercent = 60)
        assertTrue(decide("open spotify", openSpotify, "0.60", sixty) is Decision.RunNow)
        assertTrue(decide("open spotify", openSpotify, "0.65", sixty) is Decision.RunNow)
        assertTrue(decide("open spotify", openSpotify, "0.59", sixty) is Decision.AskFirst)
        // the same 65% is not enough at the default 70%
        assertTrue(decide("open spotify", openSpotify, "0.65") is Decision.AskFirst)
    }

    @Test
    fun theThresholdStaysBetweenFiftyAndNinetyFivePercent() {
        assertEquals(0.5, config.copy(thresholdPercent = 5).threshold, 1e-12)
        assertEquals(0.95, config.copy(thresholdPercent = 100).threshold, 1e-12)
        assertEquals(0.7, ExecutionConfig().threshold, 1e-12)
        assertTrue(decide("open spotify", openSpotify, "0.97", config.copy(thresholdPercent = 95)) is Decision.RunNow)
        assertTrue(decide("open spotify", openSpotify, "0.94", config.copy(thresholdPercent = 95)) is Decision.AskFirst)
    }

    @Test
    fun missingConfidenceAlwaysAsksAndIsNeverInvented() {
        val d = decide("open spotify", openSpotify, confidence = null)
        assertTrue(d is Decision.AskFirst)
        assertNull(d.confidence)
        assertTrue((d as Decision.AskFirst).reasons.any { it.contains("no confidence") })
        // even a threshold of 50% cannot turn "unknown" into "confident"
        assertTrue(decide("open spotify", openSpotify, null, config.copy(thresholdPercent = 50)) is Decision.AskFirst)
        // an out-of-range number is not a confidence either
        assertNull(decide("open spotify", openSpotify, "1.7").confidence)
        assertTrue(decide("open spotify", openSpotify, "1.7") is Decision.AskFirst)
    }

    @Test
    fun callsTheEngineHeldBackAreNeverRun() {
        val held = response("[]", "0.99", extra = ""","suppressed_calls":$openSpotify""")
        val d = ExecutionPolicy.decide("open spotify", held, reg, config)
        assertTrue(d is Decision.Refuse)
        d as Decision.Refuse
        assertEquals(RefusalKind.HELD_BACK, d.kind)
        assertEquals("open_app", d.heldBack.single().name)
        // not even with automatic execution off and a 50% threshold, where the user might have expected a question
        assertTrue(ExecutionPolicy.decide("open spotify", held, reg, config.copy(autoExecute = false, thresholdPercent = 50)) is Decision.Refuse)
    }

    @Test
    fun invalidCallsAreNeverRun() {
        for (calls in listOf(
            "[" + call("run_shell", """{"cmd":"id"}""") + "]",
            "[" + call("open_app", """{"app_name":"x","price":5}""") + "]",
            "[" + call("open_app", "{}") + "]",
            "[" + call("set_alarm", """{"hour":99}""") + "]",
            "[" + call("device_info", """{"topic":"imei"}""") + "]",
        )) {
            val d = decide("do it", calls, "0.99")
            assertTrue(calls, d is Decision.Refuse)
            assertEquals(RefusalKind.INVALID_CALL, (d as Decision.Refuse).kind)
        }
        // a tool from a group that is switched off is as good as unknown
        val d = ExecutionPolicy.decide("scroll down", response("[" + call("scroll", """{"direction":"down"}""") + "]"), reg, config.copy(groups = setOf(ToolGroup.PHONE)))
        assertTrue(d is Decision.Refuse)
    }

    @Test
    fun sensitiveActionsAlwaysAskWhateverTheConfidence() {
        val sensitive = listOf(
            "[" + call("send_text", """{"recipient":"Mom","message":"on my way"}""") + "]",
            "[" + call("call_contact", """{"name":"Mom"}""") + "]",
            "[" + call("dial_number", """{"number":"5551234567"}""") + "]",
        )
        for (calls in sensitive) {
            for (cfg in listOf(config, config.copy(thresholdPercent = 50), config.copy(thresholdPercent = 95))) {
                assertTrue(calls, decide("do it", calls, "0.99", cfg) is Decision.AskFirst)
            }
        }
    }

    @Test
    fun withAutomaticExecutionOffEverythingIsShownFirst() {
        val d = decide("open spotify", openSpotify, "0.99", config.copy(autoExecute = false))
        assertTrue(d is Decision.AskFirst)
        assertTrue((d as Decision.AskFirst).reasons.single().contains("turned off"))
    }

    @Test
    fun everyAllowlistedLowRiskActionRunsByItself() {
        val ok = listOf(
            call("open_app", """{"app_name":"Spotify"}"""),
            call("open_app", """{"app_name":"Camera"}"""),
            call("open_app", """{"app_name":"Chrome"}"""),
            call("search_web", """{"query":"best pizza"}"""),
            call("open_settings", """{"page":"wifi"}"""),
            call("open_settings", """{"page":"bluetooth"}"""),
            call("set_timer", """{"minutes":10}"""),
            call("set_alarm", """{"hour":7}"""),
            call("check_battery"),
            call("change_volume", """{"action":"up"}"""),
            call("change_volume", """{"action":"mute"}"""),
            call("change_volume", """{"action":"set","level":40}"""),
            call("search_phone", """{"kind":"apps"}"""),
            call("scroll", """{"direction":"down"}"""),
            call("navigate", """{"destination":"back"}"""),
            call("navigate", """{"destination":"home"}"""),
            call("find_on_screen", """{"text":"search"}"""),
            call("tap_text", """{"text":"search"}"""),
            call("tap_result", """{"position":2}"""),
            call("type_text", """{"text":"hello"}"""),
            call("get_time"),
            call("get_time", """{"place":"California"}"""),
            call("get_date", """{"day":"Christmas"}"""),
            call("device_info", """{"topic":"storage"}"""),
            call("calculate", """{"expression":"15 percent of 80"}"""),
            call("sports_info", """{"team":"Tigers"}"""),
            call("weather_info"),
            call("answer_question", """{"question":"Who invented the telephone?"}"""),
        )
        for (c in ok) {
            val d = decide("do it", "[$c]", "0.9")
            assertTrue("$c -> $d", d is Decision.RunNow)
        }
    }

    @Test
    fun aLoudVolumeJumpIsShownFirstButSmallStepsAreNot() {
        val loud = "[" + call("change_volume", """{"action":"set","level":90}""") + "]"
        val d = decide("volume 90", loud, "0.99")
        assertTrue(d is Decision.AskFirst)
        assertTrue((d as Decision.AskFirst).reasons.single().contains("70%"))
        assertTrue(decide("volume 70", "[" + call("change_volume", """{"action":"set","level":70}""") + "]", "0.99") is Decision.RunNow)
        assertTrue(decide("volume 90", loud, "0.99", config.copy(maxAutoVolumePercent = 100)) is Decision.RunNow)
    }

    @Test
    fun theSecuritySettingsPageIsNotAutomatic() {
        assertTrue(decide("accessibility settings", "[" + call("open_settings", """{"page":"accessibility"}""") + "]", "0.99") is Decision.AskFirst)
        assertTrue(decide("display settings", "[" + call("open_settings", """{"page":"display"}""") + "]", "0.99") is Decision.RunNow)
    }

    @Test
    fun aRiskyTapRequestIsShownFirst() {
        for (label in listOf("Pay now", "Buy", "Delete", "Send", "Place order", "Submit", "Install", "Allow", "Confirm purchase")) {
            val d = decide("tap $label", "[" + call("tap_text", """{"text":"$label"}""") + "]", "0.99")
            assertTrue(label, d is Decision.AskFirst)
        }
    }

    @Test
    fun oneConfidentAnswerIsNotBlanketPermissionForASequence() {
        // The first step is harmless and the answer is confident, but the second one is not allowed to run unasked.
        val seq = "[" + call("open_app", """{"app_name":"Chrome"}""") + "," + call("tap_text", """{"text":"Buy now"}""") + "]"
        val d = decide("open chrome and buy now", seq, "0.99")
        assertTrue(d is Decision.AskFirst)
        d as Decision.AskFirst
        assertEquals(listOf(true, false), d.assessments.map { it.automatic })
        assertEquals(2, d.steps.size)
        assertTrue(d.reasons.single().contains("Buy now"))
    }

    @Test
    fun aSequenceRunsByItselfOnlyWhenEveryStepPasses() {
        val safe = "[" + call("open_app", """{"app_name":"YouTube"}""") + "," + call("search_web", """{"query":"woodworking","site":"youtube"}""") + "]"
        assertTrue(decide("youtube woodworking", safe, "0.9") is Decision.RunNow)

        val mixed = "[" + call("open_app", """{"app_name":"Messages"}""") + "," + call("send_text", """{"recipient":"Mom","message":"hi"}""") + "]"
        val d = decide("text mom", mixed, "0.99")
        assertTrue(d is Decision.AskFirst)
        assertEquals(listOf(true, false), (d as Decision.AskFirst).assessments.map { it.automatic })

        val tooMany = "[" + (1..5).joinToString(",") { call("get_time") } + "]"
        val big = decide("time five times", tooMany, "0.99")
        assertTrue(big is Decision.Refuse)
        assertEquals(RefusalKind.TOO_MANY_STEPS, (big as Decision.Refuse).kind)
    }

    @Test
    fun valuesThatWereNotInTheRequestAreShownFirst() {
        val d = decide("open spotify", openSpotify, "0.99", extra = ""","validation":{"ungrounded":["open_app.app_name"]}""")
        assertTrue(d is Decision.AskFirst)
        assertTrue((d as Decision.AskFirst).reasons.any { it.contains("not clearly in what you said") })
    }

    @Test
    fun aNegatedRequestIsRefused() {
        val d = decide("don't open spotify", openSpotify, "0.99", extra = ""","validation":{"negation":true}""")
        assertTrue(d is Decision.Refuse)
        assertEquals(RefusalKind.NEGATED, (d as Decision.Refuse).kind)
    }

    @Test
    fun nothingMatchedIsRefusedWithTheConfidenceKept() {
        val d = decide("sing me a song", "[]", "0.12")
        assertTrue(d is Decision.Refuse)
        assertEquals(RefusalKind.NOTHING_MATCHED, (d as Decision.Refuse).kind)
        assertEquals(0.12, d.confidence!!, 1e-9)
    }

    @Test
    fun everyToolIsClassifiedOnPurposeNotByDefault() {
        for (tool in reg.tools) {
            val a = ExecutionPolicy.assess(ValidatedCall(tool, ToolArgs(emptyMap())), config)
            assertFalse("${tool.name} fell through to the default", a.reason?.contains("not on the list") == true)
        }
        val unknown = AssistantTools.registry.tools.first().copy(name = "brand_new_tool")
        val a = ExecutionPolicy.assess(ValidatedCall(unknown, ToolArgs(emptyMap())), config)
        assertFalse(a.automatic)
        assertTrue(a.reason!!.contains("not on the list"))
    }

    @Test
    fun tapsAreNeverAutomaticForTheCallAndTextTools() {
        for (name in listOf("send_text", "call_contact", "dial_number")) {
            assertFalse(ExecutionPolicy.assess(ValidatedCall(reg.find(name)!!, ToolArgs(emptyMap())), config).automatic)
        }
    }

    @Test
    fun onlyInstantLocalAnswersSkipTheCancelWindow() {
        fun steps(vararg calls: String): List<app.cactusai.core.tools.ValidatedCall> {
            val d = decide("x", "[" + calls.joinToString(",") + "]")
            return (d as Decision.RunNow).steps
        }
        assertEquals(false, ExecutionPolicy.needsCancelWindow(steps(call("get_time"))))
        assertEquals(false, ExecutionPolicy.needsCancelWindow(steps(call("check_battery"))))
        assertEquals(true, ExecutionPolicy.needsCancelWindow(steps(call("open_app", """{"app_name":"Spotify"}"""))))
        assertEquals(true, ExecutionPolicy.needsCancelWindow(steps(call("get_time"), call("open_app", """{"app_name":"Spotify"}"""))))
        // opens the browser, so it can be cancelled
        assertEquals(true, ExecutionPolicy.needsCancelWindow(steps(call("answer_question", """{"question":"Who invented the telephone?"}"""))))
    }
}
