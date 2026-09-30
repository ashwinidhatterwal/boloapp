package com.offlinenarrator.benchmark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinenarrator.benchmark.app.BoloPage
import com.offlinenarrator.benchmark.app.BoloUiState
import com.offlinenarrator.benchmark.app.BoloViewModel
import com.offlinenarrator.benchmark.book.BookRecord
import com.offlinenarrator.benchmark.book.LibraryBookItem
import com.offlinenarrator.benchmark.tts.TtsVoice
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun BoloScreen(viewModel: BoloViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val modelPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importKokoroModel)
    }

    val documentPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importDocument)
    }

    when {
        !state.modelPresent -> {
            ModelSetupScreen(
                state = state,
                onImportModel = {
                    modelPicker.launch(arrayOf("application/octet-stream", "*/*"))
                },
            )
        }

        state.page == BoloPage.READER && state.activeBook != null -> {
            ReaderScreen(
                state = state,
                onBack = viewModel::backToLibrary,
                onTogglePlayback = viewModel::togglePlayback,
                onStop = viewModel::stopNarration,
                onVoice = viewModel::selectVoice,
                onSpeed = viewModel::setPlaybackSpeed,
                onJumpFraction = viewModel::jumpToFraction,
                onJumpPage = viewModel::jumpToPage,
                onJumpByPages = viewModel::jumpByPages,
                onJumpChapter = viewModel::jumpToChapter,
                onPlayLine = viewModel::playFromLine,
                onPreviousChapter = viewModel::previousChapter,
                onNextChapter = viewModel::nextChapter,
                onClearCache = viewModel::clearPreparedAudio,
            )
        }

        else -> {
            LibraryScreen(
                state = state,
                onImportDocument = {
                    documentPicker.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                            "text/plain",
                            "text/html",
                            "application/xhtml+xml",
                            "application/zip",
                            "*/*",
                        )
                    )
                },
                onCancelImport = viewModel::cancelImport,
                onOpenBook = viewModel::openBook,
                onRemoveBook = viewModel::removeBook,
                onReturnToPlayer = viewModel::returnToPlayer,
                onTogglePlayback = viewModel::togglePlayback,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSetupScreen(
    state: BoloUiState,
    onImportModel: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bolo", fontWeight = FontWeight.Bold)
                        Text(
                            "Offline natural reader",
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
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column(
                    Modifier.padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "One-time narrator setup",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "Import the Kokoro FP32 ONNX model once. Your books, generated narration and playback stay on-device.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Button(
                        onClick = onImportModel,
                        enabled = !state.isPreparingModel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (state.isPreparingModel) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Text("Import Kokoro model")
                        }
                    }

                    Text(
                        state.status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    state.error?.let {
                        Text(
                            it,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(
    state: BoloUiState,
    onImportDocument: () -> Unit,
    onCancelImport: () -> Unit,
    onOpenBook: (String) -> Unit,
    onRemoveBook: (String) -> Unit,
    onReturnToPlayer: () -> Unit,
    onTogglePlayback: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Bolo", fontWeight = FontWeight.Bold)
                        Text(
                            "Your offline library",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onImportDocument,
                        enabled = !state.isImportingBook,
                    ) {
                        Text(if (state.isImportingBook) "Indexing…" else "Import")
                    }
                },
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.isImportingBook) {
                item {
                    Card(Modifier.fillMaxWidth()) {
                        Column(
                            Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text(
                                state.importPhase ?: "Indexing document",
                                fontWeight = FontWeight.Bold,
                            )

                            state.importDetail
                                ?.takeIf { it.isNotBlank() }
                                ?.let { detail ->
                                    Text(
                                        detail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }

                            val fraction = state.importFraction
                            if (fraction != null) {
                                LinearProgressIndicator(
                                    progress = { fraction.coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    "${(fraction.coerceIn(0f, 1f) * 100).roundToInt()}%",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                LinearProgressIndicator(
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }

                            if (state.importTotal != null && state.importTotal > 0) {
                                Text(
                                    "${state.importCurrent} / ${state.importTotal}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            TextButton(onClick = onCancelImport) {
                                Text("Cancel import")
                            }
                        }
                    }
                }
            }

            if (
                state.readerStarted &&
                state.activeBook != null
            ) {
                item {
                    MiniPlayerCard(
                        state = state,
                        onReturn = onReturnToPlayer,
                        onToggle = onTogglePlayback,
                    )
                }
            }

            if (state.books.isEmpty() && !state.isImportingBook) {
                item {
                    EmptyLibraryCard(onImportDocument)
                }
            }

            items(
                items = state.books,
                key = { it.book.id },
            ) { item ->
                BookCard(
                    item = item,
                    onOpen = { onOpenBook(item.book.id) },
                    onRemove = { onRemoveBook(item.book.id) },
                )
            }

            state.error?.let { error ->
                item {
                    Text(
                        error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryCard(
    onImportDocument: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Import your first book",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "Import EPUB, PDF, DOCX, TXT or HTML. Bolo converts them into the same indexed offline book format, so even very large documents stay easy to navigate.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImportDocument) {
                Text("Choose document")
            }
        }
    }
}

@Composable
private fun BookCard(
    item: LibraryBookItem,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val book = item.book
    val page = book.pageForGlobalWord(item.progress?.globalWord ?: 0L)

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                book.author,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${book.format} · ${book.sourceName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            LinearProgressIndicator(
                progress = { item.progressFraction },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "${pageNumberText(book, page)} / ${pageCountText(book)} · ${book.format} · ${book.chapters.size} sections",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(onClick = onOpen) {
                    Text(if (item.progress != null) "Resume" else "Open")
                }
                TextButton(onClick = onRemove) {
                    Text("Remove")
                }
            }
        }
    }
}

@Composable
private fun MiniPlayerCard(
    state: BoloUiState,
    onReturn: () -> Unit,
    onToggle: () -> Unit,
) {
    val book = state.activeBook ?: return

    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text("Now playing", style = MaterialTheme.typography.labelMedium)
                Text(
                    book.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    pageNumberText(book, book.pageForGlobalWord(state.currentGlobalWord)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            OutlinedButton(onClick = onToggle) {
                Text(if (state.isPlaying) "Pause" else "Play")
            }
            TextButton(onClick = onReturn) {
                Text("Open")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderScreen(
    state: BoloUiState,
    onBack: () -> Unit,
    onTogglePlayback: () -> Unit,
    onStop: () -> Unit,
    onVoice: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onJumpFraction: (Float) -> Unit,
    onJumpPage: (Int) -> Unit,
    onJumpByPages: (Int) -> Unit,
    onJumpChapter: (Int) -> Unit,
    onPlayLine: (Int) -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClearCache: () -> Unit,
) {
    val book = state.activeBook ?: return
    val chapter = book.chapters.getOrNull(state.currentChapterIndex)
    val chapterTitle = chapter?.title ?: "Section"

    var showContents by remember { mutableStateOf(false) }
    var showOptions by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }

    val liveProgress = if (book.hasFixedPages) {
        val page = book.pageForGlobalWord(state.currentGlobalWord)
        if (book.estimatedPages <= 1) 0f else {
            ((page - 1).toFloat() / (book.estimatedPages - 1).toFloat()).coerceIn(0f, 1f)
        }
    } else if (book.totalWords <= 1L) {
        0f
    } else {
        (state.currentGlobalWord.toDouble() / (book.totalWords - 1L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    var scrubPosition by remember(book.id) { mutableStateOf(liveProgress) }
    LaunchedEffect(state.currentGlobalWord, book.id, scrubbing) {
        if (!scrubbing) scrubPosition = liveProgress
    }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("‹ Library") }
                },
                title = {
                    Column {
                        Text(
                            book.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            book.author,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { showContents = true }) {
                        Text("Chapters")
                    }
                },
            )
        },
        bottomBar = {
            CompactReaderPlayer(
                state = state,
                book = book,
                chapterTitle = chapterTitle,
                scrubPosition = scrubPosition,
                onScrub = {
                    scrubbing = true
                    scrubPosition = it
                },
                onScrubFinished = {
                    val target = scrubPosition
                    scrubbing = false
                    if (book.hasFixedPages) {
                        val page = (1 + ((book.estimatedPages - 1) * target).roundToInt())
                            .coerceIn(1, book.estimatedPages)
                        onJumpPage(page)
                    } else {
                        onJumpFraction(target)
                    }
                },
                onChooseChapter = { showContents = true },
                onToggle = onTogglePlayback,
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
                onOptions = { showOptions = true },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            ReaderChapterHeader(
                state = state,
                book = book,
                chapterTitle = chapterTitle,
                onChooseChapter = { showContents = true },
            )

            state.error?.let { error ->
                Text(
                    error,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            ProfessionalReadingPane(
                state = state,
                onPlayLine = onPlayLine,
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (showContents) {
        ContentsSheet(
            book = book,
            currentChapter = state.currentChapterIndex,
            onDismiss = { showContents = false },
            onChapter = { index ->
                showContents = false
                onJumpChapter(index)
            },
        )
    }

    if (showOptions) {
        ReaderOptionsSheet(
            state = state,
            book = book,
            onDismiss = { showOptions = false },
            onVoice = onVoice,
            onSpeed = onSpeed,
            onStop = {
                onStop()
                showOptions = false
            },
            onMinus50 = { onJumpByPages(-50) },
            onPlus50 = { onJumpByPages(50) },
            onGoToPage = {
                showOptions = false
                showGoToPage = true
            },
            onClearCache = onClearCache,
        )
    }

    if (showGoToPage) {
        GoToPageDialog(
            maxPage = book.estimatedPages,
            currentPage = book.pageForGlobalWord(state.currentGlobalWord),
            estimated = !book.hasFixedPages,
            onDismiss = { showGoToPage = false },
            onGo = { page ->
                showGoToPage = false
                onJumpPage(page)
            },
        )
    }
}

@Composable
private fun ReaderChapterHeader(
    state: BoloUiState,
    book: BookRecord,
    chapterTitle: String,
    onChooseChapter: () -> Unit,
) {
    val page = book.pageForGlobalWord(state.currentGlobalWord)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onChooseChapter)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            "Chapter ${state.currentChapterIndex + 1} of ${book.chapters.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            chapterTitle,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${pageNumberText(book, page)}  ·  Tap a sentence to play from there",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider()
}

@Composable
private fun ProfessionalReadingPane(
    state: BoloUiState,
    onPlayLine: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    LaunchedEffect(state.currentLineIndex, state.chapterLinesChapterIndex) {
        val index = state.currentLineIndex
        if (index in state.chapterLines.indices) {
            listState.animateScrollToItem((index - 3).coerceAtLeast(0))
        }
    }

    when {
        state.isLoadingChapterLines -> {
            Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(28.dp))
            }
        }
        state.chapterLines.isEmpty() -> {
            Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "No readable text in this section.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            LazyColumn(
                state = listState,
                modifier = modifier.fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(
                    items = state.chapterLines,
                    key = { _, line -> line.index },
                ) { index, line ->
                    val current = index == state.currentLineIndex
                    Surface(
                        onClick = { onPlayLine(index) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = if (current) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                    ) {
                        Text(
                            line.text,
                            modifier = Modifier.padding(
                                horizontal = 10.dp,
                                vertical = if (line.paragraphBreakAfter) 10.dp else 6.dp,
                            ),
                            fontSize = 18.sp,
                            lineHeight = 29.sp,
                            fontWeight = if (current) FontWeight.Medium else FontWeight.Normal,
                            color = if (current) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                        )
                    }
                    if (line.paragraphBreakAfter) Spacer(Modifier.height(8.dp))
                }
            }
        }
    }
}

@Composable
private fun CompactReaderPlayer(
    state: BoloUiState,
    book: BookRecord,
    chapterTitle: String,
    scrubPosition: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onChooseChapter: () -> Unit,
    onToggle: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onOptions: () -> Unit,
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable(onClick = onChooseChapter)
                        .padding(vertical = 2.dp),
                ) {
                    Text(
                        chapterTitle,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${playerStateLabel(state).lowercase().replaceFirstChar { it.uppercase() }} · ${formatTime(state.bufferedListeningMs)} ready",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onOptions) { Text("•••") }
            }

            Slider(
                value = scrubPosition.coerceIn(0f, 1f),
                onValueChange = onScrub,
                onValueChangeFinished = onScrubFinished,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(
                    onClick = onPreviousChapter,
                    enabled = state.currentChapterIndex > 0,
                ) {
                    Text("‹ Chapter", style = MaterialTheme.typography.labelLarge)
                }

                Button(
                    onClick = onToggle,
                    enabled = state.engineReady && state.playerReady &&
                        (!state.readerStarted || state.playbackStarted),
                    modifier = Modifier.size(50.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        when {
                            state.readerStarted && !state.playbackStarted -> "…"
                            state.isPlaying -> "Ⅱ"
                            else -> "▶"
                        },
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                TextButton(
                    onClick = onNextChapter,
                    enabled = state.currentChapterIndex < book.chapters.lastIndex,
                ) {
                    Text("Chapter ›", style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderOptionsSheet(
    state: BoloUiState,
    book: BookRecord,
    onDismiss: () -> Unit,
    onVoice: (String) -> Unit,
    onSpeed: (Float) -> Unit,
    onStop: () -> Unit,
    onMinus50: () -> Unit,
    onPlus50: () -> Unit,
    onGoToPage: () -> Unit,
    onClearCache: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                "Playback",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Text("Speed", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "Listening speed",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                SpeedMenu(selected = state.playbackSpeed, onSelect = onSpeed)
            }

            VoiceMenu(
                voices = state.voices,
                selectedId = state.selectedVoiceId,
                enabled = !state.readerStarted,
                onVoice = onVoice,
            )

            HorizontalDivider()
            Text("Navigate", fontWeight = FontWeight.SemiBold)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(onClick = onMinus50, modifier = Modifier.weight(1f)) {
                    Text("−50")
                }
                OutlinedButton(onClick = onGoToPage, modifier = Modifier.weight(1f)) {
                    Text("Page")
                }
                OutlinedButton(onClick = onPlus50, modifier = Modifier.weight(1f)) {
                    Text("+50")
                }
            }
            Text(
                "${pageNumberText(book, book.pageForGlobalWord(state.currentGlobalWord))} / ${pageCountText(book)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HorizontalDivider()
            ReaderDetailsCard(state = state, onClearCache = onClearCache)

            TextButton(
                onClick = onStop,
                enabled = state.readerStarted,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Stop narration")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ContentsSheet(
    book: BookRecord,
    currentChapter: Int,
    onDismiss: () -> Unit,
    onChapter: (Int) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, book.chapters) {
        val q = query.trim()
        if (q.isBlank()) {
            book.chapters
        } else {
            book.chapters.filter { chapter ->
                chapter.title.contains(q, ignoreCase = true) ||
                    (chapter.index + 1).toString() == q
            }
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Chapters",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${book.chapters.size} sections · ${pageCountText(book)} · ${book.format}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it.take(80) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Find chapter or number") },
                singleLine = true,
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                items(
                    items = filtered,
                    key = { chapter -> chapter.index },
                ) { chapter ->
                    val index = chapter.index
                    TextButton(
                        onClick = { onChapter(index) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColors(
                            containerColor = if (index == currentChapter) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        ),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.padding(end = 12.dp),
                            )
                            Column(Modifier.weight(1f)) {
                                Text(
                                    chapter.title,
                                    fontWeight = if (index == currentChapter) FontWeight.Bold else FontWeight.Normal,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    pageNumberText(book, book.pageForGlobalWord(chapter.startWord)),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (index == currentChapter) {
                                Text(
                                    "Playing",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }

                if (filtered.isEmpty()) {
                    item {
                        Text(
                            "No matching chapter.",
                            modifier = Modifier.padding(vertical = 20.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GoToPageDialog(
    maxPage: Int,
    currentPage: Int,
    estimated: Boolean,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(currentPage.toString()) }
    val parsed = value.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (estimated) "Go to estimated page" else "Go to page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = {
                        value = it.filter(Char::isDigit).take(7)
                    },
                    label = { Text("Page 1–$maxPage") },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                    ),
                    singleLine = true,
                )
                Text(
                    if (estimated) {
                        "Bolo maps the estimated page directly to the document index; skipped text is not synthesized."
                    } else {
                        "This is the PDF's real page number. Skipped pages are not synthesized."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    parsed?.let {
                        onGo(it.coerceIn(1, maxPage))
                    }
                },
                enabled = parsed != null,
            ) {
                Text("Go")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun VoiceMenu(
    voices: List<TtsVoice>,
    selectedId: String?,
    enabled: Boolean,
    onVoice: (String) -> Unit,
) {
    if (voices.isEmpty()) return

    var expanded by remember { mutableStateOf(false) }
    val selected = voices.firstOrNull { it.id == selectedId }

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { expanded = true },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Narrator · ${selected?.name ?: "Choose voice"}")
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            voices.forEach { voice ->
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
private fun SpeedMenu(
    selected: Float,
    onSelect: (Float) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    Box {
        OutlinedButton(onClick = { expanded = true }) {
            Text(formatSpeed(selected))
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            listOf(1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                DropdownMenuItem(
                    text = { Text(formatSpeed(speed)) },
                    onClick = {
                        onSelect(speed)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ReaderDetailsCard(
    state: BoloUiState,
    onClearCache: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text("Reader details", fontWeight = FontWeight.Bold)
            Metric("Generated this session", state.generatedSegments.toString())
            Metric("Dialogue segments", state.dialogueSegments.toString())
            Metric("Characters identified", state.charactersVoiced.toString())
            Metric("Cached segments reused", state.cacheHits.toString())
            Metric("Automatic QC retries", state.qcRetries.toString())
            state.meanGenerationRtf?.let {
                Metric(
                    "Mean generation RTF",
                    String.format(Locale.US, "%.3f", it),
                )
            }
            Metric("Playback gaps", state.underruns.toString())
            Metric("Prepared audio", formatBytes(state.cacheBytes))

            state.deviceSnapshot?.let { device ->
                Metric(
                    "Battery",
                    device.batteryTemperatureC?.let {
                        String.format(Locale.US, "%.1f °C", it)
                    } ?: "Unavailable",
                )
                Metric("Thermal", device.thermalStatus)
            }

            Text(
                "Audiobook Compiler v1 analyses the chapter first, feeds Kokoro quality-range chunks, keeps uncertain emotion neutral, automatically retries suspicious audio, and finishes synthesis before playback begins.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                "While prepared audio is playing, Kokoro is idle. Playback lives in Android's MediaSessionService, so lock-screen, notification and headset controls remain available without continuous neural generation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            TextButton(
                onClick = onClearCache,
                enabled = !state.readerStarted,
            ) {
                Text("Clear prepared audio")
            }
        }
    }
}

@Composable
private fun Metric(
    label: String,
    value: String,
) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun playerStateLabel(state: BoloUiState): String = when {
    state.finished -> "FINISHED"
    state.thermalPaused -> "COOLING"
    state.readerStarted && !state.playbackStarted -> "COMPILING"
    state.isPlaying -> "NOW PLAYING"
    state.isPaused -> "PAUSED"
    else -> "READY TO READ"
}

private fun pageNumberText(book: BookRecord, page: Int): String =
    if (book.hasFixedPages) "Page $page" else "Page ~$page"

private fun pageCountText(book: BookRecord): String =
    if (book.hasFixedPages) "${book.estimatedPages} pages" else "~${book.estimatedPages} pages"


private fun formatTime(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60L, total % 60L)
}

private fun formatSpeed(value: Float): String =
    if (value == value.toInt().toFloat()) {
        "${value.toInt()}.0×"
    } else {
        "${value}×"
    }

private fun formatBytes(bytes: Long): String =
    if (bytes >= 1024L * 1024L) {
        String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    } else {
        String.format(Locale.US, "%.1f KB", bytes / 1024.0)
    }
