package com.offlinenarrator.benchmark.app

import android.content.Context
import android.net.Uri
import com.offlinenarrator.benchmark.book.BookLocation
import com.offlinenarrator.benchmark.book.BookNarrationSegmenter
import com.offlinenarrator.benchmark.book.BookProgress
import com.offlinenarrator.benchmark.book.BookRecord
import com.offlinenarrator.benchmark.book.DocumentBookStore
import com.offlinenarrator.benchmark.book.LibraryBookItem
import com.offlinenarrator.benchmark.model.KokoroModelStore
import com.offlinenarrator.benchmark.playback.BackgroundAudioController
import com.offlinenarrator.benchmark.reader.NarrationCache
import com.offlinenarrator.benchmark.tts.KokoroTtsEngine
import com.offlinenarrator.benchmark.tts.SpeechRequest
import com.offlinenarrator.benchmark.tts.TtsVoice
import com.offlinenarrator.benchmark.util.DeviceDiagnostics
import com.offlinenarrator.benchmark.util.DeviceSnapshot
import dev.ffmpegkit.kokoro.KokoroRuntimeProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

enum class BoloPage {
    LIBRARY,
    READER,
}

data class BoloUiState(
    val page: BoloPage = BoloPage.LIBRARY,
    val books: List<LibraryBookItem> = emptyList(),
    val activeBook: BookRecord? = null,
    val isImportingBook: Boolean = false,
    val isPreparingModel: Boolean = false,
    val modelPresent: Boolean = false,
    val engineReady: Boolean = false,
    val playerReady: Boolean = false,
    val voices: List<TtsVoice> = emptyList(),
    val selectedVoiceId: String? = null,
    val playbackSpeed: Float = 1.0f,
    val readerStarted: Boolean = false,
    val playbackStarted: Boolean = false,
    val isGenerating: Boolean = false,
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val finished: Boolean = false,
    val status: String = "Starting…",
    val error: String? = null,
    val currentGlobalWord: Long = 0L,
    val currentChapterIndex: Int = 0,
    val bufferedListeningMs: Long = 0L,
    val generatedSegments: Int = 0,
    val cacheHits: Int = 0,
    val meanGenerationRtf: Double? = null,
    val underruns: Int = 0,
    val thermalPaused: Boolean = false,
    val cacheBytes: Long = 0L,
    val deviceSnapshot: DeviceSnapshot? = null,
)

