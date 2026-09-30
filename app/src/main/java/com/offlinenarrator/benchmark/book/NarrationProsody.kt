package com.offlinenarrator.benchmark.book

import java.util.Locale

enum class NarrationCadence {
    NEUTRAL,
    REFLECTIVE,
    ACTION,
    HESITANT,
    DIALOGUE,
}

data class ProsodyPlan(
    val cadence: NarrationCadence,
    val synthesisSpeed: Float,
)

/**
 * Small, deliberately restrained delivery changes. Playback speed selected by
 * the listener remains separate; this only changes how Kokoro phrases a batch.
 */
object NarrationProsody {
    private val reflectiveWords = setOf(
        "thought", "wondered", "remembered", "remember", "felt", "seemed",
        "realized", "realised", "perhaps", "maybe", "memory", "dreamed",
        "dreamt", "silence", "quietly", "slowly",
    )

    private val actionWords = setOf(
        "ran", "rushed", "jumped", "grabbed", "threw", "struck", "fired",
        "shouted", "screamed", "burst", "slammed", "charged", "dashed",
        "fell", "hit", "kicked", "lunged",
    )

    fun plan(batch: NarrationBatch): ProsodyPlan {
        if (batch.deliveryCue == DeliveryCue.HESITATION) {
            return ProsodyPlan(NarrationCadence.HESITANT, 0.975f)
        }

        if (batch.role == NarrationRole.DIALOGUE) {
            return ProsodyPlan(NarrationCadence.DIALOGUE, 1.0f)
        }

        val words = lexicalWords(batch.text)
        if (words.isEmpty()) return ProsodyPlan(NarrationCadence.NEUTRAL, 1.0f)

        val reflectiveHits = words.count { it in reflectiveWords }
        val actionHits = words.count { it in actionWords }
        val punctuationEnergy = batch.text.count { it == '!' || it == '—' }
        val averageSentenceWords = words.size.toFloat() /
            batch.text.count { it == '.' || it == '?' || it == '!' }.coerceAtLeast(1)

        return when {
            reflectiveHits >= 2 || (reflectiveHits >= 1 && batch.text.contains('…')) ->
                ProsodyPlan(NarrationCadence.REFLECTIVE, 0.985f)

            actionHits >= 2 && (averageSentenceWords <= 14f || punctuationEnergy >= 2) ->
                ProsodyPlan(NarrationCadence.ACTION, 1.02f)

            else -> ProsodyPlan(NarrationCadence.NEUTRAL, 1.0f)
        }
    }

    private fun lexicalWords(text: String): List<String> = text
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}']+"))
        .filter { it.isNotBlank() }
}
