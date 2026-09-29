package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationPacingTest {
    @Test
    fun strongerStructureGetsLongerBoundary() {
        val sentence = NarrationPacing.timing(NarrationBoundary.SENTENCE, DeliveryCue.NEUTRAL)
        val paragraph = NarrationPacing.timing(NarrationBoundary.PARAGRAPH, DeliveryCue.NEUTRAL)
        val scene = NarrationPacing.timing(NarrationBoundary.SCENE, DeliveryCue.NEUTRAL)
        assertTrue(sentence.targetMs < paragraph.targetMs)
        assertTrue(paragraph.targetMs < scene.targetMs)
    }

    @Test
    fun interruptionIsTighterThanNormalSentence() {
        val normal = NarrationPacing.timing(NarrationBoundary.SENTENCE, DeliveryCue.NEUTRAL)
        val interrupted = NarrationPacing.timing(NarrationBoundary.SENTENCE, DeliveryCue.INTERRUPTION)
        assertTrue(interrupted.targetMs < normal.targetMs)
    }
}
