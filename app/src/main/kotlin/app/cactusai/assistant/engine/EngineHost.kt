package app.cactusai.assistant.engine

import app.cactusai.core.engine.EngineResult
import app.cactusai.core.engine.InferenceEngine
import app.cactusai.core.models.ModelSpec
import app.cactusai.core.models.ModelStatus
import app.cactusai.core.models.ModelStore
import app.cactusai.core.models.Models
import app.cactusai.core.needle.NeedleParse
import app.cactusai.core.needle.NeedleResponse
import app.cactusai.core.needle.NeedleResponses
import app.cactusai.core.needle.TranscriptionParse
import app.cactusai.core.needle.TranscriptionResult
import app.cactusai.core.needle.Transcriptions
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import app.cactusai.core.tools.ToolRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

enum class LoadStage { NOT_LOADED, LOADING, LOADED, FAILED }

data class ModelRuntime(
    val stage: LoadStage = LoadStage.NOT_LOADED,
    /** Wall-clock time the load took on this phone, measured here. */
    val loadMs: Long? = null,
    val error: String? = null,
    /** SHA-256 recorded for the file that was loaded, to tell when a newer file is waiting for a restart. */
    val loadedSha: String? = null,
)

data class EngineState(
    val engineLinked: Boolean = false,
    val whistle: ModelRuntime = ModelRuntime(),
    val needle: ModelRuntime = ModelRuntime(),
    /** Prompt prefix length the engine reported when the tools were set up. */
    val needlePrefixTokens: Int? = null,
    val needleInitMs: Long? = null,
)

enum class FailureKind { NOT_LINKED, MODEL_MISSING, LOAD_FAILED, INFERENCE_FAILED, BAD_OUTPUT }

sealed interface HostResult<out T> {
    data class Ok<T>(val value: T) : HostResult<T>
    data class Fail(val kind: FailureKind, val message: String, val raw: String? = null) : HostResult<Nothing>
}

data class TranscribeOutput(val result: TranscriptionResult, val wallMs: Long, val loadMs: Long?)

data class InterpretOutput(val response: NeedleResponse, val wallMs: Long, val loadMs: Long?, val initMs: Long?)

/**
 * Owns the engine. Every native call runs on one dedicated thread because the engine is not
 * thread-safe, models load on first use (not at app start), and every stage is timed separately.
 *
 * The engine has no unload call: once loaded a model stays until the process ends.
 */
