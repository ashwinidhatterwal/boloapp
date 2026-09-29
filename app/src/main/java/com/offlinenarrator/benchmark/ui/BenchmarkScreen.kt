package com.offlinenarrator.benchmark.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::importKokoroModel) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Offline Narrator Lab")
                        Text(
                            "Phase 0 · local TTS benchmark",
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
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            EngineCard(
                state = state,
                onSystem = { viewModel.selectEngine("system") },
                onKokoro = { viewModel.selectEngine("kokoro") },
                onPocket = { viewModel.selectEngine("pocket") },
                onKitten = { viewModel.selectEngine("kitten") },
                onImport = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                onOpenModelPage = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/tree/main/onnx")
                        )
                    )
                },
                onDeleteModel = viewModel::deleteKokoroModel,
                onOpenPocketRelease = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/tag/v0.5.2")
                        )
                    )
                },
                onOpenPocketEnglishPack = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/The-unknown-Shadowman/PocketTTS-Android-Engine/releases/download/v0.5.2/PocketTTS-english-FP32.zip")
                        )
                    )
                },
                onOpenKittenRelease = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://github.com/gyanendra-baghel/kittentts-android/releases/tag/v1.0.0")
                        )
                    )
                },
            )

            PresetsCard(
                onNarration = viewModel::chooseNarration,
                onDialogue = viewModel::chooseDialogue,
                onNumbers = viewModel::chooseNumbers,
                onLong = viewModel::chooseLongForm,
                onPocketDiagnostic = viewModel::choosePocketDiagnostic,
            )

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                label = { Text("Benchmark text") },
                minLines = 7,
                modifier = Modifier.fillMaxWidth(),
            )

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Speed: ${String.format(Locale.US, "%.2fx", state.speed)}", fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = state.speed,
                        onValueChange = viewModel::setSpeed,
                        valueRange = 0.75f..1.5f,
                        steps = 14,
                    )
                    if (state.voices.isNotEmpty()) {
                        Text("Voice", fontWeight = FontWeight.SemiBold)
                        state.voices.take(8).forEach { voice ->
                            FilterChip(
                                selected = state.selectedVoiceId == voice.id,
                                onClick = { viewModel.selectVoice(voice.id) },
                                label = {
                                    Text(buildString {
                                        append(voice.name)
                                        voice.language?.let { append(" · $it") }
                                    })
                                }
                            )
                        }
                        if (state.voices.size > 8) {
                            Text(
                                "Showing the first 8 system voices in this benchmark build.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = viewModel::synthesize,
                    enabled = state.isReady && !state.isSynthesizing && state.text.isNotBlank(),
                ) {
                    Text(if (state.isSynthesizing) "Working…" else "Generate")
                }
                OutlinedButton(
                    onClick = { viewModel.runStress(5) },
                    enabled = state.isReady && !state.isSynthesizing && state.text.isNotBlank(),
                ) { Text("5× stress") }
            }

            if (state.isSynthesizing) {
                OutlinedButton(onClick = viewModel::cancelSynthesis) {
                    Text("Cancel synthesis")
                }
            }

            if (state.result != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = viewModel::playResult, enabled = !state.isPlaying) { Text("Play") }
                    OutlinedButton(onClick = viewModel::stopPlayback, enabled = state.isPlaying) { Text("Stop") }
                }
                ResultsCard(state)
            }

            StatusCard(state)

            Spacer(Modifier.height(12.dp))
            Text(
                "Phase-0 rule: Bolo declares no network permission. Kokoro, Pocket and Kitten synthesis stay local; setup buttons only open your browser.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun EngineCard(
    state: BenchmarkUiState,
    onSystem: () -> Unit,
    onKokoro: () -> Unit,
    onPocket: () -> Unit,
    onKitten: () -> Unit,
    onImport: () -> Unit,
    onOpenModelPage: () -> Unit,
    onDeleteModel: () -> Unit,
    onOpenPocketRelease: () -> Unit,
    onOpenPocketEnglishPack: () -> Unit,
    onOpenKittenRelease: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Engine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.selectedEngineId == "system",
                    onClick = onSystem,
                    label = { Text("System baseline") },
                )
                FilterChip(
                    selected = state.selectedEngineId == "kokoro",
                    onClick = onKokoro,
                    label = { Text("Kokoro") },
                )
                FilterChip(
                    selected = state.selectedEngineId == "pocket",
                    onClick = onPocket,
                    label = { Text("Pocket") },
                )
                FilterChip(
                    selected = state.selectedEngineId == "kitten",
                    onClick = onKitten,
                    label = { Text("Kitten") },
                )
            }
            Text(state.engineName, fontWeight = FontWeight.SemiBold)
            Text(state.engineDescription, style = MaterialTheme.typography.bodySmall)

            if (state.selectedEngineId == "kokoro") {
                HorizontalDivider()
                if (state.kokoroModelPresent) {
                    Text(
                        "${state.kokoroModelName ?: "Imported model"} · ${formatBytes(state.kokoroModelBytes)}"
                    )
                    state.kokoroModelSha256?.let { sha ->
                        Text(
                            "SHA-256 ${sha.take(16)}…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.runtimeInfo?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    state.engineInitMs?.let {
                        Text(
                            "Engine init: ${formatDuration(it)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onImport) { Text("Replace model") }
                        TextButton(onClick = onDeleteModel) { Text("Remove") }
                    }
                } else {
                    Text("Kokoro model is not bundled in the APK.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onImport) { Text("Import .onnx") }
                        OutlinedButton(onClick = onOpenModelPage) { Text("Model page") }
                    }
                }
            }

            if (state.selectedEngineId == "pocket") {
                HorizontalDivider()
                Text(
                    "Pocket diagnostic setup: in Pocket TTS set Temperature 0.3, LSD steps 1, Threads 4, Pause 250 ms, Segment size 50, then Save. v0.9 records native-start and first-audio timing.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onOpenPocketRelease) {
                        Text("Pocket app")
                    }
                    OutlinedButton(onClick = onOpenPocketEnglishPack) {
                        Text("English pack")
                    }
                }
                state.engineInitMs?.let {
                    Text(
                        "Engine init: ${formatDuration(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (state.selectedEngineId == "kitten") {
                HorizontalDivider()
                Text(
                    "KittenTTS Nano uses a separate local Android engine. Its v1.0.0 APK already includes the Nano model and 8 English voices, so no model ZIP is required.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = onOpenKittenRelease) {
                    Text("Install KittenTTS")
                }
                Text(
                    "After installing, return here and tap Kitten again. First request may include a short warm-up.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.engineInitMs?.let {
                    Text(
                        "Engine init: ${formatDuration(it)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

        }
    }
}

@Composable
private fun PresetsCard(
    onNarration: () -> Unit,
    onDialogue: () -> Unit,
    onNumbers: () -> Unit,
    onLong: () -> Unit,
    onPocketDiagnostic: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Test passages", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onNarration) { Text("Narration") }
                OutlinedButton(onClick = onDialogue) { Text("Dialogue") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onNumbers) { Text("Numbers") }
                OutlinedButton(onClick = onLong) { Text("Long") }
            }
            OutlinedButton(onClick = onPocketDiagnostic) { Text("Pocket short test") }
        }
    }
}

