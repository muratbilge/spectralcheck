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
     */
    fun decode(
        uri: Uri,
        startUs: Long = 0,
        maxDurationUs: Long = Long.MAX_VALUE,
        keepStereo: Boolean = false,
        onProgress: ((Float) -> Unit)? = null,
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
            // can see the real resolution; fall back if the codec refuses.
            var codec = MediaCodec.createDecoderByType(mime)
            var configured = false
            if ((headerInfo?.bitDepth ?: 16) > 16) {
                try {
                    format.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_FLOAT)
                    codec.configure(format, null, null, 0)
                    configured = true
                } catch (e: Exception) {
                    codec.release()
                    codec = MediaCodec.createDecoderByType(mime)
                }
            }
            if (!configured) {
                codec.configure(extractor.getTrackFormat(trackIndex), null, null, 0)
            }

            try {
                codec.start()
                return drainCodec(codec, extractor, extractor.getTrackFormat(trackIndex), headerInfo, maxDurationUs, keepStereo, startUs, onProgress)
            } finally {
                codec.stop()
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
                        val interleaved = if (pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT) {
                            val fb = outBuf.order(ByteOrder.nativeOrder()).asFloatBuffer()
                            FloatArray(fb.remaining()).also { fb.get(it) }
                        } else {
                            val sb = outBuf.order(ByteOrder.nativeOrder()).asShortBuffer()
                            val shorts = ShortArray(sb.remaining()).also { sb.get(it) }
                            FloatArray(shorts.size) { shorts[it] / 32768f }
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
                        monoChunks.add(mono)
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

        val info = AudioInfo(
            sampleRate = sampleRate,
            channels = headerInfo?.channels ?: channels,
            bitDepth = headerInfo?.bitDepth ?: 16,
            durationMs = durationMs,
        )
        return DecodedAudio(
            samples = concat(monoChunks),
            left = leftChunks.takeIf { it.isNotEmpty() }?.let { concat(it) },
            right = rightChunks.takeIf { it.isNotEmpty() }?.let { concat(it) },
            info = info,
            floatOutput = pcmEncoding == AudioFormat.ENCODING_PCM_FLOAT,
        )
    }
}
