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
}
