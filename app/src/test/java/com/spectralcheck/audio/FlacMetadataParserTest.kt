package com.spectralcheck.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Parses the ffmpeg-tagged fixture (see README); skipped without fixtures. */
class FlacMetadataParserTest {

    private val dir = System.getenv("SPECTRALCHECK_AUDIO_DIR")

    @Test
    fun `reads vorbis tags and embedded cover`() {
        assumeTrue(dir != null && File(dir, "tagged.flac").exists())
        val meta = File(dir, "tagged.flac").inputStream().use { FlacMetadataParser.parse(it) }

        assertEquals("Test Song", meta.title)
        assertEquals("Test Artist", meta.artist)
        assertEquals("Test Album", meta.album)
        assertEquals("2024", meta.date)
        assertEquals("Electronic", meta.genre)
        assertEquals("7", meta.trackNumber)

        val cover = meta.coverArt
        assertNotNull(cover)
        // PNG magic bytes
        assertEquals(0x89.toByte(), cover!![0])
        assertEquals('P'.code.toByte(), cover[1])
        assertTrue(cover.size > 500)
    }

    @Test
    fun `untagged file yields empty metadata`() {
        assumeTrue(dir != null)
        val meta = File(dir, "real.flac").inputStream().use { FlacMetadataParser.parse(it) }
        assertEquals(null, meta.title)
        assertEquals(null, meta.coverArt)
    }
}
