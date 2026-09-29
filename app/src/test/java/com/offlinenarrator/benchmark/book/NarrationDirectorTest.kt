package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationDirectorTest {
    @Test
    fun explicitSpeakerAfterQuoteIsDetected() {
        val text = "\"Wait here!\" Arjun shouted. The room went quiet."
        val units = NarrationDirector.plan(text)

        val dialogue = units.first { it.role == NarrationRole.DIALOGUE }
        assertEquals("Arjun", dialogue.speakerKey)
        assertEquals(DeliveryCue.EXCLAMATION, dialogue.deliveryCue)
    }

    @Test
    fun pronounAttributionDoesNotInventCharacter() {
        val text = "\"Come with me,\" he said. They left together."
        val units = NarrationDirector.plan(text)

        val dialogue = units.first { it.role == NarrationRole.DIALOGUE }
        assertNull(dialogue.speakerKey)
    }

    @Test
    fun wordLocationsRemainMonotonic() {
        val text = "Arjun looked up. \"Are you ready?\" Maya asked. He nodded."
        val units = NarrationDirector.plan(text)
        val total = units.sumOf { it.wordCount }

        assertEquals(countWords(text), total)
        assertTrue(units.zipWithNext().all { (a, b) ->
            b.startWord == a.startWord + a.wordCount
        })
    }
}
