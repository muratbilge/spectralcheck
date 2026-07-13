package com.spectralcheck.analysis

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.spectralcheck.audio.AudioInfo
import com.spectralcheck.audio.DecodedAudio
import com.spectralcheck.audio.FlacDecoder
import com.spectralcheck.audio.FlacHeaderParser
import com.spectralcheck.audio.FlacMetadataParser
import com.spectralcheck.audio.TrackMetadata
import com.spectralcheck.dsp.SpectrogramData
import com.spectralcheck.dsp.Stft
import com.spectralcheck.dsp.StreamingStft
import com.spectralcheck.dsp.chooseHop

class AnalysisResult(
    val fileName: String,
    val info: AudioInfo,
    val verdict: VerdictResult,
    val spectrogram: SpectrogramData,
    val metadata: TrackMetadata = TrackMetadata(),
)

private const val WINDOW_US = 30_000_000L
private const val FFT_SIZE = 4096
private const val BASE_HOP = 2048

/** Ties together decode → STFT → signal extraction → verdict. */
class Analyzer(private val context: Context) {

    private val decoder = FlacDecoder(context)
    private val stft = Stft()

    /**
     * Full-track analysis for the detail view: whole-track mono pass for the
     * spectrogram and cutoff, plus a 30 s stereo window for the joint-stereo
     * and bit-depth checks.
     *
     * @param onProgress called with a stage label and overall progress [0, 1]
     */
    fun analyzeFull(uri: Uri, onProgress: ((String, Float) -> Unit)? = null): AnalysisResult {
        onProgress?.invoke("Decoding & analyzing", 0f)

        // Stream frames as the track decodes so the whole PCM never sits in
        // memory; the hop widens on long/hi-res tracks to bound the frame grid.
        val header = runCatching {
            context.contentResolver.openInputStream(uri)?.use { FlacHeaderParser.parse(it) }
        }.getOrNull()
        val totalSamples = header?.let { it.durationMs * it.sampleRate / 1000 } ?: 0L
        val streaming = StreamingStft(FFT_SIZE, chooseHop(totalSamples, FFT_SIZE, BASE_HOP))

        val full = decoder.decode(
            uri,
            onProgress = { p -> onProgress?.invoke("Decoding & analyzing", p * 0.85f) },
            onMonoChunk = { streaming.feed(it) },
        )
        val spectrogram = streaming.finish(full.info.sampleRate)

        onProgress?.invoke("Checking stereo & bit depth", 0.85f)
        val window = runCatching {
            decoder.decode(uri, midTrackStartUs(full.info.durationMs), WINDOW_US, keepStereo = true)
        }.getOrNull()
        return build(uri, full.info, spectrogram, window)
    }

    /**
     * Fast analysis for batch scans: a single ~30 s stereo window starting a
     * third of the way in, which avoids quiet intros/outros.
     */
    fun analyzeQuick(uri: Uri): AnalysisResult {
        val header = runCatching {
            context.contentResolver.openInputStream(uri)?.use { FlacHeaderParser.parse(it) }
        }.getOrNull()
        val startUs = header?.let { midTrackStartUs(it.durationMs) } ?: 0
        val window = decoder.decode(uri, startUs, WINDOW_US, keepStereo = true)
        val spectrogram = stft.analyze(window.samples, window.info.sampleRate)
        return build(uri, window.info, spectrogram, window)
    }

    private fun midTrackStartUs(durationMs: Long): Long =
        if (durationMs > 45_000) durationMs * 1000 / 3 else 0

    private fun build(
        uri: Uri,
        info: AudioInfo,
        spectrogram: SpectrogramData,
        window: DecodedAudio?,
    ): AnalysisResult {
        val cutoff = CutoffDetector.detect(spectrogram)
        val stability = CutoffDetector.stability(spectrogram)

        val sideCutoff = window?.let { w ->
            val l = w.left
            val r = w.right
            if (l != null && r != null && l.size == r.size) {
                val side = FloatArray(l.size) { (l[it] - r[it]) / 2f }
                CutoffDetector.detect(stft.analyze(side, info.sampleRate))
                    .takeIf { !it.lowEnergy }
            } else null
        }

        val effectiveBits = if (info.bitDepth > 16 && window?.floatOutput == true) {
            BitDepth.effectiveBits(window.left ?: window.samples, info.bitDepth)
        } else null

        val verdict = VerdictEngine.judge(
            AnalysisSignals(
                cutoff = cutoff,
                sampleRate = info.sampleRate,
                stability = stability,
                sideCutoff = sideCutoff,
                headerBitDepth = info.bitDepth,
                effectiveBits = effectiveBits,
            )
        )
        return AnalysisResult(displayName(uri), info, verdict, spectrogram, readMetadata(uri))
    }

    /** FLAC-native tag/cover parsing, with MediaMetadataRetriever fallback. */
    private fun readMetadata(uri: Uri): TrackMetadata {
        val flac = runCatching {
            context.contentResolver.openInputStream(uri)?.use { FlacMetadataParser.parse(it) }
        }.getOrNull()
        if (flac != null && !flac.isEmpty) return flac

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            fun key(k: Int) = retriever.extractMetadata(k)?.takeIf { it.isNotBlank() }
            TrackMetadata(
                title = key(MediaMetadataRetriever.METADATA_KEY_TITLE),
                artist = key(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: key(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST),
                album = key(MediaMetadataRetriever.METADATA_KEY_ALBUM),
                date = key(MediaMetadataRetriever.METADATA_KEY_YEAR)
                    ?: key(MediaMetadataRetriever.METADATA_KEY_DATE),
                genre = key(MediaMetadataRetriever.METADATA_KEY_GENRE),
                trackNumber = key(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER),
                coverArt = retriever.embeddedPicture,
            )
        } catch (e: Exception) {
            flac ?: TrackMetadata()
        } finally {
            retriever.release()
        }
    }

    fun displayName(uri: Uri): String {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) return c.getString(idx) ?: uri.lastPathSegment ?: "unknown"
        }
        return uri.lastPathSegment ?: "unknown"
    }
}
