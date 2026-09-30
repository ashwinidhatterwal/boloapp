package com.offlinenarrator.benchmark.book

enum class NarrationCadence {
    NEUTRAL,
    REFLECTIVE,
    ACTION,
    HESITANT,
    DIALOGUE,
    TENDER,
    SOMBER,
    TENSE,
}

data class ProsodyPlan(
    val cadence: NarrationCadence,
    val synthesisSpeed: Float,
)

/**
 * Converts the chapter-level performance plan into the tiny controls Kokoro
 * actually exposes safely. The planner does the semantic work; this layer keeps
 * the acoustic intervention intentionally restrained.
 */
object NarrationProsody {
    fun plan(batch: NarrationBatch): ProsodyPlan {
        val performance = batch.performance
        val cadence = when (performance.mood) {
            NarrationMood.REFLECTIVE -> NarrationCadence.REFLECTIVE
            NarrationMood.TENDER -> NarrationCadence.TENDER
            NarrationMood.SOMBER -> NarrationCadence.SOMBER
            NarrationMood.HESITANT -> NarrationCadence.HESITANT
            NarrationMood.TENSE,
            NarrationMood.ANGRY,
            NarrationMood.FEARFUL -> NarrationCadence.TENSE
            NarrationMood.URGENT,
            NarrationMood.LIGHT -> NarrationCadence.ACTION
            NarrationMood.NEUTRAL -> if (batch.dialogueUnitCount > 0) {
                NarrationCadence.DIALOGUE
            } else {
                NarrationCadence.NEUTRAL
            }
        }

        return ProsodyPlan(
            cadence = cadence,
            synthesisSpeed = performance.synthesisSpeed.coerceIn(0.972f, 1.025f),
        )
    }
}
