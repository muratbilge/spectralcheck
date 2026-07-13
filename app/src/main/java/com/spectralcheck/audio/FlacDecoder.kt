package com.spectralcheck.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder

/**
 * Decodes FLAC (or any audio Android can extract) to mono float samples
 * using MediaExtractor + MediaCodec.
 */
class FlacDecoder(private val context: Context) {

    /**
     * @param startUs start position in microseconds, or 0 for the beginning
     * @param maxDurationUs stop after this much audio, or Long.MAX_VALUE
     * @param keepStereo also return the individual L/R channels (2ch files)
     * @param onProgress called with decode progress in [0, 1]
     * @param onMonoChunk when set, mono chunks are streamed to this callback
     *   as they decode and NOT accumulated — the returned [DecodedAudio] has
     *   empty samples. Keeps memory bounded for long hi-res tracks.
     */
    fun decode(
        uri: Uri,
        startUs: Long = 0,
        maxDurationUs: Long = Long.MAX_VALUE,
        keepStereo: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
        onMonoChunk: ((FloatArray) -> Unit)? = null,
    ): DecodedAudio {
        val headerInfo = runCatching {
            context.contentResolver.openInputStream(uri)?.use { FlacHeaderParser.parse(it) }
        }.getOrNull()

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var trackIndex = -1
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                if (f.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true) {
                    trackIndex = i
                    break
                }
            }
            if (trackIndex < 0) throw IOException("No audio track found")

            extractor.selectTrack(trackIndex)
            if (startUs > 0) extractor.seekTo(startUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!

            // For >16-bit files ask for float output so the bit-depth check
            // can see the real resolution; fall back to the default config if
            // the codec refuses to configure OR start with float output.
            var codec = MediaCodec.createDecoderByType(mime)
            var started = false
            if ((headerInfo?.bitDepth ?: 16) > 16) {
                try {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                    codec.configure(format, null, null, 0)
                    codec.start()
                    started = true
                } catch (e: Exception) {
                    runCatching { codec.release() }
                    codec = MediaCodec.createDecoderByType(mime)
                }
            }
            if (!started) {
                codec.configure(extractor.getTrackFormat(trackIndex), null, null, 0)
                codec.start()
            }

            try {
                return drainCodec(codec, extractor, extractor.getTrackFormat(trackIndex), headerInfo, maxDurationUs, keepStereo, startUs, onProgress, onMonoChunk)
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun drainCodec(
        codec: MediaCodec,
        extractor: MediaExtractor,
        inputFormat: MediaFormat,
        headerInfo: AudioInfo?,
        maxDurationUs: Long,
        keepStereo: Boolean,
        startUs: Long,
        onProgress: ((Float) -> Unit)?,
        onMonoChunk: ((FloatArray) -> Unit)?,
    ): DecodedAudio {
        val remainingUs = (headerInfo?.durationMs?.times(1000) ?: Long.MAX_VALUE) - startUs
        val totalUs = minOf(maxDurationUs, remainingUs).coerceAtLeast(1)
        val bufferInfo = MediaCodec.BufferInfo()
        var inputDone = false
        var outputDone = false
        val monoChunks = ArrayList<FloatArray>()
        val leftChunks = ArrayList<FloatArray>()
        val rightChunks = ArrayList<FloatArray>()
        var totalSamples = 0L
        var sampleRate = inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        var channels = inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        var pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
        var encodingVerified = false
        var encodingCorrected = false
        var peak = 0f
        var firstPtsUs = -1L

        while (!outputDone) {
            if (!inputDone) {
                val inIndex = codec.dequeueInputBuffer(10_000)
                if (inIndex >= 0) {
                    val buf = codec.getInputBuffer(inIndex)!!
                    val size = extractor.readSampleData(buf, 0)
                    if (size < 0) {
                        codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        val ptsUs = extractor.sampleTime
                        if (firstPtsUs < 0) firstPtsUs = ptsUs
                        if (ptsUs - firstPtsUs > maxDurationUs) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, ptsUs, 0)
                            extractor.advance()
                            if (onProgress != null && totalUs < Long.MAX_VALUE) {
                                onProgress(((ptsUs - firstPtsUs).toFloat() / totalUs).coerceIn(0f, 1f))
                            }
                        }
                    }
                }
            }

            val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
            when {
                outIndex >= 0 -> {
                    val outBuf = codec.getOutputBuffer(outIndex)!!
                    if (bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        outBuf.order(ByteOrder.nativeOrder())

                        // Some codecs echo "float" in the output format while
                        // actually emitting 16-bit data; reading shorts as
                        // float bit patterns yields near-zero garbage. Once a
                        // buffer has real energy, verify which interpretation
                        // is plausible and lock it in.
                        if (!encodingVerified && pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = outBuf.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
                            var pf = 0f
                            while (fb.hasRemaining()) {
                                val v = Math.abs(fb.get())
                                if (v.isFinite() && v > pf) pf = v
                            }
                            val sb = outBuf.duplicate().order(ByteOrder.nativeOrder()).asShortBuffer()
                            var ps = 0f
                            while (sb.hasRemaining()) {
                                val v = Math.abs(sb.get() / 32768f)
                                if (v > ps) ps = v
                            }
                            if (pf > 1e-6f || ps > 1e-4f) {
                                if (pf < 1e-6f || pf > 100f) {
                                    pcmEncoding = AudioFormat.ENCODING_PCM_16BIT
                                    encodingCorrected = true
                                }
                                encodingVerified = true
                            }
                        }

                        val interleaved = readPcm(outBuf, pcmEncoding)

                        for (v in interleaved) {
                            val a = Math.abs(v)
                            if (a > peak) peak = a
                        }

                        val frames = interleaved.size / channels.coerceAtLeast(1)
                        val mono = FloatArray(frames)
                        if (channels <= 1) {
                            interleaved.copyInto(mono, 0, 0, frames)
                        } else {
                            for (i in 0 until frames) {
                                var sum = 0f
                                for (ch in 0 until channels) sum += interleaved[i * channels + ch]
                                mono[i] = sum / channels
                            }
                        }
                        if (onMonoChunk != null) onMonoChunk(mono) else monoChunks.add(mono)
                        totalSamples += frames

                        if (keepStereo && channels == 2) {
                            val l = FloatArray(frames)
                            val r = FloatArray(frames)
                            for (i in 0 until frames) {
                                l[i] = interleaved[i * 2]
                                r[i] = interleaved[i * 2 + 1]
                            }
                            leftChunks.add(l)
                            rightChunks.add(r)
                        }
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                }
                outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = codec.outputFormat
                    sampleRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    if (f.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = f.getInteger(MediaFormat.KEY_PCM_ENCODING)
                    }
                }
            }
        }

        fun concat(chunks: List<FloatArray>): FloatArray {
            val out = FloatArray(chunks.sumOf { it.size })
            var off = 0
            for (c in chunks) {
                c.copyInto(out, off)
                off += c.size
            }
            return out
        }

        val durationMs = headerInfo?.durationMs
            ?: if (inputFormat.containsKey(MediaFormat.KEY_DURATION)) {
                inputFormat.getLong(MediaFormat.KEY_DURATION) / 1000
            } else {
                totalSamples * 1000 / sampleRate
            }

        val peakDb = if (peak > 0f) 20.0 * Math.log10(peak.toDouble()) else Double.NEGATIVE_INFINITY
        val decoderOutput = buildString {
            append(pcmName(pcmEncoding))
            if (encodingCorrected) append(" (corrected from float)")
            append(if (peakDb.isFinite()) ", peak %.1f dB".format(peakDb) else ", silent")
        }

        val info = AudioInfo(
            sampleRate = sampleRate,
            channels = headerInfo?.channels ?: channels,
            bitDepth = headerInfo?.bitDepth ?: 16,
            durationMs = durationMs,
            decoderOutput = decoderOutput,
        )
        return DecodedAudio(
            samples = concat(monoChunks),
            left = leftChunks.takeIf { it.isNotEmpty() }?.let { concat(it) },
            right = rightChunks.takeIf { it.isNotEmpty() }?.let { concat(it) },
            info = info,
            floatOutput = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT,
        )
    }

