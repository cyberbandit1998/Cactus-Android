package app.cactusai.core.voice

import app.cactusai.core.audio.Pcm
import app.cactusai.core.voice.TestAudio.SignalSource
import app.cactusai.core.voice.TestAudio.concat
import app.cactusai.core.voice.TestAudio.noise
import app.cactusai.core.voice.TestAudio.voice
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VoiceCaptureTest {
    private val room = -62.0
    private val speech = -24.0

    private fun phrase() = concat(noise(500, room), voice(1500, speech), noise(7000, room))

    private suspend fun turn(
        source: PcmSource,
        config: CaptureConfig = CaptureConfig(),
        stop: () -> Boolean = { false },
        trim: Boolean = true,
        updates: MutableList<CaptureUpdate>? = null,
        before: () -> String? = { null },
        calls: AtomicInteger = AtomicInteger(),
        sizes: MutableList<Int> = mutableListOf(),
    ): VoiceTurnResult<String> = VoiceTurn.listenAndTranscribe(
        source = source,
        config = config,
        stopRequested = stop,
        trimSilence = trim,
        onUpdate = { updates?.add(it) },
        beforeTranscribe = before,
        transcribe = { samples ->
            calls.incrementAndGet()
            sizes += samples.size
            "open spotify"
        },
    )

    @Test
    fun theRecordingEndsByItselfAndTheTextGoesStraightToTranscription() = runBlocking {
        val source = SignalSource(phrase())
        val calls = AtomicInteger()
        val sizes = mutableListOf<Int>()
        val result = turn(source, calls = calls, sizes = sizes)
        assertTrue(result is VoiceTurnResult.Transcribed)
        val t = result as VoiceTurnResult.Transcribed
        assertEquals("open spotify", t.value)
        assertEquals(EndReason.SILENCE, t.audio.reason)
        assertEquals(1, calls.get())
        // stopped about 1.5 s after the voice ended at 2.0 s, long before the 8 s of audio that was available
        assertTrue("recorded ${t.audio.durationMs} ms", t.audio.durationMs in 3400..3800)
        assertTrue("read ${source.position} samples", source.position < Pcm.SAMPLE_RATE * 4)
        // silence trimmed off before Whistle: roughly the spoken second and a half plus padding
        assertTrue("sent ${sizes.single()} samples", sizes.single() in Pcm.SAMPLE_RATE until Pcm.SAMPLE_RATE * 3)
    }

    @Test
    fun manualStopStillWorksWhileAutoStopIsOn() = runBlocking {
        val source = SignalSource(voice(20_000, speech))
        val calls = AtomicInteger()
        val result = turn(source, stop = { source.reads >= 25 }, calls = calls)
        val t = result as VoiceTurnResult.Transcribed
        assertEquals(EndReason.MANUAL_STOP, t.audio.reason)
        assertEquals(1000, t.audio.durationMs)
        assertEquals(1, calls.get())
    }

    @Test
    fun withAutoStopOffTheRecordingRunsUntilTheUserStopsIt() = runBlocking {
        val source = SignalSource(phrase())
        val result = turn(source, CaptureConfig(autoStop = false), stop = { source.reads >= 150 })
        val t = result as VoiceTurnResult.Transcribed
        assertEquals(EndReason.MANUAL_STOP, t.audio.reason)
        assertEquals(6000, t.audio.durationMs)
    }

    @Test
    fun withAutoStopOffTheTimeLimitStillApplies() = runBlocking {
        val result = turn(SignalSource(phrase()), CaptureConfig(autoStop = false, endpoint = EndpointConfig(maxMs = 5000)))
        val t = result as VoiceTurnResult.Transcribed
        assertEquals(EndReason.MAX_DURATION, t.audio.reason)
        assertEquals(5000, t.audio.durationMs)
    }

    @Test
    fun theTimeLimitEndsALongMonologueEvenWithAutoStopOn() = runBlocking {
        val result = turn(SignalSource(voice(60_000, speech)), CaptureConfig(endpoint = EndpointConfig(maxMs = 12_000)))
        val t = result as VoiceTurnResult.Transcribed
        assertEquals(EndReason.MAX_DURATION, t.audio.reason)
        assertEquals(12_000, t.audio.durationMs)
    }

    @Test
    fun nothingSaidEndsAfterTheNoSpeechTimeoutAndNothingIsTranscribed() = runBlocking {
        val calls = AtomicInteger()
        val result = turn(SignalSource(noise(30_000, room)), calls = calls)
        assertTrue(result is VoiceTurnResult.NothingHeard)
        val n = result as VoiceTurnResult.NothingHeard
        assertEquals(NothingHeardReason.NO_SPEECH_TIMEOUT, n.why)
        assertEquals(EndReason.NO_SPEECH, n.audio.reason)
        assertEquals(8000, n.audio.durationMs)
        assertEquals(0, calls.get())
    }

    @Test
    fun aStopTapOverSilenceTranscribesNothing() = runBlocking {
        val calls = AtomicInteger()
        val result = turn(SignalSource(noise(10_000, room)), stop = { true }, calls = calls)
        val n = result as VoiceTurnResult.NothingHeard
        assertEquals(NothingHeardReason.TOO_SHORT, n.why)
        assertEquals(EndReason.MANUAL_STOP, n.audio.reason)
        assertEquals(0, calls.get())
    }

    @Test
    fun cancellingDiscardsTheAudioAndNeverTranscribes() = runBlocking {
        val calls = AtomicInteger()
        val reads = AtomicInteger()
        val slow = object : PcmSource {
            override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
                reads.incrementAndGet()
                Thread.sleep(3)
                for (i in 0 until length) buffer[offset + i] = ((i % 50) * 300 - 7000).toShort()
                return length
            }
        }
        val job = launch(Dispatchers.Default) { turn(slow, CaptureConfig(autoStop = false), calls = calls) }
        delay(80)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(0, calls.get())
        val after = reads.get()
        delay(50)
        assertEquals("kept reading after cancellation", after, reads.get())
    }

    @Test
    fun aGateBeforeTranscriptionCanRefuse() = runBlocking {
        val calls = AtomicInteger()
        val result = turn(SignalSource(phrase()), before = { "The phone is overheating." }, calls = calls)
        val b = result as VoiceTurnResult.Blocked
        assertEquals("The phone is overheating.", b.message)
        assertEquals(0, calls.get())
    }

    @Test
    fun aBrokenMicrophoneIsReportedNotSwallowed() = runBlocking {
        val broken = object : PcmSource {
            override fun read(buffer: ShortArray, offset: Int, length: Int) = -3
        }
        try {
            turn(broken)
            fail("expected a CaptureException")
        } catch (e: CaptureException) {
            assertTrue(e.message!!.contains("-3"))
        }
    }

    @Test
    fun progressShowsListeningThenSpeechDetected() = runBlocking {
        val updates = mutableListOf<CaptureUpdate>()
        turn(SignalSource(phrase()), updates = updates)
        assertTrue(updates.isNotEmpty())
        assertEquals(SpeechState.WAITING, updates.first().state)
        assertFalse(updates.first().speechDetected)
        val firstSpeech = updates.indexOfFirst { it.speechDetected }
        assertTrue("speech never reported", firstSpeech > 0)
        assertTrue(updates[firstSpeech].elapsedMs in 500..900)
        assertTrue(updates.zipWithNext().all { (a, b) -> b.elapsedMs >= a.elapsedMs })
        assertTrue(updates.all { it.level in 0f..1f })
    }

    @Test
    fun aStopRequestedBeforeTheFirstReadReturnsAnEmptyRecording() = runBlocking {
        val source = SignalSource(phrase())
        val audio = VoiceCapture.capture(source, CaptureConfig(), stopRequested = { true })
        assertEquals(EndReason.MANUAL_STOP, audio.reason)
        assertEquals(0, audio.length)
        assertEquals(0, source.reads)
    }

    @Test
    fun theMicrophoneCanBeReleasedBetweenRecordingAndTranscription() = runBlocking {
        var open = true
        val source = SignalSource(phrase())
        val audio = VoiceCapture.capture(source, CaptureConfig(), { false })
        open = false // the app closes the microphone here
        var openDuringTranscription: Boolean? = null
        val result = VoiceTurn.afterCapture(audio, trimSilence = true) { _ ->
            openDuringTranscription = open
            "open spotify"
        }
        assertTrue(result is VoiceTurnResult.Transcribed)
        assertEquals(false, openDuringTranscription)
    }

    @Test
    fun aRecordingWithNoSpeechIsNotTranscribedWhenSplitEither() = runBlocking {
        val audio = VoiceCapture.capture(SignalSource(noise(9000, room)), CaptureConfig(), { false })
        val calls = AtomicInteger()
        val result = VoiceTurn.afterCapture(audio, trimSilence = true) { _ -> calls.incrementAndGet(); "x" }
        assertTrue(result is VoiceTurnResult.NothingHeard)
        assertEquals(0, calls.get())
    }
}
