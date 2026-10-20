package app.cactusai.assistant.assistant

import android.Manifest
import android.content.Context
import app.cactusai.assistant.actions.AndroidActionExecutor
import app.cactusai.assistant.actions.AppCatalog
import app.cactusai.assistant.audio.MicRecorder
import app.cactusai.assistant.audio.RecordingException
import app.cactusai.assistant.engine.EngineHost
import app.cactusai.assistant.engine.HostResult
import app.cactusai.assistant.history.HistoryRepository
import app.cactusai.assistant.settings.AppSettings
import app.cactusai.assistant.system.Permissions
import app.cactusai.assistant.system.ThermalMonitor
import app.cactusai.core.audio.Pcm
import app.cactusai.core.history.HistoryEntry
import app.cactusai.core.history.InputSource
import app.cactusai.core.history.Outcome
import app.cactusai.core.json.Json
import app.cactusai.core.metrics.Timings
import app.cactusai.core.needle.NeedleResponse
import app.cactusai.core.plan.Hint
import app.cactusai.core.plan.PlanOutcome
import app.cactusai.core.plan.Planner
import app.cactusai.core.plan.PlannerPolicy
import app.cactusai.core.steps.Choice
import app.cactusai.core.steps.ResultItem
import app.cactusai.core.steps.RunResult
import app.cactusai.core.steps.StepEvent
import app.cactusai.core.steps.StepOutcome
import app.cactusai.core.steps.StepRunner
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ToolRegistry
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.assistant.a11y.ScreenControl
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one command at a time: record, transcribe (Whistle), interpret (Needle), plan, confirm,
 * execute, remember. Needle only chooses among the registered tools; this class decides whether a
 * choice runs, needs the user's OK, or is refused, and the executor runs it through Android APIs.
 *
 * Every stage is timed on the device. Cancelling at any stage stops the pipeline; a model call that
 * is already running inside the engine cannot be interrupted, so its result is simply discarded.
 */
