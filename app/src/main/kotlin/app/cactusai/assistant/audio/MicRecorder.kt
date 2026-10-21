package app.cactusai.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.cactusai.assistant.system.Permissions
import app.cactusai.core.audio.Pcm
import app.cactusai.core.voice.CaptureException
import app.cactusai.core.voice.PcmSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Kept as the name the rest of the app already uses; it is the core's exception so the capture loop can throw it too. */
typealias RecordingException = CaptureException

/**
 * Owns the phone's microphone for one recording at a time, at 16 kHz mono PCM16, the format Whistle takes.
 *
 * The microphone is opened only inside [withMicrophone] and always released in `finally`, whether the block ends normally, by
 * cancellation or by an error. Deciding when to stop (the stop button, the end of speech, the time limit) belongs to
 * [app.cactusai.core.voice.VoiceCapture]; this class only reads. Nothing is written to disk and nothing is kept.
 *
 * [isActive] is true from [prepare] until the microphone is released again. The speech output reads it so the assistant never
 * talks while the microphone is open and its own voice cannot be recorded as a command.
 */
class MicRecorder(private val context: Context) {
    private val stopRequested = AtomicBoolean(false)
    private val reserved = AtomicBoolean(false)

    /** True from the moment the user asks to listen until the microphone is closed again. */
    val isActive: Boolean get() = reserved.get()

    /** Ends the current recording normally; the audio so far is kept and processed. */
    fun requestStop() = stopRequested.set(true)

    fun stopWasRequested(): Boolean = stopRequested.get()

    /** Forgets a stop request left over from earlier and marks the microphone as in use. Call just before starting a recording. */
    fun prepare() {
        stopRequested.set(false)
        reserved.set(true)
    }

    /** Marks the microphone free again; safe to call more than once. */
    fun release() = reserved.set(false)

    /**
     * Opens the microphone, runs [block] with a source to read from, and closes the microphone again before returning, so that
     * transcription never runs with the microphone still open.
     */
    @SuppressLint("MissingPermission") // checked just below; lint cannot see the helper
    suspend fun <T> withMicrophone(block: suspend (PcmSource) -> T): T = withContext(Dispatchers.IO) {
        if (!Permissions.hasMicrophone(context)) {
            release()
            throw CaptureException("Microphone permission has not been granted.")
        }
        val minBuffer = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) {
            release()
            throw CaptureException("This phone does not support 16 kHz recording.")
        }
        val bufferBytes = maxOf(minBuffer * 2, Pcm.SAMPLE_RATE / 10 * 2 * 2)

        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                Pcm.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferBytes,
            )
        } catch (e: IllegalArgumentException) {
            release()
            throw CaptureException("The microphone could not be opened (${e.message}).")
        } catch (e: SecurityException) {
            release()
            throw CaptureException("Android refused microphone access (${e.message}).")
        }

        try {
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                throw CaptureException("The microphone could not be opened. Another app may be using it.")
            }
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw CaptureException("The microphone did not start. It may be blocked by the Android privacy switch or another app.")
            }
            block(
                object : PcmSource {
                    override fun read(buffer: ShortArray, offset: Int, length: Int): Int = record.read(buffer, offset, length)
                },
            )
        } finally {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (_: IllegalStateException) {
            }
            record.release()
            release()
        }
    }
}
