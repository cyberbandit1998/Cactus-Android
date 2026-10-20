package app.cactusai.core.voice

import app.cactusai.core.audio.Pcm
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Why a recording ended. [MANUAL_STOP] is the user's own tap; the others come from [SpeechEndpointer]. */
enum class EndReason { MANUAL_STOP, SILENCE, NO_SPEECH, MAX_DURATION }

/** What the microphone is hearing right now: nothing yet ("Listening") or a voice ("Speech detected"). */
enum class SpeechState { WAITING, SPEECH }

/**
 * How easily speech is picked up. Quiet or soft voices want [HIGH]; a noisy room wants [LOW].
 * [absoluteOnsetDb] is the quietest frame (RMS, dB below full scale) that can count as speech; [onsetMarginDb] is
 * how far above the tracked background level a frame has to be.
 */
enum class SpeechSensitivity(val absoluteOnsetDb: Double, val onsetMarginDb: Double) {
    LOW(-38.0, 12.0),
    NORMAL(-45.0, 9.0),
    HIGH(-52.0, 7.0),
}

data class EndpointConfig(
    /** Quiet time after speech that ends the recording. Clamped to 0.5..4 s. */
    val silenceMs: Int = DEFAULT_SILENCE_MS,
    /** Gives up when nothing that sounds like speech has started by then. */
    val noSpeechMs: Int = DEFAULT_NO_SPEECH_MS,
    /** Hard limit on the recording. Clamped to 3..30 s (Whistle takes up to 30 s in one pass). */
    val maxMs: Int = DEFAULT_MAX_MS,
    val sensitivity: SpeechSensitivity = SpeechSensitivity.NORMAL,
) {
    fun normalized(): EndpointConfig = copy(
        silenceMs = silenceMs.coerceIn(MIN_SILENCE_MS, MAX_SILENCE_MS),
        noSpeechMs = noSpeechMs.coerceIn(2_000, 30_000),
        maxMs = maxMs.coerceIn(MIN_RECORDING_MS, MAX_RECORDING_MS),
    )

    companion object {
        const val DEFAULT_SILENCE_MS = 1_500
        const val MIN_SILENCE_MS = 500
        const val MAX_SILENCE_MS = 4_000
        const val DEFAULT_NO_SPEECH_MS = 8_000
        const val DEFAULT_MAX_MS = 30_000
        const val MIN_RECORDING_MS = 3_000
        const val MAX_RECORDING_MS = Pcm.SINGLE_PASS_SECONDS * 1_000
    }
}

/** What the detector saw, for the details view and for tests. Times are measured in recorded audio, not wall-clock. */
data class EndpointReport(
    val reason: EndReason,
    val durationMs: Long,
    val speechStartMs: Long?,
    val speechEndMs: Long?,
    val noiseFloorDb: Double,
    val speechLevelDb: Double?,
    /** Short noises that looked like speech for a moment but were too brief to count. */
    val falseStarts: Int,
) {
    fun describe(): String = buildString {
        append(
            when (reason) {
                EndReason.MANUAL_STOP -> "Stopped by you"
                EndReason.SILENCE -> "Stopped after you went quiet"
                EndReason.NO_SPEECH -> "Stopped: no speech heard"
                EndReason.MAX_DURATION -> "Stopped at the time limit"
            },
        )
        append(" · ${"%.1f".format(java.util.Locale.ROOT, durationMs / 1000.0)} s recorded")
        speechStartMs?.let { append(" · speech from ${"%.1f".format(java.util.Locale.ROOT, it / 1000.0)} s") }
        append(" · background ${noiseFloorDb.toInt()} dB")
        if (falseStarts > 0) append(" · $falseStarts short noise${if (falseStarts == 1) "" else "s"} ignored")
    }
}

/**
 * Finds the start and the end of a spoken command in a stream of 16 kHz PCM16 audio, entirely on the phone
 * and without any model.
 *
 * A plain "louder than X" rule fails in practice: it fires on a door slam, never fires in a noisy room,
 * and cuts a quiet speaker off. This detector therefore combines several cheap cues over 20 ms frames:
 *
 *  1. **Adaptive background level.** The quietest level of the first 200 ms (capped, so a command that begins
 *     at once is not mistaken for background) is tracked afterwards: it falls quickly, rises slowly, and rises
 *     faster when the sound stays perfectly steady for 1.5 s (a fan or a hum).
 *  2. **Speech must stand out** of that background by a margin and by an absolute minimum.
 *  3. **Speech must last and must vary.** It takes about 100 ms of loud frames *and* the syllable-like
 *     level changes of a voice. A click, a knock or steady noise does not start the countdown.
 *  4. **Two thresholds (hysteresis).** Once speaking, the level must fall clearly below the speech level
 *     (and below the start threshold) to count as quiet, so a trailing syllable or the room's echo does not
 *     keep the microphone open, and a natural dip does not end the command.
 *  5. **The silence countdown only runs after speech was detected**, and a noise that ends before it
 *     amounted to a real utterance (about 240 ms of voice) is forgotten instead of ending the recording.
 *
 * Trade-offs: speech-like background sound (a television, people talking nearby) cannot be told apart from
 * the user's voice, so the recording then ends at the time limit or when the user stops it; very soft speech
 * below the sensitivity's absolute minimum is not noticed; and a silence time under about a second can cut
 * off a slow talker. All thresholds are tuned on synthetic signals, not on a Pixel 7: see docs/AUTO_STOP.md.
 *
 * Time is counted in samples, so behaviour does not depend on how fast the audio is delivered.
 */
