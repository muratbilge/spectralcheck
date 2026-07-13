package com.spectralcheck.dsp

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.min

/**
 * Short-time Fourier transform with a Hann window, producing magnitude
 * frames in dBFS (0 dB = full-scale sine). Frames are computed in parallel
 * across CPU cores; the per-bin average power is accumulated during the
 * same pass.
 */
class Stft(
    private val fftSize: Int = 4096,
    private val hopSize: Int = 2048,
) {
    private val window = FloatArray(fftSize) { i ->
        (0.5 - 0.5 * cos(2.0 * PI * i / (fftSize - 1))).toFloat()
    }

    // Coherent gain of the Hann window, used to normalize magnitudes.
    private val windowSum = window.sum()

    fun analyze(
        samples: FloatArray,
        sampleRate: Int,
        onProgress: ((Float) -> Unit)? = null,
    ): SpectrogramData {
        val bins = fftSize / 2 + 1
        val frameCount = if (samples.size < fftSize) 0 else (samples.size - fftSize) / hopSize + 1
        val frames = Array(frameCount) { FloatArray(bins) }
        val powerSum = DoubleArray(bins)

        if (frameCount > 0) {
            val nThreads = min(Runtime.getRuntime().availableProcessors(), 8)
                .coerceAtLeast(1)
                .coerceAtMost(frameCount)
            val norm = 2f / windowSum
            val done = AtomicInteger()
            val partials = Array(nThreads) { DoubleArray(bins) }

            val threads = (0 until nThreads).map { t ->
                Thread {
                    val fft = RealFft(fftSize)
                    val mags = FloatArray(bins)
                    val local = partials[t]
                    var f = t
                    while (f < frameCount) {
                        fft.magnitudes(samples, f * hopSize, window, mags)
                        val out = frames[f]
                        for (b in 0 until bins) {
                            val amp = mags[b] * norm
                            local[b] += (amp * amp).toDouble()
                            out[b] = 20f * log10(amp + 1e-10f)
                        }
                        val d = done.incrementAndGet()
                        if (onProgress != null && d and 63 == 0) {
                            onProgress(d.toFloat() / frameCount)
                        }
                        f += nThreads
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }
            for (p in partials) {
                for (b in 0 until bins) powerSum[b] += p[b]
            }
        }

        val avgDb = FloatArray(bins)
        val div = frameCount.coerceAtLeast(1)
        for (b in 0 until bins) {
            avgDb[b] = (10.0 * Math.log10(powerSum[b] / div + 1e-20)).toFloat()
        }
        return SpectrogramData(frames, sampleRate, fftSize, hopSize, avgDb)
    }
}
