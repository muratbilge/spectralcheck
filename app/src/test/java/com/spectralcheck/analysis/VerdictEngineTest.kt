package com.spectralcheck.analysis

import com.spectralcheck.dsp.Fft
import com.spectralcheck.dsp.Stft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * End-to-end detector tests on synthesized signals: noise with an exact
 * brick-wall bandwidth, built in the frequency domain via inverse FFT.
 */
class VerdictEngineTest {

    private val sampleRate = 44100

    /** Noise with flat spectrum up to [cutoffHz] and silence above. */
    private fun bandLimitedNoise(n: Int, cutoffHz: Float, seed: Long): FloatArray {
        val re = FloatArray(n)
        val im = FloatArray(n)
        val rnd = Random(seed)
        val cutBin = (cutoffHz / sampleRate * n).toInt()
        for (k in 1 until n / 2) {
            if (k <= cutBin) {
                val ph = rnd.nextFloat() * 2f * PI.toFloat()
                re[k] = cos(ph)
                im[k] = sin(ph)
                re[n - k] = re[k]
                im[n - k] = -im[k]
            }
        }
        // Inverse FFT via conjugation
        for (i in 0 until n) im[i] = -im[i]
        Fft.fft(re, im)
        var peak = 0f
        for (i in 0 until n) {
            re[i] = re[i] / n
            if (abs(re[i]) > peak) peak = abs(re[i])
        }
        // Normalize to a healthy level
        val scale = 0.5f / peak
        for (i in 0 until n) re[i] *= scale
        return re
    }

    private fun judge(samples: FloatArray): VerdictResult {
        val spec = Stft().analyze(samples, sampleRate)
        val cutoff = CutoffDetector.detect(spec)
        return VerdictEngine.judge(cutoff, sampleRate)
    }

    private val n = 1 shl 19 // ~11.9 s at 44.1 kHz

    @Test
    fun `full-band noise is authentic`() {
        val v = judge(bandLimitedNoise(n, 21500f, seed = 1))
        assertEquals(Verdict.AUTHENTIC, v.verdict)
    }

    @Test
    fun `16 kHz brick wall is a transcode`() {
        val v = judge(bandLimitedNoise(n, 16000f, seed = 2))
        assertEquals(Verdict.TRANSCODE, v.verdict)
        assertTrue(v.cutoff.sharpShelf)
        assertEquals(16000f, v.cutoff.cutoffHz, 500f)
    }

    @Test
    fun `19 kHz brick wall is suspicious`() {
        val v = judge(bandLimitedNoise(n, 19000f, seed = 3))
        assertEquals(Verdict.SUSPICIOUS, v.verdict)
        assertEquals(19000f, v.cutoff.cutoffHz, 500f)
    }

    @Test
    fun `silence is inconclusive`() {
        val v = judge(FloatArray(1 shl 17))
        assertEquals(Verdict.INCONCLUSIVE, v.verdict)
    }
}