class SpeechEndpointer(config: EndpointConfig = EndpointConfig()) {
    val config: EndpointConfig = config.normalized()

    private val frameSamples = Pcm.SAMPLE_RATE * FRAME_MS / 1000
    private val pending = ShortArray(frameSamples)
    private var pendingCount = 0
    private var totalSamples = 0L
    private var frameCount = 0

    private val silenceFrames = this.config.silenceMs / FRAME_MS
    private val noSpeechFrames = this.config.noSpeechMs / FRAME_MS
    private val maxSamples = this.config.maxMs.toLong() * Pcm.SAMPLE_RATE / 1000

    private val onsetDb = this.config.sensitivity.absoluteOnsetDb
    private val onsetMargin = this.config.sensitivity.onsetMarginDb
    private val offsetDb = onsetDb - OFFSET_HYSTERESIS_DB

    private val recent = DoubleArray(STEADY_FRAMES)
    private var recentCount = 0
    private val voicedWindow = BooleanArray(ONSET_WINDOW)
    private val levelWindow = DoubleArray(ONSET_WINDOW)
    private var windowCount = 0
    private var windowHead = 0

    private var calibrationMin = Double.MAX_VALUE
    private var floorDb = INITIAL_FLOOR_DB

    private var ended: EndReason? = null

    /** The speech state for the "Listening" / "Speech detected" display. */
    var state: SpeechState = SpeechState.WAITING
        private set

    /** Level of the latest frame, dB below full scale (about -90 for silence). */
    var levelDb: Double = MIN_DB
        private set

    val noiseFloorDb: Double get() = floorDb
    val elapsedMs: Long get() = totalSamples * 1000 / Pcm.SAMPLE_RATE

    private var speechLevelDb = 0.0
    private var voicedFrames = 0
    private var silenceRun = 0
    private var speechStartMs: Long? = null
    private var lastActiveEndMs: Long? = null
    private var falseStarts = 0

    /**
     * Takes the next samples. Returns why the recording should end, or null to carry on. Once a reason has
     * been returned it is returned again for any further call.
     */
    fun feed(samples: ShortArray, offset: Int = 0, length: Int = samples.size - offset): EndReason? {
        ended?.let { return it }
        require(offset >= 0 && length >= 0 && offset + length <= samples.size) { "range out of bounds" }
        var i = offset
        val end = offset + length
        while (i < end) {
            val take = min(frameSamples - pendingCount, end - i)
            System.arraycopy(samples, i, pending, pendingCount, take)
            pendingCount += take
            i += take
            totalSamples += take
            if (pendingCount == frameSamples) {
                pendingCount = 0
                processFrame()?.let {
                    ended = it
                    return it
                }
            }
        }
        if (totalSamples >= maxSamples) {
            ended = EndReason.MAX_DURATION
            return ended
        }
        return null
    }

    fun report(reason: EndReason): EndpointReport = EndpointReport(
        reason = reason,
        durationMs = elapsedMs,
        speechStartMs = speechStartMs,
        speechEndMs = lastActiveEndMs,
        noiseFloorDb = floorDb,
        speechLevelDb = if (speechStartMs != null) speechLevelDb else null,
        falseStarts = falseStarts,
    )

    private fun processFrame(): EndReason? {
        val e = frameLevelDb()
        levelDb = e
        val index = frameCount++
        val timeMs = (index + 1L) * FRAME_MS

        push(recent, recentCount, e)
        recentCount = min(recentCount + 1, STEADY_FRAMES)

        if (index < CALIBRATION_FRAMES) {
            calibrationMin = min(calibrationMin, e)
            if (index == CALIBRATION_FRAMES - 1) floorDb = calibrationMin.coerceIn(MIN_DB + 10, INITIAL_FLOOR_CAP_DB)
            return timeouts(timeMs)
        }

        val onThreshold = max(onsetDb, floorDb + onsetMargin)
        val voiced = e >= onThreshold
        pushWindow(voiced, e)
        adaptFloor(e, voiced)

        when (state) {
            SpeechState.WAITING -> if (looksLikeSpeechStart()) beginSpeech(timeMs)
            SpeechState.SPEECH -> {
                val offThreshold = max(offsetDb, max(floorDb + OFFSET_MARGIN_DB, speechLevelDb - SPEECH_DROP_DB))
                if (e >= offThreshold) {
                    silenceRun = 0
                    lastActiveEndMs = timeMs
                    if (voiced) {
                        voicedFrames++
                        speechLevelDb += (e - speechLevelDb) * 0.1
                    }
                } else {
                    silenceRun++
                    if (silenceRun >= silenceFrames) {
                        if (voicedFrames * FRAME_MS >= MIN_UTTERANCE_MS) return EndReason.SILENCE
                        // A bump or a cough-sized noise, not a command: forget it and keep waiting.
                        falseStarts++
                        state = SpeechState.WAITING
                        speechStartMs = null
                        lastActiveEndMs = null
                        voicedFrames = 0
                        silenceRun = 0
                        windowCount = 0
                    }
                }
            }
        }
        return timeouts(timeMs)
    }

