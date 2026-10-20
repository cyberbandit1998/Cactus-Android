package app.cactusai.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import app.cactusai.assistant.engine.HostResult
import app.cactusai.assistant.system.NetworkMonitor
import app.cactusai.core.plan.PlanOutcome
import app.cactusai.core.plan.Planner
import app.cactusai.core.plan.PlannerPolicy
import app.cactusai.core.steps.StepOutcome
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the app works with Wi-Fi and mobile data switched off, using the real models.
 * It changes the phone's radios, so it only runs when asked:
 *   ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.offline=true
 * The radios are switched back on afterwards. Download both models first.
 */
@RunWith(AndroidJUnit4::class)
class OfflineTest {
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    @Test
    fun commandsAndSpeechStillWorkWithNoNetwork() {
        assumeTrue("pass -Pandroid.testInstrumentationRunnerArguments.offline=true to run this", TestSupport.argument("offline") == "true")
        val c = TestSupport.container
        assumeTrue("the native engine is not part of this build", c.engine.isLinked)
        assumeTrue("download both models first", TestSupport.modelsInstalled())
        try {
            device.executeShellCommand("svc wifi disable")
            device.executeShellCommand("svc data disable")
            Thread.sleep(4_000)
            assertFalse("the radios should be off for this test", NetworkMonitor(TestSupport.context).isOnline())

            // Speech, offline.
            val heard = runBlocking { c.engine.transcribe(TestSupport.readWavAsset("synthetic_speech/open_bluetooth_settings.wav"), "en", null) }
            assertTrue("Whistle must work offline", heard is HostResult.Ok && !heard.value.result.isEmpty)

            // Command understanding, offline.
            val groups = setOf(ToolGroup.PHONE)
            val understood = runBlocking { c.engine.interpret("open bluetooth settings", groups) } as HostResult.Ok
            val plan = Planner.plan("open bluetooth settings", understood.value.response, AssistantTools.registry, PlannerPolicy(groups = groups))
            assertTrue("Needle must produce a runnable plan offline: $plan", plan is PlanOutcome.Run || plan is PlanOutcome.NeedsConfirmation)

            // An offline phone action, offline.
            val steps = when (plan) {
                is PlanOutcome.Run -> plan.steps
                is PlanOutcome.NeedsConfirmation -> plan.steps
                is PlanOutcome.Refused -> error("refused")
            }
            val outcome = runBlocking { app.cactusai.assistant.actions.AndroidActionExecutor(
                TestSupport.context, c.apps, c.contacts, app.cactusai.assistant.actions.DeviceActions(TestSupport.context),
                app.cactusai.assistant.actions.FileSearch(TestSupport.context), c.network, c.settings,
            ).execute(steps.first()) }
            assertTrue(outcome.toString(), outcome is StepOutcome.Success)
            device.pressHome()
        } finally {
            device.executeShellCommand("svc wifi enable")
            device.executeShellCommand("svc data enable")
        }
    }
}
