package com.offlinenarrator.benchmark.benchmark

data class BenchmarkSummary(
    val runs: Int,
    val meanGenerationMs: Long,
    val meanAudioMs: Long,
    val meanRtf: Double,
    val bestRtf: Double,
    val worstRtf: Double,
)

object BenchmarkMetrics {
    fun summarize(samples: List<Pair<Long, Long>>): BenchmarkSummary {
        require(samples.isNotEmpty())
        val rtfs = samples.map { (generation, audio) ->
            if (audio <= 0) Double.NaN else generation.toDouble() / audio.toDouble()
        }.filter { it.isFinite() }

        return BenchmarkSummary(
            runs = samples.size,
            meanGenerationMs = samples.map { it.first }.average().toLong(),
            meanAudioMs = samples.map { it.second }.average().toLong(),
            meanRtf = rtfs.average(),
            bestRtf = rtfs.minOrNull() ?: Double.NaN,
            worstRtf = rtfs.maxOrNull() ?: Double.NaN,
        )
    }
}
