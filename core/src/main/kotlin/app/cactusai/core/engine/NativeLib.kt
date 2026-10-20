package app.cactusai.core.engine

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * JNI bindings to `libcactusai_native.so`, a thin C++ shim over the official Needle engine
 * (see `app/src/main/cpp`). The function names here are part of the JNI symbol names.
 */
object NativeLib {
    @Volatile
    private var loadError: String? = null

    @Volatile
    private var loaded = false

    /** Loads the shim by name on Android, or from an explicit file (used by the host-side tests). */
    @Synchronized
    fun load(absolutePath: String? = null): Boolean {
        if (loaded) return true
        try {
            if (absolutePath != null) System.load(absolutePath) else System.loadLibrary("cactusai_native")
            loaded = true
            loadError = null
        } catch (e: UnsatisfiedLinkError) {
            loadError = e.message ?: "native library could not be loaded"
        } catch (e: SecurityException) {
            loadError = e.message ?: "native library load was refused"
        }
        return loaded
    }

    fun lastLoadError(): String? = loadError

    @JvmStatic external fun nativeIsLinked(): Boolean

    @JvmStatic external fun nativeShimVersion(): String

    @JvmStatic external fun nativeLoadModel(path: String): Int

    @JvmStatic external fun nativeInit(system: String, toolsJson: String): Int

    @JvmStatic external fun nativeComplete(text: String, maxNewTokens: Int): String?

    @JvmStatic external fun nativeTranscribe(samples: FloatArray, language: String?, keywords: String?): String?

    @JvmStatic external fun nativeReset()

    @JvmStatic external fun nativeLastError(): String
}

/** [InferenceEngine] backed by the JNI shim. Calls are serialised, matching the engine's threading rule. */
class NativeEngine : InferenceEngine {
    private val lock = ReentrantLock()

    override val isLinked: Boolean
        get() = NativeLib.load() && NativeLib.nativeIsLinked()

    private fun ensureLoaded(): String? =
        if (NativeLib.load()) null else "The native library is missing (${NativeLib.lastLoadError() ?: "unknown reason"})."

    override fun loadModel(path: String): EngineResult<Unit> = lock.withLock {
        ensureLoaded()?.let { return EngineResult.Err(it) }
        if (NativeLib.nativeLoadModel(path) < 0) EngineResult.Err(NativeLib.nativeLastError()) else EngineResult.Ok(Unit)
    }

    override fun initNeedle(systemFacts: String, toolsJson: String): EngineResult<Int> = lock.withLock {
        ensureLoaded()?.let { return EngineResult.Err(it) }
        val prefix = NativeLib.nativeInit(systemFacts, toolsJson)
        if (prefix < 0) EngineResult.Err(NativeLib.nativeLastError()) else EngineResult.Ok(prefix)
    }

    override fun complete(text: String, maxNewTokens: Int): EngineResult<String> = lock.withLock {
        ensureLoaded()?.let { return EngineResult.Err(it) }
        NativeLib.nativeComplete(text, maxNewTokens)?.let { EngineResult.Ok(it) } ?: EngineResult.Err(NativeLib.nativeLastError())
    }

    override fun transcribe(samples: FloatArray, language: String?, keywords: String?): EngineResult<String> = lock.withLock {
        ensureLoaded()?.let { return EngineResult.Err(it) }
        NativeLib.nativeTranscribe(samples, language, keywords)?.let { EngineResult.Ok(it) } ?: EngineResult.Err(NativeLib.nativeLastError())
    }

    override fun reset() {
        lock.withLock {
            if (ensureLoaded() == null) NativeLib.nativeReset()
        }
    }
}
