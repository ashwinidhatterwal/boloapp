package com.offlinenarrator.benchmark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinenarrator.benchmark.benchmark.BenchmarkUiState
import com.offlinenarrator.benchmark.benchmark.BenchmarkViewModel
import com.offlinenarrator.benchmark.tts.TtsVoice
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var details by remember { mutableStateOf(false) }
    var editText by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        uri -> uri?.let(viewModel::importKokoroModel)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bolo", fontWeight = FontWeight.Bold)
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (!state.kokoroModelPresent) {
                SetupCard(state) {
                    picker.launch(arrayOf("application/octet-stream", "*/*"))
                }
            } else {
                PlayerCard(
                    state = state,
                    onStart = viewModel::startReading,
                    onPauseResume = viewModel::pauseOrResume,
                    onStop = viewModel::stopReading,
                    onVoice = viewModel::selectVoice,
                    onSpeed = viewModel::setPlaybackSpeed,
                )

                ReadingTextCard(
                    state = state,
                    expanded = editText,
                    onToggle = { editText = !editText },
                    onText = viewModel::setText,
                    onSample = viewModel::useSample,
                )

                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

                TextButton(onClick = { details = !details }) {
                    Text(if (details) "Hide reader details" else "Reader details")
                }
                if (details) DetailsCard(state, viewModel::clearPreparedAudio)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SetupCard(state: BenchmarkUiState, onImport: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Set up Bolo", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Import the Kokoro FP32 model once. Narration then stays local on your phone.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImport, enabled = !state.isPreparing) {
                Text(if (state.isPreparing) "Importing…" else "Import Kokoro model")
            }
            Text(state.status, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun PlayerCard(
    state: BenchmarkUiState,
    onStart: () -> Unit,
    onPauseResume: () -> Unit,
    onStop: () -> Unit,
    onVoice: (String) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    val selected = state.voices.firstOrNull { it.id == state.selectedVoiceId }
    val progress = if (state.totalSegments > 0) {
        (state.currentSegment.toFloat() / state.totalSegments.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                playerStateLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )
            Text("Narration", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                selected?.name ?: "Narrator",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    if (state.totalSegments > 0) "${state.currentSegment}/${state.totalSegments}" else "Ready",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${formatTime(state.bufferedListeningMs)} ready ahead",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                SpeedMenu(state.playbackSpeed, onSpeed)

                Button(
                    onClick = {
                        if (!state.readerStarted) onStart()
                        else if (state.playbackStarted) onPauseResume()
                    },
                    enabled = state.isReady &&
                        state.text.isNotBlank() &&
                        (!state.readerStarted || state.playbackStarted),
                    modifier = Modifier.size(80.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        when {
                            state.readerStarted && !state.playbackStarted -> "…"
                            state.isPlaying -> "Ⅱ"
                            else -> "▶"
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    onClick = onStop,
                    enabled = state.readerStarted,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) { Text("■") }
            }

            VoiceMenu(
                voices = state.voices,
                selectedId = state.selectedVoiceId,
                enabled = !state.readerStarted,
                onVoice = onVoice,
            )

            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.thermalPaused)
                    MaterialTheme.colorScheme.error
                else
                    MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun VoiceMenu(
    voices: List<TtsVoice>,
    selectedId: String?,
    enabled: Boolean,
    onVoice: (String) -> Unit,
) {
    if (voices.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.id == selectedId }

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Narrator · ${selected?.name ?: "Choose voice"}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            voices.forEach { voice ->
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
private fun SpeedMenu(selected: Float, onSelect: (Float) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }) { Text(formatSpeed(selected)) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(speed)) },
                    onClick = {
                        onSelect(speed)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ReadingTextCard(
    state: BenchmarkUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onText: (String) -> Unit,
    onSample: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Reading text", fontWeight = FontWeight.Bold)
                    if (!expanded) {
                        Text(
                            preview(state.text),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                TextButton(onClick = onToggle, enabled = !state.readerStarted) {
                    Text(if (expanded) "Done" else "Edit")
                }
            }

            if (expanded) {
                OutlinedTextField(
                    value = state.text,
                    onValueChange = onText,
                    enabled = !state.readerStarted,
                    label = { Text("Paste or type text") },
                    minLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = onSample, enabled = !state.readerStarted) {
                    Text("Use long reading sample")
                }
            }
        }
    }
}

@Composable
private fun DetailsCard(state: BenchmarkUiState, onClearCache: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Reader details", fontWeight = FontWeight.Bold)
            Metric("Generated", "${state.generatedSegments}/${state.totalSegments}")
            Metric("Cached segments reused", state.cacheHits.toString())
            state.meanGenerationRtf?.let {
                Metric("Mean generation RTF", String.format(Locale.US, "%.3f", it))
            }
            Metric("Playback gaps", state.underruns.toString())
            Metric("Prepared audio", formatBytes(state.cacheBytes))
            state.deviceSnapshot?.let { d ->
                Metric(
                    "Battery",
                    d.batteryTemperatureC?.let {
                        String.format(Locale.US, "%.1f °C", it)
                    } ?: "Unavailable",
                )
                Metric("Thermal", d.thermalStatus)
            }
            TextButton(onClick = onClearCache, enabled = !state.readerStarted) {
                Text("Clear prepared audio")
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(value, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
    }
}

private fun playerStateLabel(state: BenchmarkUiState): String = when {
    state.finished -> "FINISHED"
    state.thermalPaused -> "COOLING"
    state.readerStarted && !state.playbackStarted -> "PREPARING"
    state.isPlaying -> "NOW PLAYING"
    state.isPaused -> "PAUSED"
    else -> "READY TO READ"
}

private fun preview(text: String): String {
    val compact = text.replace(Regex("\\s+"), " ").trim()
    return if (compact.length <= 120) compact else compact.take(120).trimEnd() + "…"
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

private fun formatSpeed(value: Float): String =
    if (value == value.toInt().toFloat()) "${value.toInt()}.0×" else "${value}×"

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024L * 1024L)
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
