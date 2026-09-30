package com.offlinenarrator.benchmark.book

import org.junit.Assert.*
import org.junit.Test

class PreparedNarrationRegressionTest {
    @Test fun apostropheInsideSingleQuotedDialogueIsNotClosingQuote() {
        val units = NarrationDirector.plan("‘Don’t go,’ Maya said.")
        val dialogue = units.first { it.role == NarrationRole.DIALOGUE }
        assertTrue(dialogue.spokenText.contains("Don’t go"))
        assertEquals("Maya", dialogue.speakerKey)
    }
    @Test fun possessiveApostropheDoesNotCloseDialogue() {
        val units = NarrationDirector.plan("‘My parents’ house,’ Maya said.")
        assertTrue(units.first { it.role == NarrationRole.DIALOGUE }.spokenText.contains("parents’ house"))
    }
    @Test fun quoteWhitespaceAndAttachedAttributionKeepOriginalLocations() {
        for (text in listOf("\"Hello,\"said Maya.", "“ hello ”", "She said,“No.” Then left.")) {
            val plan = AudiobookNarrationCompiler.planChapter(text)
            assertEquals(countWords(text), plan.diagnostics.sourceWords)
            assertEquals(countWords(text), plan.batches.sumOf { it.wordCount })
        }
    }
    @Test fun wordFragmentationNeverInventsSourceWords() {
        val text = "x".repeat(1300)
        val plan = AudiobookNarrationCompiler.planChapter(text, tokenCounter = { it.length })
        assertEquals(1L, plan.batches.sumOf { it.wordCount })
        assertTrue(plan.batches.all { (it.modelTokenCount ?: 501) <= 225 })
        assertEquals(text, plan.batches.joinToString("") { it.text.replace(" ", "") })
    }
    @Test fun mentionedAngerIsNotAngryDelivery() {
        val unit = NarrationDirector.plan("\"He shouted angrily yesterday,\" Maya said calmly.")
            .first { it.role == NarrationRole.DIALOGUE }
        assertNotEquals(NarrationMood.ANGRY, unit.performance.mood)
    }
    @Test fun negatedAttributionDoesNotForceAnger() {
        val unit = NarrationDirector.plan("\"Fine,\" Maya said, not angrily.")
            .first { it.role == NarrationRole.DIALOGUE }
        assertNotEquals(NarrationMood.ANGRY, unit.performance.mood)
    }
    @Test fun shortDialogueParagraphsCanShareModelContext() {
        val units = NarrationDirector.plan("\"Yes.\"\n\"No.\"\n\"Maybe.\"")
        val batches = NarrationBatcher.batch(units) { it.length }
        assertTrue(batches.any { it.unitCount > 1 })
        assertEquals(units.sumOf { it.wordCount }, batches.sumOf { it.wordCount })
    }
    @Test fun sceneBreakNeverSharesBatch() {
        val units = NarrationDirector.plan("\"Yes.\"\n\n* * *\n\n\"No.\"")
        val batches = NarrationBatcher.batch(units) { it.length }
        assertFalse(batches.any { it.text.contains("Yes") && it.text.contains("No") })
    }
    @Test fun anchorBasedSeekingIsBoundedAndMonotonic() {
        val words = longArrayOf(3, 6); val times = longArrayOf(1200, 2400)
        assertEquals(1600L, PreparedTimeline.timeForWord(4, 9, 3600, words, times))
        val seeks = (0L..9L).map { PreparedTimeline.timeForWord(it, 9, 3600, words, times) }
        assertTrue(seeks.zipWithNext().all { (a,b) -> a <= b })
        assertEquals(3600L, seeks.last())
    }
    @Test fun invalidAnchorsUseBoundedFallback() {
        assertEquals(1500L, PreparedTimeline.timeForWord(3, 6, 3000, longArrayOf(1), longArrayOf()))
        assertEquals(0L, PreparedTimeline.timeForWord(3, 0, 3000, longArrayOf(), longArrayOf()))
    }
}
