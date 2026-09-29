package com.offlinenarrator.benchmark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinenarrator.benchmark.benchmark.BenchmarkUiState
import com.offlinenarrator.benchmark.benchmark.BenchmarkViewModel
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::importKokoroModel) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bolo")
                        Text(
                            "Offline natural reader",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!state.kokoroModelPresent) {
                ModelSetupCard(
                    state = state,
                    onImport = {
                        modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
                    },
                )
            } else {
                ReaderStatusCard(state)

                OutlinedTextField(
                    value = state.text,
                    onValueChange = viewModel::setText,
                    enabled = !state.readerStarted,
                    label = { Text("Text to read") },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (!state.readerStarted) {
                    TextButton(onClick = viewModel::useSample) {
                        Text("Use long reading sample")
                    }
                }

                VoicePicker(
                    state = state,
                    onVoice = viewModel::selectVoice,
                )

                PlaybackSpeedPicker(
                    selected = state.playbackSpeed,
                    onSelect = viewModel::setPlaybackSpeed,
                )

                ReaderControls(
                    state = state,
                    onStart = viewModel::startReading,
                    onPauseResume = viewModel::pauseOrResume,
                    onStop = viewModel::stopReading,
                )

                if (state.totalSegments > 0) {
                    val progress = if (state.totalSegments == 0) {
                        0f
                    } else {
                        (state.currentSegment.toFloat() / state.totalSegments.toFloat())
                            .coerceIn(0f, 1f)
                    }

                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                state.error?.let {
                    Text(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                TextButton(onClick = { details = !details }) {
                    Text(if (details) "Hide details" else "Details")
                }

                if (details) {
                    DetailsCard(
                        state = state,
                        onClearCache = viewModel::clearPreparedAudio,
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ModelSetupCard(
    state: BenchmarkUiState,
    onImport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Kokoro model required", fontWeight = FontWeight.Bold)
            Text(
                "Import the Kokoro FP32 ONNX model once. Bolo then reads locally without cloud credits or per-minute limits.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onImport,
                enabled = !state.isPreparing,
            ) {
                Text(if (state.isPreparing) "Importing…" else "Import Kokoro model")
            }
            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ReaderStatusCard(state: BenchmarkUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                when {
                    state.finished -> "Finished"
                    state.thermalPaused -> "Cooling"
                    state.isPlaying -> "Reading"
                    state.isPaused -> "Paused"
                    state.isGenerating -> "Preparing"
                    else -> "Ready"
                },
                fontWeight = FontWeight.Bold,
            )

            Text(
                state.status,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )

            if (state.readerStarted || state.finished) {
                val readySeconds = state.bufferedListeningMs / 1000
                Text(
                    "Ready ahead: ${readySeconds}s · " +
                        "segment ${state.currentSegment.coerceAtLeast(0)}/${state.totalSegments}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun VoicePicker(
    state: BenchmarkUiState,
    onVoice: (String) -> Unit,
) {
    if (state.voices.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val selected = state.voices.firstOrNull { it.id == state.selectedVoiceId }
    val label = selected?.name ?: "Voice"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = !state.readerStarted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Voice · $label")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            state.voices.forEach { voice ->
                DropdownMenuItem(
                    text = { Text(voice.name) },
                    onClick = {
                        onVoice(voice.id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun PlaybackSpeedPicker(
    selected: Float,
    onSelect: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Playback speed", fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1.0f, 1.25f, 1.5f).forEach { speed ->
                FilterChip(
                    selected = selected == speed,
                    onClick = { onSelect(speed) },
                    label = { Text(formatSpeed(speed)) },
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1.75f, 2.0f).forEach { speed ->
                FilterChip(
                    selected = selected == speed,
                    onClick = { onSelect(speed) },
                    label = { Text(formatSpeed(speed)) },
                )
            }
        }
    }
}

@Composable
private fun ReaderControls(
    state: BenchmarkUiState,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!state.readerStarted) {
            Button(
                onClick = onStart,
                enabled = state.isReady && !state.isPreparing && state.text.isNotBlank(),
            ) {
                Text(if (state.finished) "Read again" else "Start reading")
            }
        } else {
            Button(onClick = onPauseResume) {
                Text(if (state.isPaused) "Resume" else "Pause")
            }

            OutlinedButton(onClick = onStop) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun DetailsCard(
    state: BenchmarkUiState,
    onClearCache: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Reader details", fontWeight = FontWeight.Bold)
            Metric("Generated", "${state.generatedSegments}/${state.totalSegments}")
            Metric("Cache hits", state.cacheHits.toString())
            state.meanGenerationRtf?.let {
                Metric("Mean generation RTF", String.format(Locale.US, "%.3f", it))
            }
            Metric("Playback gaps", state.underruns.toString())
            Metric("Prepared audio cache", formatBytes(state.cacheBytes))

            state.deviceSnapshot?.let { d ->
                Metric(
                    "Battery",
                    d.batteryTemperatureC?.let {
                        String.format(Locale.US, "%.1f °C", it)
                    } ?: "Unavailable",
                )
                Metric("Thermal", d.thermalStatus)
            }

            Text(
                "Bolo generates short narration segments, keeps a rolling reserve, reuses cached audio, and pauses new synthesis if Android reports severe thermal pressure.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = onClearCache,
                enabled = !state.readerStarted,
            ) {
                Text("Clear prepared audio")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            value,
            fontWeight = FontWeight.Medium,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

private fun formatSpeed(value: Float): String =
    if (value == value.toInt().toFloat()) {
        "${value.toInt()}.0×"
    } else {
        "${value}×"
    }

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else ->
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
}
