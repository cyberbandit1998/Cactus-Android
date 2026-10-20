package app.cactusai.core.voice

import app.cactusai.core.audio.Pcm
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Synthetic test audio. None of this is a recording of a human voice: "voice" is a harmonic tone whose loudness
 * rises and falls like syllables, which is what the detector's cues are designed around. Passing these tests proves the
 * logic, not that the detector works in a real room; that needs a phone (docs/AUTO_STOP.md).
 */
object TestAudio {
    private fun samples(ms: Int) = ms * Pcm.SAMPLE_RATE / 1000

    private fun scaled(raw: DoubleArray, dbfs: Double): ShortArray {
        var sq = 0.0
        for (v in raw) sq += v * v
        val rms = sqrt(sq / max(1, raw.size))
        val target = 10.0.pow(dbfs / 20) * 32768
        val gain = if (rms > 0) target / rms else 0.0
        return ShortArray(raw.size) { (raw[it] * gain).coerceIn(-32768.0, 32767.0).toInt().toShort() }
    }

    fun silence(ms: Int): ShortArray = ShortArray(samples(ms))

    /** Gaussian noise with the given overall RMS level. */
    fun noise(ms: Int, dbfs: Double, seed: Long = 1): ShortArray {
        val r = Random(seed)
        return scaled(DoubleArray(samples(ms)) { r.nextGaussian() }, dbfs)
    }

    /** Steady mains hum. */
    fun hum(ms: Int, dbfs: Double, hz: Double = 60.0): ShortArray =
        scaled(DoubleArray(samples(ms)) { sin(2 * PI * hz * it / Pcm.SAMPLE_RATE) }, dbfs)

    /** Speech-like: eight harmonics of a 140 Hz voice, loudness rising and falling about four times a second. */
    fun voice(ms: Int, dbfs: Double, f0: Double = 140.0, syllablesPerSecond: Double = 4.0): ShortArray {
        val raw = DoubleArray(samples(ms)) { i ->
            val t = i.toDouble() / Pcm.SAMPLE_RATE
            var s = 0.0
            for (k in 1..8) s += sin(2 * PI * f0 * k * t) / k
            val envelope = 0.15 + 0.85 * abs(sin(PI * syllablesPerSecond * t))
            s * envelope
        }
        return scaled(raw, dbfs)
    }

    /** A short broadband bang such as a knock or a door. */
    fun click(ms: Int, dbfs: Double, seed: Long = 7): ShortArray = noise(ms, dbfs, seed)

    fun concat(vararg parts: ShortArray): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var at = 0
        for (p in parts) {
            System.arraycopy(p, 0, out, at, p.size)
            at += p.size
        }
        return out
    }

    /** Mixes two signals sample by sample (the shorter one is padded with silence). */
    fun mix(a: ShortArray, b: ShortArray): ShortArray =
        ShortArray(max(a.size, b.size)) { i -> ((a.getOrElse(i) { 0 }) + (b.getOrElse(i) { 0 })).coerceIn(-32768, 32767).toShort() }

    /** An endless source for capture tests; it always returns what was asked for, like a microphone that never stops. */
    class SignalSource(private val signal: ShortArray, private val loop: Boolean = false) : PcmSource {
        var position = 0
            private set
        var reads = 0
            private set

        override fun read(buffer: ShortArray, offset: Int, length: Int): Int {
            reads++
            var written = 0
            while (written < length) {
                if (position >= signal.size) {
                    if (!loop) {
                        // Pad with silence: a real microphone keeps delivering until it is stopped.
                        for (i in written until length) buffer[offset + i] = 0
                        position += length - written
                        return length
                    }
                    position = 0
                }
                buffer[offset + written] = signal[position]
                written++
                position++
            }
            return written
        }
    }
}
