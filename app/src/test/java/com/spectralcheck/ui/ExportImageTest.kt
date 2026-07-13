package com.spectralcheck.ui

import android.graphics.Bitmap
import com.spectralcheck.analysis.AnalysisResult
import com.spectralcheck.analysis.CutoffDetector
import com.spectralcheck.analysis.VerdictEngine
import com.spectralcheck.audio.AudioInfo
import com.spectralcheck.dsp.Stft
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders the SoX-style export image with real Android graphics (Robolectric
 * native graphics) from the ffmpeg fixtures, and writes the PNGs next to the
 * fixtures for visual inspection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ExportImageTest {

    private val dir = System.getenv("SPECTRALCHECK_AUDIO_DIR")

    private fun loadPcm(name: String): FloatArray {
        val bytes = File(dir, name).readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(fb.remaining()).also { fb.get(it) }
    }

    private fun renderFor(pcm: String, fileName: String): Bitmap {
        val spectrogram = Stft().analyze(loadPcm(pcm), 44100)
        val cutoff = CutoffDetector.detect(spectrogram)
        val stability = CutoffDetector.stability(spectrogram)
        val verdict = VerdictEngine.judge(
            com.spectralcheck.analysis.AnalysisSignals(cutoff, 44100, stability)
        )
        val result = AnalysisResult(
            fileName,
            AudioInfo(sampleRate = 44100, channels = 2, bitDepth = 16, durationMs = 15_000),
            verdict,
            spectrogram,
        )
        return SpectrogramExporter.render(result)
    }

    @Test
    fun `renders annotated images from fixtures`() {
        assumeTrue(dir != null)
        for ((pcm, name) in listOf(
            "real.pcm" to "real.flac",
            "fake128.pcm" to "fake128.flac",
            "fake320.pcm" to "fake320.flac",
        )) {
            val bmp = renderFor(pcm, name)
            assertTrue(bmp.width > 1500 && bmp.height > 800)
            val out = File(dir, "export-${name.removeSuffix(".flac")}.png")
            FileOutputStream(out).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            assertTrue(out.length() > 10_000)
        }
    }
}
