package app.cactusai.core.plan

import app.cactusai.core.needle.NeedleResponse
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.RawCall
import app.cactusai.core.tools.Sensitivity
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ToolRegistry
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.tools.ValidationResult

data class PlannerPolicy(
    /** Act without asking only at or above this confidence. */
    val actThreshold: Double = 0.7,
    /** Ask before opening a prefilled dialer or message. The user can turn this off; nothing else relaxes. */
    val confirmCallsAndTexts: Boolean = true,
    val groups: Set<ToolGroup> = setOf(ToolGroup.PHONE),
    val maxSteps: Int = 4,
)

enum class RefusalKind { NOTHING_MATCHED, INVALID_CALL, TOO_MANY_STEPS, ENGINE_ERROR, NEGATED }

enum class Hint { ENABLE_SCREEN_CONTROL }

sealed interface PlanOutcome {
    /** Safe to run now. */
    data class Run(val steps: List<ValidatedCall>) : PlanOutcome

    /** Show these steps and the reasons; run only after the user agrees. */
    data class NeedsConfirmation(val steps: List<ValidatedCall>, val reasons: List<String>) : PlanOutcome

    /** Nothing will run. [message] is written for the user; [details] is for the debug view. */
    data class Refused(
        val kind: RefusalKind,
        val message: String,
        val details: List<String> = emptyList(),
        val hint: Hint? = null,
    ) : PlanOutcome
}

object Planner {
    private val screenWords = Regex("\\b(scroll|swipe|tap|click|press|type|select|go back|go home|recent apps|recents)\\b", RegexOption.IGNORE_CASE)

    fun plan(userText: String, response: NeedleResponse, registry: ToolRegistry, policy: PlannerPolicy): PlanOutcome {
        if (!response.success) {
            return PlanOutcome.Refused(
                RefusalKind.ENGINE_ERROR,
                "The assistant model reported a problem and did not answer.",
                listOfNotNull(response.errorCode, response.error),
            )
        }

        val confirmReasons = mutableListOf<String>()
        val rawCalls: List<RawCall>
        if (response.calls.isNotEmpty()) {
            rawCalls = response.calls
        } else if (response.suppressedCalls.isNotEmpty()) {
            rawCalls = response.suppressedCalls
            confirmReasons += "The model was not sure about this one."
        } else {
            return refusedNothing(userText, policy)
        }

        if (rawCalls.size > policy.maxSteps) {
            return PlanOutcome.Refused(
                RefusalKind.TOO_MANY_STEPS,
                "That is more than ${policy.maxSteps} steps at once. Please ask for fewer things in one go.",
            )
        }

        val steps = mutableListOf<ValidatedCall>()
        val problems = mutableListOf<String>()
        for (raw in rawCalls) {
            when (val v = registry.validate(raw.name, raw.arguments, policy.groups)) {
                is ValidationResult.Ok -> steps += v.call
                is ValidationResult.Rejected -> problems += v.reason
            }
        }
        if (problems.isNotEmpty()) {
            return PlanOutcome.Refused(
                RefusalKind.INVALID_CALL,
                "I could not turn that into a safe action: ${problems.first()}.",
                problems,
                hint = if (screenWords.containsMatchIn(userText) && ToolGroup.SCREEN !in policy.groups) Hint.ENABLE_SCREEN_CONTROL else null,
            )
        }

        if (response.negated) {
            return PlanOutcome.Refused(RefusalKind.NEGATED, "It sounds like you asked me not to do that, so I did nothing.")
        }

        if (response.ungrounded.isNotEmpty()) {
            confirmReasons += "Some details were not clearly in what you said (${response.ungrounded.joinToString()})."
        }
        val confidence = response.confidence
        if (confidence == null) {
            confirmReasons += "The model gave no confidence score."
        } else if (confidence < policy.actThreshold) {
            confirmReasons += "I am only ${(confidence * 100).toInt()}% sure."
        }
        for (step in steps) {
            val needs = when (step.tool.sensitivity) {
                Sensitivity.SAFE -> false
                Sensitivity.CONFIRM_BY_DEFAULT -> policy.confirmCallsAndTexts
                Sensitivity.ALWAYS_CONFIRM -> true
            }
            if (needs) confirmReasons += "${describeKind(step)} needs your OK first."
        }

        val optimised = PlanOptimizer.collapse(steps)
        return if (confirmReasons.isEmpty()) {
            PlanOutcome.Run(optimised)
        } else {
            PlanOutcome.NeedsConfirmation(optimised, confirmReasons.distinct())
        }
    }

    private fun refusedNothing(userText: String, policy: PlannerPolicy): PlanOutcome.Refused {
        val hint = if (ToolGroup.SCREEN !in policy.groups && screenWords.containsMatchIn(userText)) Hint.ENABLE_SCREEN_CONTROL else null
        val message = if (hint != null) {
            "I could not match that to something I can do. Controlling the screen needs the optional Accessibility service."
        } else {
            "I could not match that to something I can do."
        }
        return PlanOutcome.Refused(RefusalKind.NOTHING_MATCHED, message, hint = hint)
    }

    private fun describeKind(step: ValidatedCall): String = when (step.name) {
        AssistantTools.SEND_TEXT -> "Writing a text message"
        AssistantTools.CALL_CONTACT, AssistantTools.DIAL_NUMBER -> "Opening the dialer"
        else -> "This action"
    }
}
