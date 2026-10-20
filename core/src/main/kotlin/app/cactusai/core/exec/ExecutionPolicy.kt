package app.cactusai.core.exec

import app.cactusai.core.needle.NeedleResponse
import app.cactusai.core.plan.Hint
import app.cactusai.core.plan.PlanOutcome
import app.cactusai.core.plan.Planner
import app.cactusai.core.plan.PlannerPolicy
import app.cactusai.core.plan.RefusalKind
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ToolRegistry
import app.cactusai.core.tools.ValidatedCall

/** How a command that ran was approved. Kept in the history so the user can see which ones did not ask. */
enum class ExecutionMode { AUTOMATIC, CONFIRMED }

data class ExecutionConfig(
    /** The "Automatically execute safe actions" switch. When off, everything is shown first. */
    val autoExecute: Boolean = true,
    /** Act without asking at or above this Needle confidence, in percent. Kept between 50 and 95. */
    val thresholdPercent: Int = DEFAULT_THRESHOLD_PERCENT,
    val groups: Set<ToolGroup> = setOf(ToolGroup.PHONE),
    val maxSteps: Int = 4,
    /** `set volume to N` above this is shown first; up, down and mute are always fine. */
    val maxAutoVolumePercent: Int = 70,
) {
    /** The threshold as Needle's 0..1 scale, computed from whole percent so 60 means exactly 0.60. */
    val threshold: Double get() = thresholdPercent.coerceIn(MIN_THRESHOLD_PERCENT, MAX_THRESHOLD_PERCENT) / 100.0

    companion object {
        const val MIN_THRESHOLD_PERCENT = 50
        const val MAX_THRESHOLD_PERCENT = 95
        const val DEFAULT_THRESHOLD_PERCENT = 70
    }
}

/** The verdict for one step of a plan. [reason] says why a step is not automatic; null when the planner already said it. */
data class StepAssessment(val call: ValidatedCall, val automatic: Boolean, val reason: String?)

sealed interface Decision {
    /** Needle's own calibrated confidence for this answer, or null when it reported none. Never estimated. */
    val confidence: Double?

    /** Every step passed on its own: run now, with no question. */
    data class RunNow(val steps: List<ValidatedCall>, val assessments: List<StepAssessment>, override val confidence: Double?) : Decision

    /** Show the steps and the reasons, and run only if the user says yes. */
    data class AskFirst(
        val steps: List<ValidatedCall>,
        val reasons: List<String>,
        val assessments: List<StepAssessment>,
        override val confidence: Double?,
    ) : Decision

    /** Nothing will run. [heldBack] is what the engine withheld itself, shown for information only. */
    data class Refuse(
        val kind: RefusalKind,
        val message: String,
        val details: List<String>,
        val hint: Hint?,
        val heldBack: List<ValidatedCall>,
        override val confidence: Double?,
    ) : Decision
}

/**
 * Decides whether a command runs by itself, waits for the user, or is refused.
 *
 * It builds on [Planner], which already validates every call against the allowlist and its argument rules, refuses
 * negated requests, and asks when Needle reports no confidence, a confidence under the threshold, or values that are
 * not in what the user said. On top of that this adds:
 *  - an explicit list of tools that may run without asking ([assess]); anything not on it is shown first, so a tool added
 *    later is never automatic by accident;
 *  - a verdict for **each** step, so one confident answer is not blanket permission for a sequence: it runs by itself only
 *    when every step passes on its own;
 *  - a refusal, not a question, for calls the engine held back as unsure.
 *
 * Needle reports one confidence per answer, not per call. Every call of a multi-call answer is therefore judged against
 * that one number, and each must also pass its own checks.
 */
object ExecutionPolicy {
    private val sensitiveSettingsPages = setOf("accessibility")

