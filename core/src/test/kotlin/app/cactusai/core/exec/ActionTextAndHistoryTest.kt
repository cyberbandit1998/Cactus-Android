package app.cactusai.core.exec

import app.cactusai.core.history.HistoryCodec
import app.cactusai.core.history.HistoryEntry
import app.cactusai.core.history.InputSource
import app.cactusai.core.history.Outcome
import app.cactusai.core.json.Json
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.tools.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionTextAndHistoryTest {
    private val all = setOf(ToolGroup.PHONE, ToolGroup.SCREEN, ToolGroup.INFO, ToolGroup.ONLINE)

    private fun call(tool: String, args: String = "{}"): ValidatedCall =
        (AssistantTools.full.validate(tool, Json.parse(args), all) as ValidationResult.Ok).call

    @Test
    fun eachStepHasPlainWording() {
        assertEquals("Opening Spotify", ActionText.describe(call("open_app", """{"app_name":"Spotify"}""")))
        assertEquals("Searching YouTube for “woodworking”", ActionText.describe(call("search_web", """{"query":"woodworking","site":"youtube"}""")))
        assertEquals("Searching Google for “pizza”", ActionText.describe(call("search_web", """{"query":"pizza"}""")))
        assertEquals("Opening Wi-Fi settings", ActionText.describe(call("open_settings", """{"page":"wifi"}""")))
        assertEquals("Opening settings", ActionText.describe(call("open_settings", """{"page":"main"}""")))
        assertEquals("Setting a timer for 1 hour 5 minutes", ActionText.describe(call("set_timer", """{"hours":1,"minutes":5}""")))
        assertEquals("Setting an alarm for 7:05 PM", ActionText.describe(call("set_alarm", """{"hour":19,"minute":5}""")))
        assertEquals("Setting an alarm for 12:00 AM", ActionText.describe(call("set_alarm", """{"hour":0}""")))
        assertEquals("Turning the volume up", ActionText.describe(call("change_volume", """{"action":"up"}""")))
        assertEquals("Setting the volume to 40%", ActionText.describe(call("change_volume", """{"action":"set","level":40}""")))
        assertEquals("Going back", ActionText.describe(call("navigate", """{"destination":"back"}""")))
        assertEquals("Scrolling down", ActionText.describe(call("scroll", """{"direction":"down"}""")))
        assertEquals("Checking the time in California", ActionText.describe(call("get_time", """{"place":"California"}""")))
        assertEquals("Checking the time", ActionText.describe(call("get_time")))
        assertEquals("Looking up the Tigers game online", ActionText.describe(call("sports_info", """{"team":"Tigers"}""")))
        assertEquals("Searching the web for an answer", ActionText.describe(call("answer_question", """{"question":"Who invented the telephone?"}""")))
    }

    @Test
    fun everyToolHasWordingOtherThanItsRawName() {
        for (tool in AssistantTools.full.tools) {
            val minimal = ValidatedCall(tool, app.cactusai.core.tools.ToolArgs(emptyMap()))
            assertFalse(tool.name, ActionText.describe(minimal) == tool.name)
        }
    }

    @Test
    fun aSequenceIsReadAsOneSentence() {
        val steps = listOf(call("open_app", """{"app_name":"YouTube"}"""), call("scroll", """{"direction":"down"}"""))
        assertEquals("Opening YouTube, then scrolling down", ActionText.describeAll(steps))
    }

    private fun entry(mode: ExecutionMode?, confidence: Double?, online: Boolean = false) = HistoryEntry(
        id = 7, timeMs = 1_000, source = InputSource.VOICE, text = "open spotify", interpretation = "open_app{app_name=Spotify}",
        outcome = Outcome.SUCCESS, message = "Opened Spotify.", timings = null, mode = mode, confidence = confidence, online = online,
    )

    @Test
    fun historyRemembersHowACommandWasApprovedAndItsRealConfidence() {
        val entries = listOf(entry(ExecutionMode.AUTOMATIC, 0.87), entry(ExecutionMode.CONFIRMED, 0.55, online = true), entry(null, null))
        val back = HistoryCodec.decode(HistoryCodec.encode(entries))
        assertEquals(entries, back)
        assertEquals(ExecutionMode.AUTOMATIC, back[0].mode)
        assertEquals(0.87, back[0].confidence!!, 1e-9)
        assertTrue(back[1].online)
        assertNull(back[2].mode)
        assertNull(back[2].confidence)
    }

    @Test
    fun entriesWrittenByEarlierVersionsStillLoad() {
        val old = """[{"id":1,"time":5,"source":"voice","text":"open spotify","interpretation":null,"outcome":"success","message":"ok","timings":null}]"""
        val back = HistoryCodec.decode(old).single()
        assertEquals("open spotify", back.text)
        assertNull(back.mode)
        assertNull(back.confidence)
        assertFalse(back.online)
    }

    @Test
    fun anOutOfRangeConfidenceInTheFileIsDropped() {
        val bad = """[{"id":1,"time":5,"source":"text","text":"x","outcome":"refused","message":"m","conf":7.5,"mode":"nonsense"}]"""
        val back = HistoryCodec.decode(bad).single()
        assertNull(back.confidence)
        assertNull(back.mode)
    }
}
