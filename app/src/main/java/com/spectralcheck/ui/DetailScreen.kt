package com.spectralcheck.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spectralcheck.viewmodel.AnalysisUiState
import com.spectralcheck.viewmodel.AnalysisViewModel

@Composable
fun DetailScreen(viewModel: AnalysisViewModel) {
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        is AnalysisUiState.Idle -> {}
        is AnalysisUiState.Loading -> Column(
            Modifier.fillMaxSize().padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val p = s.progress
            if (p != null) {
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                CircularProgressIndicator()
            }
            Spacer(Modifier.height(16.dp))
            Text("${s.stage}…", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(s.fileName, style = MaterialTheme.typography.bodySmall)
        }

        is AnalysisUiState.Error -> Column(
            Modifier.fillMaxSize().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Analysis failed", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(8.dp))
            Text(s.message, style = MaterialTheme.typography.bodyMedium)
        }

        is AnalysisUiState.Ready -> Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            val r = s.result
            val exportLauncher = rememberLauncherForActivityResult(
                ActivityResultContracts.CreateDocument("image/png")
            ) { uri -> if (uri != null) viewModel.exportImage(uri) }

            TrackHeader(
                fileName = r.fileName,
                metadata = r.metadata,
                cover = s.cover,
            )
            Spacer(Modifier.height(12.dp))

            // Verdict card
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.foundation.layout.Box(
                            Modifier
                                .size(14.dp)
                                .background(r.verdict.verdict.color, CircleShape),
                        )
                        Spacer(Modifier.padding(horizontal = 6.dp))
                        Text(
                            r.verdict.verdict.label,
                            style = MaterialTheme.typography.titleLarge,
                            color = r.verdict.verdict.color,
                        )
                    }
                    r.verdict.estimatedSource?.let {
                        Spacer(Modifier.height(4.dp))
                        Text("Estimated source: $it", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(r.verdict.explanation, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (r.verdict.evidence.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Evidence",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        r.verdict.evidence.forEach { e ->
                            Row(
                                Modifier.padding(vertical = 6.dp),
                                verticalAlignment = Alignment.Top,
                            ) {
                                androidx.compose.foundation.layout.Box(
                                    Modifier
                                        .padding(top = 4.dp)
                                        .size(10.dp)
                                        .background(e.signal.color, CircleShape),
                                )
                                Spacer(Modifier.padding(horizontal = 5.dp))
                                Column {
                                    Text(
                                        e.title,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium,
                                    )
                                    Text(e.detail, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            SpectrogramView(
                bitmap = s.bitmap,
                sampleRate = r.info.sampleRate,
                durationMs = r.info.durationMs,
                cutoffHz = r.verdict.cutoff.cutoffHz.takeIf { !r.verdict.cutoff.lowEnergy },
            )

            Spacer(Modifier.height(16.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    InfoRow("Sample rate", "${r.info.sampleRate} Hz")
                    InfoRow("Bit depth", "${r.info.bitDepth} bit")
                    InfoRow("Channels", "${r.info.channels}")
                    InfoRow(
                        "Duration",
                        "%d:%02d".format(r.info.durationMs / 60000, r.info.durationMs / 1000 % 60),
                    )
                    InfoRow("Detected cutoff", "%.1f kHz".format(r.verdict.cutoff.cutoffHz / 1000f))
                    InfoRow(
                        "Rolloff width",
                        "%.0f Hz %s".format(
                            r.verdict.cutoff.rolloffWidthHz,
                            if (r.verdict.cutoff.sharpShelf) "(brick wall)" else "(gradual)",
                        ),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    val base = r.fileName.substringBeforeLast('.')
                    exportLauncher.launch("$base-spectrogram.png")
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Export spectrogram image (PNG)")
            }
        }
    }
}

@Composable
private fun TrackHeader(
    fileName: String,
    metadata: com.spectralcheck.audio.TrackMetadata,
    cover: android.graphics.Bitmap?,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cover != null) {
                androidx.compose.foundation.Image(
                    bitmap = cover.asImageBitmap(),
                    contentDescription = "Album cover",
                    contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                    modifier = Modifier
                        .size(88.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp)),
                )
            } else {
                androidx.compose.foundation.layout.Box(
                    Modifier
                        .size(88.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("♪", style = MaterialTheme.typography.headlineLarge)
                }
            }
            Spacer(Modifier.padding(horizontal = 6.dp))
            Column {
                Text(
                    metadata.title ?: fileName.substringBeforeLast('.'),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                )
                metadata.artist?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                val albumLine = listOfNotNull(
                    metadata.album,
                    metadata.date?.take(4),
                ).joinToString(" · ")
                if (albumLine.isNotEmpty()) {
                    Text(
                        albumLine,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                }
                val extraLine = listOfNotNull(
                    metadata.genre,
                    metadata.trackNumber?.let { "track $it" },
                ).joinToString(" · ")
                if (extraLine.isNotEmpty()) {
                    Text(
                        extraLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (metadata.title != null) {
                    Text(
                        fileName,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
