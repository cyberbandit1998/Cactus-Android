package app.cactusai.assistant

import android.util.Log
import app.cactusai.assistant.engine.HostResult
import app.cactusai.core.plan.PlanOutcome
import app.cactusai.core.plan.Planner
import app.cactusai.core.plan.PlannerPolicy
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the REAL Needle 3 and Whistle models on the phone. Nothing here is mocked.
 * Needs the app installed with both models already downloaded (Models tab) on an arm64 phone.
 * Results are also written to logcat under the tag CactusTest.
 */
@RunWith(AndroidJUnit4::class)
class ModelPipelineTest {
    private val host get() = TestSupport.container.engine
    private val groups = setOf(ToolGroup.PHONE)

    @Before
    fun requireModels() {
        assumeTrue("the native engine is not part of this build", host.isLinked)
        assumeTrue("download both models in the app first (Models tab)", TestSupport.modelsInstalled())
    }

    private data class Row(val text: String, val tool: String?, val args: String, val confidence: Double?, val ms: Long)

    private fun run(text: String): Row {
        val r = runBlocking { host.interpret(text, groups) }
        check(r is HostResult.Ok) { "interpret failed for \"$text\": ${(r as HostResult.Fail).message}" }
        val call = r.value.response.calls.firstOrNull()
        val validated = Planner.plan(text, r.value.response, AssistantTools.registry, PlannerPolicy(groups = groups))
        val args = when (validated) {
            is PlanOutcome.Run -> validated.steps.joinToString { it.args.toString() }
            is PlanOutcome.NeedsConfirmation -> validated.steps.joinToString { it.args.toString() }
            is PlanOutcome.Refused -> "refused: ${validated.message}"
        }
        return Row(text, call?.name, args, r.value.response.confidence, r.value.wallMs)
    }

    @Test
    fun needleLoadsAndAnswersWithValidJson() {
        val row = run("open spotify")
        Log.i("CactusTest", "open spotify -> ${row.tool} ${row.args} (${row.ms} ms)")
        val state = host.state.value
        Log.i("CactusTest", "needle load: ${state.needle.loadMs} ms, tools accepted at detail ${state.toolDetail}, prefix ${state.needlePrefixTokens} tokens, screen tools left out: ${state.screenToolsLeftOut}")
        assertTrue(state.needle.loadMs != null || state.needle.stage.name == "LOADED")
    }

    /** The commands from the project brief. These are acceptance checks: a miss here is real information. */
    @Test
    fun headlineCommandsPickTheRightTool() {
        val expectations = listOf(
            "Open Spotify" to "open_app",
            "Launch YouTube" to "open_app",
            "Open my camera" to "open_app",
            "Search Google for 3D printing ideas" to "search_web",
            "Set a timer for 15 minutes" to "set_timer",
            "Open Bluetooth settings" to "open_settings",
        )
        val misses = mutableListOf<String>()
        for ((text, tool) in expectations) {
            val row = run(text)
            Log.i("CactusTest", "$text -> ${row.tool} ${row.args} conf=${row.confidence} (${row.ms} ms)")
            if (row.tool != tool) misses += "\"$text\" gave ${row.tool}, wanted $tool"
        }
        assertTrue("wrong tool for: $misses", misses.isEmpty())
    }

    @Test
    fun timerArgumentsAreExtracted() {
        val r = runBlocking { host.interpret("Set a timer for 15 minutes", groups) } as HostResult.Ok
        val plan = Planner.plan("Set a timer for 15 minutes", r.value.response, AssistantTools.registry, PlannerPolicy(groups = groups))
        val steps = when (plan) {
            is PlanOutcome.Run -> plan.steps
            is PlanOutcome.NeedsConfirmation -> plan.steps
            is PlanOutcome.Refused -> error("refused: ${plan.message}")
        }
        assertEquals("set_timer", steps.single().name)
        assertEquals(15L, steps.single().args.long("minutes"))
    }

