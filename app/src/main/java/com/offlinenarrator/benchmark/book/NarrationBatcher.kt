package com.offlinenarrator.benchmark.book

/**
 * Quality-first Kokoro chunking for prepare-ahead audiobook compilation.
 *
 * The whole chapter is analysed before this stage, but Kokoro is deliberately
 * fed medium-size performance chunks. Kokoro's own voice guidance says most
 * voices are strongest around 100-200 tokens, can be weak on very short
 * utterances, and may rush on very long ones. We therefore bundle short lines
 * (including dialogue + attribution) and split unusually long units near real
 * linguistic boundaries.
 */
object NarrationBatcher {
    private const val MIN_GOOD_TOKENS = 85
    private const val TARGET_MODEL_TOKENS = 165
    private const val MAX_MODEL_TOKENS = 225
    private const val FRAGMENT_TARGET_TOKENS = 180
    private const val MAX_UNITS = 10
    private const val FALLBACK_MAX_CHARS = 390

    fun batch(
        units: List<NarrationUnit>,
        tokenCounter: ((String) -> Int)? = null,
    ): List<NarrationBatch> {
        if (units.isEmpty()) return emptyList()

        val preparedUnits = if (tokenCounter != null) {
            units.flatMap { fragmentIfNeeded(it, tokenCounter) }
        } else {
            units
        }

        val out = mutableListOf<NarrationBatch>()
        val current = mutableListOf<NarrationUnit>()
        val pendingSilent = mutableListOf<NarrationUnit>()

        fun spokenText(values: List<NarrationUnit>): String = values
            .asSequence()
            .map { it.spokenText.trim() }
            .filter { it.isNotBlank() }
            .joinToString(" ")
            .trim()

        fun tokenCount(text: String): Int? =
            tokenCounter?.invoke(text)?.coerceAtLeast(0)

        fun currentTokens(): Int? = tokenCount(spokenText(current))

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
                val spokenUnits = current.filter { it.spokenText.isNotBlank() }
                val roles = spokenUnits.map { it.role }.toSet()
                val speakers = spokenUnits.mapNotNull { it.speakerKey }.toSet()
                var cumulativeSourceWords = 0L
                val wordEnds = mutableListOf<Long>()

                current.forEachIndexed { index, unit ->
                    cumulativeSourceWords += unit.wordCount
                    val hasLaterSpoken = current.drop(index + 1).any { it.spokenText.isNotBlank() }
                    if (unit.spokenText.isNotBlank() && hasLaterSpoken) {
                        wordEnds += cumulativeSourceWords
                    }
                }

                out += NarrationBatch(
                    text = spoken,
                    startWord = current.first().startWord,
                    wordCount = current.sumOf { it.wordCount },
                    role = if (roles.size == 1) roles.first() else NarrationRole.NARRATOR,
                    speakerKey = speakers.singleOrNull(),
                    deliveryCue = spokenUnits.last().deliveryCue,
                    boundaryAfter = current.last().boundaryAfter,
                    unitCount = spokenUnits.size,
                    modelTokenCount = tokenCount(spoken),
                    unitWordEnds = wordEnds,
                    performance = mergePerformance(spokenUnits),
                    dialogueUnitCount = spokenUnits.count { it.role == NarrationRole.DIALOGUE },
                )
            }
            current.clear()
        }

        for (unit in preparedUnits) {
            if (unit.spokenText.isBlank()) {
                if (current.isNotEmpty()) {
                    current += unit
                    flush()
                } else {
                    // Preserve source-word accounting for silent scene markers
                    // by attaching them to the next spoken batch.
                    pendingSilent += unit
                }
                continue
            }

            if (current.isEmpty()) {
                if (pendingSilent.isNotEmpty()) {
                    current += pendingSilent
                    pendingSilent.clear()
                }
                current += unit
            } else {
                val previous = current.last()
                val structuralBreak = previous.boundaryAfter in setOf(
                    NarrationBoundary.PARAGRAPH,
                    NarrationBoundary.SCENE,
                    NarrationBoundary.CHAPTER,
                )

                val existingCount = currentTokens() ?: 0
                val candidateValues = current + unit
                val candidate = spokenText(candidateValues)
                val spokenCount = candidateValues.count { it.spokenText.isNotBlank() }
                val candidateCount = tokenCount(candidate)

                val healthyEnoughToClose = existingCount >= MIN_GOOD_TOKENS
                val wouldOvershootTarget =
                    candidateCount != null &&
                        candidateCount > TARGET_MODEL_TOKENS &&
                        existingCount >= TARGET_MODEL_TOKENS

                if (
                    !structuralBreak &&
                    !wouldOvershootTarget &&
                    fits(candidate, spokenCount)
                ) {
                    current += unit
                } else if (
                    !structuralBreak &&
                    !healthyEnoughToClose &&
                    fits(candidate, spokenCount)
                ) {
                    // Avoid sending tiny utterances to Kokoro just to hit a
                    // numerical target. Short quotes especially benefit from
                    // nearby attribution/context in the same model call.
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
            } else if (tokenCounter != null && current.isNotEmpty()) {
                val count = currentTokens() ?: 0
                if (count >= TARGET_MODEL_TOKENS) flush()
            }
        }

        flush()
        if (pendingSilent.isNotEmpty() && out.isNotEmpty()) {
            val extraWords = pendingSilent.sumOf { it.wordCount }
            val last = out.last()
            out[out.lastIndex] = last.copy(
                wordCount = last.wordCount + extraWords,
                boundaryAfter = pendingSilent.last().boundaryAfter,
            )
        }
        return out
    }

    private fun fragmentIfNeeded(
        unit: NarrationUnit,
        tokenCounter: (String) -> Int,
    ): List<NarrationUnit> {
        val spoken = unit.spokenText.trim()
        if (spoken.isBlank() || tokenCounter(spoken) <= MAX_MODEL_TOKENS) {
            return listOf(unit)
        }

        val out = mutableListOf<NarrationUnit>()
        var remaining = spoken
        var nextStartWord = unit.startWord
        var remainingWords = unit.wordCount.coerceAtLeast(1L)

        while (remaining.isNotBlank() && tokenCounter(remaining) > MAX_MODEL_TOKENS) {
            val cut = bestCut(remaining, tokenCounter)
            if (cut <= 0 || cut >= remaining.length) break

            val piece = remaining.substring(0, cut).trim()
            if (piece.isBlank()) break

            var pieceWords = countWords(piece).coerceAtLeast(1L)
            if (remainingWords > 1L) {
                pieceWords = pieceWords.coerceAtMost(remainingWords - 1L)
            }

            out += unit.copy(
                text = piece,
                spokenText = piece,
                startWord = nextStartWord,
                wordCount = pieceWords,
                deliveryCue = deliveryCueForFragment(piece),
                boundaryAfter = boundaryForFragment(piece),
            )

            nextStartWord += pieceWords
            remainingWords = (remainingWords - pieceWords).coerceAtLeast(1L)
            remaining = remaining.substring(cut).trimStart()
        }

        if (remaining.isNotBlank()) {
            out += unit.copy(
                text = remaining,
                spokenText = remaining,
                startWord = nextStartWord,
                wordCount = remainingWords,
            )
        }

        return out.ifEmpty { listOf(unit) }
    }

    private fun bestCut(
        text: String,
        tokenCounter: (String) -> Int,
    ): Int {
        val natural = mutableListOf<Int>()
        for (i in 1 until text.lastIndex) {
            val ch = text[i]
            if (ch in charArrayOf('.', '?', '!', ';', ':', ',', '—', '…')) {
                natural += i + 1
            }
        }

        var best = -1
        for (cut in natural) {
            val count = tokenCounter(text.substring(0, cut).trim())
            if (count in MIN_GOOD_TOKENS..FRAGMENT_TARGET_TOKENS) {
                best = cut
            } else if (count > FRAGMENT_TARGET_TOKENS && best > 0) {
                break
            }
        }
        if (best > 0) return best

        // Fall back to the largest whitespace-delimited prefix around the
        // preferred range. This only runs for abnormally long source sentences.
        var low = 1
        var high = text.length - 1
        var safe = 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val count = tokenCounter(text.substring(0, mid).trim())
            if (count <= FRAGMENT_TARGET_TOKENS) {
                safe = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }

        val prefix = text.substring(0, safe)
        val whitespace = prefix.indexOfLast { it.isWhitespace() }
        return if (whitespace >= safe / 2) whitespace + 1 else safe
    }

    private fun boundaryForFragment(text: String): NarrationBoundary {
        val trimmed = text.trimEnd()
        return when (trimmed.lastOrNull()) {
            '.', '?', '!', '…' -> NarrationBoundary.SENTENCE
            else -> NarrationBoundary.CONTINUE
        }
    }

    private fun deliveryCueForFragment(text: String): DeliveryCue {
        val trimmed = text.trimEnd()
        return when (trimmed.lastOrNull()) {
            '?' -> DeliveryCue.QUESTION
            '!' -> DeliveryCue.EXCLAMATION
            '…' -> DeliveryCue.HESITATION
            '—' -> DeliveryCue.INTERRUPTION
            else -> DeliveryCue.NEUTRAL
        }
    }

    private fun mergePerformance(units: List<NarrationUnit>): NarrationPerformance {
        if (units.isEmpty()) return NarrationPerformance()
        val totalWeight = units.sumOf { it.wordCount.coerceAtLeast(1L) }.toFloat()
            .coerceAtLeast(1f)

        fun weighted(value: (NarrationPerformance) -> Float): Float =
            units.sumOf { unit ->
                (value(unit.performance) * unit.wordCount.coerceAtLeast(1L).toFloat()).toDouble()
            }.toFloat() / totalWeight

        val strongest = units.maxByOrNull { it.performance.confidence }
            ?.performance
            ?: NarrationPerformance()
        val mood = if (strongest.confidence >= 0.58f) {
            strongest.mood
        } else {
            NarrationMood.NEUTRAL
        }

        return NarrationPerformance(
            mood = mood,
            confidence = units.maxOf { it.performance.confidence } *
                if (units.map { it.performance.mood }.distinct().size > 2) 0.82f else 1f,
            energy = weighted { it.energy }.coerceIn(0f, 1f),
            tension = weighted { it.tension }.coerceIn(0f, 1f),
            warmth = weighted { it.warmth }.coerceIn(0f, 1f),
            synthesisSpeed = weighted { it.synthesisSpeed }.coerceIn(0.972f, 1.025f),
        )
    }
}
