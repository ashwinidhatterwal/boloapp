package com.offlinenarrator.benchmark.benchmark

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinenarrator.benchmark.model.KokoroModelStore
import com.offlinenarrator.benchmark.tts.KokoroTtsEngine
import com.offlinenarrator.benchmark.tts.SpeechRequest
import com.offlinenarrator.benchmark.tts.SupertonicTtsEngine
import com.offlinenarrator.benchmark.tts.SynthesisResult
import com.offlinenarrator.benchmark.tts.TtsEngine
import com.offlinenarrator.benchmark.tts.TtsVoice
import com.offlinenarrator.benchmark.util.AudioPlayer
import com.offlinenarrator.benchmark.util.DeviceDiagnostics
import com.offlinenarrator.benchmark.util.DeviceSnapshot
import dev.ffmpegkit.kokoro.KokoroRuntimeProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val selectedEngineId: String = "kokoro",
    val engineName: String = "Kokoro 82M",
    val engineDescription: String = "",
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val isSynthesizing: Boolean = false,
    val isPlaying: Boolean = false,
    val status: String = "Starting…",
    val error: String? = null,
    val text: String = BenchmarkPassages.narration,
    val voices: List<TtsVoice> = emptyList(),
    val selectedVoiceId: String? = null,
    val result: SynthesisResult? = null,
    val deviceSnapshot: DeviceSnapshot? = null,
    val kokoroModelPresent: Boolean = false,
)

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val kokoroStore = KokoroModelStore(app, "fp32")
    private val audioPlayer = AudioPlayer()

    private val _state = MutableStateFlow(
        BenchmarkUiState(
            kokoroModelPresent = kokoroStore.exists(),
            deviceSnapshot = DeviceDiagnostics.capture(app),
        )
    )
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    private var engine: TtsEngine? = null
    private var synthesisJob: Job? = null

    init {
        selectEngine(if (kokoroStore.exists()) "kokoro" else "supertonic")
    }

    fun setText(value: String) = _state.update { it.copy(text = value) }
    fun useSample() = setText(BenchmarkPassages.narration)
    fun selectVoice(id: String) = _state.update { it.copy(selectedVoiceId = id) }

    fun selectEngine(id: String) {
        viewModelScope.launch { prepareEngine(id) }
    }

    private suspend fun prepareEngine(id: String) {
        _state.update {
            it.copy(
                selectedEngineId = id,
                isPreparing = true,
                isReady = false,
                isPlaying = false,
                error = null,
                result = null,
                status = "Preparing…",
            )
        }

        audioPlayer.stop()
        withContext(Dispatchers.Default) { engine?.release() }

        engine = when (id) {
            "supertonic" -> SupertonicTtsEngine(app)
            else -> KokoroTtsEngine(
                app,
                kokoroStore,
                KokoroRuntimeProfile.CPU_ALL_8,
            )
        }

        val selected = checkNotNull(engine)

        if (id == "kokoro" && !kokoroStore.exists()) {
            _state.update {
                it.copy(
                    engineName = "Kokoro 82M",
                    engineDescription = "Natural local neural voice · ONNX FP32.",
                    isPreparing = false,
                    isReady = false,
                    voices = emptyList(),
                    selectedVoiceId = null,
                    status = "Import the Kokoro FP32 model.",
                    kokoroModelPresent = false,
                )
            }
            return
        }

        val init = selected.initialize()
        val voices = if (init.isSuccess) selected.voices() else emptyList()

        _state.update {
            it.copy(
                engineName = selected.displayName,
                engineDescription = selected.description,
                isPreparing = false,
                isReady = init.isSuccess,
                voices = voices,
                selectedVoiceId = voices.firstOrNull()?.id,
                status = if (init.isSuccess) {
                    "Ready · runs locally."
                } else {
                    "Engine setup required."
                },
                error = init.exceptionOrNull()?.message,
                deviceSnapshot = DeviceDiagnostics.capture(app),
                kokoroModelPresent = kokoroStore.exists(),
            )
        }
    }

    fun importKokoroModel(uri: Uri) {
        viewModelScope.launch {
            _state.update {
                it.copy(
                    isPreparing = true,
                    error = null,
                    status = "Importing Kokoro model…",
                )
            }

            val result = kokoroStore.importFrom(uri)
            if (result.isFailure) {
                _state.update {
                    it.copy(
                        isPreparing = false,
                        error = result.exceptionOrNull()?.message ?: "Import failed",
                        status = "Kokoro model import failed.",
                    )
                }
                return@launch
            }

            _state.update {
                it.copy(
                    kokoroModelPresent = true,
                    status = "Kokoro model imported.",
                )
            }
            prepareEngine("kokoro")
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
                    result = null,
                    status = "Generating locally…",
                )
            }

            val result = try {
                withTimeout(SYNTHESIS_TIMEOUT_MS) {
                    active.synthesize(
                        SpeechRequest(
                            text = snapshot.text,
                            speed = 1.0f,
                            voiceId = snapshot.selectedVoiceId,
                        )
                    )
                }
            } catch (_: TimeoutCancellationException) {
                active.cancel()
                Result.failure(
                    IllegalStateException("Synthesis timed out after 180 seconds.")
                )
            }

            _state.update {
                if (result.isSuccess) {
                    it.copy(
                        isSynthesizing = false,
                        result = result.getOrThrow(),
                        status = "Generated · listen and compare.",
                        deviceSnapshot = DeviceDiagnostics.capture(app),
                    )
                } else {
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

    fun cancelSynthesis() {
        synthesisJob?.cancel()
        synthesisJob = null
        engine?.cancel()
        _state.update { it.copy(isSynthesizing = false, status = "Cancelled.") }
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
    }
}
