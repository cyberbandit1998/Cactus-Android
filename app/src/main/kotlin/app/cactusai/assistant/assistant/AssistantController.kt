package app.cactusai.assistant.assistant

import android.Manifest
import android.content.Context
import app.cactusai.assistant.a11y.ScreenControl
import app.cactusai.assistant.actions.AndroidActionExecutor
import app.cactusai.assistant.actions.AppCatalog
import app.cactusai.assistant.audio.MicRecorder
import app.cactusai.assistant.engine.EngineHost
import app.cactusai.assistant.engine.HostResult
import app.cactusai.assistant.history.HistoryRepository
import app.cactusai.assistant.settings.AppSettings
import app.cactusai.assistant.speech.SpeechOutput
import app.cactusai.assistant.system.Permissions
import app.cactusai.assistant.system.ThermalMonitor
import app.cactusai.core.ask.InfoExecutor
import app.cactusai.core.exec.ActionText
import app.cactusai.core.exec.Decision
import app.cactusai.core.exec.ExecutionMode
import app.cactusai.core.exec.ExecutionPolicy
import app.cactusai.core.history.HistoryEntry
import app.cactusai.core.history.InputSource
import app.cactusai.core.history.Outcome
import app.cactusai.core.info.AnswerOrigin
import app.cactusai.core.info.AnswerSpeech
import app.cactusai.core.info.InfoAnswer
import app.cactusai.core.json.Json
import app.cactusai.core.metrics.Timings
import app.cactusai.core.models.ModelKind
import app.cactusai.core.needle.NeedleResponse
import app.cactusai.core.plan.Hint
import app.cactusai.core.steps.Choice
import app.cactusai.core.steps.ResultItem
import app.cactusai.core.steps.RunResult
import app.cactusai.core.steps.StepEvent
import app.cactusai.core.steps.StepOutcome
import app.cactusai.core.steps.StepPolicy
import app.cactusai.core.steps.StepRunner
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolRegistry
import app.cactusai.core.tools.ValidatedCall
import app.cactusai.core.voice.CaptureException
import app.cactusai.core.voice.CapturedAudio
import app.cactusai.core.voice.NothingHeardReason
import app.cactusai.core.voice.VoiceCapture
import app.cactusai.core.voice.VoiceTurn
import app.cactusai.core.voice.VoiceTurnResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs one command at a time: record (and notice by itself when the speaker has finished), transcribe (Whistle), interpret
 * (Needle), decide, run, remember, and speak the answer if there is one.
 *
 * Needle only chooses among the registered tools. [ExecutionPolicy] decides whether a choice runs by itself, waits for the
 * user's OK or is refused, step by step; the executor runs it through Android APIs. Sensitive actions are always confirmed;
 * there is no setting that changes that.
 *
 * Every stage is timed on the device. Cancelling at any stage stops the pipeline; a model call that is already running inside
 * the engine cannot be interrupted, so its result is simply discarded.
 */
