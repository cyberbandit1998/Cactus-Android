package app.cactusai.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import app.cactusai.assistant.system.Permissions
import app.cactusai.core.audio.Pcm
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

class RecordingException(message: String) : Exception(message)

class RecordedAudio(val samples: ShortArray, val length: Int) {
    val durationMs: Long get() = Pcm.durationMs(length)
}

/**
 * Push-to-talk recording at 16 kHz mono PCM16, the format Whistle takes.
 *
 * The microphone is opened only inside [record] and always released in `finally`, whether the
 * recording ends by [requestStop], by the 30 second limit, by cancellation or by an error. Nothing
 * is written to disk and nothing is kept after the audio has been transcribed.
 */
class MicRecorder(private val context: Context) {
    private val stopRequested = AtomicBoolean(false)

    /** Ends the current recording normally; the audio so far is returned from [record]. */
    fun requestStop() = stopRequested.set(true)

    /** Forgets a stop request left over from earlier. Call just before starting a recording. */
    fun prepare() = stopRequested.set(false)

    /**
     * Records until [requestStop], [maxMs], or cancellation of the calling coroutine (which discards
     * the audio). [onProgress] gets the elapsed time and a 0..1 loudness for the on-screen indicator.
     */
    @SuppressLint("MissingPermission") // checked just below; lint cannot see the helper
    suspend fun record(maxMs: Long = Pcm.SINGLE_PASS_SECONDS * 1000L, onProgress: (elapsedMs: Long, level: Float) -> Unit): RecordedAudio =
        withContext(Dispatchers.IO) {
            if (!Permissions.hasMicrophone(context)) throw RecordingException("Microphone permission has not been granted.")
            // A stop requested while the microphone was still opening is honoured, so it is not cleared here.

            val minBuffer = AudioRecord.getMinBufferSize(Pcm.SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) throw RecordingException("This phone does not support 16 kHz recording.")
            val chunk = Pcm.SAMPLE_RATE / 10 // 100 ms
            val bufferBytes = maxOf(minBuffer * 2, chunk * 2 * 2)

            val record = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    Pcm.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (e: IllegalArgumentException) {
                throw RecordingException("The microphone could not be opened (${e.message}).")
            } catch (e: SecurityException) {
                throw RecordingException("Android refused microphone access (${e.message}).")
            }

            try {
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    throw RecordingException("The microphone could not be opened. Another app may be using it.")
                }
                record.startRecording()
                if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                    throw RecordingException("The microphone did not start. It may be blocked by the Android privacy switch or another app.")
                }

                val capacity = (maxMs * Pcm.SAMPLE_RATE / 1000).toInt()
                val all = ShortArray(capacity)
                var total = 0
                val buf = ShortArray(chunk)
                val startedAt = System.nanoTime()
                while (total < capacity && !stopRequested.get()) {
                    currentCoroutineContext().ensureActive()
                    val n = record.read(buf, 0, min(buf.size, capacity - total))
                    if (n < 0) throw RecordingException("Reading from the microphone failed (code $n).")
                    if (n == 0) continue
                    System.arraycopy(buf, 0, all, total, n)
                    total += n
                    var peak = 0
                    for (i in 0 until n) peak = maxOf(peak, abs(buf[i].toInt()))
                    onProgress((System.nanoTime() - startedAt) / 1_000_000, (peak / 32768f).coerceIn(0f, 1f))
                }
                RecordedAudio(all, total)
            } finally {
                try {
                    if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
                } catch (_: IllegalStateException) {
                }
                record.release()
                stopRequested.set(false)
            }
        }
}
