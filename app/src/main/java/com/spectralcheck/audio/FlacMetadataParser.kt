package com.spectralcheck.audio

import java.io.IOException
import java.io.InputStream

data class TrackMetadata(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val date: String? = null,
    val genre: String? = null,
    val trackNumber: String? = null,
    /** Raw embedded cover art (JPEG/PNG bytes), or null. */
    val coverArt: ByteArray? = null,
) {
    val isEmpty: Boolean
        get() = title == null && artist == null && album == null && coverArt == null
}

/**
 * Reads Vorbis-comment tags (block type 4) and embedded cover art
 * (PICTURE, block type 6) from a FLAC file's metadata blocks.
 */
object FlacMetadataParser {

    private const val FRONT_COVER = 3

    fun parse(stream: InputStream): TrackMetadata {
        val magic = ByteArray(4)
        FlacHeaderParser.readFully(stream, magic)
        if (String(magic, Charsets.US_ASCII) != "fLaC") throw IOException("Not a FLAC file")

        var tags = emptyMap<String, String>()
        var picture: ByteArray? = null
        var pictureType = -1

        while (true) {
            val header = ByteArray(4)
            FlacHeaderParser.readFully(stream, header)
            val type = header[0].toInt() and 0x7F
            val length = ((header[1].toInt() and 0xFF) shl 16) or
                ((header[2].toInt() and 0xFF) shl 8) or
                (header[3].toInt() and 0xFF)

            when (type) {
                4 -> { // VORBIS_COMMENT
                    val body = ByteArray(length)
                    FlacHeaderParser.readFully(stream, body)
                    tags = parseVorbisComment(body)
                }
                6 -> { // PICTURE
                    val body = ByteArray(length)
                    FlacHeaderParser.readFully(stream, body)
                    val (picType, data) = parsePicture(body)
                    // Prefer the front cover if several pictures are embedded
                    if (picture == null || (picType == FRONT_COVER && pictureType != FRONT_COVER)) {
                        picture = data
                        pictureType = picType
                    }
                }
                else -> FlacHeaderParser.skipFully(stream, length.toLong())
            }
            if (header[0].toInt() and 0x80 != 0) break // last metadata block
        }

        return TrackMetadata(
            title = tags["TITLE"],
            artist = tags["ARTIST"] ?: tags["ALBUMARTIST"],
            album = tags["ALBUM"],
            date = tags["DATE"] ?: tags["YEAR"],
            genre = tags["GENRE"],
            trackNumber = tags["TRACKNUMBER"],
            coverArt = picture,
        )
    }

    /** Vorbis comments use little-endian lengths and KEY=value UTF-8 entries. */
    private fun parseVorbisComment(body: ByteArray): Map<String, String> {
        var pos = 0
        fun u32(): Int {
            val v = (body[pos].toInt() and 0xFF) or
                ((body[pos + 1].toInt() and 0xFF) shl 8) or
                ((body[pos + 2].toInt() and 0xFF) shl 16) or
                ((body[pos + 3].toInt() and 0xFF) shl 24)
            pos += 4
            return v
        }

        val out = HashMap<String, String>()
        try {
            // NB: don't write `pos += u32()` — the left operand is read
            // before u32() advances pos, silently dropping 4 bytes.
            val vendorLen = u32()
            pos += vendorLen
            val count = u32()
            repeat(count) {
                val len = u32()
                if (len < 0 || pos + len > body.size) return out
                val entry = String(body, pos, len, Charsets.UTF_8)
                pos += len
                val eq = entry.indexOf('=')
                if (eq > 0) out[entry.substring(0, eq).uppercase()] = entry.substring(eq + 1)
            }
        } catch (e: IndexOutOfBoundsException) {
            // Malformed block: keep whatever parsed cleanly
        }
        return out
    }

    /** PICTURE blocks use big-endian lengths. Returns (pictureType, imageBytes). */
    private fun parsePicture(body: ByteArray): Pair<Int, ByteArray?> {
        var pos = 0
        fun u32(): Int {
            val v = ((body[pos].toInt() and 0xFF) shl 24) or
                ((body[pos + 1].toInt() and 0xFF) shl 16) or
                ((body[pos + 2].toInt() and 0xFF) shl 8) or
                (body[pos + 3].toInt() and 0xFF)
            pos += 4
            return v
        }

        return try {
            val picType = u32()
            val mimeLen = u32()
            pos += mimeLen
            val descLen = u32()
            pos += descLen
            pos += 16 // width, height, depth, colors
            val dataLen = u32()
            if (dataLen <= 0 || pos + dataLen > body.size) return picType to null
            picType to body.copyOfRange(pos, pos + dataLen)
        } catch (e: IndexOutOfBoundsException) {
            -1 to null
        }
    }
}
