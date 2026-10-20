package app.cactusai.core

import app.cactusai.core.engine.EngineResult
import app.cactusai.core.engine.NativeEngine
import app.cactusai.core.engine.NativeLib
import app.cactusai.core.json.Json
import app.cactusai.core.json.JsonValue
import app.cactusai.core.tools.AssistantTools
import app.cactusai.core.tools.ToolGroup
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * Checks the JNI shim's marshalling on the build machine. The engine behind it is a TEST DOUBLE
 * (native/host-test/fake_needle.cpp) that only echoes its inputs, so these tests prove nothing about
 * Needle or Whistle themselves. They prove the glue: UTF-8 and emoji, float arrays, error reporting,
 * buffer handling, serialisation of calls, and the telemetry opt-out.
 */
class NativeShimHostTest {
    private val engine = NativeEngine()

    @Before
    fun requireShim() {
        val path = System.getProperty("cactusai.hostShim")
        assumeTrue("host shim not built (needs cmake, a C++ compiler and a JDK)", path != null && File(path).isFile)
        assertTrue(NativeLib.load(path))
    }

    private fun obj(result: EngineResult<String>): JsonValue.Obj =
        Json.parse((result as EngineResult.Ok).value) as JsonValue.Obj

    private fun err(result: EngineResult<*>): String = (result as EngineResult.Err).message

    private fun modelFile(bytes: ByteArray = SyntheticCact.build()): String {
        val f = Files.createTempFile("model", ".cact").toFile()
        f.deleteOnExit()
        f.writeBytes(bytes)
        return f.path
    }

    @Test
    fun reportsLinkedAndVersion() {
        assertTrue(engine.isLinked)
        assertEquals("1", NativeLib.nativeShimVersion())
    }

    @Test
    fun telemetryIsSwitchedOffBeforeAnyEngineCall() {
        val env = obj(engine.complete("__env__", 1))
        assertEquals("0", env.string("telemetry"))
        assertEquals("1", env.string("doNotTrack"))
    }

    @Test
    fun loadsAModelFileThroughAMemoryMap() {
        val before = obj(engine.complete("__stats__", 1)).long("loads")!!
        assertTrue(engine.loadModel(modelFile()) is EngineResult.Ok)
        assertEquals(before + 1, obj(engine.complete("__stats__", 1)).long("loads"))
    }

    @Test
    fun reportsModelLoadFailuresWithReasons() {
        assertTrue(err(engine.loadModel("/definitely/not/here.cact")).contains("cannot open"))
        assertTrue(err(engine.loadModel(modelFile(ByteArray(300)))).contains("unknown format tag"))
        assertTrue(err(engine.loadModel(modelFile(ByteArray(0)))).contains("empty"))
    }

    @Test
    fun initialisesWithFactsAndTheRealToolsJson() {
        val tools = AssistantTools.registry.toNeedleToolsJson(setOf(ToolGroup.PHONE))
        val prefix = (engine.initNeedle(AssistantTools.systemFacts("2026-10-20 Tue 14:30", "en-US"), tools) as EngineResult.Ok).value
        assertTrue(prefix > 0)
        val stats = obj(engine.complete("__stats__", 1))
        assertTrue(stats.string("system")!!.contains("device: phone"))
        assertEquals(tools.length.toLong(), stats.long("toolsLength"))
    }

    @Test
    fun initFailureIsAnErrorNotACrash() {
        assertTrue(err(engine.initNeedle("", "")).contains("needle_init failed"))
    }

    @Test
    fun textSurvivesUtf8RoundTripIncludingEmojiAndCjk() {
        val text = "open spotify 🎵 café 日本語 ü"
        val reply = obj(engine.complete(text, 64))
        assertEquals(text, reply.string("echo"))
        assertEquals(64L, reply.long("max"))
    }

    @Test
    fun engineErrorsCarryTheEngineMessageAndCode() {
        assertTrue(err(engine.complete("__fail__", 8)).contains("boom in out buffer"))
        assertTrue(err(engine.complete("__fail__", 8)).contains("code -2"))
        assertTrue(err(engine.complete("__fail_with_last_error__", 8)).contains("engine says no"))
    }

    @Test
    fun audioArraysAndOptionsReachTheEngine() {
        val samples = FloatArray(16_000) { 0.001f }
        val withOptions = obj(engine.transcribe(samples, "en", "Spotify, Bambu"))
        assertEquals(16_000L, withOptions.long("n"))
        assertEquals(16.0, withOptions.double("sum")!!, 0.01)
        assertEquals("en", withOptions.string("language"))
        assertEquals("Spotify, Bambu", withOptions.string("keywords"))
        val plain = obj(engine.transcribe(samples, null, null))
        assertEquals("auto", plain.string("language"))
        assertEquals("none", plain.string("keywords"))
    }

    @Test
    fun longClipsGetALargeEnoughOutputBuffer() {
        val reply = obj(engine.transcribe(FloatArray(16_000 * 30), "en", null))
        assertTrue(reply.long("cap")!! >= 30 * 4096)
    }

    @Test
    fun badAudioIsRejectedBeforeOrByTheEngine() {
        assertTrue(err(engine.transcribe(FloatArray(0), "en", null)).contains("empty"))
        assertTrue(err(engine.transcribe(FloatArray(16_000 * 121), "en", null)).contains("too long"))
        assertTrue(err(engine.transcribe(FloatArray(123), "en", null)).contains("bad clip"))
    }

    @Test
    fun resetReachesTheEngine() {
        val before = obj(engine.complete("__stats__", 1)).long("resets")!!
        engine.reset()
        assertEquals(before + 1, obj(engine.complete("__stats__", 1)).long("resets"))
    }

    @Test
    fun concurrentCallersAreSerialisedWithoutDeadlockOrCorruption() {
        val pool = Executors.newFixedThreadPool(6)
        val done = CountDownLatch(6)
        val failures = java.util.concurrent.atomic.AtomicInteger()
        repeat(6) { t ->
            pool.execute {
                try {
                    repeat(60) { i ->
                        val text = "thread $t call $i é🎵"
                        val got = (engine.complete(text, 8) as? EngineResult.Ok)?.let { (Json.parse(it.value) as JsonValue.Obj).string("echo") }
                        if (got != text) failures.incrementAndGet()
                    }
                } finally {
                    done.countDown()
                }
            }
        }
        assertTrue(done.await(30, TimeUnit.SECONDS))
        pool.shutdownNow()
        assertEquals(0, failures.get())
    }
}
