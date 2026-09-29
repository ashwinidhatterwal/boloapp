package com.offlinenarrator.benchmark.book

/**
 * Groups compatible sentence units into model calls while keeping exact source
 * word spans. This restores paragraph context and amortizes ONNX fixed cost.
 */
object NarrationBatcher {
    private const val MAX_BATCH_CHARS = 520
    private const val MAX_UNITS = 4

    fun batch(units: List<NarrationUnit>): List<NarrationBatch> {
        if (units.isEmpty()) return emptyList()

        val out = mutableListOf<NarrationBatch>()
        val current = mutableListOf<NarrationUnit>()

        fun flush() {
            if (current.isEmpty()) return

            val spoken = current
                .asSequence()
                .map { it.spokenText.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" ")
                .trim()

            if (spoken.isNotBlank()) {
                out += NarrationBatch(
                    text = spoken,
                    startWord = current.first().startWord,
                    wordCount = current.sumOf { it.wordCount },
                    role = current.first { it.spokenText.isNotBlank() }.role,
                    speakerKey = current.first { it.spokenText.isNotBlank() }.speakerKey,
                    deliveryCue = current.last { it.spokenText.isNotBlank() }.deliveryCue,
                    boundaryAfter = current.last().boundaryAfter,
                    unitCount = current.count { it.spokenText.isNotBlank() },
                )
            }
            current.clear()
        }

        for (unit in units) {
            if (unit.spokenText.isBlank()) {
                if (current.isNotEmpty()) {
                    current += unit
                    flush()
                }
                continue
            }

            if (current.isEmpty()) {
                current += unit
            } else {
                val previousSpoken = current.lastOrNull { it.spokenText.isNotBlank() }
                val compatible = previousSpoken != null &&
                    previousSpoken.boundaryAfter == NarrationBoundary.SENTENCE &&
                    previousSpoken.role == unit.role &&
                    previousSpoken.speakerKey == unit.speakerKey &&
                    current.count { it.spokenText.isNotBlank() } < MAX_UNITS

                val combinedChars = current.sumOf { it.spokenText.length } +
                    unit.spokenText.length + current.size

                if (compatible && combinedChars <= MAX_BATCH_CHARS) {
                    current += unit
                } else {
                    flush()
                    current += unit
                }
            }

            if (
                unit.boundaryAfter == NarrationBoundary.PARAGRAPH ||
                unit.boundaryAfter == NarrationBoundary.SCENE ||
                unit.boundaryAfter == NarrationBoundary.CHAPTER
            ) {
                flush()
            }
        }

        flush()
        return out
    }
}
