package com.offlinenarrator.benchmark.app

import android.content.Context
import android.net.Uri
import com.offlinenarrator.benchmark.book.BookLocation
import com.offlinenarrator.benchmark.book.BookProgress
import com.offlinenarrator.benchmark.book.BookRecord
import com.offlinenarrator.benchmark.book.CharacterVoiceStore
import com.offlinenarrator.benchmark.book.DocumentImportProgress
import com.offlinenarrator.benchmark.book.ReaderLine
import com.offlinenarrator.benchmark.book.SentenceSegmenter
import com.offlinenarrator.benchmark.book.lineIndexForWord
import com.offlinenarrator.benchmark.book.DocumentBookStore
import com.offlinenarrator.benchmark.book.LibraryBookItem
import com.offlinenarrator.benchmark.model.KokoroModelStore
import com.offlinenarrator.benchmark.playback.BackgroundAudioController
import com.offlinenarrator.benchmark.reader.NarrationCache
import com.offlinenarrator.benchmark.reader.AudiobookStore
import com.offlinenarrator.benchmark.reader.ChapterPreparationCompiler
import com.offlinenarrator.benchmark.reader.NarrationCoordinator
import com.offlinenarrator.benchmark.reader.PlaybackPreparationGate
import com.offlinenarrator.benchmark.reader.AudiobookPreparationScheduler
import com.offlinenarrator.benchmark.book.DirectorSettings
import com.offlinenarrator.benchmark.book.DirectorSettingsStore
import com.offlinenarrator.benchmark.tts.KokoroTtsEngine
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
    val compilationFraction: Float? = null,
    val compilationEtaMs: Long? = null,
    val directorSettings: DirectorSettings = DirectorSettings(),
    val preparationQueued: Boolean = false,
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
    private val audiobooks = AudiobookStore(app)
    private val chapterCompiler = ChapterPreparationCompiler(app)
    private val directorSettingsStore = DirectorSettingsStore(app)
    private val engine = KokoroTtsEngine(
        app,
        modelStore,
        KokoroRuntimeProfile.CPU_BASELINE,
    )

    private val initialized = AtomicBoolean(false)

    private val savedSpeed = prefs.getFloat("playback_speed", 1.0f)
        .coerceIn(0.75f, 2.0f)

    private val _state = MutableStateFlow(
        BoloUiState(
            modelPresent = modelStore.exists(),
            playbackSpeed = savedSpeed,
            deviceSnapshot = DeviceDiagnostics.capture(app),
            directorSettings = directorSettingsStore.load(),
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
    
    private var selectedLocation: BookLocation? = null
    private var resumePositionMs: Long = 0L

    init {
        initialize()
    }

    private fun initialize() {
        if (!initialized.compareAndSet(false, true)) return

        scope.launch {
            refreshLibrary()
            val preparedSizeBytes = withContext(Dispatchers.IO) { audiobooks.sizeBytes() }
            _state.update { it.copy(cacheBytes = preparedSizeBytes) }

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
            scope.launch {
                androidx.work.WorkManager.getInstance(app).getWorkInfosByTagFlow("bolo-preparation").collect { jobs ->
                    val snapshot = _state.value
                    val relevant = jobs.filter { "bolo-book-${snapshot.activeBook?.id}" in it.tags }
                    val running = relevant.firstOrNull { it.state == androidx.work.WorkInfo.State.RUNNING }
                    val queued = relevant.any { !it.state.isFinished }
                    val failure = relevant.lastOrNull { it.state == androidx.work.WorkInfo.State.FAILED }?.outputData?.getString("error")
                    _state.update { current ->
                        if (current.isGenerating || current.readerStarted) current.copy(preparationQueued = queued)
                        else current.copy(preparationQueued = queued,
                            status = running?.progress?.getString("status")?.takeIf { it.isNotBlank() }
                                ?: if (queued) "Preparation queued; waiting for device conditions." else current.status,
                            error = failure ?: current.error)
                    }
                }
            }
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

            val result = NarrationCoordinator.mutex.withLock { modelStore.importFrom(uri) }

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

        loadVoiceChoices()
        _state.update { it.copy(isPreparingModel = false, modelPresent = true,
            engineReady = true, status = "Ready. Model loads only during preparation.", error = null) }
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
            AudiobookPreparationScheduler.cancel(app, bookId)
            withContext(Dispatchers.IO) { audiobooks.deleteBook(bookId) }
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
        if (!snapshot.playerReady || snapshot.activeBook == null) return

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
                PlaybackPreparationGate.playing = true
                scope.launch { NarrationCoordinator.mutex.withLock {
                    player.resume()
                } }
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
        player.stop()
        PlaybackPreparationGate.playing = false

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
        if (_state.value.playbackStarted && player.seekToWord(book.id, location.chapterIndex, location.wordOffset)) {
            selectedLocation = location
            resumePositionMs = 0L
            if (forcePlayback && player.isPaused()) player.resume()
            refreshPlaybackState()
            saveCurrentPosition()
            return
        }
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
            androidx.work.WorkManager.getInstance(app).cancelAllWorkByTag("bolo-preparation")
            NarrationCoordinator.mutex.withLock { withContext(Dispatchers.IO) { audiobooks.clear() }; cache.clear() }
            _state.update {
                it.copy(
                    cacheBytes = 0L,
                    status = "Prepared audio cleared.",
                )
            }
        }
    }

    fun saveDirectorSettings(settings: DirectorSettings) {
        runCatching { directorSettingsStore.save(settings) }
            .onSuccess { _state.update { it.copy(directorSettings = settings, status = "Narration settings saved. Existing editions remain available until cleared.", error = null) } }
            .onFailure { failure -> _state.update { it.copy(error = failure.message) } }
    }

    fun prepareAhead(chapterCount: Int, chargingOnly: Boolean) {
        val snapshot = _state.value
        val book = snapshot.activeBook ?: return
        val first = snapshot.currentChapterIndex
        val last = if (chapterCount <= 0) book.chapters.lastIndex else minOf(book.chapters.lastIndex, first + chapterCount - 1)
        AudiobookPreparationScheduler.schedule(app, book.id, first..last, chargingOnly)
        _state.update { it.copy(preparationQueued = true, status = if (chargingOnly) "Preparation queued for charging." else "Background preparation queued.") }
    }

    fun cancelPreparation() {
        _state.value.activeBook?.id?.let { AudiobookPreparationScheduler.cancel(app, it) }
        _state.update { it.copy(preparationQueued = false, status = "Preparation cancelled; completed audio kept.") }
    }

    private fun startNarration(start: BookLocation, initialSeekMs: Long) {
        val snapshot = _state.value
        val book = snapshot.activeBook ?: return
        if (!snapshot.playerReady) return
        readerJob?.cancel()
        player.reset(snapshot.playbackSpeed)
        PlaybackPreparationGate.playing = false
        selectedLocation = start
        resumePositionMs = initialSeekMs
        _state.update { it.copy(readerStarted = true, playbackStarted = false, isGenerating = true,
            isPlaying = false, isPaused = false, finished = false, error = null,
            currentChapterIndex = start.chapterIndex, currentGlobalWord = start.globalWord,
            compilationFraction = null, compilationEtaMs = null, status = "Opening prepared chapter…") }
        readerJob = scope.launch {
            try {
                val settings = directorSettingsStore.load()
                val existing = chapterCompiler.find(book, start.chapterIndex, settings)
                val complete = existing != null && withContext(Dispatchers.IO) { audiobooks.complete(existing) }
                val chapter = if (complete) existing!! else chapterCompiler.prepare(book, start.chapterIndex, settings) { p ->
                    _state.update { it.copy(qcRetries = p.retries, meanGenerationRtf = p.rtf,
                        compilationFraction = if (p.total > 0) p.completed.toFloat() / p.total else null,
                        compilationEtaMs = p.etaMs, generatedSegments = p.completed, status = p.status) }
                }
                ensureActive()
                PlaybackPreparationGate.playing = true
                NarrationCoordinator.mutex.withLock {
                    ensureActive()
                    PlaybackPreparationGate.playing = true
                    val bookChapter = book.chapters[start.chapterIndex]
                    for (entry in chapter.entries) {
                        val batch = entry.batch
                        player.enqueue(java.io.File(chapter.dir, entry.fileName), entry.durationMs,
                            book.id, book.title, bookChapter.title, start.chapterIndex, bookChapter.startWord,
                            batch.startWord, batch.wordCount, batch.unitWordEnds.toLongArray(), entry.timeAnchors)
                    }
                    player.markInputComplete()
                    if (player.mediaItemCount() > 0) {
                        player.seekToWord(book.id, start.chapterIndex, start.wordOffset, initialSeekMs)
                        player.start()
                    } else error("Chapter contains no playable narration")
                }
                val preparedSizeBytes = withContext(Dispatchers.IO) { audiobooks.sizeBytes() }
                _state.update { it.copy(isGenerating = false, playbackStarted = player.started,
                    cacheHits = if (complete) chapter.entries.size else 0, compilationFraction = 1f, compilationEtaMs = null,
                    cacheBytes = preparedSizeBytes,
                    status = if (chapter.warning.isNotBlank()) chapter.warning else "Playing prepared chapter · ${chapter.director} direction · narrator unloaded.") }
            } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (failure: Exception) {
                player.stop(); PlaybackPreparationGate.playing = false
                _state.update { it.copy(readerStarted = false, playbackStarted = false, isGenerating = false,
                    isPlaying = false, isPaused = false, error = failure.message,
                    status = "Preparation stopped; completed chunks kept. Press Play to retry.") }
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
                        PlaybackPreparationGate.playing = false
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
                        val prepared = chapterCompiler.find(book, next.index, directorSettingsStore.load())
                        if (prepared != null && withContext(Dispatchers.IO) { audiobooks.complete(prepared) }) {
                            startNarration(location, 0L)
                        }
                    } else {
                        PlaybackPreparationGate.playing = false
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
        player.playbackError?.let { failure ->
            stopNarrationInternal(savePosition = true)
            _state.update { it.copy(error = failure, status = "Audio playback stopped. Prepared work is kept.") }
            return
        }

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

    companion object {
        @Volatile
        private var instance: BookReaderRuntime? = null

        fun get(context: Context): BookReaderRuntime =
            instance ?: synchronized(this) {
                instance ?: BookReaderRuntime(context).also { instance = it }
            }

        private const val DEFAULT_VOICE_ID = "am_onyx"
    }
}
