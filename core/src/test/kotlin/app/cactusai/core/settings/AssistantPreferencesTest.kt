package app.cactusai.core.settings

import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.voice.SpeechSensitivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantPreferencesTest {
    @Test
    fun theDefaultsAreTheDocumentedOnes() {
        val p = AssistantPreferences()
        assertTrue(p.autoStop)
        assertEquals(1500, p.silenceMs)
        assertEquals(30_000, p.maxRecordMs)
        assertTrue(p.autoExecute)
        assertEquals(70, p.thresholdPercent)
        assertTrue(p.speakAnswers)
    }

    @Test
    fun theSilenceTimeoutIsKeptBetweenHalfASecondAndFourSeconds() {
        assertEquals(500, AssistantPreferences(silenceMs = 100).captureConfig().endpoint.silenceMs)
        assertEquals(4000, AssistantPreferences(silenceMs = 99_000).captureConfig().endpoint.silenceMs)
        assertEquals(2300, AssistantPreferences(silenceMs = 2300).captureConfig().endpoint.silenceMs)
    }

    @Test
    fun theLongestRecordingIsKeptBetweenThreeAndThirtySeconds() {
        assertEquals(3000, AssistantPreferences(maxRecordMs = 10).captureConfig().endpoint.maxMs)
        assertEquals(30_000, AssistantPreferences(maxRecordMs = 600_000).captureConfig().endpoint.maxMs)
    }

    @Test
    fun theSensitivityAndTheSwitchReachTheCaptureConfig() {
        val c = AssistantPreferences(autoStop = false, sensitivity = SpeechSensitivity.HIGH).captureConfig()
        assertFalse(c.autoStop)
        assertEquals(SpeechSensitivity.HIGH, c.endpoint.sensitivity)
    }

    @Test
    fun theConfidenceThresholdIsKeptBetweenFiftyAndNinetyFivePercent() {
        assertEquals(0.5, AssistantPreferences(thresholdPercent = 10).executionConfig(false).threshold, 0.0)
        assertEquals(0.95, AssistantPreferences(thresholdPercent = 100).executionConfig(false).threshold, 0.0)
    }

    @Test
    fun sixtyPercentIsExactlySixtyPercent() {
        assertEquals(0.60, AssistantPreferences(thresholdPercent = 60).executionConfig(false).threshold, 0.0)
        assertTrue(60 in AssistantPreferences.THRESHOLD_PRESETS)
    }

    @Test
    fun screenToolsAreOfferedOnlyWhileTheAccessibilityServiceIsOn() {
        val off = AssistantPreferences().executionConfig(screenAvailable = false).groups
        val on = AssistantPreferences().executionConfig(screenAvailable = true).groups
        assertFalse(ToolGroup.SCREEN in off)
        assertTrue(ToolGroup.SCREEN in on)
        assertTrue(setOf(ToolGroup.PHONE, ToolGroup.INFO, ToolGroup.ONLINE).all { it in off })
    }

    @Test
    fun theAutomaticSwitchReachesThePolicy() {
        assertFalse(AssistantPreferences(autoExecute = false).executionConfig(false).autoExecute)
    }

    @Test
    fun speechRateAndPitchStayInTheRangeAndTheSwitchesAreIndependent() {
        val s = AssistantPreferences(speechRate = 9f, speechPitch = 0.01f, voiceOutput = true, speakAnswers = false).speechSettings()
        assertEquals(2.0f, s.rate, 0f)
        assertEquals(0.5f, s.pitch, 0f)
        assertTrue(s.enabled)
        assertFalse(s.autoSpeak)
    }

    @Test
    fun aBlankVoiceMeansTheSystemDefault() {
        assertNull(AssistantPreferences(voiceName = "  ").speechSettings().voiceName)
        assertEquals("en-us-x-tpd-local", AssistantPreferences(voiceName = " en-us-x-tpd-local ").speechSettings().voiceName)
    }

    @Test
    fun favouriteTeamsAndHomePlaceAreCleanedAndLimited() {
        val p = AssistantPreferences(favoriteTeams = listOf(" Tigers ", "", "Tigers", "Lions") + (1..20).map { "Team $it" }, homePlace = "  Detroit  ")
        val i = p.infoSettings()
        assertEquals("Tigers", i.favoriteTeams.first())
        assertEquals(10, i.favoriteTeams.size)
        assertEquals(1, i.favoriteTeams.count { it == "Tigers" })
        assertEquals("Detroit", i.homePlace)
        assertNull(AssistantPreferences(homePlace = " ").infoSettings().homePlace)
    }

    @Test
    fun teamsAreReadFromOneLineOrSeveral() {
        assertEquals(listOf("Detroit Tigers", "Lions"), AssistantPreferences.parseTeams("Detroit Tigers, Lions\nDetroit Tigers"))
        assertTrue(AssistantPreferences.parseTeams("   \n ,").isEmpty())
    }

    @Test
    fun theCancelWindowCanBeZeroAndIsCapped() {
        assertEquals(0, AssistantPreferences(cancelWindowMs = -5).normalized().cancelWindowMs)
        assertEquals(AssistantPreferences.MAX_CANCEL_WINDOW_MS, AssistantPreferences(cancelWindowMs = 60_000).normalized().cancelWindowMs)
    }

    @Test
    fun thereIsNoSettingThatTurnsOffTheConfirmationOfSensitiveActions() {
        // The data class has no such field; a future one would have to be added on purpose and would fail this test.
        val names = AssistantPreferences::class.java.declaredFields.map { it.name.lowercase() }
        assertFalse(names.any { "confirm" in it || "sensitive" in it })
    }
}