class EngineHost(
    private val engine: InferenceEngine,
    private val store: ModelStore,
    private val registry: ToolRegistry = AssistantTools.registry,
    private val today: () -> String = { SimpleDateFormat("yyyy-MM-dd EEE", Locale.US).format(Date()) },
    private val localeTag: () -> String = { Locale.getDefault().toLanguageTag() },
) {
    private val dispatcher: CoroutineDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "cactus-engine").apply { isDaemon = true } }.asCoroutineDispatcher()

    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    // Touched only on the engine thread.
    private var initializedSignature: String? = null

    val isLinked: Boolean get() = engine.isLinked

    suspend fun transcribe(samples: FloatArray, language: String?, keywords: String?): HostResult<TranscribeOutput> =
        withContext(dispatcher) {
            _state.value = _state.value.copy(engineLinked = engine.isLinked)
            if (!engine.isLinked) return@withContext notLinked()
            val loadMs = when (val l = ensureLoaded(Models.WHISTLE)) {
                is HostResult.Fail -> return@withContext l
                is HostResult.Ok -> l.value
            }
            val start = System.nanoTime()
            when (val r = engine.transcribe(samples, language, keywords)) {
                is EngineResult.Err -> HostResult.Fail(FailureKind.INFERENCE_FAILED, "Speech recognition failed: ${r.message}")
                is EngineResult.Ok -> {
                    val wallMs = elapsedMs(start)
                    when (val p = Transcriptions.parse(r.value)) {
                        is TranscriptionParse.Malformed -> HostResult.Fail(FailureKind.BAD_OUTPUT, "Speech recognition returned something unreadable (${p.reason}).", r.value)
                        is TranscriptionParse.Parsed -> HostResult.Ok(TranscribeOutput(p.result, wallMs, loadMs))
                    }
                }
            }
        }

    suspend fun interpret(text: String, groups: Set<ToolGroup>): HostResult<InterpretOutput> =
        withContext(dispatcher) {
            _state.value = _state.value.copy(engineLinked = engine.isLinked)
            if (!engine.isLinked) return@withContext notLinked()
            var loadMs: Long? = null
            var initMs: Long? = null
            for (attempt in 0..1) {
                when (val l = ensureLoaded(Models.NEEDLE_3)) {
                    is HostResult.Fail -> return@withContext l
                    is HostResult.Ok -> if (l.value != null) loadMs = (loadMs ?: 0) + l.value
                }
                when (val i = ensureInitialised(groups)) {
                    is HostResult.Fail -> return@withContext i
                    is HostResult.Ok -> if (i.value != null) initMs = (initMs ?: 0) + i.value
                }
                engine.reset() // each command is its own conversation
                val start = System.nanoTime()
                when (val r = engine.complete(text, MAX_NEW_TOKENS)) {
                    is EngineResult.Err -> {
                        if (attempt == 0) {
                            // Start over once: reload the model and set the tools up again.
                            markNeedleStale()
                            continue
                        }
                        return@withContext HostResult.Fail(FailureKind.INFERENCE_FAILED, "The command model failed: ${r.message}")
                    }
                    is EngineResult.Ok -> {
                        val wallMs = elapsedMs(start)
                        return@withContext when (val p = NeedleResponses.parse(r.value)) {
                            is NeedleParse.Malformed -> HostResult.Fail(FailureKind.BAD_OUTPUT, "The command model returned something I could not read (${p.reason}).", r.value)
                            is NeedleParse.Parsed -> HostResult.Ok(InterpretOutput(p.response, wallMs, loadMs, initMs))
                        }
                    }
                }
            }
            HostResult.Fail(FailureKind.INFERENCE_FAILED, "The command model failed.")
        }

    private fun notLinked() = HostResult.Fail(
        FailureKind.NOT_LINKED,
        "This build of the app does not contain the Needle engine, so it cannot run the models. Rebuild it after running scripts/fetch-engine (see the README).",
    )

    private fun markNeedleStale() {
        initializedSignature = null
        _state.value = _state.value.copy(needle = ModelRuntime())
    }

    /** Returns the load time in ms when this call loaded the model, null when it was already loaded. */
    private fun ensureLoaded(spec: ModelSpec): HostResult<Long?> {
        if (runtime(spec).stage == LoadStage.LOADED) return HostResult.Ok(null)
        val installed = when (val status = store.status(spec)) {
            is ModelStatus.Installed -> status
            ModelStatus.NotInstalled, is ModelStatus.Partial ->
                return HostResult.Fail(FailureKind.MODEL_MISSING, "${spec.displayName} is not downloaded yet. Open the Models tab and download it.")
            is ModelStatus.Invalid ->
                return HostResult.Fail(FailureKind.MODEL_MISSING, "The ${spec.displayName} file is damaged (${status.reason}). Open the Models tab, delete it and download it again.")
        }
        setRuntime(spec, ModelRuntime(LoadStage.LOADING))
        val start = System.nanoTime()
        return when (val r = engine.loadModel(installed.file.path)) {
            is EngineResult.Err -> {
                setRuntime(spec, ModelRuntime(LoadStage.FAILED, error = r.message))
                HostResult.Fail(FailureKind.LOAD_FAILED, "${spec.displayName} could not be loaded: ${r.message}")
            }
            is EngineResult.Ok -> {
                val ms = elapsedMs(start)
                setRuntime(spec, ModelRuntime(LoadStage.LOADED, loadMs = ms, loadedSha = installed.sha256))
                HostResult.Ok(ms)
            }
        }
    }

    /** Sets the facts and tools up for Needle; returns the time taken when it actually ran. */
    private fun ensureInitialised(groups: Set<ToolGroup>): HostResult<Long?> {
        val facts = AssistantTools.systemFacts(today(), localeTag())
        val signature = registry.signature(groups) + "|" + facts
        if (signature == initializedSignature) return HostResult.Ok(null)
        val start = System.nanoTime()
        return when (val r = engine.initNeedle(facts, registry.toNeedleToolsJson(groups))) {
            is EngineResult.Err -> HostResult.Fail(FailureKind.INFERENCE_FAILED, "The command model could not be set up: ${r.message}")
            is EngineResult.Ok -> {
                initializedSignature = signature
                val ms = elapsedMs(start)
                _state.value = _state.value.copy(needlePrefixTokens = r.value, needleInitMs = ms)
                HostResult.Ok(ms)
            }
        }
    }

    private fun runtime(spec: ModelSpec) = if (spec.kind == Models.NEEDLE_3.kind) _state.value.needle else _state.value.whistle

    private fun setRuntime(spec: ModelSpec, r: ModelRuntime) {
        _state.value = if (spec.kind == Models.NEEDLE_3.kind) _state.value.copy(needle = r) else _state.value.copy(whistle = r)
    }

    private fun elapsedMs(startNanos: Long) = (System.nanoTime() - startNanos) / 1_000_000

    private companion object {
        const val MAX_NEW_TOKENS = 512
    }
}
