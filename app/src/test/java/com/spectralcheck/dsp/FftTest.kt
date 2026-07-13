package com.spectralcheck.dsp

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class FftTest {

    @Test
    fun `impulse transforms to flat spectrum`() {
        val n = 64
        val re = FloatArray(n).also { it[0] = 1f }
        val im = FloatArray(n)
        Fft.fft(re, im)
        for (k in 0 until n) {
            assertEquals(1f, re[k], 1e-4f)
            assertEquals(0f, im[k], 1e-4f)
        }
    }

    @Test
    fun `matches naive DFT on random input`() {
        val n = 256
        val rnd = Random(42)
        val sig = FloatArray(n) { rnd.nextFloat() * 2f - 1f }

        val re = sig.copyOf()
        val im = FloatArray(n)
        Fft.fft(re, im)

        for (k in 0 until n) {
            var expRe = 0.0
            var expIm = 0.0
            for (t in 0 until n) {
                val ang = -2.0 * PI * k * t / n
                expRe += sig[t] * cos(ang)
                expIm += sig[t] * sin(ang)
            }
            assertEquals("re[$k]", expRe.toFloat(), re[k], 1e-2f)
            assertEquals("im[$k]", expIm.toFloat(), im[k], 1e-2f)
        }
    }

    @Test
    fun `sine at exact bin peaks in that bin`() {
        val n = 1024
        val bin = 100
        val re = FloatArray(n) { i -> sin(2.0 * PI * bin * i / n).toFloat() }
        val im = FloatArray(n)
        Fft.fft(re, im)
        // A unit sine contributes n/2 magnitude at its bin
        val mag = kotlin.math.sqrt(re[bin] * re[bin] + im[bin] * im[bin])
        assertEquals(n / 2f, mag, 0.5f)
    }
}
