package com.spectralcheck.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

class StftTest {

    @Test
    fun `full-scale sine reads near 0 dBFS at its frequency`() {
        val sampleRate = 44100
        val freq = 1000f
        val samples = FloatArray(sampleRate * 2) { i ->
            sin(2.0 * PI * freq * i / sampleRate).toFloat()
        }

        val spec = Stft().analyze(samples, sampleRate)
        assertTrue("expected frames", spec.frames.isNotEmpty())

        val avg = spec.averageSpectrumDb()
        val peakBin = (freq / spec.freqPerBin).roundToInt()
        // Highest value within +-1 bin of the sine frequency
        val peak = maxOf(avg[peakBin - 1], avg[peakBin], avg[peakBin + 1])
        assertEquals(0f, peak, 2.5f)

        // Far away from the sine the spectrum must be very quiet
        val farBin = (10_000f / spec.freqPerBin).roundToInt()
        assertTrue("leakage too high: ${avg[farBin]}", avg[farBin] < -60f)
    }

    @Test
    fun `frame count follows hop size`() {
        val samples = FloatArray(4096 + 2048 * 3)
        val spec = Stft(fftSize = 4096, hopSize = 2048).analyze(samples, 44100)
        assertEquals(4, spec.frames.size)
        assertEquals(2049, spec.binCount)
    }
}
