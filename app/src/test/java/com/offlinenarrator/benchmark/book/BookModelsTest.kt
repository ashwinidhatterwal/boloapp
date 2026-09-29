package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BookModelsTest {
    private fun hugeBook(): BookRecord {
        val chapters = mutableListOf<BookChapter>()
        var start = 0L

        repeat(100) { index ->
            val words = 5_000L
            chapters += BookChapter(
                index = index,
                title = "Chapter ${index + 1}",
                fileName = "%05d.txt".format(index),
                wordCount = words,
                startWord = start,
            )
            start += words
        }

        return BookRecord(
            id = "test",
            title = "Huge Book",
            author = "Test",
            sourceName = "huge.epub",
            importedAt = 1L,
            totalWords = start,
            chapters = chapters,
        )
    }

    @Test
    fun pageJumpMapsDirectlyToDistantChapter() {
        val book = hugeBook()

        // 500,000 words at 250 words/page is about 2,000 pages.
        assertEquals(2000, book.estimatedPages)

        val global = book.globalWordForPage(1500)
        val location = book.locateGlobalWord(global)

        assertTrue(location.chapterIndex > 70)
        assertEquals(global, location.globalWord)
    }

    @Test
    fun chapterLookupWorksAtBookEnd() {
        val book = hugeBook()
        val location = book.locateGlobalWord(book.totalWords - 1L)

        assertEquals(99, location.chapterIndex)
        assertEquals(4_999L, location.wordOffset)
    }

    @Test
    fun narrationOffsetsAdvanceByWords() {
        val text = "One two three. Four five six. Seven eight nine."
        val units = BookNarrationSegmenter.split(text)

        assertTrue(units.isNotEmpty())
        assertEquals(0L, units.first().startWord)
        assertEquals(9L, units.sumOf { it.wordCount })
    }

    @Test
    fun checkpointedDropStartsNearDistantWord() {
        val text = (1..5_000).joinToString(" ") { "word$it" }
        val checkpoints = buildWordCheckpoints(text, intervalWords = 100L)
        val tail = dropWords(text, 4_500L, checkpoints)

        assertTrue(tail.startsWith("word4501"))
        assertTrue(checkpoints.size > 40)
    }

}