class AssistantController(
    private val context: Context,
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val engine: EngineHost,
    private val recorder: MicRecorder,
    private val executor: AndroidActionExecutor,
    private val info: InfoExecutor,
    private val speech: SpeechOutput,
    private val history: HistoryRepository,
    private val apps: AppCatalog,
    private val thermal: ThermalMonitor,
    private val registry: ToolRegistry = AssistantTools.full,
) {
    private val _state = MutableStateFlow(AssistantUiState())
    val state: StateFlow<AssistantUiState> = _state.asStateFlow()

    private var job: Job? = null
    private var decision: CompletableDeferred<Boolean>? = null
    private var pickedChoice: CompletableDeferred<Choice?>? = null
    private var lastTypedText: String? = null

    @Volatile
    private var approvedMidRun = false

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
        // Do not make the user speak into a microphone only to say afterwards that the engine or a model is missing.
        val missing = if (!engine.isLinked) {
            "This build of the app has no AI engine inside, so it cannot hear or understand commands. Rebuild it with the engine (see the README)."
        } else {
            engine.missingModelMessage(ModelKind.WHISTLE) ?: engine.missingModelMessage(ModelKind.NEEDLE)
        }
        if (missing != null) {
            _state.value = AssistantUiState(phase = Phase.DONE, response = missing, outcome = Outcome.FAILED)
            return
        }
        // Whatever is being said stops first, and the recorder is marked in use from this moment, so the assistant's own voice
        // can neither be recorded as the next command nor start again while the microphone is open.
        val wasSpeaking = speech.isSpeaking
        speech.stopForMicrophone()
        speech.prepare() // a spoken answer is likely, and the speech engine takes a moment to start
        recorder.prepare()
        job = scope.launch { runVoice(wasSpeaking) }
    }

    /** Ends the recording by hand; the audio is then transcribed and run. Works whether or not automatic stopping is on. */
    fun stopListening() {
        if (_state.value.phase == Phase.LISTENING) recorder.requestStop()
    }

    fun submitText(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || isBusy) return
        lastTypedText = trimmed
        engine.missingModelMessage(ModelKind.NEEDLE)?.let {
            _state.value = AssistantUiState(phase = Phase.DONE, source = InputSource.TEXT, recognizedText = trimmed, response = it, outcome = Outcome.FAILED)
            return
        }
        speech.stop()
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

    /** The Stop Speaking button. */
    fun stopSpeaking() = speech.stop()

    /** Stops whatever is running: discards a recording, ignores a model result that is still coming, and stops speaking. */
    fun cancel() {
        val wasText = _state.value.recognizedText
        decision?.complete(false)
        pickedChoice?.complete(null)
        job?.cancel()
        job = null
        recorder.release()
        speech.stop()
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
        recorder.release()
        _state.value = AssistantUiState(phase = Phase.DONE, response = "Listening stopped because Cactus AI left the screen. Open it and tap the microphone again.", outcome = Outcome.CANCELLED)
    }

    /**
     * The assistant overlay went away. A command that is already running is left to finish: opening the browser for a search is
     * exactly what hides the overlay, and cancelling then would throw the lookup away. Anything still waiting for the user
     * (a recording, a question) can no longer be answered, so it is cancelled.
     */
    fun onOverlayHidden() {
        if (!isBusy || _state.value.phase == Phase.EXECUTING) return
        cancel()
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

    private suspend fun runVoice(wasSpeaking: Boolean) {
        val prefs = settings.current.assistant
        val capture = prefs.captureConfig()
        _state.value = AssistantUiState(
            phase = Phase.LISTENING, source = InputSource.VOICE,
            status = if (capture.autoStop) "Listening… I will stop when you stop talking" else "Listening… tap again when you are done",
            autoStopSeconds = if (capture.autoStop) capture.endpoint.silenceMs / 1000f else null,
            maxRecordSeconds = capture.endpoint.maxMs / 1000,
        )
        // The speaker needs a moment to fall silent, or its last word could be taken for the start of the command.
        if (wasSpeaking) delay(SPEECH_TAIL_MS)

        val audio: CapturedAudio = try {
            recorder.withMicrophone { source ->
                VoiceCapture.capture(source, capture, stopRequested = recorder::stopWasRequested) { u ->
                    _state.update { it.copy(micLevel = u.level, elapsedMs = u.elapsedMs, speechDetected = u.speechDetected) }
                }
            }
        } catch (e: CaptureException) {
            fail(null, InputSource.VOICE, e.message ?: "The microphone could not be used.")
            return
        } finally {
            recorder.release()
        }

        val stoppedAt = System.nanoTime()
        var timings = Timings(recordMs = audio.durationMs)
        _state.update {
            it.copy(
                phase = Phase.TRANSCRIBING, status = "Turning your voice into text…", micLevel = 0f, speechDetected = false,
                listeningReport = audio.report.describe(),
            )
        }

        val keywords = if (settings.current.biasSpeechToAppNames) withContext(Dispatchers.IO) { apps.speechKeywords() } else null
        val turn = VoiceTurn.afterCapture(
            audio = audio,
            trimSilence = settings.current.trimSilence,
            beforeTranscribe = { overheatingMessage() },
        ) { samples -> engine.transcribe(samples, settings.current.language, keywords) }

        when (turn) {
            is VoiceTurnResult.NothingHeard -> {
                val message = when (turn.why) {
                    NothingHeardReason.NO_SPEECH_TIMEOUT -> "I did not hear anything, so I stopped listening. Tap the microphone and start speaking."
                    NothingHeardReason.TOO_SHORT -> "I did not hear anything. Tap the microphone and speak after it starts listening."
                }
                _state.update { AssistantUiState(phase = Phase.DONE, source = InputSource.VOICE, response = message, outcome = Outcome.REFUSED, timings = timings, listeningReport = it.listeningReport) }
            }
            is VoiceTurnResult.Blocked -> fail(null, InputSource.VOICE, turn.message)
            is VoiceTurnResult.Transcribed -> when (val t = turn.value) {
                is HostResult.Fail -> fail(null, InputSource.VOICE, t.message, timings = timings, hint = null)
                is HostResult.Ok -> {
                    timings = timings.copy(
                        transcribeMs = t.value.wallMs,
                        modelLoadMs = t.value.loadMs,
                        whistleTtftMs = t.value.result.ttftMs,
                        whistleDecodeTps = t.value.result.decodeTps,
                    )
                    if (t.value.result.isEmpty) {
                        _state.update { AssistantUiState(phase = Phase.DONE, source = InputSource.VOICE, response = "I could not make out any words. Try again a little closer to the microphone.", outcome = Outcome.REFUSED, timings = timings, listeningReport = it.listeningReport) }
                        return
                    }
                    _state.update { it.copy(recognizedText = t.value.result.text) }
                    runCommand(t.value.result.text, InputSource.VOICE, timings, stoppedAt)
                }
            }
        }
    }

    private fun overheatingMessage(): String? =
        if (ThermalMonitor.shouldPause(thermal.status.value)) {
            "The phone is overheating (${ThermalMonitor.label(thermal.status.value)}). I paused so it can cool down; try again in a minute."
        } else {
            null
        }

    private suspend fun runCommand(text: String, source: InputSource, base: Timings, startedAt: Long) {
        var timings = base
        info.userText = text
        _state.value = _state.value.copy(
            phase = Phase.INTERPRETING, source = source, recognizedText = text, status = "Working out what to do…",
            response = "", action = "", results = emptyList(), interpretation = null, rawJson = null,
            confirmation = null, choice = null, permission = null, hint = null, outcome = null,
            confidence = null, confidenceKnown = false, executionMode = null, answers = emptyList(),
        )

        overheatingMessage()?.let {
            fail(text, source, it, timings)
            return
        }

        val prefs = settings.current.assistant
        val requested = prefs.executionConfig(screenAvailable = ScreenControl.isAvailable)
        val interpreted = when (val r = engine.interpret(text, requested.groups)) {
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

        // Validate against what Needle was really given (groups may have been left out to fit its context window).
        val config = requested.copy(groups = interpreted.effectiveGroups)
        val verdict = ExecutionPolicy.decide(text, interpreted.response, registry, config)
        val raw = prettyJson(interpreted.response)
        _state.update { it.copy(confidence = verdict.confidence, confidenceKnown = true, rawJson = raw) }

        when (verdict) {
            is Decision.Refuse -> {
                val held = if (verdict.heldBack.isNotEmpty()) "Held back by the model: " + describeSteps(verdict.heldBack).joinToString("; ") else null
                _state.update { it.copy(interpretation = describeResponse(interpreted.response, null, extra = held)) }
                finish(text, source, Outcome.REFUSED, verdict.message, null, timings, startedAt, hint = verdict.hint, confidence = verdict.confidence)
            }
            is Decision.RunNow -> {
                _state.update { it.copy(interpretation = describeResponse(interpreted.response, verdict.steps)) }
                if (ExecutionPolicy.needsCancelWindow(verdict.steps)) showCancelWindow(verdict.steps, prefs.cancelWindowMs)
                execute(text, source, verdict.steps, timings, startedAt, ExecutionMode.AUTOMATIC, verdict.confidence)
            }
            is Decision.AskFirst -> {
                val summary = describeSteps(verdict.steps)
                _state.update {
                    it.copy(
                        phase = Phase.CONFIRMING, status = "Please confirm",
                        interpretation = describeResponse(interpreted.response, verdict.steps),
                        confirmation = Confirmation(summary, verdict.reasons),
                    )
                }
                if (!awaitDecision()) {
                    finish(text, source, Outcome.DECLINED, "Okay, I did nothing.", summary.joinToString("; "), timings, startedAt, confidence = verdict.confidence)
                } else {
                    execute(text, source, verdict.steps, timings, startedAt, ExecutionMode.CONFIRMED, verdict.confidence)
                }
            }
        }
    }

    /**
     * Shows what is about to happen, with the Cancel button, for the length of the cancel window before a command that runs by
     * itself starts. The Cancel button cancels the whole job, which ends the wait.
     */
    private suspend fun showCancelWindow(steps: List<ValidatedCall>, ms: Int) {
        if (ms <= 0) return
        val what = ActionText.describeAll(steps)
        _state.update { it.copy(phase = Phase.EXECUTING, status = "Starting: $what. Tap Cancel to stop.", action = what) }
        delay(ms.toLong())
    }

    private suspend fun execute(
        text: String,
        source: InputSource,
        steps: List<ValidatedCall>,
        base: Timings,
        startedAt: Long,
        mode: ExecutionMode,
        confidence: Double?,
    ) {
        val interpretation = describeSteps(steps).joinToString("; ")
        _state.update { it.copy(phase = Phase.EXECUTING, status = ActionText.describeAll(steps) + "…", confirmation = null, executionMode = mode) }
        val executeStart = System.nanoTime()
        approvedMidRun = false
        val runner = StepRunner(executor, StepPolicy(toolTimeoutMs = InfoExecutor.SLOW_TOOLS))
        val messages = mutableListOf<String>()
        val results = mutableListOf<ResultItem>()
        val answers = mutableListOf<InfoAnswer>()
        var remaining = steps

        while (remaining.isNotEmpty()) {
            val run = runner.run(remaining) { event -> _state.update { it.copy(action = describeEvent(event)) } }
            when (run) {
                is RunResult.Completed -> {
                    messages += run.messages
                    results += run.results
                    answers += run.answers
                    remaining = emptyList()
                }
                is RunResult.Stopped -> {
                    for (e in run.events) {
                        // Properties from :core cannot be smart-cast across modules, hence the local val.
                        val done = (e as? StepEvent.Finished)?.outcome as? StepOutcome.Success ?: continue
                        messages += done.message
                        results += done.results
                        done.answer?.let { answers += it }
                    }
                    when (val resolved = resolve(run.outcome, run.message)) {
                        is Resolution.Continue -> {
                            messages += resolved.success.message
                            results += resolved.success.results
                            resolved.success.answer?.let { answers += it }
                            remaining = remaining.drop(run.stoppedAt + 1)
                        }
                        is Resolution.End -> {
                            val t = base.copy(executeMs = (System.nanoTime() - executeStart) / 1_000_000, totalMs = (System.nanoTime() - startedAt) / 1_000_000)
                            finish(
                                text, source, resolved.outcome, resolved.message, interpretation, t, startedAt,
                                results = results, permission = resolved.permission, hint = resolved.hint, partial = messages,
                                answers = answers, mode = modeNow(mode), confidence = confidence,
                            )
                            return
                        }
                    }
                }
            }
        }
        val t = base.copy(executeMs = (System.nanoTime() - executeStart) / 1_000_000)
        finish(
            text, source, Outcome.SUCCESS, messages.joinToString("\n").ifBlank { "Done." }, interpretation, t, startedAt,
            results = results, answers = answers, mode = modeNow(mode), confidence = confidence,
        )
    }

    /** A command that started by itself but then needed the user's OK for one step counts as confirmed. */
    private fun modeNow(started: ExecutionMode): ExecutionMode = if (approvedMidRun) ExecutionMode.CONFIRMED else started

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
        // A step that turned out to touch something sensitive on screen (a Pay or Delete button, say) always waits for the user,
        // even in a command that was running by itself.
        is StepOutcome.NeedsApproval -> {
            _state.update { it.copy(phase = Phase.CONFIRMING, status = "Please confirm", confirmation = Confirmation(listOf(outcome.message), emptyList())) }
            if (awaitDecision()) {
                approvedMidRun = true
                _state.update { it.copy(confirmation = null, phase = Phase.EXECUTING, status = "Doing it…", executionMode = ExecutionMode.CONFIRMED) }
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
        answers: List<InfoAnswer> = emptyList(),
        mode: ExecutionMode? = null,
        confidence: Double? = null,
    ) {
        val total = timings.copy(totalMs = (System.nanoTime() - startedAt) / 1_000_000)
        val shown = if (partial.isNotEmpty() && outcome != Outcome.SUCCESS) (partial + message).joinToString("\n") else message
        if (!text.isNullOrBlank()) {
            history.add(
                HistoryEntry(
                    id = System.currentTimeMillis(), timeMs = System.currentTimeMillis(), source = source, text = text,
                    interpretation = interpretation ?: _state.value.interpretation, outcome = outcome, message = shown, timings = total,
                    mode = mode.takeIf { outcome == Outcome.SUCCESS || outcome == Outcome.FAILED || outcome == Outcome.NEEDS_INPUT },
                    confidence = confidence,
                    online = answers.any { it.origin == AnswerOrigin.ONLINE },
                ),
            )
        }
        _state.update {
            it.copy(
                phase = Phase.DONE, source = source, status = "", response = shown, results = results, timings = total,
                permission = permission, hint = hint, outcome = outcome, confirmation = null, choice = null, micLevel = 0f,
                speechDetected = false, answers = answers, executionMode = mode,
            )
        }
        speakAnswers(outcome, answers)
    }

    /** Answers are read aloud once the command is over and the microphone is closed; the speaker itself refuses while it is open. */
    private fun speakAnswers(outcome: Outcome, answers: List<InfoAnswer>) {
        if (outcome != Outcome.SUCCESS || answers.isEmpty()) return
        val words = AnswerSpeech.forSpeaker(answers)
        if (words.isNotBlank()) speech.speakAnswer(words)
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

    private fun describeResponse(response: NeedleResponse, steps: List<ValidatedCall>?, extra: String? = null): String {
        val lines = mutableListOf<String>()
        lines += if (steps.isNullOrEmpty()) "No action chosen" else describeSteps(steps).joinToString("\n")
        lines += response.confidence?.let { "Confidence: ${(it * 100).toInt()}%" } ?: "Confidence: not reported by the model"
        extra?.let { lines += it }
        response.reasoning?.takeIf { it.isNotBlank() }?.let { lines += "Reasoning: $it" }
        if (response.ungrounded.isNotEmpty()) lines += "Not found in your words: ${response.ungrounded.joinToString()}"
        return lines.joinToString("\n")
    }

    private fun prettyJson(response: NeedleResponse): String =
        Json.parseOrNull(response.rawJson)?.let { Json.writePretty(it) } ?: response.rawJson

    private fun describeEvent(e: StepEvent): String = when (e) {
        is StepEvent.Started -> "Step ${e.index + 1} of ${e.total}: ${ActionText.describe(e.call)}"
        is StepEvent.Finished -> ""
        is StepEvent.TimedOut -> "${e.call.name} took too long"
        is StepEvent.Cancelled -> "Stopped"
    }

    private companion object {
        /** After a spoken answer is cut off, how long to wait before the microphone opens. */
        const val SPEECH_TAIL_MS = 350L
    }
}
