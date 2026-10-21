package app.cactusai.assistant

import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cactusai.assistant.a11y.ScreenControl
import app.cactusai.assistant.actions.AndroidBrowserOps
import app.cactusai.assistant.assistant.Phase
import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.speech.SpeakResult
import app.cactusai.core.speech.SpeechState
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Checks for the automatic-stop, automatic-execution, question and spoken-answer features that need an Android runtime.
 * They are COMPILED in CI and run only on a phone. What they cannot cover (a real voice, Chrome's page, the system voice, coming
 * back to the app) is in docs/DEVICE_TEST_CHECKLIST.md sections 10 to 15.
 */
@RunWith(AndroidJUnit4::class)
class AssistantWorkflowTest {
    private val c get() = TestSupport.container

    @Test
    fun assistantSettingsAreStoredAndKeptInTheirRanges() {
        val before = c.settings.current.assistant
        try {
            c.settings.updateAssistant { it.copy(thresholdPercent = 60, silenceMs = 2_000) }
            assertEquals(60, c.settings.current.assistant.thresholdPercent)
            assertEquals(2_000, c.settings.current.assistant.silenceMs)
            c.settings.updateAssistant { it.copy(thresholdPercent = 5, silenceMs = 99_000) }
            assertEquals(50, c.settings.current.assistant.thresholdPercent)
            assertEquals(4_000, c.settings.current.assistant.silenceMs)
        } finally {
            c.settings.updateAssistant { before }
        }
    }

    @Test
    fun theSpeechEngineStartsOrSaysWhyItCannot() = runBlocking {
        c.speech.prepare()
        withTimeout(10_000) {
            while (c.speech.state.value != SpeechState.IDLE && c.speech.state.value != SpeechState.UNAVAILABLE) delay(100)
        }
        if (c.speech.state.value == SpeechState.UNAVAILABLE) assertTrue(c.speech.problem != null)
    }

    @Test
    fun nothingIsSpokenWhileTheMicrophoneIsInUse() {
        c.recorder.prepare()
        try {
            assertEquals(SpeakResult.MICROPHONE_ACTIVE, c.speech.speak("This should never be heard."))
            assertFalse(c.speech.isSpeaking)
        } finally {
            c.recorder.release()
        }
        assertFalse(c.recorder.isActive)
    }

    @Test
    fun theBrowserCannotBeReadWhileTheAccessibilityServiceIsOff() {
        assumeFalse("the tester already switched the service on", ScreenControl.isAvailable)
        assertFalse(AndroidBrowserOps(TestSupport.context, c.network).canRead)
    }

    @Test
    fun aTypedLocalQuestionIsAnsweredOnThePhoneWithoutTheWeb() = runBlocking {
        assumeTrue("download both models in the app first", TestSupport.modelsInstalled())
        val controller = c.controller
        controller.clear()
        controller.submitText("what is fifteen percent of 240")
        withTimeout(120_000) {
            while (controller.state.value.phase != Phase.DONE) {
                // With no confidence reported, or one under the threshold, the command is shown first: say yes.
                if (controller.state.value.phase == Phase.CONFIRMING) controller.confirm()
                delay(200)
            }
        }
        val state = controller.state.value
        assertTrue("answers: ${state.answers} response: ${state.response}", state.answers.isNotEmpty())
        assertEquals(AnswerOrigin.LOCAL, state.answers.first().origin)
        assertTrue(state.response.contains("36"))
        controller.stopSpeaking()
    }
}
