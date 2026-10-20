package app.cactusai.core.voice

import app.cactusai.core.audio.Pcm
import app.cactusai.core.voice.TestAudio.concat
import app.cactusai.core.voice.TestAudio.hum
import app.cactusai.core.voice.TestAudio.noise
import app.cactusai.core.voice.TestAudio.voice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndpointerTest {
    private class Run(val reason: EndReason?, val endedAtMs: Long, val speechSeenAtMs: Long?, val speechEverDetected: Boolean, val endpointer: SpeechEndpointer)

    /** Feeds [signal] in 40 ms chunks like the recorder does, until the detector ends it or the signal ends. */
    private fun run(signal: ShortArray, config: EndpointConfig = EndpointConfig(), chunk: Int = 640): Run {
        val e = SpeechEndpointer(config)
        var speechAt: Long? = null
        var ever = false
        var i = 0
        while (i < signal.size) {
            val n = minOf(chunk, signal.size - i)
            val r = e.feed(signal, i, n)
            i += n
            if (e.state == SpeechState.SPEECH) {
                ever = true
                if (speechAt == null) speechAt = e.elapsedMs
            }
            if (r != null) return Run(r, e.elapsedMs, speechAt, ever, e)
        }
        return Run(null, e.elapsedMs, speechAt, ever, e)
    }

    private val room = -62.0 // a quiet room
    private val speech = -24.0 // normal speech at arm's length

    @Test
    fun speechStartIsDetectedPromptly() {
        val r = run(concat(noise(1000, room), voice(1500, speech), noise(4000, room)))
        val at = r.speechSeenAtMs
        assertNotNull(at)
        assertTrue("speech seen at $at ms", at!! in 1000..1400)
    }

    @Test
    fun recordingEndsAfterTheDefaultSilenceAfterSpeech() {
        val r = run(concat(noise(1000, room), voice(1500, speech), noise(5000, room)))
        assertEquals(EndReason.SILENCE, r.reason)
        // speech ends at 2500 ms; the default countdown is 1500 ms
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs in 3950..4200)
        assertEquals(1500, r.endpointer.config.silenceMs)
    }

    @Test
    fun silenceTimeoutIsConfigurable() {
        for (silence in listOf(500, 1500, 3000, 4000)) {
            val r = run(concat(noise(300, room), voice(1500, speech), noise(6000, room)), EndpointConfig(silenceMs = silence))
            assertEquals("silence $silence", EndReason.SILENCE, r.reason)
            val expected = 1800L + silence
            assertTrue("silence $silence ended at ${r.endedAtMs}, expected about $expected", r.endedAtMs in (expected - 80)..(expected + 250))
        }
    }

    @Test
    fun silenceTimeoutIsClampedToHalfASecondUpToFour() {
        assertEquals(500, SpeechEndpointer(EndpointConfig(silenceMs = 100)).config.silenceMs)
        assertEquals(4000, SpeechEndpointer(EndpointConfig(silenceMs = 9000)).config.silenceMs)
        assertEquals(30_000, SpeechEndpointer(EndpointConfig(maxMs = 90_000)).config.maxMs)
        assertEquals(3_000, SpeechEndpointer(EndpointConfig(maxMs = 10)).config.maxMs)
    }

    @Test
    fun theSilenceCountdownDoesNotStartBeforeSpeech() {
        // Five quiet seconds first: with a 1.5 s timeout a naive "silence" rule would have stopped long before the voice.
        val r = run(concat(noise(5000, room), voice(1000, speech), noise(4000, room)))
        assertEquals(EndReason.SILENCE, r.reason)
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs in 7450..7700)
    }

    @Test
    fun aBriefNaturalPauseDoesNotEndTheCommand() {
        val r = run(concat(noise(300, room), voice(800, speech), noise(900, room, seed = 3), voice(800, speech), noise(4000, room)))
        assertEquals(EndReason.SILENCE, r.reason)
        // second phrase ends at 2800 ms, so the end is about 4300 ms, not about 2600 ms (after the first phrase)
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs >= 4200)
    }

    @Test
    fun noSpeechTimeoutEndsAfterAboutEightSeconds() {
        val r = run(noise(12_000, room))
        assertEquals(EndReason.NO_SPEECH, r.reason)
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs in 7950..8100)
        assertTrue(!r.speechEverDetected)
        assertEquals(8000, EndpointConfig.DEFAULT_NO_SPEECH_MS)
    }

    @Test
    fun noSpeechTimeoutDoesNotFireOnceSpeechHasStarted() {
        val r = run(concat(noise(7000, room), voice(3000, speech), noise(4000, room)))
        assertEquals(EndReason.SILENCE, r.reason)
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs > 10_000)
    }

    @Test
    fun maximumRecordingDurationStopsContinuousSpeech() {
        val r = run(voice(40_000, speech))
        assertEquals(EndReason.MAX_DURATION, r.reason)
        assertEquals(30_000, r.endedAtMs)
        assertEquals(EndReason.MAX_DURATION, run(voice(40_000, speech), EndpointConfig(maxMs = 10_000)).reason)
        assertEquals(10_000, run(voice(40_000, speech), EndpointConfig(maxMs = 10_000)).endedAtMs)
    }

    @Test
    fun aKnockDoesNotStartSpeechOrEndTheWait() {
        val r = run(concat(noise(1500, room), TestAudio.click(30, -18.0), noise(2000, room)))
        assertNull(r.reason)
        assertTrue("a 30 ms bang counted as speech", !r.speechEverDetected)
    }

    @Test
    fun aShortNoiseIsForgottenAndRealSpeechStillWorks() {
        val r = run(concat(noise(800, room), voice(120, speech), noise(2200, room, seed = 5), voice(1200, speech), noise(4000, room)))
        assertEquals(EndReason.SILENCE, r.reason)
        // the real phrase ends at 4320 ms; ending after the cough-sized noise would be at about 2400 ms
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs > 5500)
        assertTrue(r.endpointer.report(EndReason.SILENCE).falseStarts <= 1)
    }

    @Test
    fun steadyHumIsNotMistakenForSpeech() {
        val r = run(hum(9000, -33.0))
        assertEquals(EndReason.NO_SPEECH, r.reason)
        assertTrue(!r.speechEverDetected)
    }

    @Test
    fun speechIsFoundAndEndedInsideContinuousHum() {
        val signal = TestAudio.mix(hum(12_000, -33.0), concat(TestAudio.silence(5000), voice(1500, -14.0), TestAudio.silence(5500)))
        val r = run(signal)
        assertEquals(EndReason.SILENCE, r.reason)
        val at = r.speechSeenAtMs
        assertTrue("speech seen at $at", at != null && at in 5000..5600)
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs in 7900..8300)
    }

    @Test
    fun aNoisyRoomIsLearnedAndSpeechStillStandsOut() {
        val signal = TestAudio.mix(noise(14_000, -38.0), concat(TestAudio.silence(5000), voice(1500, -18.0), TestAudio.silence(7500)))
        val r = run(signal)
        assertEquals(EndReason.SILENCE, r.reason)
        val at = r.speechSeenAtMs
        assertTrue("speech seen at $at", at != null && at in 5000..5700)
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs in 7900..8400)
    }

    @Test
    fun speakingImmediatelyAfterTheTapStillWorks() {
        val r = run(concat(voice(1500, speech), noise(4000, room)))
        assertEquals(EndReason.SILENCE, r.reason)
        val at = r.speechSeenAtMs
        assertTrue("speech seen at $at", at != null && at <= 600)
        assertTrue("ended at ${r.endedAtMs}", r.endedAtMs in 2950..3250)
    }

    @Test
    fun aVeryQuietVoiceNeedsTheHighSensitivityLevel() {
        val quiet = concat(noise(500, -70.0), voice(1500, -50.0), noise(5000, -70.0))
        assertEquals(EndReason.NO_SPEECH, run(concat(quiet, noise(6000, -70.0))).reason)
        val high = run(quiet, EndpointConfig(sensitivity = SpeechSensitivity.HIGH))
        assertEquals(EndReason.SILENCE, high.reason)
    }

    @Test
    fun chunkSizeDoesNotChangeTheResult() {
        val signal = concat(noise(1000, room), voice(1500, speech), noise(5000, room))
        val a = run(signal, chunk = 640)
        val b = run(signal, chunk = 97)
        val c = run(signal, chunk = 4000)
        assertEquals(EndReason.SILENCE, a.reason)
        assertEquals(a.reason, b.reason)
        assertEquals(a.reason, c.reason)
        // the end is decided on 20 ms frames, so only the reporting granularity of the chunk differs
        assertTrue(kotlin.math.abs(a.endedAtMs - b.endedAtMs) <= 40)
        assertTrue(kotlin.math.abs(a.endedAtMs - c.endedAtMs) <= 4000)
    }

    @Test
    fun afterTheEndFurtherAudioChangesNothing() {
        val e = SpeechEndpointer()
        val sig = concat(noise(500, room), voice(1200, speech), noise(4000, room))
        var reason: EndReason? = null
        var i = 0
        while (i < sig.size && reason == null) {
            val n = minOf(640, sig.size - i)
            reason = e.feed(sig, i, n)
            i += n
        }
        assertEquals(EndReason.SILENCE, reason)
        assertEquals(reason, e.feed(voice(500, speech)))
    }

    @Test
    fun theReportDescribesWhatHappened() {
        val r = run(concat(noise(1000, room), voice(1500, speech), noise(5000, room)))
        val report = r.endpointer.report(EndReason.SILENCE)
        assertEquals(EndReason.SILENCE, report.reason)
        assertTrue(report.speechStartMs!! in 900..1300)
        assertTrue(report.noiseFloorDb < -55)
        assertTrue(report.describe().startsWith("Stopped after you went quiet"))
        assertTrue(Pcm.SAMPLE_RATE == 16_000)
    }
}
