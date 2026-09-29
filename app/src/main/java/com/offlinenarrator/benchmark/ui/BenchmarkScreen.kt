package com.offlinenarrator.benchmark.ui

import android.content.Intent
import android.net.Uri
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
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BenchmarkScreen(viewModel: BenchmarkViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var advancedExpanded by remember { mutableStateOf(false) }

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::importKokoroModel) },
    )

    val fp16ModelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri -> uri?.let(viewModel::importKokoroFp16Model) },
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bolo Voice Lab")
                        Text(
                            "Local TTS benchmark",
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
            EngineCard(
                state = state,
                onQuality = { viewModel.selectEngine("kokoro") },
                onExperimental = { viewModel.selectEngine("experimental") },
                onSystem = { viewModel.selectEngine("system") },
                onImport = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
            )

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            PassagePicker(
                onNarration = viewModel::chooseNarration,
                onDialogue = viewModel::chooseDialogue,
                onNumbers = viewModel::chooseNumbers,
                onLong = viewModel::chooseLongForm,
            )

            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::setText,
                label = { Text("Text") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
            )

            VoiceAndSpeedCard(
                state = state,
                onSpeed = viewModel::setSpeed,
                onVoice = viewModel::selectVoice,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = viewModel::synthesize,
                    enabled = state.isReady && !state.isSynthesizing && !state.isLabRunning && state.text.isNotBlank(),
                ) {
                    Text(if (state.isSynthesizing) "Working…" else "Generate")
                }

                OutlinedButton(
                    onClick = viewModel::playResult,
                    enabled = state.result != null && !state.isPlaying && !state.isLabRunning,
                ) {
                    Text("Play")
                }

                if (state.isPlaying) {
                    TextButton(onClick = viewModel::stopPlayback) {
                        Text("Stop")
                    }
                }
            }

            if (state.isSynthesizing || state.isLabRunning) {
                TextButton(onClick = viewModel::cancelSynthesis) {
                    Text(if (state.isLabRunning) "Cancel lab" else "Cancel synthesis")
                }
            }

            state.result?.let {
                ResultSummaryCard(state)
            }

            AdvancedCard(
                state = state,
                expanded = advancedExpanded,
                onToggle = { advancedExpanded = !advancedExpanded },
                onStress = { viewModel.runStress(5) },
                onImport = { modelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                onDeleteModel = viewModel::deleteKokoroModel,
                onImportFp16 = { fp16ModelPicker.launch(arrayOf("application/octet-stream", "*/*")) },
                onDeleteFp16 = viewModel::deleteKokoroFp16Model,
                onRunPerformanceLab = viewModel::runPerformanceLab,
                onRuntimeProfile = viewModel::selectKokoroRuntimeProfile,
                onOpenModelPage = {
                    context.startActivity(
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("https://huggingface.co/onnx-community/Kokoro-82M-v1.0-ONNX/tree/main/onnx")
                        )
                    )
                },
            )

            Spacer(Modifier.height(8.dp))
            Text(
                "All synthesis in this benchmark stays on-device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun EngineCard(
    state: BenchmarkUiState,
    onQuality: () -> Unit,
    onExperimental: () -> Unit,
    onSystem: () -> Unit,
    onImport: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Engine", fontWeight = FontWeight.Bold)

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(
                    selected = state.selectedEngineId == "kokoro",
                    onClick = onQuality,
                    label = { Text("Quality") },
                )
                FilterChip(
                    selected = state.selectedEngineId == "experimental",
                    onClick = onExperimental,
                    label = { Text("Experimental") },
                )
                FilterChip(
                    selected = state.selectedEngineId == "system",
                    onClick = onSystem,
                    label = { Text("System") },
                )
            }

            Text(state.engineName, fontWeight = FontWeight.SemiBold)
            Text(
                state.engineDescription,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.selectedEngineId == "kokoro") {
                if (state.kokoroModelPresent) {
                    Text(
                        (state.kokoroModelName ?: "Kokoro model") +
                            " · " + formatBytes(state.kokoroModelBytes),
                        style = MaterialTheme.typography.bodySmall,
                    )
                } else {
                    Button(onClick = onImport) {
                        Text("Import Kokoro model")
                    }
                }
            }

            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PassagePicker(
    onNarration: () -> Unit,
    onDialogue: () -> Unit,
    onNumbers: () -> Unit,
    onLong: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Passage", fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onNarration) { Text("Narration") }
                TextButton(onClick = onDialogue) { Text("Dialogue") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onNumbers) { Text("Numbers") }
                TextButton(onClick = onLong) { Text("Long") }
            }
        }
    }
}

