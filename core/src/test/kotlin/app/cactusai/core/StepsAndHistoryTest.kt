package app.cactusai.core

import app.cactusai.core.history.HistoryCodec
import app.cactusai.core.history.HistoryEntry
import app.cactusai.core.history.HistoryStore
import app.cactusai.core.history.InputSource
import app.cactusai.core.history.Outcome
import app.cactusai.core.metrics.Timings
import app.cactusai.core.steps.Choice
import app.cactusai.core.steps.ResultAction
import app.cactusai.core.steps.ResultItem
import app.cactusai.core.steps.RunResult
import app.cactusai.core.steps.StepEvent
import app.cactusai.core.steps.StepOutcome
import app.cactusai.core.steps.StepPolicy
import app.cactusai.core.steps.StepRunner
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.tools.ValidationResult
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class StepsAndHistoryTest {
    private fun call(tool: String, args: String): ValidatedCall {
        val parsed = app.cactusai.core.json.Json.parse(args)
        return (AssistantTools.registry.validate(tool, parsed, setOf(ToolGroup.PHONE, ToolGroup.SCREEN)) as ValidationResult.Ok).call
    }

    private val battery = call("check_battery", "{}")
    private val openMaps = call("open_app", """{"app_name":"Maps"}""")
    private val scroll = call("scroll", """{"direction":"down"}""")

    @Test
    fun runsEveryStepInOrderAndReportsEachOne() = runTest {
        val seen = mutableListOf<String>()
        val runner = StepRunner({ c -> seen += c.name; StepOutcome.Success("did ${c.name}") }, StepPolicy(settleMs = 100))
        val result = runner.run(listOf(openMaps, scroll, battery)) as RunResult.Completed
        assertEquals(listOf("open_app", "scroll", "check_battery"), seen)
        assertEquals(listOf("did open_app", "did scroll", "did check_battery"), result.messages)
        assertEquals(3, result.events.count { it is StepEvent.Started })
        assertEquals(3, result.events.count { it is StepEvent.Finished })
        assertEquals(200, currentTime)
    }

    @Test
    fun stopsAtTheFirstFailureAndNeverRunsLaterSteps() = runTest {
        val seen = mutableListOf<String>()
        val runner = StepRunner({ c ->
            seen += c.name
            if (c.name == "open_app") StepOutcome.Unavailable("Maps is not installed") else StepOutcome.Success("ok")
        })
        val result = runner.run(listOf(openMaps, scroll)) as RunResult.Stopped
        assertEquals(listOf("open_app"), seen)
        assertEquals("Maps is not installed", result.message)
        assertTrue(result.outcome is StepOutcome.Unavailable)
    }

    @Test
    fun needsChoiceAndPermissionAlsoStop() = runTest {
        val choice = StepOutcome.NeedsChoice("Which camera?", listOf(Choice("a", "Camera"), Choice("b", "Camera"))) { StepOutcome.Success("opened ${it.id}") }
        val r1 = StepRunner({ choice }).run(listOf(openMaps, scroll)) as RunResult.Stopped
        assertTrue(r1.outcome is StepOutcome.NeedsChoice)
        assertEquals(0, r1.stoppedAt)
        assertEquals("opened b", ((r1.outcome as StepOutcome.NeedsChoice).resolve(Choice("b", "Camera")) as StepOutcome.Success).message)
        val r2 = StepRunner({ StepOutcome.NeedsPermission("Contacts permission needed", "READ_CONTACTS") }).run(listOf(openMaps)) as RunResult.Stopped
        assertTrue(r2.outcome is StepOutcome.NeedsPermission)
    }

    @Test
    fun approvalStopsTheRunAndCanBeResumedFromTheStoppedIndex() = runTest {
        val seen = mutableListOf<String>()
        val runner = StepRunner({ c ->
            seen += c.name
            if (c.name == "scroll") StepOutcome.NeedsApproval("Tap Pay now?") { StepOutcome.Success("tapped") } else StepOutcome.Success("ok")
        }, StepPolicy(settleMs = 1))
        val steps = listOf(battery, scroll, openMaps)
        val stopped = runner.run(steps) as RunResult.Stopped
        assertEquals(1, stopped.stoppedAt)
        assertEquals(listOf("check_battery", "scroll"), seen)
        assertEquals("tapped", ((stopped.outcome as StepOutcome.NeedsApproval).proceed() as StepOutcome.Success).message)
        val rest = runner.run(steps.drop(stopped.stoppedAt + 1)) as RunResult.Completed
        assertEquals(listOf("ok"), rest.messages)
    }

    @Test
    fun successCarriesResultRows() = runTest {
        val rows = listOf(ResultItem("Spotify", "com.spotify.music", ResultAction.LaunchPackage("com.spotify.music")))
        val r = StepRunner({ StepOutcome.Success("Here are your apps", rows) }).run(listOf(battery)) as RunResult.Completed
        assertEquals(rows, r.results)
    }

    @Test
    fun aStepThatNeverFinishesTimesOut() = runTest {
        val runner = StepRunner({ awaitCancellation() }, StepPolicy(perStepTimeoutMs = 5_000))
        val job = async { runner.run(listOf(scroll, battery)) }
        advanceTimeBy(5_001)
        runCurrent()
        val result = job.await() as RunResult.Stopped
        assertTrue(result.events.last() is StepEvent.TimedOut)
        assertTrue("timed out at $currentTime ms", currentTime in 5_000..5_001)
        assertEquals(1, result.events.count { it is StepEvent.Started })
    }

    @Test
    fun cancellingMidSequenceStopsTheStepInFlight() = runTest {
        val events = mutableListOf<StepEvent>()
        var secondRan = false
        val runner = StepRunner({ c ->
            if (c.name == "scroll") {
                secondRan = true
                awaitCancellation()
            } else {
                StepOutcome.Success("ok")
            }
        }, StepPolicy(settleMs = 10, perStepTimeoutMs = 60_000))
        val job = launch(start = CoroutineStart.UNDISPATCHED) { runner.run(listOf(battery, scroll, openMaps)) { events += it } }
        advanceTimeBy(50)
        runCurrent()
        assertTrue(secondRan)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertTrue(events.last() is StepEvent.Cancelled)
        assertFalse(events.any { it is StepEvent.Started && it.call.name == "open_app" })
    }

    @Test
    fun cancellationIsNotSwallowedByTheExecutor() = runTest {
        val runner = StepRunner({ delay(1_000); throw CancellationException("stop") })
        val job = async { runner.run(listOf(battery)) }
        advanceTimeBy(1_001)
        runCurrent()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
    }

    // ---- history ----

    private fun entry(id: Long, text: String = "open spotify", outcome: Outcome = Outcome.SUCCESS) = HistoryEntry(
        id, 1_700_000_000_000L + id, InputSource.VOICE, text, "open_app{app_name=Spotify}", outcome, "Opened Spotify",
        Timings(transcribeMs = 120, interpretMs = 80, totalMs = 300, whistleTtftMs = 13.5),
    )

    @Test
    fun historyRoundTripsThroughJson() {
        val list = listOf(entry(2), entry(1, "say \"hi\"\nthere é", Outcome.REFUSED))
        assertEquals(list, HistoryCodec.decode(HistoryCodec.encode(list)))
    }

    @Test
    fun historyDecodeSkipsDamagedRecordsAndSurvivesGarbage() {
        val good = HistoryCodec.encode(listOf(entry(1)))
        val withBad = good.dropLast(1) + """,{"id":"x"},5,{"id":9,"time":1,"source":"VOICE","outcome":"NOPE"}]"""
        assertEquals(listOf(entry(1)), HistoryCodec.decode(withBad))
        assertEquals(emptyList<HistoryEntry>(), HistoryCodec.decode("not json at all"))
        assertEquals(emptyList<HistoryEntry>(), HistoryCodec.decode(""))
    }

    @Test
    fun storePersistsCapsAndClears() {
        val dir = Files.createTempDirectory("hist").toFile()
        try {
            val file = java.io.File(dir, "h/history.json")
            val store = HistoryStore(file, maxEntries = 3)
            (1L..5L).forEach { store.add(entry(it)) }
            assertEquals(listOf(5L, 4L, 3L), store.all().map { it.id })
            assertEquals(listOf(5L, 4L, 3L), HistoryStore(file, 3).all().map { it.id })
            store.clear()
            assertTrue(store.all().isEmpty())
            assertFalse(file.exists())
            assertTrue(HistoryStore(file, 3).all().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