    /** Reads an output buffer as normalized floats for any PCM encoding. */
    private fun readPcm(buf: java.nio.ByteBuffer, encoding: Int): FloatArray {
        return when (encoding) {
            AudioFormat.ENCODING_PCM_FLOAT -> {
                val fb = buf.asFloatBuffer()
                FloatArray(fb.remaining()).also { fb.get(it) }
            }
            AudioFormat.ENCODING_PCM_32BIT -> {
                val ib = buf.asIntBuffer()
                val scale = 1f / 2147483648f
                FloatArray(ib.remaining()) { ib.get() * scale }
            }
            AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                val n = buf.remaining() / 3
                val out = FloatArray(n)
                val scale = 1f / 8388608f
                for (i in 0 until n) {
                    val b0 = buf.get().toInt() and 0xFF
                    val b1 = buf.get().toInt() and 0xFF
                    val b2 = buf.get().toInt() // sign byte
                    out[i] = ((b2 shl 16) or (b1 shl 8) or b0) * scale
                }
                out
            }
            else -> {
                val sb = buf.asShortBuffer()
                val scale = 1f / 32768f
                FloatArray(sb.remaining()) { sb.get() * scale }
            }
        }
    }

    private fun pcmName(encoding: Int): String = when (encoding) {
        AudioFormat.ENCODING_PCM_FLOAT -> "float PCM"
        AudioFormat.ENCODING_PCM_32BIT -> "32-bit PCM"
        AudioFormat.ENCODING_PCM_24BIT_PACKED -> "24-bit PCM"
        AudioFormat.ENCODING_PCM_16BIT -> "16-bit PCM"
        else -> "PCM enc $encoding"
    }
}