class AssistantController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val engine: EngineHost,
    private val recorder: MicRecorder,
    private val executor: AndroidActionExecutor,
    private val history: HistoryRepository,
    private val apps: AppCatalog,
    private val thermal: ThermalMonitor,
    private val registry: ToolRegistry = AssistantTools.registry,
) {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var decision: CompletableDeferred<Boolean>? = null
    private var pickedChoice: CompletableDeferred<Choice?>? = null
    private var lastTypedText: String? = null

    val isBusy: Boolean get() = job?.isActive == true

    // ---- entry points used by the screens ----

    fun startListening() {
        if (isBusy) return
        if (!settings.current.voiceActivationEnabled) {
            _state.value = AssistantUiState(phase = Phase.DONE, response = "Voice activation is turned off. Turn it on in Settings, or type a command.", outcome = Outcome.REFUSED)
            return
        }
        if (!Permissions.hasMicrophone(context)) {
            _state.value = AssistantUiState(permission = PermissionNeed.MICROPHONE, status = "Allow the microphone to talk to me.")
            return
        }
        job = scope.launch { runVoice() }
    }

    /** Ends the recording; the audio is then transcribed and run. */
    fun stopListening() {
        if (_state.value.phase == Phase.LISTENING) recorder.requestStop()
    }

    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isBusy) return
        lastTypedText = trimmed
        job = scope.launch { runCommand(trimmed, InputSource.TEXT, Timings(), System.nanoTime()) }
    }

    fun confirm() {
        decision?.complete(true)
    }

    fun decline() {
        decision?.complete(false)
    }

    fun choose(choice: Choice) {
        pickedChoice?.complete(choice)
    }

    fun dismissChoice() {
        pickedChoice?.complete(null)
    }

    /** Stops whatever is running: discards a recording, ignores a model result that is still coming. */
    fun cancel() {
        val wasText = _state.value.recognizedText
        decision?.complete(false)
        pickedChoice?.complete(null)
        job?.cancel()
        job = null
        _state.value = AssistantUiState(phase = Phase.DONE, recognizedText = wasText, response = "Cancelled.", outcome = Outcome.CANCELLED)
    }

    /**
     * The app left the screen. Android silences the microphone for background apps, so a recording in
     * progress would capture nothing: stop it and say why. Work that is already past recording is left alone.
     */
    fun onAppBackgrounded() {
        if (_state.value.phase != Phase.LISTENING) return
        job?.cancel()
        job = null
        _state.value = AssistantUiState(phase = Phase.DONE, response = "Listening stopped because Cactus AI left the screen. Open it and tap the microphone again.", outcome = Outcome.CANCELLED)
    }

    fun clear() {
        if (!isBusy) _state.value = AssistantUiState()
    }

    /** After the user grants a permission the command that needed it can simply be repeated. */
    fun retryAfterPermission() {
        val typed = lastTypedText
        if (_state.value.permission == PermissionNeed.CONTACTS && typed != null) submitText(typed) else _state.update { it.copy(permission = null) }
    }

    /** The user said no in Android's permission dialog: explain what that means instead of failing silently. */
    fun permissionDenied(need: PermissionNeed) {
        val message = when (need) {
            PermissionNeed.MICROPHONE -> "Without the microphone I cannot hear you. You can still type commands, or allow it later in Android settings > Apps > Cactus AI > Permissions."
            PermissionNeed.CONTACTS -> "Without contacts I cannot look people up by name. You can dial a number instead, or allow it later in Android settings > Apps > Cactus AI > Permissions."
        }
        _state.value = AssistantUiState(phase = Phase.DONE, response = message, outcome = Outcome.REFUSED)
    }

    fun onResultClicked(item: ResultItem) {
        val problem = executor.perform(item.action)
        if (problem != null) _state.update { it.copy(action = problem) }
    }

    // ---- pipeline ----

    private suspend fun runVoice() {
        _state.value = AssistantUiState(phase = Phase.LISTENING, source = InputSource.VOICE, status = "Listening… tap again when you are done")
        val audio = try {
            recorder.record { elapsed, level -> _state.update { it.copy(micLevel = level, elapsedMs = elapsed) } }
        } catch (e: RecordingException) {
            fail(null, InputSource.VOICE, e.message ?: "The microphone could not be used.")
            return
        }
        val stoppedAt = System.nanoTime()
        var timings = Timings(recordMs = audio.durationMs)
        _state.update { it.copy(phase = Phase.TRANSCRIBING, status = "Turning your voice into text…", micLevel = 0f) }

        if (ThermalMonitor.shouldPause(thermal.status.value)) {
            fail(null, InputSource.VOICE, "The phone is overheating (${ThermalMonitor.label(thermal.status.value)}). I paused so it can cool down; try again in a minute.")
            return
        }

        var samples = Pcm.toFloat(audio.samples, audio.length)
        if (settings.current.trimSilence) samples = Pcm.trimSilence(samples)
        if (Pcm.durationMs(samples.size) < MIN_SPEECH_MS) {
            _state.value = AssistantUiState(phase = Phase.DONE, source = InputSource.VOICE, response = "I did not hear anything. Tap the microphone and speak after it starts listening.", outcome = Outcome.REFUSED, timings = timings)
            return
        }

        val keywords = if (settings.current.biasSpeechToAppNames) withContext(Dispatchers.IO) { apps.speechKeywords() } else null
        when (val t = engine.transcribe(samples, settings.current.language, keywords)) {
            is HostResult.Fail -> {
                fail(null, InputSource.VOICE, t.message, timings = timings, hint = null)
                return
            }
            is HostResult.Ok -> {
                timings = timings.copy(
                    transcribeMs = t.value.wallMs,
                    modelLoadMs = t.value.loadMs,
                    whistleTtftMs = t.value.result.ttftMs,
                    whistleDecodeTps = t.value.result.decodeTps,
                )
                if (t.value.result.isEmpty) {
                    _state.value = AssistantUiState(phase = Phase.DONE, source = InputSource.VOICE, response = "I could not make out any words. Try again a little closer to the microphone.", outcome = Outcome.REFUSED, timings = timings)
                    return
                }
                _state.update { it.copy(recognizedText = t.value.result.text) }
                runCommand(t.value.result.text, InputSource.VOICE, timings, stoppedAt)
            }
        }
    }

    private suspend fun runCommand(text: String, source: InputSource, base: Timings, startedAt: Long) {
        var timings = base
        _state.value = _state.value.copy(
            phase = Phase.INTERPRETING, source = source, recognizedText = text, status = "Working out what to do…",
            response = "", action = "", results = emptyList(), interpretation = null, rawJson = null,
            confirmation = null, choice = null, permission = null, hint = null, outcome = null,
        )

        if (ThermalMonitor.shouldPause(thermal.status.value)) {
            fail(text, source, "The phone is overheating (${ThermalMonitor.label(thermal.status.value)}). I paused so it can cool down; try again in a minute.", timings)
            return
        }

        val requestedGroups = if (ScreenControl.isAvailable) setOf(ToolGroup.PHONE, ToolGroup.SCREEN) else setOf(ToolGroup.PHONE)
        val interpreted = when (val r = engine.interpret(text, requestedGroups)) {
            is HostResult.Fail -> {
                fail(text, source, r.message, timings, rawJson = r.raw)
                return
            }
            is HostResult.Ok -> r.value
        }
        timings = timings.copy(
            interpretMs = interpreted.wallMs,
            modelLoadMs = listOfNotNull(timings.modelLoadMs, interpreted.loadMs, interpreted.initMs).takeIf { it.isNotEmpty() }?.sum(),
            needlePrefillTps = interpreted.response.prefillTps,
            needleDecodeTps = interpreted.response.decodeTps,
        )

        val s = settings.current
        // Validate against what Needle was really given (the screen tools may have been left out to fit its context).
        val policy = PlannerPolicy(actThreshold = s.actThreshold.toDouble(), confirmCallsAndTexts = s.confirmCallsAndTexts, groups = interpreted.effectiveGroups)
        val plan = Planner.plan(text, interpreted.response, registry, policy)
        val raw = prettyJson(interpreted.response)
        when (plan) {
            is PlanOutcome.Refused -> {
                _state.update { it.copy(interpretation = describeResponse(interpreted.response, null), rawJson = raw) }
                finish(text, source, Outcome.REFUSED, plan.message, null, timings, startedAt, hint = plan.hint)
            }
            is PlanOutcome.Run -> {
                _state.update { it.copy(interpretation = describeResponse(interpreted.response, plan.steps), rawJson = raw) }
                execute(text, source, plan.steps, timings, startedAt)
            }
            is PlanOutcome.NeedsConfirmation -> {
                val summary = describeSteps(plan.steps)
                _state.update {
                    it.copy(
                        phase = Phase.CONFIRMING, status = "Please confirm",
                        interpretation = describeResponse(interpreted.response, plan.steps), rawJson = raw,
                        confirmation = Confirmation(summary, plan.reasons),
                    )
                }
                if (!awaitDecision()) {
                    finish(text, source, Outcome.DECLINED, "Okay, I did nothing.", summary.joinToString("; "), timings, startedAt)
                } else {
                    execute(text, source, plan.steps, timings, startedAt)
                }
            }
        }
    }

    private suspend fun execute(text: String, source: InputSource, steps: List<ValidatedCall>, base: Timings, startedAt: Long) {
        val interpretation = describeSteps(steps).joinToString("; ")
        _state.update { it.copy(phase = Phase.EXECUTING, status = "Doing it…", confirmation = null) }
        val executeStart = System.nanoTime()
        val runner = StepRunner(executor)
        val messages = mutableListOf<String>()
        val results = mutableListOf<ResultItem>()
        var remaining = steps

        while (remaining.isNotEmpty()) {
            val run = runner.run(remaining) { event -> _state.update { it.copy(action = describeEvent(event)) } }
            when (run) {
                is RunResult.Completed -> {
                    messages += run.messages
                    results += run.results
                    remaining = emptyList()
                }
                is RunResult.Stopped -> {
                    for (e in run.events) {
                        // Properties from :core cannot be smart-cast across modules, hence the local val.
                        val done = (e as? StepEvent.Finished)?.outcome as? StepOutcome.Success ?: continue
                        messages += done.message
                        results += done.results
                    }
                    when (val resolved = resolve(run.outcome, run.message)) {
                        is Resolution.Continue -> {
                            messages += resolved.success.message
                            results += resolved.success.results
                            remaining = remaining.drop(run.stoppedAt + 1)
                        }
                        is Resolution.End -> {
                            val t = base.copy(executeMs = (System.nanoTime() - executeStart) / 1_000_000, totalMs = (System.nanoTime() - startedAt) / 1_000_000)
                            finish(text, source, resolved.outcome, resolved.message, interpretation, t, startedAt, results = results, permission = resolved.permission, hint = resolved.hint, partial = messages)
                            return
                        }
                    }
                }
            }
        }
        val t = base.copy(executeMs = (System.nanoTime() - executeStart) / 1_000_000)
        finish(text, source, Outcome.SUCCESS, messages.joinToString("\n").ifBlank { "Done." }, interpretation, t, startedAt, results = results)
    }

    private sealed interface Resolution {
        data class Continue(val success: StepOutcome.Success) : Resolution
        data class End(val outcome: Outcome, val message: String, val permission: PermissionNeed? = null, val hint: Hint? = null) : Resolution
    }

    /** Turns a step that stopped the run into either "carry on" or "this is how it ends", asking the user when needed. */
    private suspend fun resolve(outcome: StepOutcome?, fallbackMessage: String): Resolution = when (outcome) {
        null -> Resolution.End(Outcome.FAILED, fallbackMessage)
        is StepOutcome.Success -> Resolution.Continue(outcome)
        is StepOutcome.Failure -> Resolution.End(Outcome.FAILED, outcome.message)
        is StepOutcome.Unavailable -> Resolution.End(Outcome.FAILED, outcome.message, hint = outcome.hint)
        is StepOutcome.NeedsPermission -> Resolution.End(
            Outcome.NEEDS_INPUT, outcome.message,
            permission = if (outcome.permission == Manifest.permission.READ_CONTACTS) PermissionNeed.CONTACTS else PermissionNeed.MICROPHONE,
        )
        is StepOutcome.NeedsChoice -> {
            _state.update { it.copy(phase = Phase.CHOOSING, status = "Which one?", choice = ChoicePrompt(outcome.message, outcome.choices)) }
            val picked = awaitChoice()
            _state.update { it.copy(choice = null, phase = Phase.EXECUTING, status = "Doing it…") }
            if (picked == null) Resolution.End(Outcome.CANCELLED, "Okay, I did nothing more.") else resolve(outcome.resolve(picked), fallbackMessage)
        }
        is StepOutcome.NeedsApproval -> {
            _state.update { it.copy(phase = Phase.CONFIRMING, status = "Please confirm", confirmation = Confirmation(listOf(outcome.message), emptyList())) }
            if (awaitDecision()) {
                _state.update { it.copy(confirmation = null, phase = Phase.EXECUTING, status = "Doing it…") }
                resolve(outcome.proceed(), fallbackMessage)
            } else {
                Resolution.End(Outcome.DECLINED, "Okay, I did nothing.")
            }
        }
    }

    private suspend fun awaitDecision(): Boolean {
        val d = CompletableDeferred<Boolean>()
        decision = d
        return try {
            d.await()
        } finally {
            decision = null
        }
    }

    private suspend fun awaitChoice(): Choice? {
        val d = CompletableDeferred<Choice?>()
        pickedChoice = d
        return try {
            d.await()
        } finally {
            pickedChoice = null
        }
    }

    // ---- finishing ----

    private fun finish(
        text: String?,
        source: InputSource,
        outcome: Outcome,
        message: String,
        interpretation: String?,
        timings: Timings,
        startedAt: Long,
        results: List<ResultItem> = emptyList(),
        permission: PermissionNeed? = null,
        hint: Hint? = null,
        partial: List<String> = emptyList(),
    ) {
        val total = timings.copy(totalMs = (System.nanoTime() - startedAt) / 1_000_000)
        val shown = if (partial.isNotEmpty() && outcome != Outcome.SUCCESS) (partial + message).joinToString("\n") else message
        if (!text.isNullOrBlank()) {
            history.add(HistoryEntry(System.currentTimeMillis(), System.currentTimeMillis(), source, text, interpretation ?: _state.value.interpretation, outcome, shown, total))
        }
        _state.update {
            it.copy(
                phase = Phase.DONE, source = source, status = "", response = shown, results = results, timings = total,
                permission = permission, hint = hint, outcome = outcome, confirmation = null, choice = null, micLevel = 0f,
            )
        }
    }

    private fun fail(text: String?, source: InputSource, message: String, timings: Timings = Timings(), hint: Hint? = null, rawJson: String? = null) {
        if (rawJson != null) _state.update { it.copy(rawJson = rawJson) }
        finish(text, source, Outcome.FAILED, message, null, timings, System.nanoTime(), hint = hint)
    }

    // ---- text for the screens ----

    private fun describeSteps(steps: List<ValidatedCall>): List<String> =
        steps.map { call ->
            val args = call.args.toMap().entries.joinToString(", ") { "${it.key}: ${it.value}" }
            if (args.isEmpty()) call.name else "${call.name} ($args)"
        }

    private fun describeResponse(response: NeedleResponse, steps: List<ValidatedCall>?): String {
        val lines = mutableListOf<String>()
        lines += if (steps.isNullOrEmpty()) "No action chosen" else describeSteps(steps).joinToString("\n")
        response.confidence?.let { lines += "Confidence: ${(it * 100).toInt()}%" }
        response.reasoning?.takeIf { it.isNotBlank() }?.let { lines += "Reasoning: $it" }
        if (response.ungrounded.isNotEmpty()) lines += "Not found in your words: ${response.ungrounded.joinToString()}"
        return lines.joinToString("\n")
    }

    private fun prettyJson(response: NeedleResponse): String =
        Json.parseOrNull(response.rawJson)?.let { Json.writePretty(it) } ?: response.rawJson

    private fun describeEvent(e: StepEvent): String = when (e) {
        is StepEvent.Started -> "Step ${e.index + 1} of ${e.total}: ${e.call.name}"
        is StepEvent.Finished -> ""
        is StepEvent.TimedOut -> "${e.call.name} took too long"
        is StepEvent.Cancelled -> "Stopped"
    }

    private companion object {
        const val MIN_SPEECH_MS = 250L
    }
}
