package com.offlinenarrator.benchmark.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.style.TextAlign
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

    val epubPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        uri?.let(viewModel::importEpub)
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
                onPreviousChapter = viewModel::previousChapter,
                onNextChapter = viewModel::nextChapter,
                onClearCache = viewModel::clearPreparedAudio,
            )
        }

        else -> {
            LibraryScreen(
                state = state,
                onImportEpub = {
                    epubPicker.launch(arrayOf("application/epub+zip", "application/zip", "*/*"))
                },
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
    onImportEpub: () -> Unit,
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
                        onClick = onImportEpub,
                        enabled = !state.isImportingBook,
                    ) {
                        Text(if (state.isImportingBook) "Indexing…" else "Import EPUB")
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
                        Row(
                            Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                            Column {
                                Text("Indexing EPUB", fontWeight = FontWeight.Bold)
                                Text(
                                    "Building chapters and fast book locations…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
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
                    EmptyLibraryCard(onImportEpub)
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
    onImportEpub: () -> Unit,
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
                "Bolo indexes an EPUB by chapter and location. Even a very large novel can be opened or jumped through without loading the whole book into memory.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onImportEpub) {
                Text("Choose EPUB")
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

            LinearProgressIndicator(
                progress = { item.progressFraction },
                modifier = Modifier.fillMaxWidth(),
            )

            Text(
                "~$page / ~${book.estimatedPages} pages · ${book.chapters.size} chapters",
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
                    "Page ~${book.pageForGlobalWord(state.currentGlobalWord)}",
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
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
    onClearCache: () -> Unit,
) {
    val book = state.activeBook ?: return
    val chapter = book.chapters.getOrNull(state.currentChapterIndex)

    var details by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showGoToPage by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }

    val liveProgress = if (book.totalWords <= 1L) {
        0f
    } else {
        (state.currentGlobalWord.toDouble() / (book.totalWords - 1L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    var scrubPosition by remember(book.id) { mutableStateOf(liveProgress) }

    LaunchedEffect(
        state.currentGlobalWord,
        book.id,
        scrubbing,
    ) {
        if (!scrubbing) {
            scrubPosition = liveProgress
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            book.title,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            chapter?.title ?: "Book",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) {
                        Text("Library")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            BookPlayerCard(
                state = state,
                book = book,
                chapterTitle = chapter?.title ?: "Chapter",
                onToggle = onTogglePlayback,
                onStop = onStop,
                onVoice = onVoice,
                onSpeed = onSpeed,
            )

            BookNavigationCard(
                state = state,
                book = book,
                chapterTitle = chapter?.title ?: "Chapter",
                scrubPosition = scrubPosition,
                onScrub = {
                    scrubbing = true
                    scrubPosition = it
                },
                onScrubFinished = {
                    val target = scrubPosition
                    scrubbing = false
                    onJumpFraction(target)
                },
                onMinus50 = { onJumpByPages(-50) },
                onPlus50 = { onJumpByPages(50) },
                onContents = { showContents = true },
                onGoToPage = { showGoToPage = true },
                onPreviousChapter = onPreviousChapter,
                onNextChapter = onNextChapter,
            )

            state.error?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            TextButton(onClick = { details = !details }) {
                Text(if (details) "Hide reader details" else "Reader details")
            }

            if (details) {
                ReaderDetailsCard(
                    state = state,
                    onClearCache = onClearCache,
                )
            }

            Spacer(Modifier.height(24.dp))
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

    if (showGoToPage) {
        GoToPageDialog(
            maxPage = book.estimatedPages,
            currentPage = book.pageForGlobalWord(state.currentGlobalWord),
            onDismiss = { showGoToPage = false },
            onGo = { page ->
                showGoToPage = false
                onJumpPage(page)
            },
        )
    }
}

@Composable
private fun BookPlayerCard(
    state: BoloUiState,
    book: BookRecord,
    chapterTitle: String,
    onToggle: () -> Unit,
    onStop: () -> Unit,
    onVoice: (String) -> Unit,
    onSpeed: (Float) -> Unit,
) {
    val selectedVoice = state.voices.firstOrNull { it.id == state.selectedVoiceId }
    val page = book.pageForGlobalWord(state.currentGlobalWord)
    val progress = if (book.totalWords <= 1L) {
        0f
    } else {
        (state.currentGlobalWord.toDouble() / (book.totalWords - 1L).toDouble())
            .toFloat()
            .coerceIn(0f, 1f)
    }

    Card(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(13.dp),
        ) {
            Text(
                playerStateLabel(state),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
            )

            Text(
                chapterTitle,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Text(
                selectedVoice?.name ?: "Narrator",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Page ~$page / ~${book.estimatedPages}",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "${formatTime(state.bufferedListeningMs)} ready",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(22.dp),
            ) {
                SpeedMenu(
                    selected = state.playbackSpeed,
                    onSelect = onSpeed,
                )

                Button(
                    onClick = onToggle,
                    enabled = state.engineReady &&
                        state.playerReady &&
                        (!state.readerStarted || state.playbackStarted),
                    modifier = Modifier.size(82.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(
                        when {
                            state.readerStarted && !state.playbackStarted -> "…"
                            state.isPlaying -> "Ⅱ"
                            else -> "▶"
                        },
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }

                OutlinedButton(
                    onClick = onStop,
                    enabled = state.readerStarted,
                    modifier = Modifier.size(52.dp),
                    shape = CircleShape,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("■")
                }
            }

            VoiceMenu(
                voices = state.voices,
                selectedId = state.selectedVoiceId,
                enabled = !state.readerStarted,
                onVoice = onVoice,
            )

            Text(
                state.status,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.thermalPaused) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BookNavigationCard(
    state: BoloUiState,
    book: BookRecord,
    chapterTitle: String,
    scrubPosition: Float,
    onScrub: (Float) -> Unit,
    onScrubFinished: () -> Unit,
    onMinus50: () -> Unit,
    onPlus50: () -> Unit,
    onContents: () -> Unit,
    onGoToPage: () -> Unit,
    onPreviousChapter: () -> Unit,
    onNextChapter: () -> Unit,
) {
    val previewPage = (
        1 +
            ((book.estimatedPages - 1) * scrubPosition.coerceIn(0f, 1f))
                .roundToInt()
        ).coerceIn(1, book.estimatedPages)

    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Book position", fontWeight = FontWeight.Bold)
            Text(
                chapterTitle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            Slider(
                value = scrubPosition,
                onValueChange = onScrub,
                onValueChangeFinished = onScrubFinished,
                valueRange = 0f..1f,
            )

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    "Start",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "Page ~$previewPage",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "End",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onMinus50,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("−50 pages")
                }
                OutlinedButton(
                    onClick = onPlus50,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("+50 pages")
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onContents,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Contents")
                }
                OutlinedButton(
                    onClick = onGoToPage,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Go to page")
                }
            }

            HorizontalDivider()

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(
                    onClick = onPreviousChapter,
                    enabled = state.currentChapterIndex > 0,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Previous chapter")
                }
                TextButton(
                    onClick = onNextChapter,
                    enabled = state.currentChapterIndex < book.chapters.lastIndex,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Next chapter")
                }
            }

            Text(
                "EPUBs are reflowable, so Bolo uses estimated pages at about 250 words each. Chapter and word locations are exact.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    ModalBottomSheet(
        onDismissRequest = onDismiss,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            Text(
                "Contents",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "${book.chapters.size} chapters · ~${book.estimatedPages} pages",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 560.dp),
                contentPadding = PaddingValues(bottom = 28.dp),
            ) {
                itemsIndexed(
                    items = book.chapters,
                    key = { _, chapter -> chapter.index },
                ) { index, chapter ->
                    TextButton(
                        onClick = { onChapter(index) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    chapter.title,
                                    fontWeight = if (index == currentChapter) {
                                        FontWeight.Bold
                                    } else {
                                        FontWeight.Normal
                                    },
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "Page ~${book.pageForGlobalWord(chapter.startWord)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (index == currentChapter) {
                                Text(
                                    "Current",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun GoToPageDialog(
    maxPage: Int,
    currentPage: Int,
    onDismiss: () -> Unit,
    onGo: (Int) -> Unit,
) {
    var value by remember { mutableStateOf(currentPage.toString()) }
    val parsed = value.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Go to estimated page") },
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
                    "Bolo maps the page directly to the book index; skipped pages are not synthesized.",
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
            Metric("Cached segments reused", state.cacheHits.toString())
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
                "Playback lives in Android's MediaSessionService, so lock-screen, notification and headset play/pause controls can keep working while the screen is off.",
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
    state.readerStarted && !state.playbackStarted -> "PREPARING"
    state.isPlaying -> "NOW PLAYING"
    state.isPaused -> "PAUSED"
    else -> "READY TO READ"
}

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
