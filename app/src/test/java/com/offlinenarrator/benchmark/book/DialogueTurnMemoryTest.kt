package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DialogueTurnMemoryTest {
    @Test
    fun alternatesOnlyAfterTwoExplicitNearbySpeakers() {
        val units = NarrationDirector.plan(
            "\"Where?\" Maya asked. \"Outside.\" Arjun replied. \"For three hours?\""
        )
        val dialogue = units.filter { it.role == NarrationRole.DIALOGUE }
        assertEquals(listOf("Maya", "Arjun", "Maya"), dialogue.map { it.speakerKey })
    }

    @Test
    fun doesNotInventSpeakerWithOnlyOneKnownCharacter() {
        val units = NarrationDirector.plan(
            "\"Where?\" Maya asked. A long silence followed. \"Outside.\""
        )
        val dialogue = units.filter { it.role == NarrationRole.DIALOGUE }
        assertEquals("Maya", dialogue.first().speakerKey)
        assertNull(dialogue.last().speakerKey)
    }
}
