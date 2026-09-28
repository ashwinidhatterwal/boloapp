package com.offlinenarrator.benchmark.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkMetricsTest {
    @Test
    fun summarizesRealTimeFactor() {
        val summary = BenchmarkMetrics.summarize(
            listOf(
                500L to 1_000L,
                400L to 1_000L,
                600L to 1_000L,
            )
        )

        assertEquals(3, summary.runs)
        assertEquals(500L, summary.meanGenerationMs)
        assertEquals(1_000L, summary.meanAudioMs)
        assertEquals(0.5, summary.meanRtf, 0.0001)
        assertTrue(summary.bestRtf <= summary.worstRtf)
    }
}
