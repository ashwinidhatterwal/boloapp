package com.offlinenarrator.benchmark.book

/**
 * Paragraph-aware, token-budgeted narration batching.
 *
 * Sentence locations stay independent for the reader UI, but compatible
 * sentences are sent to Kokoro together so it can hear the surrounding thought
 * and so ONNX startup cost is amortized. When [tokenCounter] is supplied the
 * budget is based on the exact punctuation-preserving Kokoro tokenization.
 */
object NarrationBatcher {
    private const val TARGET_MODEL_TOKENS = 380
    private const val MAX_MODEL_TOKENS = 440
    private const val MAX_UNITS = 8
    private const val FALLBACK_MAX_CHARS = 680

    fun batch(
        units: List<NarrationUnit>,
        tokenCounter: ((String) -> Int)? = null,
    ): List<NarrationBatch> {
        if (units.isEmpty()) return emptyList()

        val out = mutableListOf<NarrationBatch>()
        val current = mutableListOf<NarrationUnit>()

        fun spokenText(values: List<NarrationUnit>): String = values
            .asSequence()
            .map { it.spokenText.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        fun tokenCount(text: String): Int? =
            tokenCounter?.invoke(text)?.coerceAtLeast(0)

        fun fits(candidate: String, spokenCount: Int): Boolean {
            if (spokenCount > MAX_UNITS) return false
            val exact = tokenCount(candidate)
            return if (exact != null) exact <= MAX_MODEL_TOKENS
            else candidate.length <= FALLBACK_MAX_CHARS
        }

        fun flush() {
            if (current.isEmpty()) return
            val spoken = spokenText(current)
            if (spoken.isNotBlank()) {
                val firstSpoken = current.first { it.spokenText.isNotBlank() }
                val lastSpoken = current.last { it.spokenText.isNotBlank() }
                var cumulativeSourceWords = 0L
                val wordEnds = mutableListOf<Long>()
                val spokenUnits = current.filter { it.spokenText.isNotBlank() }
                current.forEach { unit ->
                    cumulativeSourceWords += unit.wordCount
                    if (unit.spokenText.isNotBlank() && unit !== spokenUnits.last()) {
                        wordEnds += cumulativeSourceWords
                    }
                }
                out += NarrationBatch(
                    text = spoken,
                    startWord = current.first().startWord,
                    wordCount = current.sumOf { it.wordCount },
                    role = firstSpoken.role,
                    speakerKey = firstSpoken.speakerKey,
                    deliveryCue = lastSpoken.deliveryCue,
                    boundaryAfter = current.last().boundaryAfter,
                    unitCount = spokenUnits.size,
                    modelTokenCount = tokenCount(spoken),
                    unitWordEnds = wordEnds,
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
                val previous = current.lastOrNull { it.spokenText.isNotBlank() }
                val compatible = previous != null &&
                    previous.boundaryAfter in setOf(
                        NarrationBoundary.CONTINUE,
                        NarrationBoundary.SENTENCE,
                    ) &&
                    previous.role == unit.role &&
                    previous.speakerKey == unit.speakerKey

                val candidateValues = current + unit
                val candidate = spokenText(candidateValues)
                val spokenCount = candidateValues.count { it.spokenText.isNotBlank() }

                if (compatible && fits(candidate, spokenCount)) {
                    current += unit
                } else {
                    flush()
                    current += unit
                }
            }

            // Structural boundaries are author intent. Never batch across them.
            if (
                unit.boundaryAfter == NarrationBoundary.PARAGRAPH ||
                unit.boundaryAfter == NarrationBoundary.SCENE ||
                unit.boundaryAfter == NarrationBoundary.CHAPTER
            ) {
                flush()
            } else if (tokenCounter != null && current.isNotEmpty()) {
                // Once a thought has reached a healthy context size, don't keep
                // growing it just because the hard ceiling still has room.
                val text = spokenText(current)
                val count = tokenCount(text) ?: 0
                if (count >= TARGET_MODEL_TOKENS) flush()
            }
        }

        flush()
        return out
    }
}
