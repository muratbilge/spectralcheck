package com.spectralcheck.ui

import android.graphics.Bitmap
import com.spectralcheck.dsp.SpectrogramData

/** Renders STFT magnitude frames into a bitmap with a colormap. */
object SpectrogramBitmap {

    private const val MAX_WIDTH = 4096
    private const val MAX_HEIGHT = 1024

    /**
     * Picks the top of the dB color range: 0 dBFS for normal content, lower
     * for quiet material (or misbehaving decoders) so the image is never
     * uniformly black.
     */
    fun autoCeil(data: SpectrogramData): Float {
        val max = data.averageSpectrumDb().max()
        return if (max < -20f) (max + 10f).coerceIn(-60f, 0f) else 0f
    }

    fun render(
        data: SpectrogramData,
        palette: Array<FloatArray> = Palettes.INFERNO,
        dbCeil: Float = autoCeil(data),
        dbSpan: Float = 100f,
    ): Bitmap {
        val dbFloor = dbCeil - dbSpan
        val frames = data.frames
        val bins = data.binCount
        require(frames.isNotEmpty() && bins > 0) { "Empty spectrogram" }

        val width = frames.size.coerceAtMost(MAX_WIDTH)
        val height = bins.coerceAtMost(MAX_HEIGHT)
        val framesPerCol = frames.size.toFloat() / width
        val binsPerRow = bins.toFloat() / height

        val pixels = IntArray(width * height)
        for (x in 0 until width) {
            val f0 = (x * framesPerCol).toInt()
            val f1 = ((x + 1) * framesPerCol).toInt().coerceAtMost(frames.size).coerceAtLeast(f0 + 1)
            for (y in 0 until height) {
                // y = 0 is the top of the image = highest frequency
                val bTop = ((height - 1 - y) * binsPerRow).toInt()
                val bEnd = ((height - y) * binsPerRow).toInt().coerceAtMost(bins).coerceAtLeast(bTop + 1)
                // Max-pool over the covered cells so narrow peaks stay visible
                var v = dbFloor
                for (f in f0 until f1) {
                    val row = frames[f]
                    for (b in bTop until bEnd) {
                        if (row[b] > v) v = row[b]
                    }
                }
                val t = (v - dbFloor) / (dbCeil - dbFloor)
                pixels[y * width + x] = Palettes.color(palette, t)
            }
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
