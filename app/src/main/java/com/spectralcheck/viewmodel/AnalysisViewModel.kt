package com.spectralcheck.viewmodel

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectralcheck.analysis.AnalysisResult
import com.spectralcheck.analysis.Analyzer
import com.spectralcheck.ui.SpectrogramExporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface AnalysisUiState {
    data object Idle : AnalysisUiState
    data class Loading(
        val fileName: String,
        val stage: String = "Starting",
        val progress: Float? = null,
    ) : AnalysisUiState
    data class Ready(
        val result: AnalysisResult,
        val bitmap: Bitmap,
        val cover: Bitmap? = null,
    ) : AnalysisUiState
    data class Error(val message: String) : AnalysisUiState
}

class AnalysisViewModel(application: Application) : AndroidViewModel(application) {

    private val analyzer = Analyzer(application)
    private val _state = MutableStateFlow<AnalysisUiState>(AnalysisUiState.Idle)
    val state: StateFlow<AnalysisUiState> = _state

    private fun decodeCover(bytes: ByteArray?): Bitmap? {
        if (bytes == null) return null
        return try {
            // Bound the decode so huge embedded art can't exhaust memory
            val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            var sample = 1
            while (opts.outWidth / sample > 1024 || opts.outHeight / sample > 1024) sample *= 2
            val decode = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decode)
        } catch (e: Exception) {
            null
        }
    }

    /** Renders the SoX-style annotated spectrogram PNG to [uri]. */
    fun exportImage(uri: Uri) {
        val ready = _state.value as? AnalysisUiState.Ready ?: return
        viewModelScope.launch(Dispatchers.Default) {
            val message = try {
                val image = SpectrogramExporter.render(ready.result)
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use { out ->
                    image.compress(Bitmap.CompressFormat.PNG, 100, out)
                } ?: throw java.io.IOException("Could not open destination")
                image.recycle()
                "Spectrogram image exported"
            } catch (e: Exception) {
                "Export failed: ${e.message}"
            }
            withContext(Dispatchers.Main) {
                Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun analyze(uri: Uri) {
        viewModelScope.launch {
            val name = withContext(Dispatchers.IO) { analyzer.displayName(uri) }
            _state.value = AnalysisUiState.Loading(name)
            _state.value = withContext(Dispatchers.Default) {
                try {
                    val result = analyzer.analyzeFull(uri) { stage, p ->
                        // Throttle state updates to visible increments
                        val cur = _state.value
                        if (cur !is AnalysisUiState.Loading ||
                            cur.stage != stage ||
                            p - (cur.progress ?: -1f) > 0.02f
                        ) {
                            _state.value = AnalysisUiState.Loading(name, stage, p)
                        }
                    }
                    val bitmap = com.spectralcheck.ui.SpectrogramBitmap.render(result.spectrogram)
                    AnalysisUiState.Ready(result, bitmap, decodeCover(result.metadata.coverArt))
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // Throwable, not Exception: OutOfMemoryError must land on
                    // the error screen instead of killing the app.
                    AnalysisUiState.Error(e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }
}
