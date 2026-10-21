package app.cactusai.assistant.assistant

import app.cactusai.core.exec.ExecutionMode
import app.cactusai.core.history.InputSource
import app.cactusai.core.history.Outcome
import app.cactusai.core.info.InfoAnswer
import app.cactusai.core.metrics.Timings
import app.cactusai.core.plan.Hint
import app.cactusai.core.steps.Choice
import app.cactusai.core.steps.ResultItem

enum class Phase { IDLE, LISTENING, TRANSCRIBING, INTERPRETING, CONFIRMING, CHOOSING, EXECUTING, DONE }

/** What the microphone indicator says. Always shown in words, never by colour alone. */
enum class MicState(val label: String) {
    IDLE("Idle"),
    LISTENING("Listening"),
    SPEECH_DETECTED("Speech detected"),
    PROCESSING("Processing"),
}

enum class PermissionNeed { MICROPHONE, CONTACTS }

data class Confirmation(val steps: List<String>, val reasons: List<String>)

data class ChoicePrompt(val message: String, val choices: List<Choice>)

/** Everything the screens (and the assistant overlay) draw. One command at a time. */
data class AssistantUiState(
    val phase: Phase = Phase.IDLE,
    val source: InputSource? = null,
    /** Recognised speech, or the typed command. */
    val recognizedText: String = "",
    /** Short live status such as "Listening…". */
    val status: String = "",
    /** The assistant's answer for the user. */
    val response: String = "",
    /** What is happening right now, step by step. */
    val action: String = "",
    val micLevel: Float = 0f,
    val elapsedMs: Long = 0,
    /** The detector has heard speech in this recording (the silence countdown only runs after that). */
    val speechDetected: Boolean = false,
    /** Seconds of quiet after speech that end the recording, or null when the recording only ends when the user taps. */
    val autoStopSeconds: Float? = null,
    /** The longest a recording can last, in seconds. */
    val maxRecordSeconds: Int = 30,
    /** Human-readable form of the tool calls the model chose. */
    val interpretation: String? = null,
    /** The model's raw JSON envelope, for the debug viewer. */
    val rawJson: String? = null,
    /** Needle's own confidence for this command (0..1), or null when it reported none. */
    val confidence: Double? = null,
    /** True once the model has answered, so "not reported" can be told apart from "not asked yet". */
    val confidenceKnown: Boolean = false,
    /** Whether this command ran by itself or after the user said yes; null until something runs. */
    val executionMode: ExecutionMode? = null,
    /** The information answers for this command (time, scores, search results...), for the card and the speaker. */
    val answers: List<InfoAnswer> = emptyList(),
    /** How the last recording ended and what the detector measured, for the details view. */
    val listeningReport: String? = null,
    val confirmation: Confirmation? = null,
    val choice: ChoicePrompt? = null,
    val permission: PermissionNeed? = null,
    val results: List<ResultItem> = emptyList(),
    val timings: Timings? = null,
    val hint: Hint? = null,
    val outcome: Outcome? = null,
) {
    val busy: Boolean get() = phase != Phase.IDLE && phase != Phase.DONE
    val isError: Boolean get() = outcome == Outcome.FAILED

    val micState: MicState
        get() = when (phase) {
            Phase.LISTENING -> if (speechDetected) MicState.SPEECH_DETECTED else MicState.LISTENING
            Phase.TRANSCRIBING, Phase.INTERPRETING, Phase.EXECUTING -> MicState.PROCESSING
            Phase.IDLE, Phase.CONFIRMING, Phase.CHOOSING, Phase.DONE -> MicState.IDLE
        }
}
