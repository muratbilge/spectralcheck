package com.spectralcheck.analysis

enum class Verdict {
    AUTHENTIC,
    SUSPICIOUS,
    TRANSCODE,
    INCONCLUSIVE,
}

enum class Signal { LOSSY, LOSSLESS, NEUTRAL }

/** One independent observation shown to the user in the Evidence list. */
data class Evidence(val title: String, val detail: String, val signal: Signal)

/** Everything the engine can weigh; only [cutoff] and [sampleRate] are required. */
data class AnalysisSignals(
    val cutoff: CutoffResult,
    val sampleRate: Int,
    val stability: CutoffStability? = null,
    /** Cutoff of the side (L-R) channel, or null when mono/silent/unknown. */
    val sideCutoff: CutoffResult? = null,
    val headerBitDepth: Int = 16,
    /** From [BitDepth.effectiveBits], or null when not measurable. */
    val effectiveBits: Int? = null,
)

data class VerdictResult(
    val verdict: Verdict,
    /** Estimated lossy source, e.g. "MP3 ~128 kbps", or null. */
    val estimatedSource: String?,
    val explanation: String,
    val cutoff: CutoffResult,
    val evidence: List<Evidence> = emptyList(),
)

/**
 * Combines independent spectral signals into a lossless-authenticity verdict.
 * Lossy encoders low-pass the signal at a bitrate-dependent frequency and
 * leave a near-silent band up to Nyquist; true lossless audio either keeps
 * energy up to Nyquist or rolls off gradually with the content.
 */
object VerdictEngine {

    private fun severity(v: Verdict) = when (v) {
        Verdict.AUTHENTIC -> 0
        Verdict.INCONCLUSIVE -> 1
        Verdict.SUSPICIOUS -> 2
        Verdict.TRANSCODE -> 3
    }

    private fun atLeast(v: Verdict, floor: Verdict) =
        if (severity(v) < severity(floor)) floor else v

    fun judge(cutoff: CutoffResult, sampleRate: Int): VerdictResult =
        judge(AnalysisSignals(cutoff, sampleRate))