    /** A wider sheet for judging accuracy on this phone. It reports; it only fails if the pipeline itself breaks. */
    @Test
    fun commandSheetReport() {
        val sheet = listOf(
            "Open Spotify" to "open_app", "Launch YouTube" to "open_app", "Open Google Maps" to "open_app", "Open my messages" to "open_app",
            "Show my installed apps" to "search_phone", "Search Google for the latest technology news" to "search_web",
            "Look up 3D printing tutorials" to "search_web", "Search YouTube for Bambu Lab P1S tips" to "search_web",
            "Open Reddit" to "open_app", "Search for pizza recipes" to "search_web", "Open Wi-Fi settings" to "open_settings",
            "Open battery settings" to "open_settings", "Open display settings" to "open_settings", "Open the clock" to "open_app",
            "Set an alarm for 7 am" to "set_alarm", "Set a timer for 90 seconds" to "set_timer", "Open the dialer" to "dial_number",
            "Dial 555-123-4567" to "dial_number", "Call Mom" to "call_contact", "Text John that I'm on my way" to "send_text",
            "What is my battery level" to "check_battery", "Turn the volume up" to "change_volume", "Set the volume to 40 percent" to "change_volume",
            "Find John in my contacts" to "search_phone", "What is the capital of France" to null, "Tell me a joke" to null,
        )
        var right = 0
        var total = 0L
        val lines = mutableListOf<String>()
        for ((text, tool) in sheet) {
            val row = run(text)
            total += row.ms
            val ok = row.tool == tool
            if (ok) right++
            lines += "${if (ok) "OK  " else "MISS"} | $text -> ${row.tool} ${row.args} | conf=${row.confidence} | ${row.ms} ms"
        }
        lines.forEach { Log.i("CactusTest", it) }
        Log.i("CactusTest", "command sheet: $right of ${sheet.size} picked the expected tool; mean ${total / sheet.size} ms per command (measured on this phone)")
        assertTrue(true)
    }

    @Test
    fun whistleLoadsAndSilenceGivesAnEmptyTranscript() {
        val r = runBlocking { host.transcribe(FloatArray(32_000), "en", null) }
        check(r is HostResult.Ok) { (r as HostResult.Fail).message }
        Log.i("CactusTest", "whistle load ${r.value.loadMs} ms, silence transcribed in ${r.value.wallMs} ms -> \"${r.value.result.text}\"")
        assertTrue("silence should not produce words", r.value.result.isEmpty)
    }

    /** Real audio through the real model. The clips are synthetic text-to-speech (see assets README), not a human voice. */
    @Test
    fun whistleTranscribesSyntheticSpeech() {
        for (name in listOf("open_bluetooth_settings", "set_timer_15_minutes", "search_google_printing")) {
            val samples = TestSupport.readWavAsset("synthetic_speech/$name.wav")
            val r = runBlocking { host.transcribe(samples, "en", null) }
            check(r is HostResult.Ok) { (r as HostResult.Fail).message }
            Log.i("CactusTest", "$name -> \"${r.value.result.text}\" in ${r.value.wallMs} ms (ttft ${r.value.result.ttftMs} ms)")
            assertTrue("$name produced no text", !r.value.result.isEmpty)
        }
    }

    @Test
    fun whistleTextReachesNeedle() {
        val samples = TestSupport.readWavAsset("synthetic_speech/open_bluetooth_settings.wav")
        val heard = (runBlocking { host.transcribe(samples, "en", null) } as HostResult.Ok).value.result.text
        val r = runBlocking { host.interpret(heard, groups) }
        check(r is HostResult.Ok) { (r as HostResult.Fail).message }
        Log.i("CactusTest", "heard \"$heard\" -> ${r.value.response.calls.joinToString { it.name }} (${r.value.wallMs} ms)")
        assertTrue(heard.isNotBlank())
    }
}
