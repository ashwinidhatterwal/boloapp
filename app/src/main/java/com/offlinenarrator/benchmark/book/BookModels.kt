package com.offlinenarrator.benchmark.book

import kotlin.math.ceil

const val WORDS_PER_ESTIMATED_PAGE = 250L

data class WordCheckpoint(
    val wordOffset: Long,
    val charOffset: Int,
)

data class PageAnchor(
    val pageNumber: Int,
    val globalWord: Long,
)

data class BookChapter(
    val index: Int,
    val title: String,
    val fileName: String,
    val wordCount: Long,
    val startWord: Long,
    val checkpoints: List<WordCheckpoint> = emptyList(),
)

data class BookRecord(
    val id: String,
    val title: String,
    val author: String,
    val sourceName: String,
    val format: String = "EPUB",
    val importedAt: Long,
    val totalWords: Long,
    val chapters: List<BookChapter>,
    val pageAnchors: List<PageAnchor> = emptyList(),
) {
    val hasFixedPages: Boolean
        get() = pageAnchors.isNotEmpty()

    val estimatedPages: Int
        get() = if (pageAnchors.isNotEmpty()) {
            maxOf(1, pageAnchors.maxOfOrNull { it.pageNumber } ?: pageAnchors.size)
        } else {
            maxOf(
                1,
                ceil(totalWords.toDouble() / WORDS_PER_ESTIMATED_PAGE.toDouble()).toInt(),
            )
        }

    fun pageForGlobalWord(globalWord: Long): Int {
        val safe = globalWord.coerceIn(0L, maxOf(0L, totalWords - 1L))
        if (pageAnchors.isEmpty()) {
            return (safe / WORDS_PER_ESTIMATED_PAGE).toInt() + 1
        }

        var low = 0
        var high = pageAnchors.lastIndex
        var best = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (pageAnchors[mid].globalWord <= safe) {
                best = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return pageAnchors[best].pageNumber
    }

    fun globalWordForPage(page: Int): Long {
        val safePage = page.coerceIn(1, estimatedPages)
        if (pageAnchors.isNotEmpty()) {
            val anchor = pageAnchors.firstOrNull { it.pageNumber >= safePage }
                ?: pageAnchors.last()
            return anchor.globalWord.coerceIn(0L, maxOf(0L, totalWords - 1L))
        }

        return ((safePage - 1L) * WORDS_PER_ESTIMATED_PAGE)
            .coerceIn(0L, maxOf(0L, totalWords - 1L))
    }

    fun chapterForGlobalWord(globalWord: Long): BookChapter {
        if (chapters.isEmpty()) error("Book has no chapters")
        val target = globalWord.coerceIn(0L, maxOf(0L, totalWords - 1L))

        var low = 0
        var high = chapters.lastIndex
        while (low <= high) {
            val mid = (low + high) ushr 1
            val chapter = chapters[mid]
            val end = chapter.startWord + chapter.wordCount

            when {
                target < chapter.startWord -> high = mid - 1
                target >= end && mid < chapters.lastIndex -> low = mid + 1
                else -> return chapter
            }
        }

        return chapters[low.coerceIn(0, chapters.lastIndex)]
    }

    fun locateGlobalWord(globalWord: Long): BookLocation {
        val chapter = chapterForGlobalWord(globalWord)
        val local = (globalWord - chapter.startWord)
            .coerceIn(0L, maxOf(0L, chapter.wordCount - 1L))
        return BookLocation(
            chapterIndex = chapter.index,
            wordOffset = local,
            globalWord = chapter.startWord + local,
        )
    }
}

data class BookLocation(
    val chapterIndex: Int,
    val wordOffset: Long,
    val globalWord: Long,
)

data class ReaderLine(
    val index: Int,
    val text: String,
    val startWord: Long,
    val wordCount: Long,
    val paragraphBreakAfter: Boolean = false,
)

fun lineIndexForWord(
    lines: List<ReaderLine>,
    localWord: Long,
): Int {
    if (lines.isEmpty()) return -1
    val target = localWord.coerceAtLeast(0L)
    var low = 0
    var high = lines.lastIndex
    var best = 0
    while (low <= high) {
        val mid = (low + high) ushr 1
        if (lines[mid].startWord <= target) {
            best = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }
    return best
}

data class BookProgress(
    val chapterIndex: Int,
    val segmentStartWord: Long,
    val positionMs: Long,
    val globalWord: Long,
    val updatedAt: Long,
)

data class LibraryBookItem(
    val book: BookRecord,
    val progress: BookProgress?,
) {
    val progressFraction: Float
        get() {
            if (book.totalWords <= 0L) return 0f
            return ((progress?.globalWord ?: 0L).toDouble() / book.totalWords.toDouble())
                .toFloat()
                .coerceIn(0f, 1f)
        }
}

enum class NarrationRole {
    NARRATOR,
    DIALOGUE,
}

enum class DeliveryCue {
    NEUTRAL,
    QUESTION,
    EXCLAMATION,
    HESITATION,
    INTERRUPTION,
}

enum class NarrationBoundary {
    CONTINUE,
    SENTENCE,
    PARAGRAPH,
    SCENE,
    CHAPTER,
}

enum class NarrationMood {
    NEUTRAL,
    REFLECTIVE,
    TENSE,
    URGENT,
    TENDER,
    SOMBER,
    ANGRY,
    FEARFUL,
    LIGHT,
    HESITANT,
}

/**
 * A deliberately restrained performance hint. It is not an acting command.
 * Low-confidence evidence is mixed back toward neutral so Bolo would rather
 * underplay a line than confidently perform the wrong emotion.
 */
data class NarrationPerformance(
    val mood: NarrationMood = NarrationMood.NEUTRAL,
    val confidence: Float = 0f,
    val energy: Float = 0.5f,
    val tension: Float = 0.25f,
    val warmth: Float = 0.5f,
    val synthesisSpeed: Float = 1.0f,
)

data class NarrationUnit(
    /** Source text used for exact word/location accounting. */
    val text: String,
    /** Text actually sent to the TTS front-end after conservative normalization. */
    val spokenText: String = text,
    val startWord: Long,
    val wordCount: Long,
    val role: NarrationRole = NarrationRole.NARRATOR,
    val speakerKey: String? = null,
    val deliveryCue: DeliveryCue = DeliveryCue.NEUTRAL,
    val boundaryAfter: NarrationBoundary = NarrationBoundary.SENTENCE,
    val performance: NarrationPerformance = NarrationPerformance(),
    /** Compatibility/diagnostic only; Natural Narrator never blindly appends it. */
    val pauseAfterMs: Int = 0,
)

data class NarrationBatch(
    val text: String,
    val startWord: Long,
    val wordCount: Long,
    val role: NarrationRole,
    val speakerKey: String?,
    val deliveryCue: DeliveryCue,
    val boundaryAfter: NarrationBoundary,
    val unitCount: Int,
    /** Exact Kokoro phoneme-token count when the engine is available. */
    val modelTokenCount: Int? = null,
    /** Source-word offsets at spoken sentence boundaries inside this batch. */
    val unitWordEnds: List<Long> = emptyList(),
    /** Context-derived performance averaged conservatively across this chunk. */
    val performance: NarrationPerformance = NarrationPerformance(),
    /** Number of dialogue units represented, including mixed narration/dialogue chunks. */
    val dialogueUnitCount: Int = 0,
)

/**
 * Fast allocation-light word counter used by import and navigation indexing.
 * A word is any contiguous run of non-whitespace characters, matching Bolo's
 * existing location semantics without allocating a Regex MatchResult per word.
 */
fun countWords(text: String): Long {
    var count = 0L
    var inWord = false
    for (ch in text) {
        val nonWhitespace = !ch.isWhitespace()
        if (nonWhitespace && !inWord) count += 1L
        inWord = nonWhitespace
    }
    return count
}

fun buildWordCheckpoints(
    text: String,
    intervalWords: Long = 500L,
): List<WordCheckpoint> {
    if (text.isBlank()) return emptyList()

    val checkpoints = mutableListOf(
        WordCheckpoint(
            wordOffset = 0L,
            charOffset = firstWordChar(text).coerceAtLeast(0),
        )
    )

    var wordIndex = 0L
    var inWord = false
    text.forEachIndexed { index, ch ->
        val nonWhitespace = !ch.isWhitespace()
        if (nonWhitespace && !inWord) {
            if (
                wordIndex > 0L &&
                wordIndex % intervalWords == 0L
            ) {
                checkpoints += WordCheckpoint(
                    wordOffset = wordIndex,
                    charOffset = index,
                )
            }
            wordIndex += 1L
        }
        inWord = nonWhitespace
    }

    return checkpoints
}

fun dropWords(
    text: String,
    wordsToDrop: Long,
    checkpoints: List<WordCheckpoint> = emptyList(),
): String {
    if (wordsToDrop <= 0L) return text.trim()

    val checkpoint = checkpoints
        .asSequence()
        .filter { it.wordOffset <= wordsToDrop }
        .maxByOrNull { it.wordOffset }

    val initialChar = checkpoint?.charOffset
        ?.coerceIn(0, text.length)
        ?: 0
    var seen = checkpoint?.wordOffset ?: 0L
    var inWord = false

    for (index in initialChar until text.length) {
        val nonWhitespace = !text[index].isWhitespace()
        if (nonWhitespace && !inWord) {
            if (seen >= wordsToDrop) {
                return text.substring(index).trimStart()
            }
            seen += 1L
        }
        inWord = nonWhitespace
    }

    return ""
}

private fun firstWordChar(text: String): Int {
    for (i in text.indices) {
        if (!text[i].isWhitespace()) return i
    }
    return 0
}
