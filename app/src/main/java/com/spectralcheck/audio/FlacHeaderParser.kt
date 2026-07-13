package com.spectralcheck.audio

import java.io.IOException
import java.io.InputStream

/**
 * Reads sample rate, channel count, bit depth and duration straight from the
 * FLAC STREAMINFO block. MediaFormat does not reliably expose bit depth, so
 * we parse the 34-byte header ourselves.
 */
object FlacHeaderParser {

    fun parse(stream: InputStream): AudioInfo {
        val magic = ByteArray(4)
        readFully(stream, magic)
        if (String(magic, Charsets.US_ASCII) != "fLaC") {
            throw IOException("Not a FLAC file")
        }

        // Metadata blocks: 1 header byte (last-flag + type) + 3 length bytes.
        while (true) {
            val header = ByteArray(4)
            readFully(stream, header)
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            if (type == 0) { // STREAMINFO
                val body = ByteArray(length)
                readFully(stream, body)
                // Bytes 10..17 pack: 20-bit sample rate, 3-bit channels-1,
                // 5-bit bps-1, 36-bit total samples.
                val b10 = body[10].toInt() and 0xFF
                val b11 = body[11].toInt() and 0xFF
                val b12 = body[12].toInt() and 0xFF
                val b13 = body[13].toInt() and 0xFF
                val sampleRate = (b10 shl 12) or (b11 shl 4) or (b12 shr 4)
                val channels = ((b12 shr 1) and 0x07) + 1
                val bitDepth = (((b12 and 0x01) shl 4) or (b13 shr 4)) + 1
                var totalSamples = (b13 and 0x0F).toLong()
                for (i in 14..17) {
                    totalSamples = (totalSamples shl 8) or (body[i].toLong() and 0xFF)
                }
                val durationMs = if (sampleRate > 0) totalSamples * 1000 / sampleRate else 0
                return AudioInfo(sampleRate, channels, bitDepth, durationMs)
            }

            skipFully(stream, length.toLong())
            if (header[0].toInt() and 0x80 != 0) break // last metadata block
        }
        throw IOException("FLAC STREAMINFO block not found")
    }

    internal fun readFully(stream: InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = stream.read(buf, off, buf.size - off)
            if (n < 0) throw IOException("Unexpected end of file")
            off += n
        }
    }

    internal fun skipFully(stream: InputStream, count: Long) {
        var remaining = count
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped <= 0) {
                if (stream.read() < 0) throw IOException("Unexpected end of file")
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }
}
