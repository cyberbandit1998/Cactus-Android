package app.cactusai.core.audio

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Audio helpers for Whistle, which takes 16 kHz mono floats in [-1, 1]. */
object Pcm {
    const val SAMPLE_RATE = 16_000

    /** Whistle transcribes up to 30 s in a single pass and windows longer clips. */
    const val SINGLE_PASS_SECONDS = 30

    fun toFloat(src: ShortArray, length: Int = src.size): FloatArray {
        require(length in 0..src.size) { "length out of range" }
        return FloatArray(length) { src[it] / 32768f }
    }

    fun durationMs(sampleCount: Int): Long = sampleCount * 1000L / SAMPLE_RATE

    fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Float {
        if (to <= from) return 0f
        var sum = 0.0
        for (i in from until to) sum += samples[i].toDouble() * samples[i]
        return sqrt(sum / (to - from)).toFloat()
    }

    fun peak(samples: FloatArray): Float {
        var p = 0f
        for (s in samples) p = max(p, abs(s))
        return p
    }

    /**
     * Cuts leading and trailing silence, keeping [padMs] of margin so quiet word starts survive.
     * The threshold adapts to the clip's own noise floor. Returns an empty array when nothing in the
     * clip is louder than the noise (nothing to transcribe).
     */
    fun trimSilence(samples: FloatArray, frameMs: Int = 20, padMs: Int = 200, minRms: Float = 0.006f): FloatArray {
        val frame = SAMPLE_RATE * frameMs / 1000
        val frames = samples.size / frame
        if (frames == 0) return FloatArray(0)
        val levels = FloatArray(frames) { rms(samples, it * frame, (it + 1) * frame) }
        val noiseFloor = levels.sortedArray()[frames / 10]
        // Cap at a quarter of the loudest frame so a clip that is all speech (a high "noise floor")
        // is never trimmed away.
        val threshold = max(minRms, minOf(noiseFloor * 2.5f, levels.max() * 0.25f))
        val first = levels.indexOfFirst { it >= threshold }
        if (first < 0) return FloatArray(0)
        val last = levels.indexOfLast { it >= threshold }
        val pad = padMs / frameMs
        val from = max(0, first - pad) * frame
        val to = minOf(frames, last + 1 + pad) * frame
        return samples.copyOfRange(from, minOf(to, samples.size))
    }
}
