package app.cactusai.core.info

import app.cactusai.core.json.Json
import app.cactusai.core.json.JsonValue
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolDetail
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ToolLadder
import app.cactusai.core.tools.ValidationResult
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalGuardAndToolsTest {
    private val today = LocalDate.of(2026, 10, 20)
    private val reg = AssistantTools.full
    private val everything = setOf(ToolGroup.PHONE, ToolGroup.SCREEN, ToolGroup.INFO, ToolGroup.ONLINE)

    private fun route(q: String) = LocalGuard.reroute(q, today)

    // ---- questions that must stay on the phone ----

    @Test
    fun timeQuestionsAreAnsweredLocally() {
        for (q in listOf("What time is it?", "what's the time", "What is the current time", "tell me the time", "what time is it right now", "time")) {
            assertEquals(q, "get_time", route(q)?.tool)
        }
        assertEquals(mapOf("place" to "california"), route("What's the time in California?")?.args)
        assertEquals(mapOf("place" to "tokyo"), route("what time is it in Tokyo")?.args)
    }

    @Test
    fun dateQuestionsAreAnsweredLocally() {
        assertEquals("get_date", route("What's today's date?")?.tool)
        assertEquals("get_date", route("what is the date")?.tool)
        assertEquals(mapOf("day" to "tomorrow"), route("What day is tomorrow?")?.args)
        assertEquals(mapOf("day" to "today"), route("what day is it")?.args)
        assertEquals(mapOf("day" to "christmas"), route("What day of the week is Christmas?")?.args)
        assertEquals(mapOf("day" to "halloween"), route("How many days until Halloween?")?.args)
        assertEquals(mapOf("day" to "christmas"), route("days till christmas")?.args)
    }

    @Test
    fun deviceQuestionsAreAnsweredLocally() {
        assertEquals("check_battery", route("What's my battery percentage?")?.tool)
        assertEquals("check_battery", route("how is my battery")?.tool)
        assertEquals(mapOf("topic" to "charging"), route("Is my phone charging?")?.args)
        assertEquals(mapOf("topic" to "android_version"), route("What's my Android version?")?.args)
        assertEquals(mapOf("topic" to "storage"), route("How much storage do I have left?")?.args)
        assertEquals(mapOf("topic" to "model"), route("what phone is this")?.args)
    }

    @Test
    fun arithmeticIsAnsweredLocally() {
        assertEquals("calculate", route("what's 15 percent of 80")?.tool)
        assertEquals("calculate", route("twelve times seven")?.tool)
        assertEquals("calculate", route("what is 2 plus 2")?.tool)
        assertNull("a bare number is not a sum", route("what is 2026"))
    }

    @Test
    fun realQuestionsAreNotCapturedAndStillGoOnline() {
        for (q in listOf(
            "What time is the Michigan game?", "Who invented the telephone?", "How far away is the moon?", "What day is the Lions game?",
            "When is my birthday", "What is the president of the United States", "What causes the northern lights?",
            "What year did the Titanic sink?", "how many days are there in a leap year", "What's the weather", "What is the time zone of Texas",
        )) {
            assertNull(q, route(q))
        }
    }

    // ---- the tool definitions ----

    @Test
    fun theInformationToolsAreRegisteredInTheirGroups() {
        val groups = reg.tools.associate { it.name to it.group }
        for (t in listOf("get_time", "get_date", "device_info", "calculate")) assertEquals(t, ToolGroup.INFO, groups[t])
        for (t in listOf("sports_info", "weather_info", "answer_question")) assertEquals(t, ToolGroup.ONLINE, groups[t])
        assertEquals(24, reg.tools.size)
        // the original registry is untouched
        assertEquals(17, AssistantTools.registry.tools.size)
    }

    private fun ok(tool: String, args: String) =
        (reg.validate(tool, Json.parse(args), everything) as? ValidationResult.Ok ?: error("expected Ok for $tool $args")).call

    private fun rejected(tool: String, args: String, groups: Set<ToolGroup> = everything) =
        (reg.validate(tool, Json.parse(args), groups) as? ValidationResult.Rejected ?: error("expected rejection for $tool $args")).reason

    @Test
    fun theInformationToolsValidateTheirArguments() {
        assertEquals("California", ok("get_time", """{"place":"California"}""").args.string("place"))
        ok("get_time", "{}")
        ok("get_date", "{}")
        assertEquals("christmas", ok("get_date", """{"day":"christmas"}""").args.string("day").orEmpty().lowercase())
        assertEquals("storage", ok("device_info", """{"topic":"STORAGE"}""").args.string("topic"))
        assertTrue(rejected("device_info", """{"topic":"imei"}""").contains("one of"))
        assertTrue(rejected("device_info", "{}").contains("needs"))
        assertEquals("15 percent of 80", ok("calculate", """{"expression":"15 percent of 80"}""").args.string("expression"))
        assertTrue(rejected("calculate", """{"expression":"${"1".repeat(101)}"}""").contains("longer"))
        assertEquals("college_football", ok("sports_info", """{"team":"Michigan","league":"college_football","when":"next"}""").args.string("league"))
        ok("sports_info", """{"team":"Tigers"}""")
        ok("sports_info", """{"league":"nfl"}""")
        assertTrue(rejected("sports_info", """{"team":"Tigers","league":"cricket"}""").contains("one of"))
        assertTrue(rejected("sports_info", """{"team":"Tigers","when":"someday"}""").contains("one of"))
        ok("weather_info", """{"place":"Detroit","when":"tomorrow"}""")
        assertTrue(rejected("weather_info", """{"when":"yesterday"}""").contains("one of"))
        assertEquals("Who invented the telephone?", ok("answer_question", """{"question":"Who invented the telephone?"}""").args.string("question"))
        assertTrue(rejected("answer_question", "{}").contains("needs"))
        assertTrue(rejected("answer_question", """{"question":"x","url":"http://evil"}""").contains("no argument"))
    }

    @Test
    fun informationToolsAreOffWhenTheirGroupIsOff() {
        assertTrue(rejected("get_time", "{}", setOf(ToolGroup.PHONE)).contains("turned off"))
        assertTrue(rejected("answer_question", """{"question":"x"}""", setOf(ToolGroup.PHONE, ToolGroup.INFO)).contains("turned off"))
    }

    @Test
    fun theWholeToolListStaysCompactAndValid() {
        val full = reg.toNeedleToolsJson(everything, ToolDetail.FULL)
        val compact = reg.toNeedleToolsJson(everything, ToolDetail.COMPACT)
        val minimal = reg.toNeedleToolsJson(everything, ToolDetail.MINIMAL)
        println("tool list sizes (characters): full=${full.length} compact=${compact.length} minimal=${minimal.length}")
        assertTrue("full ${full.length}", full.length < 9_500)
        assertTrue("compact ${compact.length}", compact.length < full.length)
        assertTrue("minimal ${minimal.length}", minimal.length < compact.length)
        for (json in listOf(full, compact, minimal)) {
            val arr = Json.parse(json) as JsonValue.Arr
            assertEquals(24, arr.items.size)
            // every tool has a short description and only plain parameter schemas
            for (item in arr.items.map { it as JsonValue.Obj }) {
                assertTrue(item.string("name")!!, item.string("description")!!.length <= 120)
            }
        }
        // the information tools alone are a small addition
        val info = reg.toNeedleToolsJson(setOf(ToolGroup.INFO, ToolGroup.ONLINE), ToolDetail.FULL)
        assertTrue("info tools ${info.length}", info.length < 3_300)
    }

    @Test
    fun theInformationToolDescriptionsAreOneShortSentenceEach() {
        for (t in AssistantTools.information) {
            assertTrue(t.name, t.description.length <= 120)
            assertTrue("${t.name} param text", t.params.all { it.description.length <= 70 })
        }
    }

    // ---- the fallback ladder ----

    @Test
    fun theLadderGivesUpWordingFirstThenOptionalGroupsInOrder() {
        val steps = ToolLadder.steps(everything)
        assertEquals(
            listOf(
                everything to ToolDetail.FULL, everything to ToolDetail.COMPACT, everything to ToolDetail.MINIMAL,
                (everything - ToolGroup.SCREEN) to ToolDetail.MINIMAL,
                (everything - ToolGroup.SCREEN - ToolGroup.ONLINE) to ToolDetail.MINIMAL,
                setOf(ToolGroup.PHONE) to ToolDetail.MINIMAL,
            ),
            steps,
        )
        assertNotNull(steps.last().first.contains(ToolGroup.PHONE))
    }

    @Test
    fun theLadderForTheOriginalGroupsIsTheOriginalLadder() {
        val phoneAndScreen = setOf(ToolGroup.PHONE, ToolGroup.SCREEN)
        assertEquals(
            listOf(
                phoneAndScreen to ToolDetail.FULL, phoneAndScreen to ToolDetail.COMPACT, phoneAndScreen to ToolDetail.MINIMAL,
                setOf(ToolGroup.PHONE) to ToolDetail.MINIMAL,
            ),
            ToolLadder.steps(phoneAndScreen),
        )
        assertEquals(3, ToolLadder.steps(setOf(ToolGroup.PHONE)).size)
    }
}
