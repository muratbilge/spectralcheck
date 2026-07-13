package com.spectralcheck.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

class RealFftTest {

    @Test
    fun `matches naive DFT magnitudes on random input`() {
        val n = 256
        val rnd = Random(7)
        val sig = FloatArray(n) { rnd.nextFloat() * 2f - 1f }
        val window = FloatArray(n) { 1f }

        val out = FloatArray(n / 2 + 1)
        RealFft(n).magnitudes(sig, 0, window, out)

        for (k in 0..n / 2) {
            var re = 0.0
            var im = 0.0
            for (t in 0 until n) {
                val ang = -2.0 * PI * k * t / n
                re += sig[t] * cos(ang)
                im += sig[t] * sin(ang)
            }
            assertEquals("bin $k", sqrt(re * re + im * im).toFloat(), out[k], 2e-2f)
        }
    }

    @Test
    fun `applies the window and offset`() {
        val n = 64
        val sig = FloatArray(n * 2) { if (it >= n) 1f else 0f }
        val window = FloatArray(n) { 0.5f }
        val out = FloatArray(n / 2 + 1)
        // Second half of sig is all ones, windowed by 0.5 => DC = n * 0.5
        RealFft(n).magnitudes(sig, n, window, out)
        assertEquals(n * 0.5f, out[0], 1e-3f)
        assertEquals(0f, out[5], 1e-3f)
    }
}
