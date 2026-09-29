package com.offlinenarrator.benchmark.ui

import android.content.Context
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinenarrator.benchmark.benchmark.BenchmarkUiState
import com.offlinenarrator.benchmark.benchmark.BenchmarkViewModel
import com.offlinenarrator.benchmark.tts.SupertonicTtsEngine
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var details by remember { mutableStateOf(false) }

    val kokoroPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::importKokoroModel) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bolo Voice Lab")
                        Text(
                            "Kokoro vs Supertonic",
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
            EnginePicker(
                state = state,
                onKokoro = { viewModel.selectEngine("kokoro") },
                onSupertonic = { viewModel.selectEngine("supertonic") },
                onImportKokoro = {
                    kokoroPicker.launch(arrayOf("application/octet-stream", "*/*"))
                },
                onOpenSupertonic = { openSupertonicSetup(context) },
            )

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                label = { Text("Narration text") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = viewModel::useSample) {
                Text("Use standard narration sample")
            }

            VoicePicker(
                state = state,
                onVoice = viewModel::selectVoice,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::synthesize,
                    enabled = state.isReady &&
                        !state.isSynthesizing &&
                        state.text.isNotBlank(),
                ) {
                    Text(if (state.isSynthesizing) "Generating…" else "Generate")
                }

                OutlinedButton(
                    onClick = viewModel::playResult,
                    enabled = state.result != null && !state.isPlaying,
                ) {
                    Text("Play")
                }

                if (state.isPlaying) {
                    TextButton(onClick = viewModel::stopPlayback) {
                        Text("Stop")
                    }
                }
            }

            if (state.isSynthesizing) {
                TextButton(onClick = viewModel::cancelSynthesis) {
                    Text("Cancel")
                }
            }

            state.result?.let {
                ResultCard(state)
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
                DetailsCard(state)
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EnginePicker(
    state: BenchmarkUiState,
    onKokoro: () -> Unit,
    onSupertonic: () -> Unit,
    onImportKokoro: () -> Unit,
    onOpenSupertonic: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Voice engine", fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.selectedEngineId == "kokoro",
                    onClick = onKokoro,
                    label = { Text("Kokoro") },
                )
                FilterChip(
                    selected = state.selectedEngineId == "supertonic",
                    onClick = onSupertonic,
                    label = { Text("Supertonic 3") },
                )
            }

            Text(state.engineName, fontWeight = FontWeight.SemiBold)
            Text(
                state.engineDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.selectedEngineId == "kokoro" && !state.kokoroModelPresent) {
                Button(onClick = onImportKokoro) {
                    Text("Import Kokoro model")
                }
            }

            if (state.selectedEngineId == "supertonic" && !state.isReady) {
                OutlinedButton(onClick = onOpenSupertonic) {
                    Text("Open Supertonic setup")
                }
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
    val label = selected?.name ?: "Choose voice"

    Box {
        OutlinedButton(
            onClick = { expanded = true },
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
private fun ResultCard(state: BenchmarkUiState) {
    val result = state.result ?: return

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Result", fontWeight = FontWeight.Bold)
            Metric("Generation", formatDuration(result.generationTimeMs))
            Metric("Audio", formatDuration(result.audioDurationMs))
            Metric("RTF", String.format(Locale.US, "%.3f", result.realTimeFactor))
            Metric(
                "Generation speed",
                String.format(Locale.US, "%.2f× realtime", result.generatedRealtimeMultiple),
            )

            Text(
                if (result.realTimeFactor < 1.0) {
                    "Faster than playback"
                } else {
                    "Slower than playback"
                },
                color = if (result.realTimeFactor < 1.0) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DetailsCard(state: BenchmarkUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Details", fontWeight = FontWeight.Bold)
            state.result?.sampleRate?.let {
                Metric("Sample rate", "$it Hz")
            }
            state.deviceSnapshot?.let { d ->
                Metric(
                    "Battery",
                    d.batteryTemperatureC?.let {
                        String.format(Locale.US, "%.1f °C", it)
                    } ?: "Unavailable",
                )
                Metric("Thermal", d.thermalStatus)
                Metric("Device", d.device)
            }
            Text(
                if (state.selectedEngineId == "supertonic") {
                    "Supertonic runs in the companion engine process, so this screen intentionally does not show misleading Bolo-process memory numbers."
                } else {
                    "Kokoro uses the fixed CPU ALL_OPT / 8-thread profile from the previous benchmark."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun openSupertonicSetup(context: Context) {
    val intent = context.packageManager
        .getLaunchIntentForPackage(SupertonicTtsEngine.ENGINE_PACKAGE)
        ?: return
    context.startActivity(intent)
}

private fun formatDuration(ms: Long): String =
    if (ms >= 1000L) {
        String.format(Locale.US, "%.2f s", ms / 1000.0)
    } else {
        "$ms ms"
    }
