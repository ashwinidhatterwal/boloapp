package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudiobookNarrationCompilerTest {
    @Test
    fun quoteSpanDoesNotCreateFakeParagraphPause() {
        val units = NarrationDirector.plan(
            "\"Don't wake him,\" Maya whispered softly. He nodded."
        )
        val dialogue = units.first { it.role == NarrationRole.DIALOGUE }
        assertEquals(NarrationBoundary.CONTINUE, dialogue.boundaryAfter)
    }

    @Test
    fun sceneMarkerWordsRemainAccountedFor() {
        val text = "The room went still.\n\n* * *\n\nThe door opened."
        val plan = AudiobookNarrationCompiler.planChapter(
            chapterText = text,
            tokenCounter = { it.length },
        )
        assertEquals(plan.diagnostics.sourceWords, plan.diagnostics.plannedWords)
        assertTrue(plan.diagnostics.warnings.isEmpty())
    }

    @Test
    fun chunksStayBelowQualityFirstCeiling() {
        val text = buildString {
            repeat(18) { index ->
                append("Sentence $index carries enough ordinary words to create a useful narration context. ")
            }
        }
        val plan = AudiobookNarrationCompiler.planChapter(
            chapterText = text,
            tokenCounter = { value -> value.length },
        )
        assertTrue(plan.batches.isNotEmpty())
        assertTrue(plan.batches.all { (it.modelTokenCount ?: 0) <= 225 })
    }

    @Test
    fun loudDeliveryDoesNotAutomaticallyMeanAnger() {
        val neutral = NarrationDirector.plan("\"Hello!\" Maya shouted.")
            .first { it.role == NarrationRole.DIALOGUE }
        val angry = NarrationDirector.plan("\"Get out!\" Maya shouted angrily.")
            .first { it.role == NarrationRole.DIALOGUE }

        assertEquals(NarrationMood.NEUTRAL, neutral.performance.mood)
        assertEquals(NarrationMood.ANGRY, angry.performance.mood)
        assertTrue(neutral.performance.energy > 0.5f)
    }

    @Test
    fun allAutomaticTempoChangesRemainSubtle() {
        val text = "He ran and rushed across the hall. \"Stop!\" Maya shouted angrily. " +
            "She hesitated… then whispered softly, \"Please.\""
        val plan = AudiobookNarrationCompiler.planChapter(text)
        assertTrue(plan.units.all { it.performance.synthesisSpeed in 0.972f..1.025f })
    }
}
