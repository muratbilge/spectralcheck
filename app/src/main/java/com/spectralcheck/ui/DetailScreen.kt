package com.spectralcheck.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.spectralcheck.audio.TrackMetadata
import com.spectralcheck.viewmodel.AnalysisUiState
import com.spectralcheck.viewmodel.AnalysisViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(viewModel: AnalysisViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analysis") },
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
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (val s = state) {
                is AnalysisUiState.Idle -> {}
                is AnalysisUiState.Loading -> LoadingContent(s)
                is AnalysisUiState.Error -> ErrorContent(s.message)
                is AnalysisUiState.Ready -> ReadyContent(s, viewModel)
            }
        }
    }
}

@Composable
private fun LoadingContent(s: AnalysisUiState.Loading) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        val p = s.progress
        if (p != null) {
            LinearProgressIndicator(progress = { p }, modifier = Modifier.fillMaxWidth())
        } else {
            CircularProgressIndicator()
        }
        Spacer(Modifier.height(20.dp))
        Text("${s.stage}…", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            s.fileName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ErrorContent(message: String) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Analysis failed", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ReadyContent(s: AnalysisUiState.Ready, viewModel: AnalysisViewModel) {
    val r = s.result
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("image/png")
    ) { uri -> if (uri != null) viewModel.exportImage(uri) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
    ) {
        TrackHeader(fileName = r.fileName, metadata = r.metadata, cover = s.cover)
        Spacer(Modifier.height(12.dp))

        // Verdict card, tinted with the verdict color
        val verdictColor = r.verdict.verdict.color
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = verdictColor.copy(alpha = 0.13f)),
        ) {
            Column(Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).background(verdictColor, CircleShape))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        r.verdict.verdict.label,
                        style = MaterialTheme.typography.titleLarge,
                        color = verdictColor,
                    )
                }
                r.verdict.estimatedSource?.let {
                    Spacer(Modifier.height(6.dp))
                    Text("Estimated source: $it", style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    r.verdict.explanation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (r.verdict.evidence.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            SectionCard("Evidence") {
                r.verdict.evidence.forEach { e ->
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                        Box(
                            Modifier
                                .padding(top = 5.dp)
                                .size(9.dp)
                                .background(e.signal.color, CircleShape),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                e.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            Text(
                                e.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text(
            "SPECTROGRAM",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Box(Modifier.clip(RoundedCornerShape(12.dp))) {
            SpectrogramView(
                bitmap = s.bitmap,
                sampleRate = r.info.sampleRate,
                durationMs = r.info.durationMs,
                cutoffHz = r.verdict.cutoff.cutoffHz.takeIf { !r.verdict.cutoff.lowEnergy },
            )
        }

        Spacer(Modifier.height(16.dp))
        SectionCard("File") {
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
            r.info.decoderOutput?.let { InfoRow("Decoder output", it) }
        }

        Spacer(Modifier.height(16.dp))
        FilledTonalButton(
            onClick = {
                val base = r.fileName.substringBeforeLast('.')
                exportLauncher.launch("$base-spectrogram.png")
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Outlined.Image, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Export spectrogram (PNG)")
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun TrackHeader(
    fileName: String,
    metadata: TrackMetadata,
    cover: android.graphics.Bitmap?,
) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (cover != null) {
                Image(
                    bitmap = cover.asImageBitmap(),
                    contentDescription = "Album cover",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(88.dp).clip(RoundedCornerShape(10.dp)),
                )
            } else {
                Box(
                    Modifier
                        .size(88.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("♪", style = MaterialTheme.typography.headlineLarge)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column {
                Text(
                    metadata.title ?: fileName.substringBeforeLast('.'),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                )
                metadata.artist?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                }
                val albumLine = listOfNotNull(metadata.album, metadata.date?.take(4))
                    .joinToString(" · ")
                if (albumLine.isNotEmpty()) {
                    Text(
                        albumLine,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
        Modifier.fillMaxWidth().padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
