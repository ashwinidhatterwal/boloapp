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

data class NarrationUnit(
    val text: String,
    val startWord: Long,
    val wordCount: Long,
)

fun countWords(text: String): Long =
    Regex("""\S+""").findAll(text).count().toLong()

fun buildWordCheckpoints(
    text: String,
    intervalWords: Long = 500L,
): List<WordCheckpoint> {
    if (text.isBlank()) return emptyList()

    val checkpoints = mutableListOf(
        WordCheckpoint(
            wordOffset = 0L,
            charOffset = 0,
        )
    )

    var wordIndex = 0L
    for (match in Regex("""\S+""").findAll(text)) {
        if (
            wordIndex > 0L &&
            wordIndex % intervalWords == 0L
        ) {
            checkpoints += WordCheckpoint(
                wordOffset = wordIndex,
                charOffset = match.range.first,
            )
        }
        wordIndex += 1L
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

    val tail = text.substring(initialChar)
    val matcher = Regex("""\S+""")

    for (match in matcher.findAll(tail)) {
        if (seen >= wordsToDrop) {
            return tail.substring(match.range.first).trimStart()
        }
        seen += 1L
    }

    return ""
}
