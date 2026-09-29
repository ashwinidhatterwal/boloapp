package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookModelsTest {
    @Test
    fun reflowableBooksUseEstimatedPages() {
        val book = BookRecord(
            id = "x",
            title = "Test",
            author = "Author",
            sourceName = "test.epub",
            format = "EPUB",
            importedAt = 0L,
            totalWords = 1_000L,
            chapters = listOf(
                BookChapter(0, "One", "00000.txt", 1_000L, 0L),
            ),
        )

        assertFalse(book.hasFixedPages)
        assertEquals(4, book.estimatedPages)
        assertEquals(3, book.pageForGlobalWord(500L))
        assertEquals(750L, book.globalWordForPage(4))
    }

    @Test
    fun pdfBooksUseRealPageAnchors() {
        val book = BookRecord(
            id = "pdf",
            title = "PDF",
            author = "Author",
            sourceName = "test.pdf",
            format = "PDF",
            importedAt = 0L,
            totalWords = 600L,
            chapters = listOf(
                BookChapter(0, "Pages 1–3", "00000.txt", 600L, 0L),
            ),
            pageAnchors = listOf(
                PageAnchor(1, 0L),
                PageAnchor(2, 100L),
                PageAnchor(3, 450L),
            ),
        )

        assertTrue(book.hasFixedPages)
        assertEquals(3, book.estimatedPages)
        assertEquals(2, book.pageForGlobalWord(300L))
        assertEquals(450L, book.globalWordForPage(3))
    }

    @Test
    fun distantLocationUsesChapterIndex() {
        val chapters = (0 until 100).map { index ->
            BookChapter(
                index = index,
                title = "Section ${index + 1}",
                fileName = "%05d.txt".format(index),
                wordCount = 5_000L,
                startWord = index * 5_000L,
            )
        }
        val book = BookRecord(
            id = "big",
            title = "Big",
            author = "Author",
            sourceName = "big.txt",
            format = "TXT",
            importedAt = 0L,
            totalWords = 500_000L,
            chapters = chapters,
        )

        val location = book.locateGlobalWord(book.globalWordForPage(1_500))
        assertEquals(74, location.chapterIndex)
        assertEquals(374_750L, location.globalWord)
    }
}
