package com.spectralcheck.analysis

import com.spectralcheck.dsp.SpectrogramData

data class CutoffResult(
    /** Highest frequency with significant energy, in Hz. */
    val cutoffHz: Float,
    /** True when the rolloff is a lossy-encoder-style brick wall. */
    val sharpShelf: Boolean,
    /** Mid-band reference level in dB. */
    val plateauDb: Float,
    /** Level just above the cutoff in dB (energy in the "dead" zone). */
    val aboveCutoffDb: Float,
    /** Width in Hz over which the spectrum drops 30 dB at the cutoff. */
    val rolloffWidthHz: Float,
    /** True when the signal was too quiet to judge. */
    val lowEnergy: Boolean,
)

data class CutoffStability(
    val medianHz: Float,
    /** Interquartile range of per-frame cutoffs — small = encoder lowpass. */
    val iqrHz: Float,
    val frames: Int,
)

/**
 * Finds the effective bandwidth of a track from its time-averaged spectrum
 * and characterizes how sharply the energy rolls off at that edge.
 */
object CutoffDetector {

    private const val DROP_DB = 25f       // "significant energy" = plateau - 25 dB
    private const val SHARP_WIDTH_HZ = 1500f
    private const val SILENCE_DB = -75f

    fun detect(spectrogram: SpectrogramData): CutoffResult {
        val spectrum = spectrogram.averageSpectrumDb()
        val freqPerBin = spectrogram.freqPerBin
        return detect(spectrum, freqPerBin)
    }

    /**
     * How much the cutoff frequency moves from frame to frame. An encoder
     * lowpass sits at exactly the same frequency for the whole track, while
     * the bandwidth of natural music follows the arrangement.
     */
    fun stability(spectrogram: SpectrogramData): CutoffStability? {
        val freqPerBin = spectrogram.freqPerBin
        val frames = spectrogram.frames
        // ~600 sampled frames characterize the whole track just as well
        val stride = (frames.size / 600).coerceAtLeast(1)
        val cutoffs = ArrayList<Float>(frames.size / stride + 1)
        var f = 0
        while (f < frames.size) {
            val r = detect(frames[f], freqPerBin)
            if (!r.lowEnergy) cutoffs.add(r.cutoffHz)
            f += stride
        }
        if (cutoffs.size < 8) return null
        cutoffs.sort()
        fun q(p: Double) = cutoffs[((cutoffs.size - 1) * p).toInt()]
        return CutoffStability(q(0.5), q(0.75) - q(0.25), cutoffs.size)
    }

    fun detect(spectrumDb: FloatArray, freqPerBin: Float): CutoffResult {
        val bins = spectrumDb.size
        val nyquist = (bins - 1) * freqPerBin

        // Reference level: median over the 1-8 kHz mid band, where almost all
        // music has energy regardless of encoding.
        val midLo = (1000f / freqPerBin).toInt().coerceAtMost(bins - 1)
        val midHi = (8000f / freqPerBin).toInt().coerceAtMost(bins - 1)
        val mid = spectrumDb.copyOfRange(midLo, (midHi + 1).coerceAtLeast(midLo + 1))
        mid.sort()
        val plateau = mid[mid.size / 2]

        if (plateau < SILENCE_DB) {
            return CutoffResult(0f, false, plateau, plateau, 0f, lowEnergy = true)
        }

        val threshold = plateau - DROP_DB

        // Scan down from Nyquist for the last bin above threshold. Require a
        // small run of consecutive loud bins so single noise spikes don't count.
        var cutoffBin = midHi
        var run = 0
        for (b in bins - 1 downTo midLo) {
            if (spectrumDb[b] > threshold) run++ else run = 0
            if (run >= 3) {
                cutoffBin = b + run - 1
                break
            }
        }
        val cutoffHz = cutoffBin * freqPerBin

        // Rolloff width: distance from (plateau - 10 dB) to (plateau - 40 dB)
        // around the cutoff. Lossy encoders drop off within a few hundred Hz.
        var hiBin = cutoffBin
        while (hiBin < bins - 1 && spectrumDb[hiBin] > plateau - 40f) hiBin++
        var loBin = cutoffBin
        while (loBin > midLo && spectrumDb[loBin] < plateau - 10f) loBin--
        val rolloffWidthHz = (hiBin - loBin) * freqPerBin

        // Mean level in the zone above the cutoff (skip the transition band).
        val deadStart = (hiBin + 2).coerceAtMost(bins - 1)
        var aboveCutoff = plateau
        if (deadStart < bins - 2) {
            var sum = 0f
            for (b in deadStart until bins) sum += spectrumDb[b]
            aboveCutoff = sum / (bins - deadStart)
        }

        val nearNyquist = cutoffHz > nyquist * 0.95f
        val sharp = !nearNyquist &&
            rolloffWidthHz < SHARP_WIDTH_HZ &&
            aboveCutoff < plateau - 45f

        return CutoffResult(
            cutoffHz = cutoffHz,
            sharpShelf = sharp,
            plateauDb = plateau,
            aboveCutoffDb = aboveCutoff,
            rolloffWidthHz = rolloffWidthHz,
            lowEnergy = false,
        )
    }
}
