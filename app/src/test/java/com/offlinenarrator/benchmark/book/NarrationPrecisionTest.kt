package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationPrecisionTest {
    @Test
    fun narrationDoesNotBatchNeighboringSentences() {
        val text = "Dr. Rao waited. It was quiet. Then the door opened."
        val units = NarrationDirector.plan(text)
        assertEquals(3, units.size)
        assertEquals("Dr. Rao waited.", units[0].text)
        assertEquals("It was quiet.", units[1].text)
        assertEquals("Then the door opened.", units[2].text)
    }

    @Test
    fun punctuationCreatesMeaningfulPausePolicy() {
        val text = "Wait.\nReally?\nEnough!\nDone."
        val units = NarrationDirector.plan(text)
        assertEquals(4, units.size)
        assertEquals(DeliveryCue.QUESTION, units[1].deliveryCue)
        assertEquals(DeliveryCue.EXCLAMATION, units[2].deliveryCue)
        assertEquals(NarrationBoundary.CHAPTER, units[3].boundaryAfter)
    }

    @Test
    fun locationsStillCoverAllWordsAfterDialogueSplitting() {
        val text = "\"Stop!\" Maya shouted. Then she turned away."
        val units = NarrationDirector.plan(text)
        assertEquals(countWords(text), units.sumOf { it.wordCount })
        assertTrue(units.zipWithNext().all { (a, b) ->
            b.startWord == a.startWord + a.wordCount
        })
    }
}
