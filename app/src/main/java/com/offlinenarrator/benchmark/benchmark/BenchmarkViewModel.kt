package com.offlinenarrator.benchmark.benchmark

import android.app.Application
import android.net.Uri
import ai.onnxruntime.OrtEnvironment
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinenarrator.benchmark.model.KokoroModelStore
import com.offlinenarrator.benchmark.tts.KokoroTtsEngine
import dev.ffmpegkit.kokoro.KokoroRuntimeProfile
import com.offlinenarrator.benchmark.tts.KittenTtsEngine
import com.offlinenarrator.benchmark.tts.SpeechRequest
import com.offlinenarrator.benchmark.tts.SynthesisResult
import com.offlinenarrator.benchmark.tts.SystemTtsEngine
import com.offlinenarrator.benchmark.tts.TtsEngine
import com.offlinenarrator.benchmark.tts.TtsVoice
import com.offlinenarrator.benchmark.util.AudioPlayer
import com.offlinenarrator.benchmark.util.DeviceDiagnostics
import com.offlinenarrator.benchmark.util.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File


data class BenchmarkUiState(
    val selectedEngineId: String = "system",
    val engineName: String = "Android System TTS",
    val engineDescription: String = "",
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val isSynthesizing: Boolean = false,
    val isPlaying: Boolean = false,
    val status: String = "Starting…",
    val error: String? = null,
    val text: String = BenchmarkPassages.narration,
    val speed: Float = 1.0f,
    val voices: List<TtsVoice> = emptyList(),
    val selectedVoiceId: String? = null,
    val result: SynthesisResult? = null,
    val stressSummary: BenchmarkSummary? = null,
    val deviceSnapshot: DeviceSnapshot? = null,
    val kokoroModelPresent: Boolean = false,
    val kokoroModelBytes: Long = 0L,
    val kokoroModelName: String? = null,
    val kokoroModelSha256: String? = null,
    val runtimeInfo: String? = null,
    val engineInitMs: Long? = null,
    val kokoroRuntimeProfile: String = "cpu_all_8",
    val fp16ModelPresent: Boolean = false,
    val fp16ModelBytes: Long = 0L,
    val fp16ModelName: String? = null,
    val fp16ModelSha256: String? = null,
    val isLabRunning: Boolean = false,
    val labProgress: String? = null,
    val labReport: PerformanceLabReport? = null,
)

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val modelStore = KokoroModelStore(app, "fp32")
    private val fp16ModelStore = KokoroModelStore(app, "fp16")
    private val audioPlayer = AudioPlayer()
    private val runtimePrefs = app.getSharedPreferences("kokoro_runtime", 0)

    private val _state = MutableStateFlow(
        BenchmarkUiState(
            kokoroModelPresent = modelStore.exists(),
            kokoroModelBytes = modelStore.sizeBytes(),
            kokoroRuntimeProfile = migrateKokoroProfileId(
                runtimePrefs.getString("profile", "cpu_all_8")
            ),
            fp16ModelPresent = fp16ModelStore.exists(),
            fp16ModelBytes = fp16ModelStore.sizeBytes(),
            deviceSnapshot = DeviceDiagnostics.capture(app),
        )
    )
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    private var engine: TtsEngine? = null
    private var synthesisJob: Job? = null

    init {
        if (fp16ModelStore.exists()) {
            viewModelScope.launch {
                val metadata = fp16ModelStore.metadata()
                _state.update {
                    it.copy(
                        fp16ModelPresent = fp16ModelStore.exists(),
                        fp16ModelBytes = fp16ModelStore.sizeBytes(),
                        fp16ModelName = metadata?.displayName,
                        fp16ModelSha256 = metadata?.sha256,
                    )
                }
            }
        }

        selectEngine(if (modelStore.exists()) "kokoro" else "system")
    }

    fun setText(value: String) = _state.update { it.copy(text = value) }
    fun setSpeed(value: Float) = _state.update { it.copy(speed = value) }
    fun chooseNarration() = setText(BenchmarkPassages.narration)
    fun chooseDialogue() = setText(BenchmarkPassages.dialogue)
    fun chooseNumbers() = setText(BenchmarkPassages.numbers)
    fun chooseLongForm() = setText(BenchmarkPassages.longForm)

    fun selectVoice(id: String) = _state.update { it.copy(selectedVoiceId = id) }

    fun selectEngine(id: String) {
        viewModelScope.launch {
            prepareEngine(id)
        }
    }

    private suspend fun prepareEngine(id: String) {
        _state.update {
            it.copy(
                selectedEngineId = id,
                isPreparing = true,
                isReady = false,
                error = null,
                result = null,
                stressSummary = null,
                status = "Preparing engine…",
                runtimeInfo = null,
                engineInitMs = null,
            )
        }

        audioPlayer.stop()
        withContext(Dispatchers.Default) { engine?.release() }
        engine = when (id) {
            "kokoro" -> KokoroTtsEngine(app, modelStore, kokoroProfileFromId(_state.value.kokoroRuntimeProfile))
            "experimental" -> KittenTtsEngine(app)
            else -> SystemTtsEngine(app)
        }
        val selected = checkNotNull(engine)

        if (id == "kokoro" && !modelStore.exists()) {
            _state.update {
                it.copy(
                    engineName = selected.displayName,
                    engineDescription = selected.description,
                    isPreparing = false,
                    isReady = false,
                    voices = emptyList(),
                    selectedVoiceId = null,
                    status = "Import a Kokoro ONNX model to continue.",
                    kokoroModelPresent = false,
                    kokoroModelBytes = 0L,
                    deviceSnapshot = DeviceDiagnostics.capture(app),
                )
            }
            return
        }

        val metadata = if (id == "kokoro") modelStore.metadata() else null
        val initStarted = System.nanoTime()
        val init = selected.initialize()
        val initMs = (System.nanoTime() - initStarted) / 1_000_000L
        val runtimeInfo = if (id == "kokoro") {
            val version = runCatching {
                OrtEnvironment.getEnvironment().version
            }.getOrNull() ?: "unknown"
            "ONNX Runtime $version · ${kokoroProfileDescription(_state.value.kokoroRuntimeProfile)}"
        } else null

        if (init.isSuccess) {
            val voices = selected.voices()
            _state.update {
                it.copy(
                    engineName = selected.displayName,
                    engineDescription = selected.description,
                    isPreparing = false,
                    isReady = true,
                    voices = voices,
                    selectedVoiceId = voices.firstOrNull()?.id,
                    status = "Ready — synthesis stays on this phone.",
                    deviceSnapshot = DeviceDiagnostics.capture(app),
                    kokoroModelPresent = modelStore.exists(),
                    kokoroModelBytes = modelStore.sizeBytes(),
                    kokoroModelName = metadata?.displayName,
                    kokoroModelSha256 = metadata?.sha256,
                    runtimeInfo = runtimeInfo,
                    engineInitMs = initMs,
                )
            }
        } else {
            _state.update {
                it.copy(
                    engineName = selected.displayName,
                    engineDescription = selected.description,
                    isPreparing = false,
                    isReady = false,
                    error = init.exceptionOrNull()?.message ?: "Engine initialization failed",
                    status = "Engine failed to initialize.",
                    deviceSnapshot = DeviceDiagnostics.capture(app),
                    kokoroModelName = metadata?.displayName,
                    kokoroModelSha256 = metadata?.sha256,
                    runtimeInfo = runtimeInfo,
                    engineInitMs = initMs,
                )
            }
        }
    }

    fun importKokoroModel(uri: Uri) {
        viewModelScope.launch {
            _state.update { it.copy(isPreparing = true, error = null, status = "Copying model into private app storage…") }
            val imported = modelStore.importFrom(uri)
            if (imported.isFailure) {
                _state.update {
                    it.copy(
                        isPreparing = false,
                        error = imported.exceptionOrNull()?.message ?: "Model import failed",
                        status = "Model import failed.",
                    )
                }
                return@launch
            }
            val metadata = modelStore.metadata()
            _state.update {
                it.copy(
                    kokoroModelPresent = true,
                    kokoroModelBytes = modelStore.sizeBytes(),
                    kokoroModelName = metadata?.displayName,
                    kokoroModelSha256 = metadata?.sha256,
                    status = "Model imported.",
                )
            }
            prepareEngine("kokoro")
        }
    }

    fun deleteKokoroModel() {
        viewModelScope.launch {
            if (_state.value.selectedEngineId == "kokoro") {
                withContext(Dispatchers.Default) { engine?.release() }
                engine = null
            }
            modelStore.delete()
            _state.update {
                it.copy(
                    kokoroModelPresent = false,
                    kokoroModelBytes = 0L,
                    kokoroModelName = null,
                    kokoroModelSha256 = null,
                    runtimeInfo = if (it.selectedEngineId == "kokoro") null else it.runtimeInfo,
                    engineInitMs = if (it.selectedEngineId == "kokoro") null else it.engineInitMs,
                    isReady = it.selectedEngineId != "kokoro" && it.isReady,
                    status = if (it.selectedEngineId == "kokoro") "Model removed. Import one to continue." else it.status,
                )
            }
        }
    }


    fun importKokoroFp16Model(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isPreparing = true,
                    error = null,
                    status = "Copying FP16 model into private app storage…",
                )
            }

            val imported = fp16ModelStore.importFrom(uri)
            if (imported.isFailure) {
                _state.update {
                    it.copy(
                        isPreparing = false,
                        error = imported.exceptionOrNull()?.message ?: "FP16 model import failed",
                        status = "FP16 model import failed.",
                    )
                }
                return@launch
            }

            val metadata = fp16ModelStore.metadata()
            _state.update {
                it.copy(
                    isPreparing = false,
                    fp16ModelPresent = true,
                    fp16ModelBytes = fp16ModelStore.sizeBytes(),
                    fp16ModelName = metadata?.displayName,
                    fp16ModelSha256 = metadata?.sha256,
                    status = if (metadata?.sha256 == OFFICIAL_FP16_SHA256) {
                        "Official Kokoro FP16 model imported."
                    } else {
                        "FP16 slot imported. Hash differs from the official v1.0 FP16 file."
                    },
                )
            }
        }
    }

    fun deleteKokoroFp16Model() {
        viewModelScope.launch {
            fp16ModelStore.delete()
            _state.update {
                it.copy(
                    fp16ModelPresent = false,
                    fp16ModelBytes = 0L,
                    fp16ModelName = null,
                    fp16ModelSha256 = null,
                    labReport = null,
                    status = "FP16 model removed.",
                )
            }
        }
    }


    fun selectKokoroRuntimeProfile(id: String) {
        if (id == _state.value.kokoroRuntimeProfile) return

        runtimePrefs.edit().putString("profile", id).apply()
        _state.update {
            it.copy(
                kokoroRuntimeProfile = id,
                result = null,
                stressSummary = null,
                error = null,
            )
        }

        if (_state.value.selectedEngineId == "kokoro") {
            selectEngine("kokoro")
        }
    }

    private fun migrateKokoroProfileId(id: String?): String = when (id) {
        // v0.13's CPU optimized profile used up to 8 cores with ALL_OPT.
        "cpu_optimized" -> "cpu_all_8"

        // XNNPACK was rejected on the Vivo benchmark. Migrate those stored
        // selections back to the proven CPU profile.
        "xnnpack_4", "xnnpack_6", "xnnpack_8" -> "cpu_all_8"

        "cpu_baseline", "cpu_all_2", "cpu_all_4", "cpu_all_6", "cpu_all_8" ->
            id ?: "cpu_all_8"

        else -> "cpu_all_8"
    }

    private fun kokoroProfileFromId(id: String): KokoroRuntimeProfile = when (id) {
        "cpu_all_2" -> KokoroRuntimeProfile.CPU_ALL_2
        "cpu_all_4" -> KokoroRuntimeProfile.CPU_ALL_4
        "cpu_all_6" -> KokoroRuntimeProfile.CPU_ALL_6
        "cpu_all_8" -> KokoroRuntimeProfile.CPU_ALL_8
        else -> KokoroRuntimeProfile.CPU_BASELINE
    }

    private fun kokoroProfileDescription(id: String): String = when (id) {
        "cpu_all_2" -> "CPU · ALL_OPT · 2 threads"
        "cpu_all_4" -> "CPU · ALL_OPT · 4 threads"
        "cpu_all_6" -> "CPU · ALL_OPT · 6 threads"
        "cpu_all_8" -> "CPU · ALL_OPT · 8 threads"
        else -> "CPU · BASIC_OPT · 4-thread baseline"
    }

    fun runPerformanceLab() {
        if (_state.value.isLabRunning || _state.value.isSynthesizing) return

        if (!modelStore.exists()) {
            _state.update {
                it.copy(
                    error = "Import the FP32 Kokoro model before running the lab.",
                    status = "Performance Lab needs the FP32 model.",
                )
            }
            return
        }

        synthesisJob?.cancel()
        synthesisJob = viewModelScope.launch {
            audioPlayer.stop()
            withContext(Dispatchers.Default) { engine?.release() }
            engine = null

            _state.update {
                it.copy(
                    isLabRunning = true,
                    isSynthesizing = false,
                    labProgress = "Preparing Kokoro Performance Lab…",
                    labReport = null,
                    error = null,
                    result = null,
                    stressSummary = null,
                    status = "Performance Lab running. Keep the phone awake and avoid other heavy apps.",
                )
            }

            data class LabModel(
                val label: String,
                val store: KokoroModelStore,
                val sha256: String?,
            )

            val fp32Metadata = modelStore.metadata()
            val models = mutableListOf(
                LabModel(
                    label = "FP32",
                    store = modelStore,
                    sha256 = fp32Metadata?.sha256,
                )
            )

            if (fp16ModelStore.exists()) {
                val fp16Metadata = fp16ModelStore.metadata()
                models += LabModel(
                    label = "FP16",
                    store = fp16ModelStore,
                    sha256 = fp16Metadata?.sha256,
                )
            }

            val profileIds = listOf(
                "cpu_all_2",
                "cpu_all_4",
                "cpu_all_6",
                "cpu_all_8",
            )

            val entries = mutableListOf<PerformanceLabEntry>()
            val totalConfigurations = models.size * profileIds.size
            var configurationIndex = 0

            for (model in models) {
                for (profileId in profileIds) {
                    configurationIndex += 1

                    val before = DeviceDiagnostics.capture(app)
                    if (before.thermalStatus in LAB_ABORT_THERMAL_STATES) {
                        _state.update {
                            it.copy(
                                labProgress = "Stopped before configuration $configurationIndex/$totalConfigurations because thermal status reached ${before.thermalStatus}.",
                            )
                        }
                        break
                    }

                    val label = kokoroProfileDescription(profileId)
                    _state.update {
                        it.copy(
                            labProgress = "Quick matrix $configurationIndex/$totalConfigurations · ${model.label} · $label",
                        )
                    }

                    val candidate = KokoroTtsEngine(
                        app,
                        model.store,
                        kokoroProfileFromId(profileId),
                    )
                    engine = candidate

                    val initStarted = System.nanoTime()
                    val init = candidate.initialize()
                    val initMs = (System.nanoTime() - initStarted) / 1_000_000L

                    if (init.isFailure) {
                        entries += PerformanceLabEntry(
                            modelLabel = model.label,
                            modelSha256 = model.sha256,
                            profileId = profileId,
                            profileLabel = label,
                            initMs = initMs,
                            generationMs = null,
                            audioMs = null,
                            rtf = null,
                            rssMb = null,
                            pssMb = null,
                            nativePssMb = null,
                            temperatureC = before.batteryTemperatureC,
                            thermalStatus = before.thermalStatus,
                            error = init.exceptionOrNull()?.message ?: "Initialization failed",
                        )
                        candidate.release()
                        engine = null
                        System.gc()
                        delay(LAB_COOLDOWN_MS)
                        continue
                    }

                    val run = synthesizeWithTimeout(
                        candidate,
                        SpeechRequest(
                            text = BenchmarkPassages.narration,
                            speed = 1.0f,
                            voiceId = candidate.voices().firstOrNull { voice ->
                                voice.id.contains("heart", ignoreCase = true)
                            }?.id ?: candidate.voices().firstOrNull()?.id,
                        ),
                        timeoutMs = LAB_SHORT_TIMEOUT_MS,
                    )

                    val after = DeviceDiagnostics.capture(app)
                    if (run.isSuccess) {
                        val result = run.getOrThrow()
                        entries += PerformanceLabEntry(
                            modelLabel = model.label,
                            modelSha256 = model.sha256,
                            profileId = profileId,
                            profileLabel = label,
                            initMs = initMs,
                            generationMs = result.generationTimeMs,
                            audioMs = result.audioDurationMs,
                            rtf = result.realTimeFactor,
                            rssMb = after.rssMb,
                            pssMb = after.totalPssMb,
                            nativePssMb = after.nativePssMb,
                            temperatureC = after.batteryTemperatureC,
                            thermalStatus = after.thermalStatus,
                        )
                    } else {
                        entries += PerformanceLabEntry(
                            modelLabel = model.label,
                            modelSha256 = model.sha256,
                            profileId = profileId,
                            profileLabel = label,
                            initMs = initMs,
                            generationMs = null,
                            audioMs = null,
                            rtf = null,
                            rssMb = after.rssMb,
                            pssMb = after.totalPssMb,
                            nativePssMb = after.nativePssMb,
                            temperatureC = after.batteryTemperatureC,
                            thermalStatus = after.thermalStatus,
                            error = run.exceptionOrNull()?.message ?: "Synthesis failed",
                        )
                    }

                    _state.update {
                        it.copy(
                            labReport = PerformanceLabReport(entries = entries.toList()),
                        )
                    }

                    candidate.release()
                    engine = null
                    System.gc()
                    delay(LAB_COOLDOWN_MS)
                }
            }

            val winner = entries
                .filter { it.succeeded }
                .minByOrNull { it.rtf ?: Double.POSITIVE_INFINITY }

            if (winner == null) {
                _state.update {
                    it.copy(
                        isLabRunning = false,
                        labProgress = null,
                        labReport = PerformanceLabReport(entries = entries),
                        error = "No Kokoro configuration completed successfully.",
                        status = "Performance Lab finished without a usable configuration.",
                        deviceSnapshot = DeviceDiagnostics.capture(app),
                    )
                }
                synthesisJob = null
                prepareEngine("kokoro")
                return@launch
            }

            val winnerStore = if (winner.modelLabel == "FP16") fp16ModelStore else modelStore
            val winnerEngine = KokoroTtsEngine(
                app,
                winnerStore,
                kokoroProfileFromId(winner.profileId),
            )
            engine = winnerEngine

            _state.update {
                it.copy(
                    labProgress = "Validating fastest candidate · ${winner.modelLabel} · ${winner.profileLabel}",
                )
            }

            val validationInit = winnerEngine.initialize()
            var representativeResult: SynthesisResult? = null
            var validation = PerformanceValidation(
                error = validationInit.exceptionOrNull()?.message,
            )

            if (validationInit.isSuccess) {
                val stressSamples = mutableListOf<Pair<Long, Long>>()
                var validationError: String? = null

                for (index in 0 until 5) {
                    _state.update {
                        it.copy(
                            labProgress = "Winner stress test ${index + 1}/5 · ${winner.modelLabel} · ${winner.profileLabel}",
                        )
                    }

                    val run = synthesizeWithTimeout(
                        winnerEngine,
                        SpeechRequest(
                            text = BenchmarkPassages.narration,
                            speed = 1.0f,
                            voiceId = winnerEngine.voices().firstOrNull { voice ->
                                voice.id.contains("heart", ignoreCase = true)
                            }?.id ?: winnerEngine.voices().firstOrNull()?.id,
                        ),
                        timeoutMs = LAB_SHORT_TIMEOUT_MS,
                    )

                    if (run.isFailure) {
                        validationError = run.exceptionOrNull()?.message ?: "Stress validation failed"
                        break
                    }

                    representativeResult = run.getOrThrow()
                    stressSamples += representativeResult!!.generationTimeMs to representativeResult!!.audioDurationMs
                }

                val stressSummary = if (stressSamples.isNotEmpty()) {
                    BenchmarkMetrics.summarize(stressSamples)
                } else null

                var longResult: SynthesisResult? = null
                if (validationError == null) {
                    _state.update {
                        it.copy(
                            labProgress = "Long-text validation · token-aware chunking · ${winner.modelLabel}",
                        )
                    }

                    val longRun = synthesizeWithTimeout(
                        winnerEngine,
                        SpeechRequest(
                            text = BenchmarkPassages.longForm,
                            speed = 1.0f,
                            voiceId = winnerEngine.voices().firstOrNull { voice ->
                                voice.id.contains("heart", ignoreCase = true)
                            }?.id ?: winnerEngine.voices().firstOrNull()?.id,
                        ),
                        timeoutMs = LAB_LONG_TIMEOUT_MS,
                    )

                    if (longRun.isSuccess) {
                        longResult = longRun.getOrThrow()
                        representativeResult = longResult
                    } else {
                        validationError = longRun.exceptionOrNull()?.message ?: "Long-text validation failed"
                    }
                }

                val finalSnapshot = DeviceDiagnostics.capture(app)
                validation = PerformanceValidation(
                    stressMeanRtf = stressSummary?.meanRtf,
                    stressBestRtf = stressSummary?.bestRtf,
                    stressWorstRtf = stressSummary?.worstRtf,
                    longGenerationMs = longResult?.generationTimeMs,
                    longAudioMs = longResult?.audioDurationMs,
                    longRtf = longResult?.realTimeFactor,
                    finalRssMb = finalSnapshot.rssMb,
                    finalPssMb = finalSnapshot.totalPssMb,
                    finalTemperatureC = finalSnapshot.batteryTemperatureC,
                    finalThermalStatus = finalSnapshot.thermalStatus,
                    error = validationError,
                )
            }

            winnerEngine.release()
            engine = null
            System.gc()
            delay(LAB_COOLDOWN_MS)

            runtimePrefs.edit().putString("profile", winner.profileId).apply()
            _state.update { it.copy(kokoroRuntimeProfile = winner.profileId) }

            val report = PerformanceLabReport(
                entries = entries,
                winnerModelLabel = winner.modelLabel,
                winnerProfileId = winner.profileId,
                winnerProfileLabel = winner.profileLabel,
                winnerRtf = winner.rtf,
                validation = validation,
            )

            // Restore the normal Quality engine to FP32 after the lab. The lab
            // report preserves the fastest model/precision independently.
            prepareEngine("kokoro")
            _state.update {
                it.copy(
                    isLabRunning = false,
                    labProgress = null,
                    labReport = report,
                    kokoroRuntimeProfile = winner.profileId,
                    result = representativeResult,
                    status = buildString {
                        append("Performance Lab complete. Fastest measured: ")
                        append(winner.modelLabel)
                        append(" · ")
                        append(winner.profileLabel)
                        winner.rtf?.let { rtf ->
                            append(" · RTF ")
                            append(String.format(java.util.Locale.US, "%.3f", rtf))
                        }
                    },
                    deviceSnapshot = DeviceDiagnostics.capture(app),
                )
            }
            synthesisJob = null
        }
    }

    fun synthesize() {
        val snapshot = _state.value
        val active = engine ?: return
        if (!snapshot.isReady || snapshot.isSynthesizing || snapshot.text.isBlank()) return

        synthesisJob?.cancel()
        synthesisJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isSynthesizing = true,
                    error = null,
                    status = "Generating locally…",
                    stressSummary = null,
                )
            }

            val result = synthesizeWithTimeout(
                active,
                SpeechRequest(
                    text = snapshot.text,
                    speed = snapshot.speed,
                    voiceId = snapshot.selectedVoiceId,
                )
            )

            if (result.isSuccess) {
                _state.update {
                    it.copy(
                        isSynthesizing = false,
                        result = result.getOrThrow(),
                        status = "Generated. Ready to play.",
                        deviceSnapshot = DeviceDiagnostics.capture(app),
                    )
                }
            } else {
                _state.update {
                    it.copy(
                        isSynthesizing = false,
                        error = result.exceptionOrNull()?.message ?: "Synthesis failed",
                        status = "Synthesis failed.",
                        deviceSnapshot = DeviceDiagnostics.capture(app),
                    )
                }
            }
            synthesisJob = null
        }
    }

    fun runStress(iterations: Int = 5) {
        val snapshot = _state.value
        val active = engine ?: return
        if (!snapshot.isReady || snapshot.isSynthesizing || snapshot.text.isBlank()) return

        synthesisJob?.cancel()
        synthesisJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    isSynthesizing = true,
                    error = null,
                    status = "Running $iterations local synthesis passes…",
                    stressSummary = null,
                )
            }

            val samples = mutableListOf<Pair<Long, Long>>()
            var last: SynthesisResult? = null

            repeat(iterations) { index ->
                _state.update { it.copy(status = "Stress run ${index + 1} of $iterations…") }

                val run = synthesizeWithTimeout(
                    active,
                    SpeechRequest(snapshot.text, snapshot.speed, snapshot.selectedVoiceId)
                )

                if (run.isFailure) {
                    _state.update {
                        it.copy(
                            isSynthesizing = false,
                            error = run.exceptionOrNull()?.message ?: "Stress run failed",
                            status = "Stress test stopped.",
                            deviceSnapshot = DeviceDiagnostics.capture(app),
                        )
                    }
                    synthesisJob = null
                    return@launch
                }

                last = run.getOrThrow()
                samples += last!!.generationTimeMs to last!!.audioDurationMs
            }

            _state.update {
                it.copy(
                    isSynthesizing = false,
                    result = last,
                    stressSummary = BenchmarkMetrics.summarize(samples),
                    status = "Stress test complete.",
                    deviceSnapshot = DeviceDiagnostics.capture(app),
                )
            }
            synthesisJob = null
        }
    }

    fun cancelSynthesis() {
        synthesisJob?.cancel()
        synthesisJob = null
        engine?.cancel()
        _state.update {
            it.copy(
                isSynthesizing = false,
                isLabRunning = false,
                labProgress = null,
                status = "Synthesis cancel requested.",
            )
        }
    }

    private suspend fun synthesizeWithTimeout(
        active: TtsEngine,
        request: SpeechRequest,
        timeoutMs: Long = SYNTHESIS_TIMEOUT_MS,
    ): Result<SynthesisResult> {
        return try {
            withTimeout(timeoutMs) {
                active.synthesize(request)
            }
        } catch (_: TimeoutCancellationException) {
            active.cancel()
            val diagnostic = active.diagnosticStatus()
            Result.failure(
                IllegalStateException(
                    buildString {
                        append("Synthesis timed out after ${timeoutMs / 1000} seconds.")
                        if (!diagnostic.isNullOrBlank()) {
                            append("\nEngine stage: ")
                            append(diagnostic)
                        }
                    }
                )
            )
        }
    }

    fun playResult() {
        val file: File = _state.value.result?.audioFile ?: return
        if (!file.exists()) return
        _state.update { it.copy(isPlaying = true) }
        audioPlayer.play(file) {
            _state.update { it.copy(isPlaying = false) }
        }
    }

    fun stopPlayback() {
        audioPlayer.stop()
        _state.update { it.copy(isPlaying = false) }
    }

    override fun onCleared() {
        synthesisJob?.cancel()
        engine?.cancel()
        audioPlayer.release()
        engine?.release()
        engine = null
        super.onCleared()
    }

    private companion object {
        const val SYNTHESIS_TIMEOUT_MS = 180_000L
        const val LAB_SHORT_TIMEOUT_MS = 180_000L
        const val LAB_LONG_TIMEOUT_MS = 600_000L
        const val LAB_COOLDOWN_MS = 750L

        const val OFFICIAL_FP16_SHA256 =
            "ba4527a874b42b21e35f468c10d326fdff3c7fc8cac1f85e9eb6c0dfc35c334a"

        val LAB_ABORT_THERMAL_STATES = setOf(
            "Severe",
            "Critical",
            "Emergency",
            "Shutdown",
        )
    }
}
