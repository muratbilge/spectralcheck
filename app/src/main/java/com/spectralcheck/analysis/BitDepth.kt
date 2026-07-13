package com.spectralcheck.analysis

import kotlin.math.abs
import kotlin.math.roundToInt

object BitDepth {

    /**
     * Checks whether float samples from a >16-bit file actually sit on the
     * 16-bit quantization grid, which means the "hi-res" file was padded
     * from a 16-bit source. Returns 16 for padded content, [headerBits]
     * for genuine hi-res content, or null when there is too little signal
     * to judge.
     */
    fun effectiveBits(samples: FloatArray, headerBits: Int): Int? {
        var checked = 0
        val step = (samples.size / 500_000).coerceAtLeast(1)
        var i = 0
        while (i < samples.size) {
            val x = samples[i]
            if (x != 0f && abs(x) <= 1f) {
                val v = x * 32768f
                if (abs(v - v.roundToInt()) > 1e-3f) return headerBits
                checked++
            }
            i += step
        }
        return if (checked >= 1000) 16 else null
    }
}
