package com.offlinenarrator.benchmark.book

import kotlin.math.roundToInt

data class NarrationPlanDiagnostics(
    val sourceWords: Long,
    val plannedWords: Long,
    val batchCount: Int,
    val minTokens: Int?,
    val meanTokens: Int?,
    val maxTokens: Int?,
    val shortBatches: Int,
    val qualityRangeBatches: Int,
    val warnings: List<String>,
)

data class ChapterNarrationPlan(
    val units: List<NarrationUnit>,
    val batches: List<NarrationBatch>,
    val diagnostics: NarrationPlanDiagnostics,
)

/**
 * Pure planning phase of Bolo's prepare-ahead audiobook compiler.
 *
 * The important architectural rule is that chapter understanding happens
 * before neural synthesis. This makes the expensive Kokoro calls deterministic,
 * cacheable and inspectable rather than making performance decisions while the
 * listener is already waiting for audio.
 */
object AudiobookNarrationCompiler {
    private const val KOKORO_HARD_CONTEXT = 500
    private const val QUALITY_LOW = 75
    private const val QUALITY_HIGH = 225
    private const val VERY_SHORT = 20

    fun planChapter(
        chapterText: String,
        startWord: Long = 0L,
        checkpoints: List<WordCheckpoint> = emptyList(),
        sourceFormat: String = "EPUB",
        tokenCounter: ((String) -> Int)? = null,
    ): ChapterNarrationPlan {
        val units = NarrationDirector.plan(
            chapterText = chapterText,
            startWord = startWord,
            checkpoints = checkpoints,
            sourceFormat = sourceFormat,
        )
        val batches = NarrationBatcher.batch(
            units = units,
            tokenCounter = tokenCounter,
        )

        val sourceWords = units.sumOf { it.wordCount }
        val plannedWords = batches.sumOf { it.wordCount }
        val tokenCounts = batches.mapNotNull { batch ->
            batch.modelTokenCount ?: tokenCounter?.invoke(batch.text)
        }

        val warnings = mutableListOf<String>()
        if (units.isNotEmpty() && batches.isEmpty()) {
            warnings += "readable source produced no synthesis batches"
        }
        if (sourceWords != plannedWords) {
            warnings += "source-word accounting mismatch: $sourceWords planned as $plannedWords"
        }
        if (tokenCounts.any { it > KOKORO_HARD_CONTEXT }) {
            warnings += "a batch exceeds Kokoro's hard model context"
        }
        val overQuality = tokenCounts.count { it > QUALITY_HIGH }
        if (overQuality > 0) {
            warnings += "$overQuality batch(es) exceed the preferred audiobook quality range"
        }

        val mean = if (tokenCounts.isNotEmpty()) tokenCounts.average().roundToInt() else null
        val diagnostics = NarrationPlanDiagnostics(
            sourceWords = sourceWords,
            plannedWords = plannedWords,
            batchCount = batches.size,
            minTokens = tokenCounts.minOrNull(),
            meanTokens = mean,
            maxTokens = tokenCounts.maxOrNull(),
            shortBatches = tokenCounts.count { it < VERY_SHORT },
            qualityRangeBatches = tokenCounts.count { it in QUALITY_LOW..QUALITY_HIGH },
            warnings = warnings,
        )

        // Hard failures indicate a programming/planning bug. Soft quality-range
        // misses remain diagnostics because chapter endings can legitimately be
        // short and should not be padded with unrelated text.
        require(tokenCounts.none { it > KOKORO_HARD_CONTEXT }) {
            "Narration plan exceeded Kokoro context"
        }
        require(sourceWords == plannedWords || units.isEmpty()) {
            "Narration plan lost source-word locations"
        }

        return ChapterNarrationPlan(
            units = units,
            batches = batches,
            diagnostics = diagnostics,
        )
    }
}
