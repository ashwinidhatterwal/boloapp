package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationTextNormalizerTest {
    @Test
    fun ellipsisAndDashAreNormalized() {
        assertEquals(
            "He waited… then turned — slowly.",
            NarrationTextNormalizer.normalize("He waited... then turned – slowly."),
        )
    }

    @Test
    fun romanChapterHeadingGetsSpeakableNumber() {
        assertEquals("Chapter 14.", NarrationTextNormalizer.normalize("CHAPTER XIV"))
    }

    @Test
    fun sceneMarkersAreRecognized() {
        assertTrue(NarrationTextNormalizer.isSceneMarker("* * *"))
        assertTrue(NarrationTextNormalizer.isSceneMarker("— — —"))
    }
}