@Composable
private fun VoiceAndSpeedCard(
    state: BenchmarkUiState,
    onSpeed: (Float) -> Unit,
    onVoice: (String) -> Unit,
) {
    var voiceMenuExpanded by remember { mutableStateOf(false) }

    val selectedVoice = state.voices.firstOrNull { it.id == state.selectedVoiceId }
    val selectedVoiceLabel = selectedVoice?.let {
        if (it.language.isNullOrBlank()) it.name else it.name + " · " + it.language
    } ?: "No voice"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Speed", fontWeight = FontWeight.SemiBold)
                Text(String.format(Locale.US, "%.2fx", state.speed))
            }

            Slider(
                value = state.speed,
                onValueChange = onSpeed,
                valueRange = 0.75f..1.5f,
                steps = 14,
            )

            if (state.voices.isNotEmpty()) {
                Text("Voice", fontWeight = FontWeight.SemiBold)

                Box {
                    OutlinedButton(
                        onClick = { voiceMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selectedVoiceLabel)
                    }

                    DropdownMenu(
                        expanded = voiceMenuExpanded,
                        onDismissRequest = { voiceMenuExpanded = false },
                    ) {
                        state.voices.forEach { voice ->
                            val label = if (voice.language.isNullOrBlank()) {
                                voice.name
                            } else {
                                voice.name + " · " + voice.language
                            }

                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    onVoice(voice.id)
                                    voiceMenuExpanded = false
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ResultSummaryCard(state: BenchmarkUiState) {
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
                "Speed",
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
private fun AdvancedCard(
    state: BenchmarkUiState,
    expanded: Boolean,
    onToggle: () -> Unit,
    onStress: () -> Unit,
    onImport: () -> Unit,
    onDeleteModel: () -> Unit,
    onImportFp16: () -> Unit,
    onDeleteFp16: () -> Unit,
    onRunPerformanceLab: () -> Unit,
    onRuntimeProfile: (String) -> Unit,
    onOpenModelPage: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Advanced", fontWeight = FontWeight.SemiBold)
                TextButton(onClick = onToggle) {
                    Text(if (expanded) "Hide" else "Show")
                }
            }

            if (!expanded) return@Column

            if (state.selectedEngineId == "kokoro") {
                HorizontalDivider()
                Text("Kokoro model", fontWeight = FontWeight.SemiBold)

                if (state.kokoroModelPresent) {
                    Metric(
                        "Model",
                        (state.kokoroModelName ?: "Imported") +
                            " · " + formatBytes(state.kokoroModelBytes),
                    )
                    state.kokoroModelSha256?.let {
                        Metric("SHA-256", it.take(16) + "…")
                    }
                    state.runtimeInfo?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Text("Runtime profile", fontWeight = FontWeight.SemiBold)
                    RuntimeProfileSelector(
                        selected = state.kokoroRuntimeProfile,
                        onSelect = onRuntimeProfile,
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onImport) { Text("Replace") }
                        TextButton(onClick = onDeleteModel) { Text("Remove") }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(onClick = onImport) { Text("Import") }
                        OutlinedButton(onClick = onOpenModelPage) { Text("Model source") }
                    }
                }
            }

            if (state.selectedEngineId == "kokoro") {
                HorizontalDivider()
                PerformanceLabSection(
                    state = state,
                    onImportFp16 = onImportFp16,
                    onDeleteFp16 = onDeleteFp16,
                    onRun = onRunPerformanceLab,
                    onOpenModelPage = onOpenModelPage,
                )
            }

            if (state.selectedEngineId == "experimental") {
                HorizontalDivider()
                Text("Experimental provider", fontWeight = FontWeight.SemiBold)
                Text(
                    "External local TTS service · current provider: KittenTTS family.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            state.engineInitMs?.let {
                Metric("Engine init", formatDuration(it))
            }

            HorizontalDivider()
            OutlinedButton(
                onClick = onStress,
                enabled = state.isReady && !state.isSynthesizing && !state.isLabRunning && state.text.isNotBlank(),
            ) {
                Text("Run 5× stress test")
            }

            state.stressSummary?.let { stress ->
                Metric("Mean RTF", String.format(Locale.US, "%.3f", stress.meanRtf))
                Metric(
                    "Best / worst",
                    String.format(Locale.US, "%.3f / %.3f", stress.bestRtf, stress.worstRtf),
                )
            }

            HorizontalDivider()
            Text("Diagnostics", fontWeight = FontWeight.SemiBold)
            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error)
            }

            state.deviceSnapshot?.let { d ->
                Metric("Device", d.device)
                Metric("OS", d.androidVersion)
                Metric("Process PSS (resident share)", String.format(Locale.US, "%.1f MB", d.totalPssMb))
                Metric("Native PSS (resident share)", String.format(Locale.US, "%.1f MB", d.nativePssMb))
                Metric(
                    "Native allocated (not RSS)",
                    String.format(Locale.US, "%.1f MB", d.nativeHeapAllocatedMb),
                )
                Metric("Process RSS", String.format(Locale.US, "%.1f MB", d.rssMb))
                Metric("Java heap used", String.format(Locale.US, "%.1f MB", d.javaHeapUsedMb))
                Metric("System RAM available", String.format(Locale.US, "%.0f MB", d.systemAvailableMb))
                Metric("CPU cores", d.cpuCores.toString())
                Metric(
                    "Battery",
                    d.batteryTemperatureC?.let {
                        String.format(Locale.US, "%.1f °C", it)
                    } ?: "Unavailable",
                )
                Metric("Thermal", d.thermalStatus)
            }
        }
    }
}