class BookReaderRuntime private constructor(
    context: Context,
) {
    private val app = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val prefs = app.getSharedPreferences("bolo_reader", Context.MODE_PRIVATE)
    private val bookStore = DocumentBookStore(app)
    private val modelStore = KokoroModelStore(app, "fp32")
    private val cache = NarrationCache(app)
    private val engine = KokoroTtsEngine(
        app,
        modelStore,
        KokoroRuntimeProfile.CPU_ALL_8,
    )

    private val initialized = AtomicBoolean(false)

    private val savedSpeed = prefs.getFloat("playback_speed", 1.0f)
        .coerceIn(0.75f, 2.0f)

    private val _state = MutableStateFlow(
        BoloUiState(
            modelPresent = modelStore.exists(),
            playbackSpeed = savedSpeed,
            deviceSnapshot = DeviceDiagnostics.capture(app),
        )
    )
    val state: StateFlow<BoloUiState> = _state.asStateFlow()

    private val player = BackgroundAudioController(app) {
        scope.launch { refreshPlaybackState() }
    }

    private var readerJob: Job? = null
    private var progressJob: Job? = null
    private val synthesisMutex = Mutex()

    private var selectedLocation: BookLocation? = null
    private var resumePositionMs: Long = 0L

    init {
        initialize()
    }

    private fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        scope.launch {
            refreshLibrary()
            _state.update { it.copy(cacheBytes = cache.sizeBytes()) }

            ensurePlayerConnected()
            loadVoiceChoices()
            _state.update {
                it.copy(
                    status = if (it.modelPresent) {
                        "Library ready."
                    } else {
                        "Import the Kokoro model once to enable narration."
                    },
                )
            }
            startProgressTicker()
        }
    }

    private suspend fun ensurePlayerConnected() {
        if (player.isConnected()) {
            _state.update { it.copy(playerReady = true) }
            return
        }

        runCatching { player.connect() }
            .onSuccess {
                player.setSpeed(_state.value.playbackSpeed)
                _state.update {
                    it.copy(
                        playerReady = true,
                        error = null,
                    )
                }
            }
            .onFailure { failure ->
                _state.update {
                    it.copy(
                        playerReady = false,
                        error = "Playback service failed: ${failure.message}",
                        status = "Playback service unavailable.",
                    )
                }
            }
    }

    suspend fun refreshLibrary() {
        val books = bookStore.listBooks().map { book ->
            LibraryBookItem(
                book = book,
                progress = bookStore.loadProgress(book.id),
            )
        }
        _state.update { it.copy(books = books) }
    }

    fun importDocument(uri: Uri) {
        if (_state.value.isImportingBook) return

        scope.launch {
            _state.update {
                it.copy(
                    isImportingBook = true,
                    error = null,
                    status = "Indexing document…",
                )
            }

            val result = bookStore.importDocument(uri)

            if (result.isSuccess) {
                refreshLibrary()
                val book = result.getOrThrow()
                _state.update {
                    it.copy(
                        isImportingBook = false,
                        status = "Imported ${book.title}.",
                    )
                }
                openBook(book.id)
            } else {
                _state.update {
                    it.copy(
                        isImportingBook = false,
                        error = result.exceptionOrNull()?.message ?: "Document import failed.",
                        status = "Document import failed.",
                    )
                }
            }
        }
    }

    fun importKokoroModel(uri: Uri) {
        stopNarration()
        scope.launch {
            _state.update {
                it.copy(
                    isPreparingModel = true,
                    error = null,
                    status = "Importing Kokoro model…",
                )
            }

            val result = modelStore.importFrom(uri)

            if (result.isFailure) {
                _state.update {
                    it.copy(
                        isPreparingModel = false,
                        error = result.exceptionOrNull()?.message ?: "Model import failed.",
                        status = "Model import failed.",
                    )
                }
                return@launch
            }

            loadVoiceChoices()
            _state.update {
                it.copy(
                    isPreparingModel = false,
                    modelPresent = true,
                    engineReady = false,
                    status = "Model ready. Import or open a document.",
                )
            }
        }
    }

    private fun loadVoiceChoices() {
        val voices = engine.voices()
        val savedVoice = prefs.getString("voice_id", null)
        val preferred = voices.firstOrNull { it.id == savedVoice }
            ?: voices.firstOrNull { it.id == DEFAULT_VOICE_ID }
            ?: voices.firstOrNull { it.id == "af_heart" }
            ?: voices.firstOrNull()

        preferred?.id?.let {
            prefs.edit().putString("voice_id", it).apply()
        }

        _state.update {
            it.copy(
                voices = voices,
                selectedVoiceId = preferred?.id,
            )
        }
    }

    private suspend fun prepareEngine() {
        if (!modelStore.exists()) {
            _state.update {
                it.copy(
                    modelPresent = false,
                    engineReady = false,
                    isPreparingModel = false,
                    voices = emptyList(),
                    selectedVoiceId = null,
                    status = "Import the Kokoro model once to enable narration.",
                )
            }
            return
        }

        _state.update {
            it.copy(
                isPreparingModel = true,
                modelPresent = true,
                engineReady = false,
                error = null,
                status = "Loading narrator…",
            )
        }

        val result = engine.initialize()
        loadVoiceChoices()

        _state.update {
            it.copy(
                isPreparingModel = false,
                modelPresent = true,
                engineReady = result.isSuccess,
                status = if (result.isSuccess) "Narrator ready." else "Kokoro failed to load.",
                error = result.exceptionOrNull()?.message,
                deviceSnapshot = DeviceDiagnostics.capture(app),
            )
        }
    }

    fun openBook(bookId: String) {
        scope.launch {
            val current = _state.value
            val book = current.books.firstOrNull { it.book.id == bookId }?.book
                ?: bookStore.listBooks().firstOrNull { it.id == bookId }
                ?: return@launch

            if (
                current.readerStarted &&
                current.activeBook?.id != book.id
            ) {
                stopNarrationInternal(savePosition = true)
            }

            val progress = bookStore.loadProgress(book.id)
            val location = if (progress != null) {
                val chapter = book.chapters.getOrNull(progress.chapterIndex)
                if (chapter != null) {
                    BookLocation(
                        chapterIndex = chapter.index,
                        wordOffset = progress.segmentStartWord
                            .coerceIn(0L, maxOf(0L, chapter.wordCount - 1L)),
                        globalWord = progress.globalWord
                            .coerceIn(0L, maxOf(0L, book.totalWords - 1L)),
                    )
                } else {
                    book.locateGlobalWord(0L)
                }
            } else {
                book.locateGlobalWord(0L)
            }

            selectedLocation = location
            resumePositionMs = progress?.positionMs ?: 0L

            _state.update {
                it.copy(
                    page = BoloPage.READER,
                    activeBook = book,
                    currentGlobalWord = progress?.globalWord ?: location.globalWord,
                    currentChapterIndex = location.chapterIndex,
                    finished = false,
                    error = null,
                    status = if (it.engineReady) {
                        if (progress != null) "Resume ready." else "Ready to read."
                    } else {
                        "Loading narrator…"
                    },
                )
            }

            if (!_state.value.playerReady) {
                ensurePlayerConnected()
            }

            if (!_state.value.engineReady && !_state.value.isPreparingModel) {
                prepareEngine()
            }
        }
    }

    fun backToLibrary() {
        _state.update { it.copy(page = BoloPage.LIBRARY) }

        if (!_state.value.readerStarted) {
            engine.release()
            _state.update {
                it.copy(
                    engineReady = false,
                    isPreparingModel = false,
                    status = "Library ready.",
                )
            }
        }

        scope.launch { refreshLibrary() }
    }

    fun returnToPlayer() {
        if (_state.value.activeBook != null) {
            _state.update { it.copy(page = BoloPage.READER) }
        }
    }

    fun removeBook(bookId: String) {
        scope.launch {
            val current = _state.value
            if (current.activeBook?.id == bookId) {
                stopNarrationInternal(savePosition = false)
                _state.update {
                    it.copy(
                        activeBook = null,
                        page = BoloPage.LIBRARY,
                    )
                }
            }
            bookStore.deleteBook(bookId)
            refreshLibrary()
        }
    }

    fun selectVoice(id: String) {
        if (_state.value.readerStarted) return
        prefs.edit().putString("voice_id", id).apply()
        _state.update { it.copy(selectedVoiceId = id) }
    }

    fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(0.75f, 2.0f)
        prefs.edit().putFloat("playback_speed", safe).apply()
        player.setSpeed(safe)
        _state.update { it.copy(playbackSpeed = safe) }
        refreshPlaybackState()
    }

    fun togglePlayback() {
        val snapshot = _state.value
        if (!snapshot.engineReady || !snapshot.playerReady || snapshot.activeBook == null) return

        when {
            !snapshot.readerStarted -> {
                val start = selectedLocation
                    ?: snapshot.activeBook.locateGlobalWord(snapshot.currentGlobalWord)
                startNarration(start, resumePositionMs)
            }

            snapshot.playbackStarted && player.isPlaying() -> {
                player.pause()
            }

            snapshot.playbackStarted -> {
                player.resume()
            }
        }

        refreshPlaybackState()
    }

    fun stopNarration() {
        stopNarrationInternal(savePosition = true)
    }

    private fun stopNarrationInternal(savePosition: Boolean) {
        if (savePosition) {
            saveCurrentPosition()
        }

        readerJob?.cancel()
        readerJob = null
        engine.cancel()
        player.stop()

        _state.update {
            it.copy(
                readerStarted = false,
                playbackStarted = false,
                isGenerating = false,
                isPlaying = false,
                isPaused = false,
                bufferedListeningMs = 0L,
                underruns = 0,
                thermalPaused = false,
                status = if (it.activeBook != null) "Ready to resume." else "Ready.",
            )
        }
    }

    fun jumpToFraction(fraction: Float) {
        val book = _state.value.activeBook ?: return
        val safe = fraction.coerceIn(0f, 1f)
        val global = if (book.totalWords <= 1L) {
            0L
        } else {
            (safe * (book.totalWords - 1L).toDouble()).toLong()
        }
        jumpToGlobalWord(global)
    }

    fun jumpToPage(page: Int) {
        val book = _state.value.activeBook ?: return
        jumpToGlobalWord(book.globalWordForPage(page))
    }

    fun jumpByPages(deltaPages: Int) {
        val book = _state.value.activeBook ?: return
        val currentPage = book.pageForGlobalWord(_state.value.currentGlobalWord)
        jumpToPage((currentPage + deltaPages).coerceIn(1, book.estimatedPages))
    }

    fun jumpToChapter(chapterIndex: Int) {
        val book = _state.value.activeBook ?: return
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return
        jumpToGlobalWord(chapter.startWord)
    }

    fun previousChapter() {
        val book = _state.value.activeBook ?: return
        val target = (_state.value.currentChapterIndex - 1).coerceAtLeast(0)
        val chapter = book.chapters.getOrNull(target) ?: return
        jumpToGlobalWord(chapter.startWord)
    }

    fun nextChapter() {
        val book = _state.value.activeBook ?: return
        val target = (_state.value.currentChapterIndex + 1)
            .coerceAtMost(book.chapters.lastIndex)
        val chapter = book.chapters.getOrNull(target) ?: return
        jumpToGlobalWord(chapter.startWord)
    }

    private fun jumpToGlobalWord(globalWord: Long) {
        val book = _state.value.activeBook ?: return
        val location = book.locateGlobalWord(globalWord)
        val shouldRestart = _state.value.readerStarted && !_state.value.isPaused

        if (_state.value.readerStarted) {
            stopNarrationInternal(savePosition = false)
        }

        selectedLocation = location
        resumePositionMs = 0L

        bookStore.saveProgress(
            book.id,
            BookProgress(
                chapterIndex = location.chapterIndex,
                segmentStartWord = location.wordOffset,
                positionMs = 0L,
                globalWord = location.globalWord,
                updatedAt = System.currentTimeMillis(),
            ),
        )

        _state.update {
            it.copy(
                currentGlobalWord = location.globalWord,
                currentChapterIndex = location.chapterIndex,
                finished = false,
                status = if (book.hasFixedPages) {
                    "Moved to page ${book.pageForGlobalWord(location.globalWord)}."
                } else {
                    "Moved to page ~${book.pageForGlobalWord(location.globalWord)}."
                },
            )
        }

        if (shouldRestart) {
            startNarration(location, 0L)
        }
    }

    fun clearPreparedAudio() {
        if (_state.value.readerStarted) return
        scope.launch {
            cache.clear()
            _state.update {
                it.copy(
                    cacheBytes = 0L,
                    status = "Prepared audio cleared.",
                )
            }
        }
    }

    private fun startNarration(
        start: BookLocation,
        initialSeekMs: Long,
    ) {
        val snapshot = _state.value
        val book = snapshot.activeBook ?: return
        if (!snapshot.engineReady || !snapshot.playerReady) return

        readerJob?.cancel()
        engine.cancel()
        player.reset(snapshot.playbackSpeed)

        selectedLocation = start
        resumePositionMs = initialSeekMs

        _state.update {
            it.copy(
                readerStarted = true,
                playbackStarted = false,
                isGenerating = true,
                isPlaying = false,
                isPaused = false,
                finished = false,
                status = preparationStatus(snapshot.playbackSpeed),
                error = null,
                currentGlobalWord = start.globalWord,
                currentChapterIndex = start.chapterIndex,
                bufferedListeningMs = 0L,
                generatedSegments = 0,
                cacheHits = 0,
                meanGenerationRtf = null,
                underruns = 0,
                thermalPaused = false,
            )
        }

        readerJob = scope.launch {
            val modelSha = modelStore.metadata()?.sha256 ?: "unknown-model"
            val voiceId = _state.value.selectedVoiceId ?: DEFAULT_VOICE_ID

            var cacheHits = 0
            var generatedAudioMs = 0L
            var generationMs = 0L
            var generatedSegments = 0
            var firstSeekPending = initialSeekMs.coerceAtLeast(0L)

            try {
                for (chapterIndex in start.chapterIndex..book.chapters.lastIndex) {
                    ensureActive()

                    val chapter = book.chapters[chapterIndex]
                    val chapterText = bookStore.readChapter(book.id, chapterIndex)
                    val chapterStartWord = if (chapterIndex == start.chapterIndex) {
                        start.wordOffset
                    } else {
                        0L
                    }

                    val units = BookNarrationSegmenter.split(
                        chapterText = chapterText,
                        startWord = chapterStartWord,
                        checkpoints = chapter.checkpoints,
                    )

                    for ((unitIndex, unit) in units.withIndex()) {
                        ensureActive()

                        while (
                            player.started &&
                            player.bufferedListeningMs() >=
                                targetListeningBufferMs(_state.value.playbackSpeed)
                        ) {
                            delay(400L)
                            ensureActive()
                        }

                        awaitThermalHeadroom()

                        val cached = cache.get(
                            modelSha = modelSha,
                            voiceId = voiceId,
                            text = unit.text,
                        )

                        val prepared = if (cached != null) {
                            cacheHits += 1
                            cached
                        } else {
                            val synthesized = try {
                                withTimeout(SEGMENT_TIMEOUT_MS) {
                                    synthesisMutex.withLock {
                                        ensureActive()
                                        engine.synthesize(
                                            SpeechRequest(
                                                text = unit.text,
                                                speed = 1.0f,
                                                voiceId = voiceId,
                                            )
                                        )
                                    }
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
                                text = unit.text,
                                source = result.audioFile,
                                durationMs = result.audioDurationMs,
                            )
                        }

                        player.enqueue(
                            file = prepared.file,
                            durationMs = prepared.durationMs,
                            bookId = book.id,
                            bookTitle = book.title,
                            chapterTitle = chapter.title,
                            chapterIndex = chapter.index,
                            chapterGlobalStart = chapter.startWord,
                            segmentStartWord = unit.startWord,
                            segmentWordCount = unit.wordCount,
                        )

                        generatedSegments += 1

                        val meanRtf = if (generatedAudioMs > 0L) {
                            generationMs.toDouble() / generatedAudioMs.toDouble()
                        } else {
                            null
                        }

                        _state.update {
                            it.copy(
                                generatedSegments = generatedSegments,
                                cacheHits = cacheHits,
                                meanGenerationRtf = meanRtf,
                                status = if (player.started) {
                                    "Playing · preparing ahead"
                                } else {
                                    preparationStatus(_state.value.playbackSpeed)
                                },
                            )
                        }

                        val isLastUnitInBook =
                            chapterIndex == book.chapters.lastIndex &&
                                unitIndex == units.lastIndex

                        if (
                            !player.started &&
                            (
                                player.bufferedListeningMs() >=
                                    initialListeningBufferMs(_state.value.playbackSpeed) ||
                                    isLastUnitInBook
                            )
                        ) {
                            player.start()
                            if (firstSeekPending > 0L) {
                                player.seekCurrent(firstSeekPending)
                                firstSeekPending = 0L
                            }

                            _state.update {
                                it.copy(
                                    playbackStarted = true,
                                    isPlaying = true,
                                    status = "Playing · preparing ahead",
                                )
                            }
                        }
                    }
                }

                player.markInputComplete()
                if (!player.started && player.mediaItemCount() > 0) {
                    player.start()
                    if (firstSeekPending > 0L) {
                        player.seekCurrent(firstSeekPending)
                        firstSeekPending = 0L
                    }
                }

                _state.update {
                    it.copy(
                        playbackStarted = player.started,
                        isGenerating = false,
                        cacheBytes = cache.sizeBytes(),
                        status = "Playing · book audio prepared to the end.",
                        deviceSnapshot = DeviceDiagnostics.capture(app),
                    )
                }
            } catch (t: Throwable) {
                if (isActive) {
                    player.markInputComplete()
                    _state.update {
                        it.copy(
                            isGenerating = false,
                            error = t.message ?: "Narration failed.",
                            status = "Narration stopped.",
                            deviceSnapshot = DeviceDiagnostics.capture(app),
                        )
                    }
                }
            }
        }
    }

    private fun startProgressTicker() {
        if (progressJob?.isActive == true) return

        progressJob = scope.launch {
            while (isActive) {
                refreshPlaybackState()
                saveCurrentPosition()

                if (player.isEnded()) {
                    val book = _state.value.activeBook
                    if (book != null) {
                        val endWord = maxOf(0L, book.totalWords - 1L)
                        bookStore.saveProgress(
                            book.id,
                            BookProgress(
                                chapterIndex = book.chapters.lastIndex,
                                segmentStartWord = maxOf(
                                    0L,
                                    book.chapters.last().wordCount - 1L,
                                ),
                                positionMs = 0L,
                                globalWord = endWord,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                    }

                    readerJob?.cancel()
                    readerJob = null
                    _state.update {
                        it.copy(
                            readerStarted = false,
                            playbackStarted = false,
                            isGenerating = false,
                            isPlaying = false,
                            isPaused = false,
                            finished = true,
                            bufferedListeningMs = 0L,
                            status = "Finished.",
                            deviceSnapshot = DeviceDiagnostics.capture(app),
                        )
                    }
                    refreshLibrary()
                }

                delay(1_000L)
            }
        }
    }

    private fun refreshPlaybackState() {
        val descriptor = player.currentDescriptor()
        val active = _state.value.activeBook

        var globalWord = _state.value.currentGlobalWord
        var chapterIndex = _state.value.currentChapterIndex

        if (
            descriptor != null &&
            active != null &&
            descriptor.bookId == active.id
        ) {
            chapterIndex = descriptor.chapterIndex
            val fraction = if (descriptor.durationMs > 0L) {
                descriptor.positionMs.toDouble() / descriptor.durationMs.toDouble()
            } else {
                0.0
            }.coerceIn(0.0, 1.0)

            val insideSegment = (descriptor.segmentWordCount * fraction).toLong()
            globalWord = (
                descriptor.chapterGlobalStart +
                    descriptor.segmentStartWord +
                    insideSegment
                ).coerceIn(0L, maxOf(0L, active.totalWords - 1L))
        }

        _state.update {
            it.copy(
                playerReady = player.isConnected(),
                playbackStarted = player.started,
                isPlaying = player.isPlaying(),
                isPaused = player.isPaused(),
                currentGlobalWord = globalWord,
                currentChapterIndex = chapterIndex,
                bufferedListeningMs = player.bufferedListeningMs(),
                underruns = player.underruns,
            )
        }
    }

    private fun saveCurrentPosition() {
        val descriptor = player.currentDescriptor() ?: return
        val book = _state.value.activeBook ?: return
        if (descriptor.bookId != book.id) return

        val fraction = if (descriptor.durationMs > 0L) {
            descriptor.positionMs.toDouble() / descriptor.durationMs.toDouble()
        } else {
            0.0
        }.coerceIn(0.0, 1.0)

        val estimatedInsideSegment =
            (descriptor.segmentWordCount * fraction).toLong()

        val globalWord = (
            descriptor.chapterGlobalStart +
                descriptor.segmentStartWord +
                estimatedInsideSegment
            ).coerceIn(0L, maxOf(0L, book.totalWords - 1L))

        selectedLocation = BookLocation(
            chapterIndex = descriptor.chapterIndex,
            wordOffset = descriptor.segmentStartWord,
            globalWord = descriptor.chapterGlobalStart + descriptor.segmentStartWord,
        )
        resumePositionMs = descriptor.positionMs

        bookStore.saveProgress(
            book.id,
            BookProgress(
                chapterIndex = descriptor.chapterIndex,
                segmentStartWord = descriptor.segmentStartWord,
                positionMs = descriptor.positionMs,
                globalWord = globalWord,
                updatedAt = System.currentTimeMillis(),
            ),
        )
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
                        "Cooling · cached audio keeps playing"
                    } else {
                        it.status
                    },
                )
            }

            if (!blocked) return
            delay(2_000L)
        }
    }

    private fun preparationStatus(speed: Float): String =
        if (speed >= 1.5f) {
            "Preparing a larger ${displaySpeed(speed)} reserve…"
        } else {
            "Preparing audio…"
        }

    private fun initialListeningBufferMs(speed: Float): Long = when {
        speed >= 2.0f -> 120_000L
        speed >= 1.75f -> 90_000L
        speed >= 1.5f -> 60_000L
        speed >= 1.25f -> 30_000L
        else -> 12_000L
    }

    private fun targetListeningBufferMs(speed: Float): Long = when {
        speed >= 2.0f -> 300_000L
        speed >= 1.75f -> 240_000L
        speed >= 1.5f -> 180_000L
        speed >= 1.25f -> 90_000L
        else -> 45_000L
    }

    private fun displaySpeed(speed: Float): String =
        if (speed == speed.toInt().toFloat()) {
            "${speed.toInt()}.0×"
        } else {
            "${speed}×"
        }

    companion object {
        @Volatile
        private var instance: BookReaderRuntime? = null

        fun get(context: Context): BookReaderRuntime =
            instance ?: synchronized(this) {
                instance ?: BookReaderRuntime(context).also { instance = it }
            }

        private const val DEFAULT_VOICE_ID = "am_onyx"
        private const val SEGMENT_TIMEOUT_MS = 90_000L

        private val THERMAL_BLOCK_STATES = setOf(
            "Severe",
            "Critical",
            "Emergency",
            "Shutdown",
        )
    }
}
