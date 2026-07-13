package com.spectralcheck.ui

/** Colormaps as (t, r, g, b) control points, interpolated linearly. */
object Palettes {

    val INFERNO = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 4f),
        floatArrayOf(0.25f, 87f, 16f, 110f),
        floatArrayOf(0.50f, 188f, 55f, 84f),
        floatArrayOf(0.75f, 249f, 142f, 9f),
        floatArrayOf(1.00f, 252f, 255f, 164f),
    )

    /** Approximation of the SoX spectrogram default palette. */
    val SOX = arrayOf(
        floatArrayOf(0.00f, 0f, 0f, 0f),
        floatArrayOf(0.10f, 10f, 0f, 60f),
        floatArrayOf(0.25f, 40f, 0f, 130f),
        floatArrayOf(0.42f, 120f, 0f, 180f),
        floatArrayOf(0.58f, 200f, 30f, 140f),
        floatArrayOf(0.72f, 255f, 90f, 60f),
        floatArrayOf(0.85f, 255f, 180f, 30f),
        floatArrayOf(1.00f, 255f, 255f, 200f),
    )

    fun color(stops: Array<FloatArray>, t: Float): Int {
        val x = t.coerceIn(0f, 1f)
        for (i in 1 until stops.size) {
            if (x <= stops[i][0]) {
                val lo = stops[i - 1]
                val hi = stops[i]
                val f = if (hi[0] > lo[0]) (x - lo[0]) / (hi[0] - lo[0]) else 0f
                val r = (lo[1] + (hi[1] - lo[1]) * f).toInt()
                val g = (lo[2] + (hi[2] - lo[2]) * f).toInt()
                val b = (lo[3] + (hi[3] - lo[3]) * f).toInt()
                return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return 0xFFFFFFFF.toInt()
    }
}
