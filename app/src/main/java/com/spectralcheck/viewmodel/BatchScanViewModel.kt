package com.spectralcheck.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.spectralcheck.analysis.Analyzer
import com.spectralcheck.analysis.Verdict
import com.spectralcheck.io.FlacFileScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

data class BatchItem(
    val uri: Uri,
    val name: String,
    val verdict: Verdict?,
    val estimatedSource: String?,
    val cutoffHz: Float?,
    val title: String? = null,
    val artist: String? = null,
    val error: String? = null,
)

data class BatchUiState(
    val scanning: Boolean = false,
    val total: Int = 0,
    val done: Int = 0,
    val items: List<BatchItem> = emptyList(),
    val error: String? = null,
)

class BatchScanViewModel(application: Application) : AndroidViewModel(application) {

    private val analyzer = Analyzer(application)
    private val _state = MutableStateFlow(BatchUiState())
    val state: StateFlow<BatchUiState> = _state
    private var job: Job? = null

    fun scan(treeUri: Uri) {
        job?.cancel()
        job = viewModelScope.launch(Dispatchers.Default) {
            _state.value = BatchUiState(scanning = true)
            val entries = try {
                FlacFileScanner.scan(getApplication(), treeUri)
            } catch (e: Exception) {
                _state.value = BatchUiState(error = e.message ?: "Folder scan failed")
                return@launch
            }
            if (entries.isEmpty()) {
                _state.value = BatchUiState(error = "No FLAC files found in this folder")
                return@launch
            }

            _state.value = BatchUiState(scanning = true, total = entries.size)
            val semaphore = Semaphore(2)
            val results = java.util.Collections.synchronizedList(ArrayList<BatchItem>())
            entries.map { entry ->
                async {
                    semaphore.withPermit {
                        val item = try {
                            val r = analyzer.analyzeQuick(entry.uri)
                            BatchItem(
                                uri = entry.uri,
                                name = entry.name,
                                verdict = r.verdict.verdict,
                                estimatedSource = r.verdict.estimatedSource,
                                cutoffHz = r.verdict.cutoff.cutoffHz,
                                title = r.metadata.title,
                                artist = r.metadata.artist,
                            )
                        } catch (e: Exception) {
                            BatchItem(
                                entry.uri, entry.name, null, null, null,
                                error = e.message ?: "failed",
                            )
                        }
                        results.add(item)
                        _state.value = _state.value.copy(
                            done = results.size,
                            items = sorted(results.toList()),
                        )
                    }
                }
            }.awaitAll()

            _state.value = _state.value.copy(scanning = false)
        }
    }

    private fun sorted(items: List<BatchItem>): List<BatchItem> =
        items.sortedWith(
            compareBy(
                { verdictRank(it.verdict) },
                { it.name.lowercase() },
            )
        )

    private fun verdictRank(v: Verdict?): Int = when (v) {
        Verdict.TRANSCODE -> 0
        Verdict.SUSPICIOUS -> 1
        Verdict.INCONCLUSIVE -> 2
        null -> 3
        Verdict.AUTHENTIC -> 4
    }
}
