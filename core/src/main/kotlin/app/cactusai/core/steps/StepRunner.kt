package app.cactusai.core.steps

import app.cactusai.core.plan.Hint
import app.cactusai.core.tools.ValidatedCall
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/** One thing the user can pick when a name matched several apps or contacts. */
data class Choice(val id: String, val label: String, val detail: String? = null)

/** What tapping a result row does. Only these three things; nothing the model wrote is ever an action. */
sealed interface ResultAction {
    data class LaunchPackage(val packageName: String) : ResultAction
    data class DialNumber(val number: String) : ResultAction
    data class OpenUri(val uri: String) : ResultAction
    data object None : ResultAction
}

/** A row in a result list: an installed app, a contact, a file. */
data class ResultItem(val title: String, val subtitle: String? = null, val action: ResultAction = ResultAction.None)

sealed interface StepOutcome {
    data class Success(val message: String, val results: List<ResultItem> = emptyList()) : StepOutcome
    data class Failure(val message: String) : StepOutcome

    /** Several apps/contacts matched; the UI asks, then [resolve] finishes the step with the picked [Choice]. */
    data class NeedsChoice(
        val message: String,
        val choices: List<Choice>,
        val resolve: suspend (Choice) -> StepOutcome,
    ) : StepOutcome

    /** The step is about to do something the user should approve first (for example tap "Pay now"). */
    data class NeedsApproval(val message: String, val proceed: suspend () -> StepOutcome) : StepOutcome

    /** A runtime permission is missing; the UI asks for it and the user can repeat the command. */
    data class NeedsPermission(val message: String, val permission: String) : StepOutcome

    /** The thing asked for is not available here (app not installed, accessibility off, offline, ...). */
    data class Unavailable(val message: String, val hint: Hint? = null) : StepOutcome
}

fun interface StepExecutor {
    /** Runs one validated step and verifies it. Must be cancellable. */
    suspend fun execute(call: ValidatedCall): StepOutcome
}

data class StepPolicy(
    val perStepTimeoutMs: Long = 8_000,
    /** Pause between steps so the screen the next step needs has time to appear. */
    val settleMs: Long = 600,
)

sealed interface StepEvent {
    data class Started(val index: Int, val total: Int, val call: ValidatedCall) : StepEvent
    data class Finished(val index: Int, val call: ValidatedCall, val outcome: StepOutcome) : StepEvent
    data class TimedOut(val index: Int, val call: ValidatedCall) : StepEvent
    data class Cancelled(val index: Int, val call: ValidatedCall) : StepEvent
}

sealed interface RunResult {
    val events: List<StepEvent>

    data class Completed(override val events: List<StepEvent>, val messages: List<String>, val results: List<ResultItem>) : RunResult

    /** [stoppedAt] is the index of the step that stopped the run; steps after it did not run. */
    data class Stopped(override val events: List<StepEvent>, val message: String, val outcome: StepOutcome?, val stoppedAt: Int) : RunResult
}

/**
 * Runs validated steps one after another. Each step has a timeout; the first step that fails,
 * times out, needs the user, or is not available stops the sequence, so later steps never run on a
 * screen that is not the one the earlier steps were meant to produce. Cancelling the calling
 * coroutine cancels the step in flight.
 */
class StepRunner(private val executor: StepExecutor, private val policy: StepPolicy = StepPolicy()) {

    suspend fun run(steps: List<ValidatedCall>, onEvent: (StepEvent) -> Unit = {}): RunResult {
        val events = mutableListOf<StepEvent>()
        fun emit(e: StepEvent) {
            events += e
            onEvent(e)
        }
        val messages = mutableListOf<String>()
        val results = mutableListOf<ResultItem>()
        for ((i, call) in steps.withIndex()) {
            emit(StepEvent.Started(i, steps.size, call))
            val outcome = try {
                withTimeoutOrNull(policy.perStepTimeoutMs) { executor.execute(call) }
            } catch (e: CancellationException) {
                emit(StepEvent.Cancelled(i, call))
                throw e
            }
            if (outcome == null) {
                emit(StepEvent.TimedOut(i, call))
                return RunResult.Stopped(events, "${call.name} took too long and was stopped.", null, i)
            }
            emit(StepEvent.Finished(i, call, outcome))
            when (outcome) {
                is StepOutcome.Success -> {
                    messages += outcome.message
                    results += outcome.results
                }
                is StepOutcome.Failure -> return RunResult.Stopped(events, outcome.message, outcome, i)
                is StepOutcome.NeedsChoice -> return RunResult.Stopped(events, outcome.message, outcome, i)
                is StepOutcome.NeedsApproval -> return RunResult.Stopped(events, outcome.message, outcome, i)
                is StepOutcome.NeedsPermission -> return RunResult.Stopped(events, outcome.message, outcome, i)
                is StepOutcome.Unavailable -> return RunResult.Stopped(events, outcome.message, outcome, i)
            }
            if (i < steps.lastIndex) delay(policy.settleMs)
        }
        return RunResult.Completed(events, messages, results)
    }
}
