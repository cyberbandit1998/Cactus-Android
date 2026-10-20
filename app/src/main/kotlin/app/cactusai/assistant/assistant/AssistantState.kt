package app.cactusai.assistant.assistant

import app.cactusai.core.history.InputSource
import app.cactusai.core.history.Outcome
import app.cactusai.core.metrics.Timings
import app.cactusai.core.plan.Hint
import app.cactusai.core.steps.Choice
import app.cactusai.core.steps.ResultItem

enum class Phase { IDLE, LISTENING, TRANSCRIBING, INTERPRETING, CONFIRMING, CHOOSING, EXECUTING, DONE }

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
    /** Human-readable form of the tool calls the model chose. */
    val interpretation: String? = null,
    /** The model's raw JSON envelope, for the debug viewer. */
    val rawJson: String? = null,
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
}
