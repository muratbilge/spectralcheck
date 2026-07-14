package com.spectralcheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spectralcheck.analysis.Verdict
import com.spectralcheck.viewmodel.BatchItem
import com.spectralcheck.viewmodel.BatchScanViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchScreen(
    viewModel: BatchScanViewModel,
    onBack: () -> Unit,
    onItemClick: (BatchItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Folder scan") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
                return@Column
            }

            if (state.total > 0) {
                Text(
                    if (state.scanning) "Analyzing ${state.done} of ${state.total}…"
                    else "${state.total} files analyzed",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(10.dp))
                VerdictSummary(state.items)
            } else if (state.scanning) {
                Text(
                    "Looking for FLAC files…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Spacer(Modifier.height(8.dp))

            LazyColumn {
                items(state.items, key = { it.uri.toString() }) { item ->
                    BatchRow(item, onClick = { onItemClick(item) })
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun VerdictSummary(items: List<BatchItem>) {
    val counts = items.groupingBy { it.verdict }.eachCount()
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (v in listOf(Verdict.TRANSCODE, Verdict.SUSPICIOUS, Verdict.INCONCLUSIVE, Verdict.AUTHENTIC)) {
            val n = counts[v] ?: 0
            if (n > 0) {
                Surface(
                    color = v.color.copy(alpha = 0.16f),
                    shape = CircleShape,
                ) {
                    Text(
                        "$n ${v.label.lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = v.color,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun BatchRow(item: BatchItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(12.dp)
                .background(
                    item.verdict?.color ?: MaterialTheme.colorScheme.outline,
                    CircleShape,
                ),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title ?: item.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
            val trackLine = when {
                item.artist != null && item.title != null -> "${item.artist} · ${item.name}"
                item.artist != null -> item.artist
                item.title != null -> item.name
                else -> null
            }
            if (trackLine != null) {
                Text(
                    trackLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            when {
                item.error != null -> Text(
                    "Error: ${item.error}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    maxLines = 1,
                )
                item.verdict != null -> Text(
                    buildString {
                        append(item.verdict.label)
                        item.cutoffHz?.let { append(" · %.1f kHz".format(it / 1000f)) }
                        item.estimatedSource?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = item.verdict.color,
                    maxLines = 1,
                )
            }
        }
    }
}
