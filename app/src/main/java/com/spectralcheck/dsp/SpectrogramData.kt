package com.spectralcheck.dsp

/**
 * STFT result: [frames] is indexed [frameIndex][binIndex] and holds
 * magnitudes in dBFS. Bin i corresponds to frequency i * [freqPerBin] Hz.
 */
class SpectrogramData(
    val frames: Array<FloatArray>,
    val sampleRate: Int,
    val fftSize: Int,
    val hopSize: Int,
    /** Per-bin average power in dB, accumulated by [Stft] during analysis. */
    private val precomputedAverageDb: FloatArray? = null,
) {
    val binCount: Int get() = if (frames.isEmpty()) 0 else frames[0].size
    val freqPerBin: Float get() = sampleRate.toFloat() / fftSize
    val frameDurationSec: Float get() = hopSize.toFloat() / sampleRate

    /** Per-bin average of linear power across all frames, returned in dB. */
    fun averageSpectrumDb(): FloatArray {
        precomputedAverageDb?.let { return it }
        val bins = binCount
        val avg = DoubleArray(bins)
        for (frame in frames) {
            for (b in 0 until bins) {
                avg[b] += Math.pow(10.0, frame[b] / 10.0)
            }
        }
        val out = FloatArray(bins)
        val n = frames.size.coerceAtLeast(1)
        for (b in 0 until bins) {
            out[b] = (10.0 * Math.log10(avg[b] / n + 1e-20)).toFloat()
        }
        return out
    }
}