    fun judge(s: AnalysisSignals): VerdictResult {
        val cutoff = s.cutoff
        val khz = cutoff.cutoffHz / 1000f

        if (cutoff.lowEnergy) {
            return VerdictResult(
                Verdict.INCONCLUSIVE, null,
                "The track is too quiet for a reliable spectral analysis.",
                cutoff,
            )
        }

        val evidence = mutableListOf<Evidence>()
        var verdict: Verdict
        var source: String?
        val explanation: String

        // --- Primary signal: where the energy stops and how abruptly ---
        if (!cutoff.sharpShelf) {
            verdict = Verdict.AUTHENTIC
            source = null
            explanation = "Energy extends to %.1f kHz with no lossy-style brick-wall cutoff.".format(khz)
            evidence += Evidence(
                "No brick-wall cutoff",
                "Spectrum reaches %.1f kHz and rolls off gradually (%.0f Hz wide)."
                    .format(khz, cutoff.rolloffWidthHz),
                Signal.LOSSLESS,
            )
        } else {
            when {
                cutoff.cutoffHz < 15500f -> {
                    verdict = Verdict.TRANSCODE
                    source = "MP3 ~96–128 kbps"
                    explanation = "Sharp brick-wall cutoff at %.1f kHz — typical of a low-bitrate lossy source.".format(khz)
                }
                cutoff.cutoffHz < 17000f -> {
                    verdict = Verdict.TRANSCODE
                    source = "MP3 ~128–192 kbps / AAC"
                    explanation = "Sharp brick-wall cutoff at %.1f kHz — typical of a mid-bitrate lossy source.".format(khz)
                }
                cutoff.cutoffHz < 20500f -> {
                    verdict = Verdict.SUSPICIOUS
                    source = "MP3 ~256–320 kbps"
                    explanation = "Sharp cutoff at %.1f kHz with silence above — consistent with a high-bitrate lossy source.".format(khz)
                }
                else -> {
                    verdict = Verdict.AUTHENTIC
                    source = null
                    explanation = "Cutoff at %.1f kHz is at/above the range lossy encoders use; likely genuine.".format(khz)
                }
            }
            evidence += Evidence(
                "Brick-wall cutoff at %.1f kHz".format(khz),
                "Energy collapses by 30 dB within %.0f Hz and stays %.0f dB below the mid band."
                    .format(cutoff.rolloffWidthHz, cutoff.plateauDb - cutoff.aboveCutoffDb),
                if (verdict == Verdict.AUTHENTIC) Signal.NEUTRAL else Signal.LOSSY,
            )
        }

        // --- Cutoff stability: encoder lowpass is constant, music is not ---
        val stability = s.stability
        if (cutoff.sharpShelf && stability != null) {
            if (stability.iqrHz < 1000f) {
                evidence += Evidence(
                    "Cutoff constant over time",
                    "Per-frame cutoff stays within ±%.0f Hz across %d frames — a fixed encoder lowpass."
                        .format(stability.iqrHz / 2, stability.frames),
                    Signal.LOSSY,
                )
            } else if (stability.iqrHz > 3000f) {
                evidence += Evidence(
                    "Bandwidth follows the music",
                    "Per-frame cutoff varies by %.1f kHz — natural content, not an encoder lowpass."
                        .format(stability.iqrHz / 1000f),
                    Signal.LOSSLESS,
                )
                if (verdict == Verdict.SUSPICIOUS) verdict = Verdict.INCONCLUSIVE
            }
        }

        // --- Joint stereo: lossy encoders cut the side channel lower ---
        val side = s.sideCutoff
        if (side != null && !side.lowEnergy && side.sharpShelf &&
            side.cutoffHz < cutoff.cutoffHz - 1500f
        ) {
            evidence += Evidence(
                "Side channel cut at %.1f kHz".format(side.cutoffHz / 1000f),
                "The stereo difference signal is band-limited well below the mid channel — a joint-stereo lossy artifact.",
                Signal.LOSSY,
            )
            verdict = atLeast(verdict, Verdict.SUSPICIOUS)
            if (source == null) source = "lossy (joint stereo)"
        }

        // --- Fake hi-res: upsampled sample rate ---
        if (s.sampleRate >= 60000 && cutoff.sharpShelf && cutoff.cutoffHz < 24500f) {
            evidence += Evidence(
                "Upsampled hi-res",
                "A %d Hz file whose content stops at %.1f kHz was upsampled from 44.1/48 kHz."
                    .format(s.sampleRate, khz),
                Signal.LOSSY,
            )
            verdict = atLeast(verdict, Verdict.SUSPICIOUS)
            if (source == null) source = "44.1/48 kHz upsample"
        }

        // --- Fake hi-res: bit-depth padding ---
        if (s.headerBitDepth > 16) {
            when (s.effectiveBits) {
                null -> evidence += Evidence(
                    "Bit depth not verified",
                    "The decoder returned 16-bit PCM, so the %d-bit content could not be checked."
                        .format(s.headerBitDepth),
                    Signal.NEUTRAL,
                )
                16 -> {
                    evidence += Evidence(
                        "Fake %d-bit".format(s.headerBitDepth),
                        "Every sample sits on the 16-bit grid — the container was padded from a 16-bit source.",
                        Signal.LOSSY,
                    )
                    verdict = atLeast(verdict, Verdict.SUSPICIOUS)
                    if (source == null) source = "16-bit source padded to ${s.headerBitDepth}-bit"
                }
                else -> evidence += Evidence(
                    "True %d-bit content".format(s.headerBitDepth),
                    "Samples use the full %d-bit resolution.".format(s.headerBitDepth),
                    Signal.LOSSLESS,
                )
            }
        }

        return VerdictResult(verdict, source, explanation, cutoff, evidence)
    }
}