@Composable
private fun ResultsCard(state: BenchmarkUiState) {
    val result = state.result ?: return
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Benchmark result", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Metric("Generation", formatDuration(result.generationTimeMs))
            Metric("Audio", formatDuration(result.audioDurationMs))
            Metric("RTF", String.format(Locale.US, "%.3f", result.realTimeFactor))
            Metric("Generation speed", String.format(Locale.US, "%.2f× realtime", result.generatedRealtimeMultiple))
            result.sampleRate?.let { Metric("Sample rate", "$it Hz") }
            Text(
                if (result.realTimeFactor < 1.0) "✓ Faster than playback — suitable for ahead-of-listener buffering."
                else "Generation is currently slower than realtime on this passage/device.",
                color = if (result.realTimeFactor < 1.0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )

            state.stressSummary?.let { stress ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Text("5× stress summary", fontWeight = FontWeight.SemiBold)
                Metric("Mean RTF", String.format(Locale.US, "%.3f", stress.meanRtf))
                Metric("Best / worst", String.format(Locale.US, "%.3f / %.3f", stress.bestRtf, stress.worstRtf))
            }
        }
    }
}

@Composable
private fun StatusCard(state: BenchmarkUiState) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Device & status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(state.status)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.deviceSnapshot?.let { d ->
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
                Metric("Device", d.device)
                Metric("OS", d.androidVersion)
                Metric("Process PSS", String.format(Locale.US, "%.1f MB", d.totalPssMb))
                Metric("Native PSS", String.format(Locale.US, "%.1f MB", d.nativePssMb))
                Metric("Native heap allocated", String.format(Locale.US, "%.1f MB", d.nativeHeapAllocatedMb))
                Metric("CPU cores", d.cpuCores.toString())
                Metric("Battery temp", d.batteryTemperatureC?.let { String.format(Locale.US, "%.1f °C", it) } ?: "Unavailable")
                Metric("Thermal status", d.thermalStatus)
            }
        }
    }
}

@Composable
private fun Metric(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private fun formatDuration(ms: Long): String = if (ms >= 1000) {
    String.format(Locale.US, "%.2f s", ms / 1000.0)
} else "$ms ms"

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
}
