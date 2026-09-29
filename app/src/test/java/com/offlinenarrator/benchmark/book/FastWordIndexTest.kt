package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FastWordIndexTest {
    @Test
    fun largeSyntheticTextGetsSparseCheckpoints() {
        val text = buildString {
            repeat(250_000) { index ->
                if (index > 0) append(' ')
                append("word")
                append(index)
            }
        }

        assertEquals(250_000L, countWords(text))
        val checkpoints = buildWordCheckpoints(text, intervalWords = 500L)
        assertTrue(checkpoints.size in 499..501)

        val tail = dropWords(text, 200_000L, checkpoints)
        assertTrue(tail.startsWith("word200000"))
    }
}
