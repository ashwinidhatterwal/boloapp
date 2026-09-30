package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationPerformancePlannerTest {
    @Test
    fun explicitWhisperControlsEnergyWithoutInventingEmotion() {
        val units = NarrationDirector.plan(
            "\"Don't wake him,\" Maya whispered softly."
        )
        val dialogue = units.first { it.role == NarrationRole.DIALOGUE }

        assertEquals(NarrationMood.NEUTRAL, dialogue.performance.mood)
        assertTrue(dialogue.performance.confidence >= 0.7f)
        assertTrue(dialogue.performance.energy < 0.5f)
        assertTrue(dialogue.performance.synthesisSpeed >= 0.972f)
    }

    @Test
    fun bareDialogueStaysCloseToNeutral() {
        val units = NarrationDirector.plan("\"Fine.\"")
        val dialogue = units.first { it.role == NarrationRole.DIALOGUE }

        assertEquals(NarrationMood.NEUTRAL, dialogue.performance.mood)
        assertTrue(dialogue.performance.synthesisSpeed in 0.99f..1.01f)
    }

    @Test
    fun performanceNeverSwingsSpeedDramatically() {
        val units = NarrationDirector.plan(
            "He ran, rushed, jumped and slammed the door. \"Move!\" Arjun shouted urgently."
        )
        assertTrue(units.all { it.performance.synthesisSpeed in 0.972f..1.025f })
    }
}
