package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationBatcherTest {
    @Test
    fun nearbyNarratorSentencesShareOneModelCall() {
        val units = NarrationDirector.plan(
            "The room was quiet. Rain touched the window. Somewhere a clock moved."
        )
        val batches = NarrationBatcher.batch(units)

        assertEquals(1, batches.size)
        assertEquals(3, batches[0].unitCount)
        assertTrue(batches[0].text.contains("Rain touched the window."))
    }

    @Test
    fun paragraphBoundaryStopsBatching() {
        val units = NarrationDirector.plan(
            "The room was quiet.\n\nRain touched the window."
        )
        val batches = NarrationBatcher.batch(units)

        assertEquals(2, batches.size)
        assertEquals(NarrationBoundary.PARAGRAPH, batches[0].boundaryAfter)
    }

    @Test
    fun differentSpeakersAreNeverBatchedTogether() {
        val units = NarrationDirector.plan(
            "\"Wait.\" Maya said. \"No.\" Arjun replied."
        )
        val dialogue = NarrationBatcher.batch(units)
            .filter { it.role == NarrationRole.DIALOGUE }

        assertTrue(dialogue.size >= 2)
        assertTrue(dialogue.zipWithNext().all { (a, b) ->
            a.speakerKey != b.speakerKey || a.speakerKey == null
        })
    }
    @Test
    fun exactTokenBudgetStopsBatchBeforeKokoroCeiling() {
        val units = NarrationDirector.plan(
            "One sentence here. Another sentence follows. A third sentence arrives. A fourth continues. A fifth completes the thought."
        )
        val batches = NarrationBatcher.batch(units) { text -> text.length * 5 }

        assertTrue(batches.size >= 2)
        assertTrue(batches.all { (it.modelTokenCount ?: 0) <= 440 || it.unitCount == 1 })
    }

    @Test
    fun batchesRetainInternalSentenceWordAnchors() {
        val units = NarrationDirector.plan("One two three. Four five six. Seven eight nine.")
        val batch = NarrationBatcher.batch(units).single()

        assertEquals(listOf(3L, 6L), batch.unitWordEnds)
        assertEquals(9L, batch.wordCount)
    }

}
