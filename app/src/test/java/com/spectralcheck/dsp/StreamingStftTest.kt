package com.spectralcheck.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StreamingStftTest {

    private fun feedInChunks(streaming: StreamingStft, samples: FloatArray, seed: Long) {
        // Irregular chunk sizes, like MediaCodec output buffers
        val rnd = Random(seed)
        var off = 0
        while (off < samples.size) {
            val n = minOf(rnd.nextInt(1, 9000), samples.size - off)
            streaming.feed(samples.copyOfRange(off, off + n))
            off += n
        }
    }

    private fun assertMatchesBatch(fftSize: Int, hop: Int) {
        val rnd = Random(3)
        val samples = FloatArray(200_000) { rnd.nextFloat() * 2f - 1f }

        val batch = Stft(fftSize, hop).analyze(samples, 44100)
        val streaming = StreamingStft(fftSize, hop)
        feedInChunks(streaming, samples, seed = 4)
        val result = streaming.finish(44100)

        assertEquals(batch.frames.size, result.frames.size)
        for (f in batch.frames.indices) {
            for (b in 0 until batch.binCount) {
                assertEquals("frame $f bin $b", batch.frames[f][b], result.frames[f][b], 1e-3f)
            }
        }
        val ba = batch.averageSpectrumDb()
        val sa = result.averageSpectrumDb()
        for (b in ba.indices) assertEquals("avg bin $b", ba[b], sa[b], 1e-3f)
    }

    @Test
    fun `matches batch STFT with normal hop`() = assertMatchesBatch(4096, 2048)

    @Test
    fun `matches batch STFT when hop exceeds fft size`() = assertMatchesBatch(4096, 8192)

    @Test
    fun `chooseHop keeps frame count bounded`() {
        // Short track: base hop untouched
        assertEquals(2048, chooseHop(44100L * 240, 4096, 2048))
        // 6-minute 96 kHz track: hop widens, frames stay under the cap
        val samples = 96000L * 360
        val hop = chooseHop(samples, 4096, 2048)
        assertTrue(hop % 2048 == 0)
        val frames = (samples - 4096) / hop + 1
        assertTrue("frames=$frames", frames <= 6000)
        // Hop never widens more than needed (at least half the cap used)
        assertTrue("frames=$frames", frames > 2048)
        // Degenerate inputs fall back to the base hop
        assertEquals(2048, chooseHop(0, 4096, 2048))
    }
}
