package app.cactusai.core

import app.cactusai.core.audio.Pcm
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PcmTest {
    private fun tone(ms: Int, amp: Float = 0.3f): FloatArray =
        FloatArray(Pcm.SAMPLE_RATE * ms / 1000) { amp * sin(2 * PI * 220 * it / Pcm.SAMPLE_RATE).toFloat() }

    private fun silence(ms: Int, level: Float = 0.0005f): FloatArray = FloatArray(Pcm.SAMPLE_RATE * ms / 1000) { if (it % 2 == 0) level else -level }

    @Test
    fun convertsShortsToFloats() {
        val f = Pcm.toFloat(shortArrayOf(0, 16384, -32768, 32767), 4)
        assertEquals(0f, f[0], 0f)
        assertEquals(0.5f, f[1], 0f)
        assertEquals(-1f, f[2], 0f)
        assertTrue(f[3] < 1f)
    }

    @Test
    fun rmsAndPeak() {
        val t = tone(100, 0.5f)
        assertEquals(0.5f / 1.4142135f, Pcm.rms(t), 0.01f)
        assertEquals(0.5f, Pcm.peak(t), 0.01f)
        assertEquals(0f, Pcm.rms(FloatArray(0)), 0f)
    }

    @Test
    fun trimsLeadingAndTrailingSilenceButKeepsPadding() {
        val clip = silence(1000) + tone(500) + silence(1000)
        val trimmed = Pcm.trimSilence(clip)
        val ms = Pcm.durationMs(trimmed.size)
        assertTrue("trimmed to $ms ms", ms in 800..1100)
    }

    @Test
    fun allSilenceBecomesEmpty() {
        assertEquals(0, Pcm.trimSilence(silence(2000)).size)
        assertEquals(0, Pcm.trimSilence(FloatArray(10)).size)
    }

    @Test
    fun speechOnlyClipIsKeptWhole() {
        val clip = tone(800)
        assertTrue(Pcm.trimSilence(clip).size >= clip.size - Pcm.SAMPLE_RATE / 50)
    }
}