    fun decide(userText: String, response: NeedleResponse, registry: ToolRegistry, config: ExecutionConfig): Decision {
        val plan = Planner.plan(
            userText, response, registry,
            PlannerPolicy(actThreshold = config.threshold, confirmCallsAndTexts = true, groups = config.groups, maxSteps = config.maxSteps),
        )
        val confidence = response.confidence
        return when (plan) {
            is PlanOutcome.Refused -> Decision.Refuse(plan.kind, plan.message, plan.details, plan.hint, emptyList(), confidence)
            is PlanOutcome.NeedsConfirmation -> {
                if (plan.suppressed) {
                    Decision.Refuse(
                        RefusalKind.HELD_BACK,
                        "The assistant model was not sure what you meant, so I did nothing.",
                        plan.reasons, null, plan.steps, confidence,
                    )
                } else {
                    val assessments = plan.steps.map { assess(it, config) }
                    // The planner already explained calls, texts and confidence; only add what it could not know.
                    val extra = assessments.filter { !it.automatic }.mapNotNull { it.reason }
                    Decision.AskFirst(plan.steps, (plan.reasons + extra).distinct(), assessments, confidence)
                }
            }
            is PlanOutcome.Run -> {
                val assessments = plan.steps.map { assess(it, config) }
                val blocked = assessments.filter { !it.automatic }
                when {
                    !config.autoExecute ->
                        Decision.AskFirst(plan.steps, listOf("Automatic execution is turned off in Settings."), assessments, confidence)
                    blocked.isNotEmpty() ->
                        Decision.AskFirst(plan.steps, blocked.map { it.reason ?: "This action is not on the automatic list." }.distinct(), assessments, confidence)
                    else -> Decision.RunNow(plan.steps, assessments, confidence)
                }
            }
        }
    }

    /** The allowlist. Every tool is named here on purpose; an unnamed one is shown first. */
    fun assess(call: ValidatedCall, config: ExecutionConfig): StepAssessment {
        val a = call.args
        return when (call.name) {
            AssistantTools.OPEN_APP, AssistantTools.SEARCH_WEB, AssistantTools.SET_TIMER, AssistantTools.SET_ALARM,
            AssistantTools.CHECK_BATTERY, AssistantTools.SEARCH_PHONE,
            AssistantTools.SCROLL, AssistantTools.NAVIGATE, AssistantTools.FIND_ON_SCREEN, AssistantTools.TAP_RESULT, AssistantTools.TYPE_TEXT,
            AssistantTools.GET_TIME, AssistantTools.GET_DATE, AssistantTools.DEVICE_INFO, AssistantTools.CALCULATE,
            AssistantTools.SPORTS_INFO, AssistantTools.WEATHER_INFO, AssistantTools.ANSWER_QUESTION,
            -> auto(call)

            AssistantTools.OPEN_SETTINGS -> {
                val page = a.string("page")
                if (page in sensitiveSettingsPages) ask(call, "Opening the $page settings can lead to security changes.") else auto(call)
            }

            AssistantTools.CHANGE_VOLUME -> {
                val level = a.int("level")
                if (a.string("action") == "set" && level != null && level > config.maxAutoVolumePercent) {
                    ask(call, "Setting the volume above ${config.maxAutoVolumePercent}% needs your OK.")
                } else {
                    auto(call)
                }
            }

            // Whatever is on screen when the tap happens is checked again by the Accessibility service just before it taps.
            AssistantTools.TAP_TEXT -> {
                val verdict = TargetSafety.assessRequestedLabel(a.string("text").orEmpty())
                if (verdict.isSafe) auto(call) else ask(call, verdict.reason)
            }

            // Calls and messages: the planner explains these itself, and no setting relaxes them.
            AssistantTools.CALL_CONTACT, AssistantTools.DIAL_NUMBER, AssistantTools.SEND_TEXT -> ask(call, null)

            else -> ask(call, "“${call.name}” is not on the list of actions that run without asking.")
        }
    }

    private fun auto(call: ValidatedCall) = StepAssessment(call, automatic = true, reason = null)

    private fun ask(call: ValidatedCall, reason: String?) = StepAssessment(call, automatic = false, reason = reason)
}
