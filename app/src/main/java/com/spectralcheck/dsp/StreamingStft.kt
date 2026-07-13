package com.spectralcheck.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min

/**
 * Picks an STFT hop so a track of [totalSamples] produces at most [maxFrames]
 * frames — long hi-res files would otherwise need hundreds of MB of frame
 * data. The hop stays a multiple of [baseHop].
 */
fun chooseHop(totalSamples: Long, fftSize: Int, baseHop: Int, maxFrames: Int = 6000): Int {
    if (totalSamples <= fftSize) return baseHop
    val framesAtBase = (totalSamples - fftSize) / baseHop + 1
    val k = ((framesAtBase + maxFrames - 1) / maxFrames).coerceAtLeast(1)
    return baseHop * k.toInt()
}

/**
 * Incremental STFT: [feed] it decoded chunks as they arrive, then [finish].
 * Unlike [Stft.analyze] this never needs the whole track in memory — peak
 * usage is the frame grid plus a small carry buffer, so multi-hundred-MB
 * hi-res tracks analyze in bounded RAM.
 */
class StreamingStft(
    private val fftSize: Int = 4096,
    private val hopSize: Int = 2048,
) {
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }
    private val norm = 2f / window.sum()
    private val bins = fftSize / 2 + 1
    private val fft = RealFft(fftSize)
    private val mags = FloatArray(bins)

    private val buf = FloatArray(fftSize * 4)
    private var buffered = 0
    private var skip = 0 // samples to discard when the hop exceeds the buffer
    private val frames = ArrayList<FloatArray>()
    private val powerSum = DoubleArray(bins)

    fun feed(chunk: FloatArray, length: Int = chunk.size) {
        var off = 0
        while (off < length) {
            if (skip > 0) {
                val d = min(skip, length - off)
                off += d
                skip -= d
                continue
            }
            val n = min(length - off, buf.size - buffered)
            System.arraycopy(chunk, off, buf, buffered, n)
            buffered += n
            off += n

            var start = 0
            while (buffered - start >= fftSize) {
                process(start)
                start += hopSize
            }
            if (start > 0) {
                if (start >= buffered) {
                    skip = start - buffered
                    buffered = 0
                } else {
                    System.arraycopy(buf, start, buf, 0, buffered - start)
                    buffered -= start
                }
            }
        }
    }

    private fun process(start: Int) {
        fft.magnitudes(buf, start, window, mags)
        val out = FloatArray(bins)
        for (b in 0 until bins) {
            val amp = mags[b] * norm
            powerSum[b] += (amp * amp).toDouble()
            out[b] = 20f * log10(amp + 1e-10f)
        }
        frames.add(out)
    }

    fun finish(sampleRate: Int): SpectrogramData {
        val avgDb = FloatArray(bins)
        val div = frames.size.coerceAtLeast(1)
        for (b in 0 until bins) {
            avgDb[b] = (10.0 * Math.log10(powerSum[b] / div + 1e-20)).toFloat()
        }
        return SpectrogramData(frames.toTypedArray(), sampleRate, fftSize, hopSize, avgDb)
    }
}
