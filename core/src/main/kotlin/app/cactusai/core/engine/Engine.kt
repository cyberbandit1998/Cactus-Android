package app.cactusai.core.engine

const val KIND_TEXT = 1
const val KIND_SPEECH = 2

sealed interface EngineResult<out T> {
    data class Ok<T>(val value: T) : EngineResult<T>
    data class Err(val message: String) : EngineResult<Nothing>
}

/**
 * The official Needle engine, as the C API exposes it (`needle_load`, `needle_init`,
 * `needle_complete`, `needle_transcribe`, `needle_reset`). One engine holds both Needle 3 and
 * Whistle. It is not thread-safe and has no unload call, so callers use a single thread and
 * models stay resident until the process ends.
 */
interface InferenceEngine {
    /** False when this build was made without the Needle engine library. */
    val isLinked: Boolean

    /** Loads a `.cact` archive; the engine works out whether it is Needle or Whistle. */
    fun loadModel(path: String): EngineResult<Unit>

    /** What the engine reports as loaded right now: a bitmask of [KIND_TEXT] and [KIND_SPEECH] (`needle_models`). */
    fun loadedKinds(): Int

    /** Sets the facts and tool list for the Needle conversation. Returns the prompt prefix length in tokens. */
    fun initNeedle(systemFacts: String, toolsJson: String): EngineResult<Int>

    /** One Needle turn; returns the engine's JSON envelope. */
    fun complete(text: String, maxNewTokens: Int): EngineResult<String>

    /** Whistle: 16 kHz mono floats in [-1, 1]. [language] is a code like "en" or null to detect. Returns JSON. */
    fun transcribe(samples: FloatArray, language: String?, keywords: String?): EngineResult<String>

    /** Starts a fresh Needle conversation, keeping the loaded tools. */
    fun reset()
}