@Composable
private fun PerformanceLabSection(
    state: BenchmarkUiState,
    onImportFp16: () -> Unit,
    onDeleteFp16: () -> Unit,
    onRun: () -> Unit,
    onOpenModelPage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Kokoro Performance Lab", fontWeight = FontWeight.Bold)
        Text(
            "One run compares FP32 and, when installed, FP16 across CPU 2/4/6/8. The fastest successful configuration is then stress-tested and validated on long text.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Metric(
            "FP32",
            if (state.kokoroModelPresent) {
                "${formatBytes(state.kokoroModelBytes)} · ready"
            } else {
                "missing"
            },
        )

        if (state.fp16ModelPresent) {
            Metric(
                "FP16",
                "${formatBytes(state.fp16ModelBytes)} · ready",
            )
            state.fp16ModelSha256?.let { sha ->
                Text(
                    "FP16 SHA-256 ${sha.take(16)}…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onImportFp16,
                    enabled = !state.isLabRunning,
                ) { Text("Replace FP16") }
                TextButton(
                    onClick = onDeleteFp16,
                    enabled = !state.isLabRunning,
                ) { Text("Remove FP16") }
            }
        } else {
            Text(
                "FP16 is optional. Import official model_fp16.onnx to add precision comparison; otherwise the suite still benchmarks FP32.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(
                    onClick = onImportFp16,
                    enabled = !state.isLabRunning,
                ) { Text("Import FP16") }
                TextButton(onClick = onOpenModelPage) { Text("Model source") }
            }
        }

        Button(
            onClick = onRun,
            enabled = state.kokoroModelPresent && !state.isLabRunning && !state.isSynthesizing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (state.isLabRunning) "Performance Lab running…" else "Run full Kokoro suite")
        }

        state.labProgress?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }

        state.labReport?.let { report ->
            if (report.entries.isNotEmpty()) {
                Text("Quick matrix", fontWeight = FontWeight.SemiBold)

                report.entries.forEach { entry ->
                    val value = if (entry.succeeded) {
                        buildString {
                            append("RTF ")
                            append(String.format(Locale.US, "%.3f", entry.rtf))
                            entry.rssMb?.let {
                                append(" · RSS ")
                                append(String.format(Locale.US, "%.0f MB", it))
                            }
                            entry.temperatureC?.let {
                                append(" · ")
                                append(String.format(Locale.US, "%.1f °C", it))
                            }
                        }
                    } else {
                        "Failed · ${entry.error ?: "unknown error"}"
                    }

                    Text(
                        "${entry.modelLabel} · ${entry.profileLabel}",
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        value,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (entry.succeeded) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
            }

            if (report.winnerModelLabel != null) {
                HorizontalDivider()
                Text("Fastest measured", fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(report.winnerModelLabel)
                        append(" · ")
                        append(report.winnerProfileLabel ?: report.winnerProfileId.orEmpty())
                        report.winnerRtf?.let {
                            append(" · RTF ")
                            append(String.format(Locale.US, "%.3f", it))
                        }
                    }
                )

                report.validation?.let { validation ->
                    validation.stressMeanRtf?.let {
                        Metric("5× mean RTF", String.format(Locale.US, "%.3f", it))
                    }
                    if (validation.stressBestRtf != null && validation.stressWorstRtf != null) {
                        Metric(
                            "5× best / worst",
                            String.format(
                                Locale.US,
                                "%.3f / %.3f",
                                validation.stressBestRtf,
                                validation.stressWorstRtf,
                            ),
                        )
                    }
                    validation.longRtf?.let {
                        Metric("Long-text RTF", String.format(Locale.US, "%.3f", it))
                    }
                    validation.finalRssMb?.let {
                        Metric("Final RSS", String.format(Locale.US, "%.0f MB", it))
                    }
                    validation.finalTemperatureC?.let {
                        Metric("Final battery temp", String.format(Locale.US, "%.1f °C", it))
                    }
                    validation.finalThermalStatus?.let {
                        Metric("Final thermal", it)
                    }
                    validation.error?.let {
                        Text(
                            "Validation issue: $it",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RuntimeProfileSelector(
    selected: String,
    onSelect: (String) -> Unit,
) {
    val profiles = listOf(
        "cpu_baseline" to "Baseline",
        "cpu_all_2" to "CPU 2",
        "cpu_all_4" to "CPU 4",
        "cpu_all_6" to "CPU 6",
        "cpu_all_8" to "CPU 8",
    )

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        profiles.chunked(2).forEach { rowProfiles ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowProfiles.forEach { (id, label) ->
                    FilterChip(
                        selected = selected == id,
                        onClick = { onSelect(id) },
                        label = { Text(label) },
                    )
                }
            }
        }

        Text(
            "ALL_OPT profiles differ only by CPU thread count. Use the same Narration passage and 1.00× speed for a fair comparison.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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

private fun formatDuration(ms: Long): String = if (ms >= 1000) {
    String.format(Locale.US, "%.2f s", ms / 1000.0)
} else {
    ms.toString() + " ms"
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L ->
        String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L ->
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    else ->
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
}
