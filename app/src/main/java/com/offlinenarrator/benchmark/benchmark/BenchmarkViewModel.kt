package com.offlinenarrator.benchmark.benchmark

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.offlinenarrator.benchmark.model.KokoroModelStore
import com.offlinenarrator.benchmark.reader.NarrationCache
import com.offlinenarrator.benchmark.reader.NarrationSegmenter
import com.offlinenarrator.benchmark.reader.RollingAudioPlayer
import com.offlinenarrator.benchmark.tts.KokoroTtsEngine
import com.offlinenarrator.benchmark.tts.SpeechRequest
import com.offlinenarrator.benchmark.tts.TtsVoice
import com.offlinenarrator.benchmark.util.DeviceDiagnostics
import com.offlinenarrator.benchmark.util.DeviceSnapshot
import dev.ffmpegkit.kokoro.KokoroRuntimeProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlin.math.roundToInt

data class BenchmarkUiState(
    val isPreparing: Boolean = false,
    val isReady: Boolean = false,
    val isGenerating: Boolean = false,
    val readerStarted: Boolean = false,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val finished: Boolean = false,
    val status: String = "Starting…",
    val error: String? = null,
    val text: String = BenchmarkPassages.longForm,
    val voices: List<TtsVoice> = emptyList(),
    val selectedVoiceId: String? = null,
    val playbackSpeed: Float = 1.0f,
    val kokoroModelPresent: Boolean = false,
    val totalSegments: Int = 0,
    val generatedSegments: Int = 0,
    val currentSegment: Int = 0,
    val bufferedListeningMs: Long = 0L,
    val cacheHits: Int = 0,
    val meanGenerationRtf: Double? = null,
    val underruns: Int = 0,
    val thermalPaused: Boolean = false,
    val cacheBytes: Long = 0L,
    val deviceSnapshot: DeviceSnapshot? = null,
)

class BenchmarkViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application.applicationContext
    private val modelStore = KokoroModelStore(app, "fp32")
    private val cache = NarrationCache(app)
    private val engine = KokoroTtsEngine(
        app,
        modelStore,
        KokoroRuntimeProfile.CPU_ALL_8,
    )

    private val player = RollingAudioPlayer(app) {
        refreshPlaybackState()
    }

    private val _state = MutableStateFlow(
        BenchmarkUiState(
            kokoroModelPresent = modelStore.exists(),
            deviceSnapshot = DeviceDiagnostics.capture(app),
        )
    )
    val state: StateFlow<BenchmarkUiState> = _state.asStateFlow()

    private var readerJob: Job? = null
    private var progressJob: Job? = null

    init {
        viewModelScope.launch {
            _state.update { it.copy(cacheBytes = cache.sizeBytes()) }
            prepareEngine()
        }
    }

    fun setText(value: String) {
        if (_state.value.readerStarted) return
        _state.update { it.copy(text = value) }
    }

    fun useSample() {
        if (_state.value.readerStarted) return
        _state.update { it.copy(text = BenchmarkPassages.longForm) }
    }

    fun selectVoice(id: String) {
        if (_state.value.readerStarted) return
        _state.update { it.copy(selectedVoiceId = id) }
    }

    fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(0.75f, 2.0f)
        player.setSpeed(safe)
        _state.update { it.copy(playbackSpeed = safe) }
        refreshPlaybackState()
    }

    private suspend fun prepareEngine() {
        _state.update {
            it.copy(
                isPreparing = true,
                isReady = false,
                error = null,
                status = if (modelStore.exists()) "Loading Kokoro…" else "Import Kokoro model to begin.",
                kokoroModelPresent = modelStore.exists(),
            )
        }

        if (!modelStore.exists()) {
            _state.update {
                it.copy(
                    isPreparing = false,
                    isReady = false,
                    voices = emptyList(),
                    selectedVoiceId = null,
                )
            }
            return
        }

        val result = engine.initialize()
        val voices = if (result.isSuccess) engine.voices() else emptyList()
        val preferred = voices.firstOrNull { it.id == "af_heart" } ?: voices.firstOrNull()

        _state.update {
            it.copy(
                isPreparing = false,
                isReady = result.isSuccess,
                voices = voices,
                selectedVoiceId = preferred?.id,
                status = if (result.isSuccess) {
                    "Ready · narration stays on-device."
                } else {
                    "Kokoro failed to load."
                },
                error = result.exceptionOrNull()?.message,
                deviceSnapshot = DeviceDiagnostics.capture(app),
                kokoroModelPresent = modelStore.exists(),
            )
        }
    }

    fun importKokoroModel(uri: Uri) {
        stopReading()

        viewModelScope.launch {
            _state.update {
                it.copy(
                    isPreparing = true,
                    error = null,
                    status = "Importing Kokoro model…",
                )
            }

            val result = modelStore.importFrom(uri)
            if (result.isFailure) {
                _state.update {
                    it.copy(
                        isPreparing = false,
                        error = result.exceptionOrNull()?.message ?: "Import failed",
                        status = "Model import failed.",
                    )
                }
                return@launch
            }

            prepareEngine()
        }
    }

    fun startReading() {
        val snapshot = _state.value
        if (!snapshot.isReady || snapshot.text.isBlank() || readerJob?.isActive == true) return

        stopReading()

        val segments = NarrationSegmenter.split(snapshot.text)
        if (segments.isEmpty()) {
            _state.update { it.copy(error = "Nothing to read.") }
            return
        }

        val voiceId = snapshot.selectedVoiceId ?: "af_heart"
        val speed = snapshot.playbackSpeed

        player.reset(speed)

        _state.update {
            it.copy(
                isGenerating = true,
                readerStarted = true,
                isPlaying = false,
                isPaused = false,
                finished = false,
                status = "Preparing first audio…",
                error = null,
                totalSegments = segments.size,
                generatedSegments = 0,
                currentSegment = 0,
                bufferedListeningMs = 0L,
                cacheHits = 0,
                meanGenerationRtf = null,
                underruns = 0,
                thermalPaused = false,
            )
        }

        startProgressTicker()

        readerJob = viewModelScope.launch {
            val modelSha = modelStore.metadata()?.sha256 ?: "unknown-model"
            var cacheHits = 0
            var generatedAudioMs = 0L
            var generationMs = 0L

            try {
                segments.forEachIndexed { index, segment ->
                    ensureActive()

                    // Once enough audio is prepared, allow the inference engine
                    // to sleep until playback consumes part of the reserve.
                    while (
                        player.started &&
                        player.bufferedSourceMs() >= targetSourceBufferMs(_state.value.playbackSpeed)
                    ) {
                        delay(400L)
                        ensureActive()
                    }

                    awaitThermalHeadroom()

                    val cached = cache.get(modelSha, voiceId, segment)
                    val prepared = if (cached != null) {
                        cacheHits += 1
                        cached
                    } else {
                        val synthesized = try {
                            withTimeout(SEGMENT_TIMEOUT_MS) {
                                engine.synthesize(
                                    SpeechRequest(
                                        text = segment,
                                        speed = 1.0f,
                                        voiceId = voiceId,
                                    )
                                )
                            }
                        } catch (_: TimeoutCancellationException) {
                            Result.failure(
                                IllegalStateException("A narration segment timed out.")
                            )
                        }

                        if (synthesized.isFailure) {
                            throw synthesized.exceptionOrNull()
                                ?: IllegalStateException("Narration synthesis failed.")
                        }

                        val result = synthesized.getOrThrow()
                        generationMs += result.generationTimeMs
                        generatedAudioMs += result.audioDurationMs

                        cache.put(
                            modelSha = modelSha,
                            voiceId = voiceId,
                            text = segment,
                            source = result.audioFile,
                            durationMs = result.audioDurationMs,
                        )
                    }

                    player.enqueue(prepared.file, prepared.durationMs)

                    val meanRtf = if (generatedAudioMs > 0L) {
                        generationMs.toDouble() / generatedAudioMs.toDouble()
                    } else {
                        null
                    }

                    _state.update {
                        it.copy(
                            generatedSegments = index + 1,
                            cacheHits = cacheHits,
                            meanGenerationRtf = meanRtf,
                            status = if (player.started) {
                                "Reading · preparing ahead…"
                            } else {
                                "Preparing first audio…"
                            },
                        )
                    }

                    if (
                        !player.started &&
                        (
                            player.bufferedSourceMs() >= initialSourceBufferMs(_state.value.playbackSpeed) ||
                                index == segments.lastIndex
                        )
                    ) {
                        player.start()
                        _state.update {
                            it.copy(
                                isPlaying = true,
                                status = "Reading · preparing ahead…",
                            )
                        }
                    }
                }

                player.markInputComplete()
                if (!player.started) player.start()

                _state.update {
                    it.copy(
                        isGenerating = false,
                        cacheBytes = cache.sizeBytes(),
                        status = "Reading · all audio prepared.",
                        deviceSnapshot = DeviceDiagnostics.capture(app),
                    )
                }
            } catch (t: Throwable) {
                if (isActive) {
                    player.markInputComplete()
                    _state.update {
                        it.copy(
                            isGenerating = false,
                            error = t.message ?: "Reader failed.",
                            status = "Narration stopped.",
                            deviceSnapshot = DeviceDiagnostics.capture(app),
                        )
                    }
                }
            }
        }
    }

    fun pauseOrResume() {
        val snapshot = _state.value
        if (!snapshot.readerStarted || snapshot.finished) return

        if (player.isPlaying()) {
            player.pause()
        } else {
            player.resume()
        }
        refreshPlaybackState()
    }

    fun stopReading() {
        readerJob?.cancel()
        readerJob = null
        progressJob?.cancel()
        progressJob = null
        engine.cancel()
        player.stop()

        _state.update {
            it.copy(
                isGenerating = false,
                readerStarted = false,
                isPlaying = false,
                isPaused = false,
                finished = false,
                status = if (it.isReady) "Ready · narration stays on-device." else it.status,
                totalSegments = 0,
                generatedSegments = 0,
                currentSegment = 0,
                bufferedListeningMs = 0L,
                underruns = 0,
                thermalPaused = false,
            )
        }
    }

    fun clearPreparedAudio() {
        if (_state.value.readerStarted) return
        viewModelScope.launch {
            cache.clear()
            _state.update {
                it.copy(
                    cacheBytes = 0L,
                    status = "Prepared audio cache cleared.",
                )
            }
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = viewModelScope.launch {
            while (isActive) {
                refreshPlaybackState()

                if (player.isEnded()) {
                    readerJob?.cancel()
                    readerJob = null
                    _state.update {
                        it.copy(
                            isGenerating = false,
                            readerStarted = false,
                            isPlaying = false,
                            isPaused = false,
                            finished = true,
                            bufferedListeningMs = 0L,
                            status = "Finished.",
                            deviceSnapshot = DeviceDiagnostics.capture(app),
                        )
                    }
                    break
                }

                delay(500L)
            }
        }
    }

    private fun refreshPlaybackState() {
        val started = player.started
        val playing = player.isPlaying()
        val paused = player.isPaused()

        _state.update {
            it.copy(
                isPlaying = playing,
                isPaused = paused,
                currentSegment = player.currentSegmentNumber(),
                bufferedListeningMs = player.bufferedListeningMs(),
                underruns = player.underruns,
                readerStarted = started && !player.isEnded(),
            )
        }
    }

    private suspend fun awaitThermalHeadroom() {
        while (true) {
            val snapshot = DeviceDiagnostics.capture(app)
            val blocked = snapshot.thermalStatus in THERMAL_BLOCK_STATES

            _state.update {
                it.copy(
                    thermalPaused = blocked,
                    deviceSnapshot = snapshot,
                    status = if (blocked) {
                        "Phone is hot · generation paused while cached audio keeps playing."
                    } else {
                        it.status
                    },
                )
            }

            if (!blocked) return
            delay(2_000L)
        }
    }

    private fun initialSourceBufferMs(speed: Float): Long {
        // Source-audio time. At faster playback, prepare proportionally more
        // speech before pressing play.
        return (12_000L * speed.coerceIn(1.0f, 2.0f)).roundToInt().toLong()
    }

    private fun targetSourceBufferMs(speed: Float): Long {
        // About 45 seconds of listening reserve at the selected playback speed.
        return (45_000L * speed.coerceIn(1.0f, 2.0f)).roundToInt().toLong()
    }

    override fun onCleared() {
        readerJob?.cancel()
        progressJob?.cancel()
        engine.cancel()
        engine.release()
        player.release()
        super.onCleared()
    }

    private companion object {
        const val SEGMENT_TIMEOUT_MS = 90_000L
        val THERMAL_BLOCK_STATES = setOf(
            "Severe",
            "Critical",
            "Emergency",
            "Shutdown",
        )
    }
}
