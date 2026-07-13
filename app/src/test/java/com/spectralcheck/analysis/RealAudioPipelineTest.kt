package com.spectralcheck.analysis

import com.spectralcheck.audio.FlacHeaderParser
import com.spectralcheck.dsp.Stft
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Runs the analysis pipeline on real encoded audio. Needs fixtures produced
 * by ffmpeg (see scripts in the repo README); skipped unless the env var
 * SPECTRALCHECK_AUDIO_DIR points at a directory containing:
 *   real.pcm / fake128.pcm / fake320.pcm  (f32le mono 44.1 kHz)
 *   real.flac                             (16-bit stereo FLAC)
 */
class RealAudioPipelineTest {

    private val dir = System.getenv("SPECTRALCHECK_AUDIO_DIR")

    private fun loadPcm(name: String): FloatArray {
        val bytes = File(dir, name).readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }

    private fun judge(name: String): VerdictResult {
        val spec = Stft().analyze(loadPcm(name), 44100)
        val cutoff = CutoffDetector.detect(spec)
        val v = VerdictEngine.judge(cutoff, 44100)
        println(
            "%s -> %s cutoff=%.0f Hz rolloff=%.0f Hz sharp=%b plateau=%.1f dB above=%.1f dB".format(
                name, v.verdict, cutoff.cutoffHz, cutoff.rolloffWidthHz,
                cutoff.sharpShelf, cutoff.plateauDb, cutoff.aboveCutoffDb,
            )
        )
        return v
    }

    @Test
    fun `genuine flac source is authentic`() {
        assumeTrue(dir != null)
        assertEquals(Verdict.AUTHENTIC, judge("real.pcm").verdict)
    }

    @Test
    fun `mp3 128k transcode is flagged`() {
        assumeTrue(dir != null)
        val v = judge("fake128.pcm")
        assertTrue("was ${v.verdict}", v.verdict == Verdict.TRANSCODE || v.verdict == Verdict.SUSPICIOUS)
    }

    @Test
    fun `mp3 320k transcode is flagged`() {
        assumeTrue(dir != null)
        val v = judge("fake320.pcm")
        assertTrue("was ${v.verdict}", v.verdict == Verdict.TRANSCODE || v.verdict == Verdict.SUSPICIOUS)
    }

    @Test
    fun `padded 24-bit fixture reads as 16-bit content`() {
        assumeTrue(dir != null)
        val f = File(dir, "fake24.pcm")
        assumeTrue(f.exists())
        val bytes = f.readBytes()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val samples = FloatArray(fb.remaining()).also { fb.get(it) }
        assertEquals(16, BitDepth.effectiveBits(samples, 24))
    }

    @Test
    fun `flac header parses stream info`() {
        assumeTrue(dir != null)
        val info = File(dir, "real.flac").inputStream().use { FlacHeaderParser.parse(it) }
        assertEquals(44100, info.sampleRate)
        assertEquals(2, info.channels)
        assertEquals(16, info.bitDepth)
        assertEquals(15_000.0, info.durationMs.toDouble(), 200.0)
    }
}
