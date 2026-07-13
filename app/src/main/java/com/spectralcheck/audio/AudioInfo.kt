package com.spectralcheck.audio

data class AudioInfo(
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val durationMs: Long,
    /** What the codec actually emitted, e.g. "float PCM, peak -3.1 dB". */
    val decoderOutput: String? = null,
)

class DecodedAudio(
    /** Mono, downmixed, in [-1, 1]. */
    val samples: FloatArray,
    /** Individual channels, only when decoded with keepStereo on a 2ch file. */
    val left: FloatArray?,
    val right: FloatArray?,
    val info: AudioInfo,
    /** True when the codec produced float PCM (preserves >16-bit resolution). */
    val floatOutput: Boolean,
)
