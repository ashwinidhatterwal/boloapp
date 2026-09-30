package com.offlinenarrator.benchmark.app

import android.content.Context
import android.net.Uri
import com.offlinenarrator.benchmark.book.AudiobookNarrationCompiler
import com.offlinenarrator.benchmark.book.BookLocation
import com.offlinenarrator.benchmark.book.BookProgress
import com.offlinenarrator.benchmark.book.BookRecord
import com.offlinenarrator.benchmark.book.CharacterVoiceStore
import com.offlinenarrator.benchmark.book.DocumentImportProgress
import com.offlinenarrator.benchmark.book.NarrationDirector
import com.offlinenarrator.benchmark.book.NarrationBatcher
import com.offlinenarrator.benchmark.book.NarrationBoundary
import com.offlinenarrator.benchmark.book.NarrationPacing
import com.offlinenarrator.benchmark.book.NarrationProsody
import com.offlinenarrator.benchmark.book.NarrationRole
import com.offlinenarrator.benchmark.book.ReaderLine
import com.offlinenarrator.benchmark.book.SentenceSegmenter
import com.offlinenarrator.benchmark.book.lineIndexForWord
import com.offlinenarrator.benchmark.book.DocumentBookStore
import com.offlinenarrator.benchmark.book.LibraryBookItem
import com.offlinenarrator.benchmark.model.KokoroModelStore
import com.offlinenarrator.benchmark.playback.BackgroundAudioController
import com.offlinenarrator.benchmark.reader.NarrationCache
import com.offlinenarrator.benchmark.reader.WavAudioFinisher
import com.offlinenarrator.benchmark.reader.WavQualityInspector
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
import kotlinx.coroutines.withContext
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
    val importPhase: String? = null,
    val importDetail: String? = null,
    val importCurrent: Int = 0,
    val importTotal: Int? = null,
    val importFraction: Float? = null,
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
    val chapterLines: List<ReaderLine> = emptyList(),
    val chapterLinesChapterIndex: Int = -1,
    val currentLineIndex: Int = -1,
    val isLoadingChapterLines: Boolean = false,
    val bufferedListeningMs: Long = 0L,
    val generatedSegments: Int = 0,
    val dialogueSegments: Int = 0,
    val charactersVoiced: Int = 0,
    val cacheHits: Int = 0,
    val meanGenerationRtf: Double? = null,
    val qcRetries: Int = 0,
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
    private val characterVoiceStore = CharacterVoiceStore(app)
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
    private var importJob: Job? = null
    private var progressJob: Job? = null
    private var chapterLinesJob: Job? = null
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

        importJob?.cancel()
        importJob = scope.launch {
            _state.update {
                it.copy(
                    isImportingBook = true,
                    importPhase = "Starting import",
                    importDetail = "",
                    importCurrent = 0,
                    importTotal = null,
                    importFraction = 0f,
                    error = null,
                    status = "Indexing document…",
                )
            }

            try {
                val result = bookStore.importDocument(uri) { progress ->
                    applyImportProgress(progress)
                }

                if (result.isSuccess) {
                    refreshLibrary()
                    val book = result.getOrThrow()
                    _state.update {
                        it.copy(
                            isImportingBook = false,
                            importPhase = null,
                            importDetail = null,
                            importCurrent = 0,
                            importTotal = null,
                            importFraction = null,
                            status = "Imported ${book.title}.",
                        )
                    }
                    openBook(book.id)
                } else {
                    _state.update {
                        it.copy(
                            isImportingBook = false,
                            importPhase = null,
                            importDetail = null,
                            importCurrent = 0,
                            importTotal = null,
                            importFraction = null,
                            error = result.exceptionOrNull()?.message ?: "Document import failed.",
                            status = "Document import failed.",
                        )
                    }
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                _state.update {
                    it.copy(
                        isImportingBook = false,
                        importPhase = null,
                        importDetail = null,
                        importCurrent = 0,
                        importTotal = null,
                        importFraction = null,
                        status = "Import cancelled.",
                    )
                }
            } finally {
                importJob = null
            }
        }
    }

    fun cancelImport() {
        importJob?.cancel()
        importJob = null
    }

    private fun applyImportProgress(progress: DocumentImportProgress) {
        _state.update {
            it.copy(
                importPhase = progress.phase,
                importDetail = progress.detail,
                importCurrent = progress.current,
                importTotal = progress.total,
                importFraction = progress.fraction,
                status = progress.phase,
            )
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
                    chapterLines = emptyList(),
                    chapterLinesChapterIndex = -1,
                    currentLineIndex = -1,
                    isLoadingChapterLines = true,
                    finished = false,
                    error = null,
                    status = if (it.engineReady) {
                        if (progress != null) "Resume ready." else "Ready to read."
                    } else {
                        "Loading narrator…"
                    },
                )
            }

            requestChapterLines(book, location.chapterIndex)

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
                        chapterLines = emptyList(),
                        chapterLinesChapterIndex = -1,
                        currentLineIndex = -1,
                        isLoadingChapterLines = false,
                    )
                }
            }
            bookStore.deleteBook(bookId)
            characterVoiceStore.clearBook(bookId)
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

    fun playFromLine(lineIndex: Int) {
        val snapshot = _state.value
        val book = snapshot.activeBook ?: return
        if (snapshot.chapterLinesChapterIndex != snapshot.currentChapterIndex) return
        val chapter = book.chapters.getOrNull(snapshot.currentChapterIndex) ?: return
        val line = snapshot.chapterLines.getOrNull(lineIndex) ?: return
        val globalWord = (chapter.startWord + line.startWord)
            .coerceIn(0L, maxOf(0L, book.totalWords - 1L))
        jumpToGlobalWord(globalWord, forcePlayback = true)
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

    private fun jumpToGlobalWord(
        globalWord: Long,
        forcePlayback: Boolean = false,
    ) {
        val book = _state.value.activeBook ?: return
        val location = book.locateGlobalWord(globalWord)
        val shouldRestart = forcePlayback || (_state.value.readerStarted && !_state.value.isPaused)

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
                currentLineIndex = lineIndexForCurrentLocation(
                    book = book,
                    chapterIndex = location.chapterIndex,
                    globalWord = location.globalWord,
                    lines = it.chapterLines,
                    linesChapterIndex = it.chapterLinesChapterIndex,
                ),
                finished = false,
                status = if (book.hasFixedPages) {
                    "Moved to page ${book.pageForGlobalWord(location.globalWord)}."
                } else {
                    "Moved to page ~${book.pageForGlobalWord(location.globalWord)}."
                },
            )
        }

        requestChapterLines(book, location.chapterIndex)

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
                currentLineIndex = lineIndexForCurrentLocation(
                    book = book,
                    chapterIndex = start.chapterIndex,
                    globalWord = start.globalWord,
                    lines = it.chapterLines,
                    linesChapterIndex = it.chapterLinesChapterIndex,
                ),
                bufferedListeningMs = 0L,
                generatedSegments = 0,
                dialogueSegments = 0,
                charactersVoiced = 0,
                cacheHits = 0,
                meanGenerationRtf = null,
                qcRetries = 0,
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
            var dialogueSegments = 0
            var qcRetries = 0
            val characterNames = linkedSetOf<String>()
            var firstSeekPending = initialSeekMs.coerceAtLeast(0L)

            try {
                // Prepare-ahead architecture: compile the selected chapter fully,
                // then stop neural synthesis before playback begins.
                for (chapterIndex in start.chapterIndex..start.chapterIndex) {
                    ensureActive()

                    val chapter = book.chapters[chapterIndex]
                    val chapterText = bookStore.readChapter(book.id, chapterIndex)
                    val chapterStartWord = if (chapterIndex == start.chapterIndex) {
                        start.wordOffset
                    } else {
                        0L
                    }

                    val chapterPlan = withContext(Dispatchers.Default) {
                        AudiobookNarrationCompiler.planChapter(
                            chapterText = chapterText,
                            startWord = chapterStartWord,
                            checkpoints = chapter.checkpoints,
                            sourceFormat = book.format,
                            tokenCounter = engine::countModelTokens,
                        )
                    }
                    val batches = chapterPlan.batches

                    for ((batchIndex, batch) in batches.withIndex()) {
                        ensureActive()

                        awaitThermalHeadroom()

                        // Professional audiobook default: one consistent narrator.
                        // Character identity is tracked for dialogue continuity, but
                        // we do not mutate Kokoro's timbre from line to line.
                        batch.speakerKey?.let(characterNames::add)
                        val unitVoiceId = voiceId
                        dialogueSegments += batch.dialogueUnitCount

                        val effectiveBoundary = effectiveBoundaryFor(
                            book = book,
                            chapterIndex = chapterIndex,
                            batchIndex = batchIndex,
                            batchesCount = batches.size,
                            planned = batch.boundaryAfter,
                        )
                        val timing = NarrationPacing.timing(
                            boundary = effectiveBoundary,
                            cue = batch.deliveryCue,
                        )
                        val prosody = NarrationProsody.plan(batch)
                        val cacheTextKey = buildString {
                            append(batch.text)
                            append("\u0000natural-boundary=")
                            append(effectiveBoundary.name)
                            append(':')
                            append(timing.targetMs)
                            append("\u0000cadence=")
                            append(prosody.cadence.name)
                            append(':')
                            append(prosody.synthesisSpeed)
                            append("\u0000mood=")
                            append(batch.performance.mood.name)
                            append(':')
                            append(batch.performance.confidence)
                            append("\u0000tokens=")
                            append(batch.modelTokenCount ?: -1)
                        }
                        val cached = cache.get(
                            modelSha = modelSha,
                            voiceId = unitVoiceId,
                            text = cacheTextKey,
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
                                                text = batch.text,
                                                speed = prosody.synthesisSpeed,
                                                voiceId = unitVoiceId,
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

                            val first = synthesized.getOrThrow()
                            val firstBoundary = withContext(Dispatchers.IO) {
                                WavAudioFinisher.normalizeBoundarySilence(
                                    file = first.audioFile,
                                    minTrailingMs = timing.minMs,
                                    targetTrailingMs = timing.targetMs,
                                    maxTrailingMs = timing.maxMs,
                                )
                            }
                            val firstDuration =
                                (first.audioDurationMs + firstBoundary.durationAdjustmentMs)
                                    .coerceAtLeast(1L)
                            var chosen = first
                            var chosenDuration = firstDuration
                            var chosenQc = withContext(Dispatchers.IO) {
                                WavQualityInspector.inspect(
                                    file = first.audioFile,
                                    wordCount = batch.wordCount,
                                    allowLongPause = effectiveBoundary >= NarrationBoundary.PARAGRAPH,
                                )
                            }
                            generationMs += first.generationTimeMs

                            // Prepare-ahead gives us time to repair suspicious output
                            // before the listener ever hears it. One conservative retry
                            // avoids endless generation loops.
                            if (!chosenQc.acceptable) {
                                qcRetries += 1
                                val retryResult = try {
                                    withTimeout(SEGMENT_TIMEOUT_MS) {
                                        synthesisMutex.withLock {
                                            ensureActive()
                                            engine.synthesize(
                                                SpeechRequest(
                                                    text = batch.text,
                                                    speed = 1.0f,
                                                    voiceId = unitVoiceId,
                                                )
                                            )
                                        }
                                    }
                                } catch (_: TimeoutCancellationException) {
                                    Result.failure(
                                        IllegalStateException("QC retry timed out.")
                                    )
                                }
                                val retry = retryResult.getOrNull()

                                if (retry != null) {
                                    val retryBoundary = withContext(Dispatchers.IO) {
                                        WavAudioFinisher.normalizeBoundarySilence(
                                            file = retry.audioFile,
                                            minTrailingMs = timing.minMs,
                                            targetTrailingMs = timing.targetMs,
                                            maxTrailingMs = timing.maxMs,
                                        )
                                    }
                                    val retryDuration =
                                        (retry.audioDurationMs + retryBoundary.durationAdjustmentMs)
                                            .coerceAtLeast(1L)
                                    val retryQc = withContext(Dispatchers.IO) {
                                        WavQualityInspector.inspect(
                                            file = retry.audioFile,
                                            wordCount = batch.wordCount,
                                            allowLongPause = effectiveBoundary >= NarrationBoundary.PARAGRAPH,
                                        )
                                    }
                                    generationMs += retry.generationTimeMs

                                    if (retryQc.score > chosenQc.score) {
                                        runCatching { chosen.audioFile.delete() }
                                        chosen = retry
                                        chosenDuration = retryDuration
                                        chosenQc = retryQc
                                    } else {
                                        runCatching { retry.audioFile.delete() }
                                    }
                                }
                            }

                            generatedAudioMs += chosenDuration
                            cache.put(
                                modelSha = modelSha,
                                voiceId = unitVoiceId,
                                text = cacheTextKey,
                                source = chosen.audioFile,
                                durationMs = chosenDuration,
                            )
                        }

                        val acousticBoundaryTimes = withContext(Dispatchers.IO) {
                            WavAudioFinisher.detectBoundaryTimes(
                                file = prepared.file,
                                wordEnds = batch.unitWordEnds,
                                totalWords = batch.wordCount,
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
                            segmentStartWord = batch.startWord,
                            segmentWordCount = batch.wordCount,
                            wordAnchorOffsets = batch.unitWordEnds.toLongArray(),
                            timeAnchorMs = acousticBoundaryTimes,
                        )

                        generatedSegments += batch.unitCount

                        val meanRtf = if (generatedAudioMs > 0L) {
                            generationMs.toDouble() / generatedAudioMs.toDouble()
                        } else {
                            null
                        }

                        _state.update {
                            it.copy(
                                generatedSegments = generatedSegments,
                                dialogueSegments = dialogueSegments,
                                charactersVoiced = characterNames.size,
                                cacheHits = cacheHits,
                                meanGenerationRtf = meanRtf,
                                qcRetries = qcRetries,
                                status = "Compiling chapter · ${formatPreparedForStatus(player.bufferedSourceMs())} prepared",
                            )
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
                        qcRetries = qcRetries,
                        cacheBytes = cache.sizeBytes(),
                        status = "Playing prepared chapter · narrator engine idle.",
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
                    val snapshot = _state.value
                    val book = snapshot.activeBook
                    readerJob?.cancel()
                    readerJob = null

                    if (book != null && snapshot.currentChapterIndex < book.chapters.lastIndex) {
                        val next = book.chapters[snapshot.currentChapterIndex + 1]
                        val location = BookLocation(
                            chapterIndex = next.index,
                            wordOffset = 0L,
                            globalWord = next.startWord,
                        )
                        selectedLocation = location
                        resumePositionMs = 0L
                        bookStore.saveProgress(
                            book.id,
                            BookProgress(
                                chapterIndex = next.index,
                                segmentStartWord = 0L,
                                positionMs = 0L,
                                globalWord = next.startWord,
                                updatedAt = System.currentTimeMillis(),
                            ),
                        )
                        player.stop()
                        _state.update {
                            it.copy(
                                readerStarted = false,
                                playbackStarted = false,
                                isGenerating = false,
                                isPlaying = false,
                                isPaused = false,
                                finished = false,
                                bufferedListeningMs = 0L,
                                currentGlobalWord = next.startWord,
                                currentChapterIndex = next.index,
                                status = "Chapter finished · press play to compile the next chapter.",
                                deviceSnapshot = DeviceDiagnostics.capture(app),
                            )
                        }
                        requestChapterLines(book, next.index)
                    } else {
                        if (book != null) {
                            val endWord = maxOf(0L, book.totalWords - 1L)
                            bookStore.saveProgress(
                                book.id,
                                BookProgress(
                                    chapterIndex = book.chapters.lastIndex,
                                    segmentStartWord = maxOf(0L, book.chapters.last().wordCount - 1L),
                                    positionMs = 0L,
                                    globalWord = endWord,
                                    updatedAt = System.currentTimeMillis(),
                                ),
                            )
                        }
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
            val insideSegment = descriptor.wordOffsetAtPosition()
            globalWord = (
                descriptor.chapterGlobalStart +
                    descriptor.segmentStartWord +
                    insideSegment
                ).coerceIn(0L, maxOf(0L, active.totalWords - 1L))
        }

        val beforeChapter = _state.value.currentChapterIndex
        _state.update {
            it.copy(
                playerReady = player.isConnected(),
                playbackStarted = player.started,
                isPlaying = player.isPlaying(),
                isPaused = player.isPaused(),
                currentGlobalWord = globalWord,
                currentChapterIndex = chapterIndex,
                currentLineIndex = if (active != null) {
                    lineIndexForCurrentLocation(
                        book = active,
                        chapterIndex = chapterIndex,
                        globalWord = globalWord,
                        lines = it.chapterLines,
                        linesChapterIndex = it.chapterLinesChapterIndex,
                    )
                } else {
                    -1
                },
                bufferedListeningMs = player.bufferedListeningMs(),
                underruns = player.underruns,
            )
        }

        if (active != null && (chapterIndex != beforeChapter || _state.value.chapterLinesChapterIndex != chapterIndex)) {
            requestChapterLines(active, chapterIndex)
        }
    }

    private fun requestChapterLines(
        book: BookRecord,
        chapterIndex: Int,
    ) {
        val current = _state.value
        if (
            current.activeBook?.id == book.id &&
            current.chapterLinesChapterIndex == chapterIndex &&
            (current.chapterLines.isNotEmpty() || current.isLoadingChapterLines)
        ) {
            return
        }

        chapterLinesJob?.cancel()
        _state.update {
            it.copy(
                isLoadingChapterLines = true,
                chapterLines = if (it.chapterLinesChapterIndex == chapterIndex) it.chapterLines else emptyList(),
                chapterLinesChapterIndex = chapterIndex,
                currentLineIndex = -1,
            )
        }

        chapterLinesJob = scope.launch {
            runCatching {
                val text = bookStore.readChapter(book.id, chapterIndex)
                SentenceSegmenter.readerLines(
                    text = text,
                    singleNewlineIsParagraph =
                        book.format.equals("EPUB", true) ||
                            book.format.equals("HTML", true),
                )
            }.onSuccess { lines ->
                val snapshot = _state.value
                if (snapshot.activeBook?.id == book.id && snapshot.currentChapterIndex == chapterIndex) {
                    val lineIndex = lineIndexForCurrentLocation(
                        book = book,
                        chapterIndex = chapterIndex,
                        globalWord = snapshot.currentGlobalWord,
                        lines = lines,
                        linesChapterIndex = chapterIndex,
                    )
                    _state.update {
                        it.copy(
                            chapterLines = lines,
                            chapterLinesChapterIndex = chapterIndex,
                            currentLineIndex = lineIndex,
                            isLoadingChapterLines = false,
                        )
                    }
                }
            }.onFailure { failure ->
                if (failure !is kotlinx.coroutines.CancellationException) {
                    _state.update {
                        it.copy(
                            isLoadingChapterLines = false,
                            error = "Could not load chapter text: ${failure.message}",
                        )
                    }
                }
            }
        }
    }

    private fun lineIndexForCurrentLocation(
        book: BookRecord,
        chapterIndex: Int,
        globalWord: Long,
        lines: List<ReaderLine>,
        linesChapterIndex: Int,
    ): Int {
        if (linesChapterIndex != chapterIndex || lines.isEmpty()) return -1
        val chapter = book.chapters.getOrNull(chapterIndex) ?: return -1
        val localWord = (globalWord - chapter.startWord).coerceAtLeast(0L)
        return lineIndexForWord(lines, localWord)
    }

    private fun saveCurrentPosition() {
        val descriptor = player.currentDescriptor() ?: return
        val book = _state.value.activeBook ?: return
        if (descriptor.bookId != book.id) return

        val estimatedInsideSegment = descriptor.wordOffsetAtPosition()

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

    private fun effectiveBoundaryFor(
        book: BookRecord,
        chapterIndex: Int,
        batchIndex: Int,
        batchesCount: Int,
        planned: NarrationBoundary,
    ): NarrationBoundary {
        if (batchIndex != batchesCount - 1) return planned
        if (chapterIndex >= book.chapters.lastIndex) return NarrationBoundary.CHAPTER

        val current = book.chapters[chapterIndex].title
        val next = book.chapters[chapterIndex + 1].title
        val currentBase = current.substringBefore(" · Part")
        val nextBase = next.substringBefore(" · Part")
        return if (
            next.contains(" · Part") &&
            currentBase.equals(nextBase, ignoreCase = true)
        ) {
            NarrationBoundary.PARAGRAPH
        } else {
            NarrationBoundary.CHAPTER
        }
    }

    private fun preparationStatus(speed: Float): String =
        if (speed >= 1.5f) {
            "Preparing a larger ${displaySpeed(speed)} reserve…"
        } else {
            "Preparing audio…"
        }

    private fun initialListeningBufferMs(speed: Float): Long = when {
        speed >= 2.0f -> 150_000L
        speed >= 1.75f -> 120_000L
        speed >= 1.5f -> 90_000L
        speed >= 1.25f -> 45_000L
        else -> 20_000L
    }

    private fun targetListeningBufferMs(speed: Float): Long = when {
        speed >= 2.0f -> 360_000L
        speed >= 1.75f -> 300_000L
        speed >= 1.5f -> 240_000L
        speed >= 1.25f -> 150_000L
        else -> 90_000L
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