    private fun timeouts(timeMs: Long): EndReason? = when {
        state == SpeechState.WAITING && frameCount >= noSpeechFrames -> EndReason.NO_SPEECH
        timeMs >= config.maxMs -> EndReason.MAX_DURATION
        else -> null
    }

    private fun beginSpeech(timeMs: Long) {
        state = SpeechState.SPEECH
        var firstVoicedAgo = 0
        var sum = 0.0
        var n = 0
        for (k in 0 until windowCount) {
            val at = windowIndex(k)
            if (voicedWindow[at]) {
                firstVoicedAgo = k
                sum += levelWindow[at]
                n++
            }
        }
        speechLevelDb = if (n > 0) sum / n else levelDb
        voicedFrames = n
        silenceRun = 0
        speechStartMs = max(0L, timeMs - (firstVoicedAgo + 1L) * FRAME_MS)
        lastActiveEndMs = timeMs
    }

    /**
     * About 100 ms of loud frames in the last 200 ms, and the level must keep changing from one frame to the next the
     * way syllables do. The median change is used because a hum that switches on makes one big jump and then stays flat.
     */
    private fun looksLikeSpeechStart(): Boolean {
        var voicedCount = 0
        for (k in 0 until windowCount) if (voicedWindow[windowIndex(k)]) voicedCount++
        if (voicedCount < ONSET_MIN_FRAMES) return false
        val deltas = DoubleArray(windowCount - 1) { k -> abs(levelWindow[windowIndex(k)] - levelWindow[windowIndex(k + 1)]) }
        deltas.sort()
        return deltas[deltas.size / 2] >= MIN_MODULATION_DB
    }

    /** The k-th most recent frame in the onset window (0 is the newest). */
    private fun windowIndex(k: Int): Int = (windowHead - 1 - k + ONSET_WINDOW * 2) % ONSET_WINDOW

    private fun pushWindow(voiced: Boolean, level: Double) {
        voicedWindow[windowHead] = voiced
        levelWindow[windowHead] = level
        windowHead = (windowHead + 1) % ONSET_WINDOW
        windowCount = min(windowCount + 1, ONSET_WINDOW)
    }

    private fun adaptFloor(e: Double, voiced: Boolean) {
        floorDb = when {
            e < floorDb -> floorDb + (e - floorDb) * FLOOR_FALL
            !voiced && state == SpeechState.WAITING -> floorDb + (e - floorDb) * FLOOR_RISE
            isSteady() -> floorDb + (e - floorDb) * FLOOR_RISE_STEADY
            else -> floorDb
        }
    }

    /** Perfectly even level for 1.5 s: a fan, a hum, a vehicle. A voice is never that even. */
    private fun isSteady(): Boolean {
        if (recentCount < STEADY_FRAMES) return false
        var sum = 0.0
        for (v in recent) sum += v
        val mean = sum / STEADY_FRAMES
        var sq = 0.0
        for (v in recent) sq += (v - mean) * (v - mean)
        return sqrt(sq / STEADY_FRAMES) < STEADY_STD_DB
    }

    private fun push(buffer: DoubleArray, count: Int, value: Double) {
        if (count < buffer.size) {
            buffer[count] = value
        } else {
            System.arraycopy(buffer, 1, buffer, 0, buffer.size - 1)
            buffer[buffer.size - 1] = value
        }
    }

    private fun frameLevelDb(): Double {
        var sum = 0.0
        for (s in pending) sum += s
        val mean = sum / frameSamples
        var sq = 0.0
        for (s in pending) {
            val d = s - mean
            sq += d * d
        }
        val rms = sqrt(sq / frameSamples) / 32768.0
        return if (rms <= 0.0) MIN_DB else max(MIN_DB, 20 * log10(rms))
    }

    companion object {
        const val FRAME_MS = 20
        const val MIN_DB = -90.0

        private const val CALIBRATION_FRAMES = 10
        private const val INITIAL_FLOOR_DB = -60.0
        private const val INITIAL_FLOOR_CAP_DB = -48.0
        private const val ONSET_WINDOW = 10
        private const val ONSET_MIN_FRAMES = 5
        private const val MIN_MODULATION_DB = 1.0
        private const val MIN_UTTERANCE_MS = 240
        private const val OFFSET_HYSTERESIS_DB = 4.0
        private const val OFFSET_MARGIN_DB = 5.0
        private const val SPEECH_DROP_DB = 28.0
        private const val FLOOR_FALL = 0.3
        private const val FLOOR_RISE = 0.02
        private const val FLOOR_RISE_STEADY = 0.06
        private const val STEADY_FRAMES = 75
        private const val STEADY_STD_DB = 1.5
    }
}
