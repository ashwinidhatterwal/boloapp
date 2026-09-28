package com.offlinenarrator.benchmark.benchmark

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinenarrator.benchmark.model.KokoroModelStore
import com.offlinenarrator.benchmark.tts.KokoroTtsEngine
import com.offlinenarrator.benchmark.tts.SpeechRequest
import com.offlinenarrator.benchmark.tts.SynthesisResult
import com.offlinenarrator.benchmark.tts.SystemTtsEngine
import com.offlinenarrator.benchmark.tts.TtsEngine
import com.offlinenarrator.benchmark.tts.TtsVoice
import com.offlinenarrator.benchmark.util.AudioPlayer
import com.offlinenarrator.benchmark.util.DeviceDiagnostics
import com.offlinenarrator.benchmark.util.DeviceSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
)

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val modelStore = KokoroModelStore(app)
    private val audioPlayer = AudioPlayer()

    private val _state = MutableStateFlow(
        BenchmarkUiState(
            kokoroModelPresent = modelStore.exists(),
            kokoroModelBytes = modelStore.sizeBytes(),
            deviceSnapshot = DeviceDiagnostics.capture(app),
        )
    )
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    private var engine: TtsEngine? = null

    init {
        selectEngine("system")
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
            )
        }

        audioPlayer.stop()
        withContext(Dispatchers.Default) { engine?.release() }
        engine = when (id) {
            "kokoro" -> KokoroTtsEngine(app, modelStore)
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

        val init = selected.initialize()
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
            _state.update {
                it.copy(
                    kokoroModelPresent = true,
                    kokoroModelBytes = modelStore.sizeBytes(),
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
                    isReady = it.selectedEngineId != "kokoro" && it.isReady,
                    status = if (it.selectedEngineId == "kokoro") "Model removed. Import one to continue." else it.status,
                )
            }
        }
    }

    fun synthesize() {
        val snapshot = _state.value
        val active = engine ?: return
        if (!snapshot.isReady || snapshot.isSynthesizing || snapshot.text.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isSynthesizing = true, error = null, status = "Generating locally…", stressSummary = null) }
            val result = active.synthesize(
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
        }
    }

    fun runStress(iterations: Int = 5) {
        val snapshot = _state.value
        val active = engine ?: return
        if (!snapshot.isReady || snapshot.isSynthesizing || snapshot.text.isBlank()) return

        viewModelScope.launch {
            _state.update { it.copy(isSynthesizing = true, error = null, status = "Running $iterations local synthesis passes…", stressSummary = null) }
            val samples = mutableListOf<Pair<Long, Long>>()
            var last: SynthesisResult? = null
            repeat(iterations) { index ->
                _state.update { it.copy(status = "Stress run ${index + 1} of $iterations…") }
                val run = active.synthesize(
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
        audioPlayer.release()
        engine?.release()
        engine = null
        super.onCleared()
    }
}
