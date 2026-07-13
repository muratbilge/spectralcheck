package com.spectralcheck.analysis

import com.spectralcheck.dsp.Fft
import com.spectralcheck.dsp.Stft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Tests for the secondary lossy signals: stability, joint stereo, upsample, bit depth. */
class SignalRulesTest {

    private fun bandLimitedNoise(n: Int, sampleRate: Int, cutoffHz: Float, seed: Long): FloatArray {
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
        for (i in 0 until n) im[i] = -im[i]
        Fft.fft(re, im)
        var peak = 0f
        for (i in 0 until n) {
            re[i] = re[i] / n
            if (abs(re[i]) > peak) peak = abs(re[i])
        }
        val scale = 0.5f / peak
        for (i in 0 until n) re[i] *= scale
        return re
    }

    private fun cutoffOf(samples: FloatArray, sampleRate: Int = 44100) =
        CutoffDetector.detect(Stft().analyze(samples, sampleRate))

    @Test
    fun `encoder lowpass has a stable per-frame cutoff`() {
        val sig = bandLimitedNoise(1 shl 19, 44100, 16000f, seed = 1)
        val spec = Stft().analyze(sig, 44100)
        val st = CutoffDetector.stability(spec)
        assertNotNull(st)
        assertTrue("iqr was ${st!!.iqrHz}", st.iqrHz < 1000f)

        val v = VerdictEngine.judge(
            AnalysisSignals(CutoffDetector.detect(spec), 44100, stability = st)
        )
        assertEquals(Verdict.TRANSCODE, v.verdict)
        assertTrue(v.evidence.any { it.title.contains("constant", ignoreCase = true) })
    }

    @Test
    fun `varying bandwidth reads as natural content`() {
        // Alternate ~1.5 s blocks of 12 kHz- and 21 kHz-wide noise
        val block = 1 shl 16
        val sig = FloatArray(block * 8)
        for (b in 0 until 8) {
            val cut = if (b % 2 == 0) 12000f else 21000f
            bandLimitedNoise(block, 44100, cut, seed = 10L + b).copyInto(sig, b * block)
        }
        val spec = Stft().analyze(sig, 44100)
        val st = CutoffDetector.stability(spec)
        assertNotNull(st)
        assertTrue("iqr was ${st!!.iqrHz}", st.iqrHz > 3000f)
    }

    @Test
    fun `band-limited side channel flags joint stereo`() {
        val mid = cutoffOf(bandLimitedNoise(1 shl 19, 44100, 21500f, seed = 20))
        val side = cutoffOf(bandLimitedNoise(1 shl 19, 44100, 13000f, seed = 21))
        val v = VerdictEngine.judge(
            AnalysisSignals(mid, 44100, sideCutoff = side)
        )
        assertEquals(Verdict.SUSPICIOUS, v.verdict)
        assertTrue(v.evidence.any { it.title.contains("Side channel") })
    }

    @Test
    fun `hi-res file with 21 kHz content is an upsample`() {
        val sr = 88200
        val cutoff = CutoffDetector.detect(
            Stft().analyze(bandLimitedNoise(1 shl 19, sr, 21000f, seed = 30), sr)
        )
        val v = VerdictEngine.judge(AnalysisSignals(cutoff, sr))
        assertEquals(Verdict.SUSPICIOUS, v.verdict)
        assertTrue(v.evidence.any { it.title.contains("Upsampled") })
    }

    @Test
    fun `samples on 16-bit grid expose padded 24-bit`() {
        val rnd = Random(40)
        val padded = FloatArray(50_000) { (rnd.nextInt(-32768, 32768)) / 32768f }
        assertEquals(16, BitDepth.effectiveBits(padded, 24))

        val true24 = FloatArray(50_000) { (rnd.nextInt(-8388608, 8388608)) / 8388608f }
        assertEquals(24, BitDepth.effectiveBits(true24, 24))

        assertNull(BitDepth.effectiveBits(FloatArray(50_000), 24))
    }

    @Test
    fun `fake 24-bit upgrades the verdict`() {
        val cutoff = cutoffOf(bandLimitedNoise(1 shl 19, 44100, 21500f, seed = 50))
        val v = VerdictEngine.judge(
            AnalysisSignals(cutoff, 44100, headerBitDepth = 24, effectiveBits = 16)
        )
        assertEquals(Verdict.SUSPICIOUS, v.verdict)
        assertTrue(v.evidence.any { it.title.startsWith("Fake") })
    }
}
