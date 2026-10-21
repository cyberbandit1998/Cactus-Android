package app.cactusai.core.settings

import app.cactusai.core.ask.InfoSettings
import app.cactusai.core.exec.ExecutionConfig
import app.cactusai.core.speech.SpeechSettings
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.voice.CaptureConfig
import app.cactusai.core.voice.EndpointConfig
import app.cactusai.core.voice.SpeechSensitivity
import app.cactusai.core.web.ReadMode
import app.cactusai.core.web.TempUnit

/**
 * Everything the user can set about listening, running commands and speaking answers, kept as plain values so the
 * clamping and the hand-over to each component can be tested without a phone. The app stores this and nothing else
 * about these features; there is no key or account in it.
 *
 * Confirming sensitive actions has no field on purpose: it is not a setting, because no setting may switch it off.
 */
data class AssistantPreferences(
    // ---- listening ----
    /** Stop recording by itself when the speaker has finished. Tapping the microphone again always works too. */
    val autoStop: Boolean = true,
    val silenceMs: Int = EndpointConfig.DEFAULT_SILENCE_MS,
    val maxRecordMs: Int = EndpointConfig.DEFAULT_MAX_MS,
    val sensitivity: SpeechSensitivity = SpeechSensitivity.NORMAL,

    // ---- running commands ----
    /** "Automatically execute safe actions". Off means every command is shown first. */
    val autoExecute: Boolean = true,
    val thresholdPercent: Int = ExecutionConfig.DEFAULT_THRESHOLD_PERCENT,
    val showConfidence: Boolean = true,
    /** How long a command that will run by itself is shown, with a Cancel button, before it starts. 0 runs it at once. */
    val cancelWindowMs: Int = DEFAULT_CANCEL_WINDOW_MS,

    // ---- speaking ----
    val speakAnswers: Boolean = true,
    val voiceOutput: Boolean = true,
    val speechRate: Float = 1.0f,
    val speechPitch: Float = 1.0f,
    val voiceName: String? = null,

    // ---- information ----
    val onlineLookups: Boolean = true,
    val readSearchResults: Boolean = true,
    val readMode: ReadMode = ReadMode.SHORT_ANSWER,
    val tempUnit: TempUnit = TempUnit.AUTO,
    val favoriteTeams: List<String> = emptyList(),
    val homePlace: String? = null,
    val use24Hour: Boolean = false,
) {
    /** The same values forced into their allowed ranges, whatever was stored or typed. */
    fun normalized(): AssistantPreferences = copy(
        silenceMs = silenceMs.coerceIn(EndpointConfig.MIN_SILENCE_MS, EndpointConfig.MAX_SILENCE_MS),
        maxRecordMs = maxRecordMs.coerceIn(EndpointConfig.MIN_RECORDING_MS, EndpointConfig.MAX_RECORDING_MS),
        thresholdPercent = thresholdPercent.coerceIn(ExecutionConfig.MIN_THRESHOLD_PERCENT, ExecutionConfig.MAX_THRESHOLD_PERCENT),
        cancelWindowMs = cancelWindowMs.coerceIn(0, MAX_CANCEL_WINDOW_MS),
        speechRate = speechRate.coerceIn(SpeechSettings.MIN_RATE, SpeechSettings.MAX_RATE),
        speechPitch = speechPitch.coerceIn(SpeechSettings.MIN_PITCH, SpeechSettings.MAX_PITCH),
        voiceName = voiceName?.trim()?.ifBlank { null },
        favoriteTeams = favoriteTeams.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_FAVORITES),
        homePlace = homePlace?.trim()?.ifBlank { null }?.take(MAX_PLACE_LENGTH),
    )

    fun captureConfig(): CaptureConfig {
        val p = normalized()
        return CaptureConfig(p.autoStop, EndpointConfig(silenceMs = p.silenceMs, maxMs = p.maxRecordMs, sensitivity = p.sensitivity).normalized())
    }

    /** [screenAvailable] adds the tools that need the Accessibility service. */
    fun executionConfig(screenAvailable: Boolean): ExecutionConfig {
        val p = normalized()
        val groups = buildSet {
            add(ToolGroup.PHONE)
            add(ToolGroup.INFO)
            add(ToolGroup.ONLINE)
            if (screenAvailable) add(ToolGroup.SCREEN)
        }
        return ExecutionConfig(autoExecute = p.autoExecute, thresholdPercent = p.thresholdPercent, groups = groups)
    }

    fun speechSettings(): SpeechSettings {
        val p = normalized()
        return SpeechSettings(enabled = p.voiceOutput, autoSpeak = p.speakAnswers, rate = p.speechRate, pitch = p.speechPitch, voiceName = p.voiceName)
    }

    fun infoSettings(): InfoSettings {
        val p = normalized()
        return InfoSettings(p.onlineLookups, p.readSearchResults, p.readMode, p.tempUnit, p.favoriteTeams, p.homePlace, p.use24Hour)
    }

    companion object {
        const val DEFAULT_CANCEL_WINDOW_MS = 1_200
        const val MAX_CANCEL_WINDOW_MS = 5_000
        const val MAX_FAVORITES = 10
        const val MAX_PLACE_LENGTH = 80

        /** The settings page offers these as one-tap values; 60 is there because the user asked to be able to test it. */
        val THRESHOLD_PRESETS: List<Int> = listOf(60, 70, 80, 90)

        /** Favourite teams are stored as one line each. */
        fun parseTeams(text: String): List<String> = text.split('\n', ',', ';').map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(MAX_FAVORITES)
    }
}
