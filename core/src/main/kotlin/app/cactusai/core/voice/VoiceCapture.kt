package app.cactusai.core.voice

import app.cactusai.core.audio.Pcm
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** The microphone could not be used. [message] is written for the user. */
class CaptureException(message: String) : Exception(message)

/** Where recorded audio comes from: the phone's microphone in the app, a synthetic signal in tests. */
interface PcmSource {
    /** Blocks until samples are available; returns how many were written, 0 for none yet, or a negative error code. */
    fun read(buffer: ShortArray, offset: Int, length: Int): Int
}

data class CaptureConfig(
    /** When false the recording only ends by the user's stop, cancellation or the time limit. */
    val autoStop: Boolean = true,
    val endpoint: EndpointConfig = EndpointConfig(),
)

/** One recording, already ended. [samples] holds [length] valid 16 kHz PCM16 samples. */
class CapturedAudio(val samples: ShortArray, val length: Int, val reason: EndReason, val report: EndpointReport) {
    val durationMs: Long get() = Pcm.durationMs(length)
}

/** Progress for the screen while recording. */
data class CaptureUpdate(val elapsedMs: Long, val level: Float, val speechDetected: Boolean, val state: SpeechState)

object VoiceCapture {
    /** 40 ms of audio per read keeps the stop decision within a couple of frames of the real end of speech. */
    private const val CHUNK_MS = 40

    /**
     * Reads from [source] until the user asks to stop ([stopRequested]), the detector ends the recording
     * (only when [CaptureConfig.autoStop]), the time limit passes, or the calling coroutine is cancelled
     * (which discards the audio). It only reads; opening and releasing the microphone is the caller's job, so
     * that it can happen in one `try`/`finally`.
     */
    suspend fun capture(
        source: PcmSource,
        config: CaptureConfig,
        stopRequested: () -> Boolean,
        onUpdate: (CaptureUpdate) -> Unit = {},
    ): CapturedAudio {
        val endpoint = SpeechEndpointer(config.endpoint)
        val capacity = (endpoint.config.maxMs.toLong() * Pcm.SAMPLE_RATE / 1000).toInt()
        val all = ShortArray(capacity)
        val buf = ShortArray(Pcm.SAMPLE_RATE * CHUNK_MS / 1000)
        var total = 0
        var reason = EndReason.MAX_DURATION
        while (true) {
            currentCoroutineContext().ensureActive()
            if (stopRequested()) {
                reason = EndReason.MANUAL_STOP
                break
            }
            val n = source.read(buf, 0, min(buf.size, capacity - total))
            if (n < 0) throw CaptureException("Reading from the microphone failed (code $n).")
            if (n == 0) continue
            System.arraycopy(buf, 0, all, total, n)
            total += n

            val ended = endpoint.feed(buf, 0, n)
            var peak = 0
            for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))
            onUpdate(
                CaptureUpdate(
                    elapsedMs = Pcm.durationMs(total),
                    level = (peak / 32768f).coerceIn(0f, 1f),
                    speechDetected = endpoint.state == SpeechState.SPEECH,
                    state = endpoint.state,
                ),
            )
            if (ended != null && (ended == EndReason.MAX_DURATION || config.autoStop)) {
                reason = ended
                break
            }
            if (total >= capacity) {
                reason = EndReason.MAX_DURATION
                break
            }
        }
        return CapturedAudio(all, total, reason, endpoint.report(reason))
    }
}

/** Why nothing was sent on to the speech model. */
enum class NothingHeardReason { NO_SPEECH_TIMEOUT, TOO_SHORT }

sealed interface VoiceTurnResult<out T> {
    /** Recording ended and the audio was transcribed; [value] is whatever the transcriber returned. */
    data class Transcribed<T>(val value: T, val audio: CapturedAudio) : VoiceTurnResult<T>

    data class NothingHeard(val audio: CapturedAudio, val why: NothingHeardReason) : VoiceTurnResult<Nothing>

    /** [beforeTranscribe] refused (for example the phone is overheating). */
    data class Blocked(val message: String, val audio: CapturedAudio) : VoiceTurnResult<Nothing>
}

object VoiceTurn {
    /** Fewer than this many milliseconds of sound after trimming is treated as nothing said. */
    const val MIN_SPEECH_MS = 250L

    /**
     * Record, then transcribe as soon as the recording ends, with no further tap: the whole spoken part of
     * "tap, speak, get the text". [transcribe] is called at most once and never for a cancelled recording or
     * one in which no speech was found.
     */
    suspend fun <T> listenAndTranscribe(
        source: PcmSource,
        config: CaptureConfig,
        stopRequested: () -> Boolean,
        trimSilence: Boolean,
        onUpdate: (CaptureUpdate) -> Unit = {},
        onRecorded: (CapturedAudio) -> Unit = {},
        beforeTranscribe: () -> String? = { null },
        transcribe: suspend (FloatArray) -> T,
    ): VoiceTurnResult<T> {
        val audio = VoiceCapture.capture(source, config, stopRequested, onUpdate)
        onRecorded(audio)
        if (audio.reason == EndReason.NO_SPEECH) return VoiceTurnResult.NothingHeard(audio, NothingHeardReason.NO_SPEECH_TIMEOUT)
        beforeTranscribe()?.let { return VoiceTurnResult.Blocked(it, audio) }

        var samples = Pcm.toFloat(audio.samples, audio.length)
        if (trimSilence) samples = Pcm.trimSilence(samples)
        if (Pcm.durationMs(samples.size) < MIN_SPEECH_MS) return VoiceTurnResult.NothingHeard(audio, NothingHeardReason.TOO_SHORT)
        return VoiceTurnResult.Transcribed(transcribe(samples), audio)
    }
}
