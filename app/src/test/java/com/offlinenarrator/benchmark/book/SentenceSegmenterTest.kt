package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceSegmenterTest {
    @Test
    fun abbreviationsAndDecimalsDoNotBreakSentences() {
        val text = "Dr. Rao waited 3.14 seconds. Then he spoke."
        val slices = SentenceSegmenter.split(text)
        assertEquals(2, slices.size)
        assertEquals("Dr. Rao waited 3.14 seconds.", slices[0].text)
        assertEquals("Then he spoke.", slices[1].text)
    }

    @Test
    fun dialogueAttributionStaysOneSelectableLine() {
        val text = "\"Stop!\" Maya shouted. Then silence followed."
        val lines = SentenceSegmenter.readerLines(text)
        assertEquals(2, lines.size)
        assertEquals("\"Stop!\" Maya shouted.", lines[0].text)
        assertEquals("Then silence followed.", lines[1].text)
    }

    @Test
    fun paragraphWithoutPunctuationStillBecomesBoundary() {
        val slices = SentenceSegmenter.split("A heading line\nNext paragraph begins here.")
        assertEquals(2, slices.size)
        assertTrue(slices[0].paragraphBreakAfter)
    }

    @Test
    fun devanagariDandaEndsSentence() {
        val slices = SentenceSegmenter.split("यह पहला वाक्य है। यह दूसरा वाक्य है।")
        assertEquals(2, slices.size)
    }

    @Test
    fun readerLineOffsetsRemainMonotonic() {
        val text = "First sentence. Second sentence? Third sentence!"
        val lines = SentenceSegmenter.readerLines(text)
        assertEquals(3, lines.size)
        assertEquals(0L, lines[0].startWord)
        assertEquals(lines[0].wordCount, lines[1].startWord)
        assertEquals(lines[0].wordCount + lines[1].wordCount, lines[2].startWord)
        assertEquals(countWords(text), lines.sumOf { it.wordCount })
    }
}
