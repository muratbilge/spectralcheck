package com.spectralcheck.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spectralcheck.viewmodel.BatchItem
import com.spectralcheck.viewmodel.BatchScanViewModel

@Composable
fun BatchScreen(
    viewModel: BatchScanViewModel,
    onItemClick: (BatchItem) -> Unit,
) {
    val state by viewModel.state.collectAsState()

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("Folder scan", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))

        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            return@Column
        }

        if (state.total > 0) {
            Text(
                if (state.scanning) "Analyzing ${state.done} / ${state.total}…"
                else "Done — ${state.total} files analyzed",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                modifier = Modifier.fillMaxWidth(),
            )
        } else if (state.scanning) {
            Text("Looking for FLAC files…", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(4.dp))
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }

        Spacer(Modifier.height(12.dp))

        LazyColumn {
            items(state.items, key = { it.uri.toString() }) { item ->
                BatchRow(item, onClick = { onItemClick(item) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun BatchRow(item: BatchItem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.foundation.layout.Box(
            Modifier.size(12.dp).background(item.verdict?.color ?: Color.DarkGray, CircleShape),
        )
        Spacer(Modifier.width(10.dp))
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
                    color = Color.Gray,
                    maxLines = 1,
                )
            }
            val sub = when {
                item.error != null -> "Error: ${item.error}"
                item.verdict != null -> buildString {
                    append(item.verdict.label)
                    item.cutoffHz?.let { append(" · %.1f kHz".format(it / 1000f)) }
                    item.estimatedSource?.let { append(" · $it") }
                }
                else -> ""
            }
            Text(sub, style = MaterialTheme.typography.bodySmall, color = Color.Gray, maxLines = 1)
        }
    }
}
