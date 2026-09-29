package com.offlinenarrator.benchmark.book

object BookNarrationSegmenter {
    private const val TARGET_CHARS = 280
    private const val HARD_CHARS = 420

    fun split(
        chapterText: String,
        startWord: Long = 0L,
        checkpoints: List<WordCheckpoint> = emptyList(),
    ): List<NarrationUnit> {
        val remaining = dropWords(
            text = chapterText,
            wordsToDrop = startWord,
            checkpoints = checkpoints,
        )
            .replace("\r\n", "\n")
            .replace(Regex("[\\t ]+"), " ")
            .trim()

        if (remaining.isBlank()) return emptyList()

        val rawChunks = mutableListOf<String>()
        val paragraphs = remaining
            .split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        for (paragraph in paragraphs) {
            val sentences = Regex("(?<=[.!?])\\s+|\\n+")
                .split(paragraph)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var current = ""

            fun flush() {
                if (current.isNotBlank()) {
                    rawChunks += current.trim()
                    current = ""
                }
            }

            for (sentence in sentences) {
                if (sentence.length > HARD_CHARS) {
                    flush()
                    rawChunks += splitOversized(sentence)
                    continue
                }

                val candidate = if (current.isBlank()) sentence else "$current $sentence"
                if (candidate.length <= TARGET_CHARS) {
                    current = candidate
                } else {
                    flush()
                    current = sentence
                }
            }

            flush()
        }

        var nextWord = startWord
        return rawChunks.mapNotNull { chunk ->
            val words = countWords(chunk)
            if (words <= 0L) {
                null
            } else {
                NarrationUnit(
                    text = chunk,
                    startWord = nextWord,
                    wordCount = words,
                ).also {
                    nextWord += words
                }
            }
        }
    }

    private fun splitOversized(text: String): List<String> {
        val out = mutableListOf<String>()
        var remaining = text.trim()

        while (remaining.length > HARD_CHARS) {
            val prefix = remaining.take(HARD_CHARS)
            val boundary = prefix.lastIndexOfAny(
                charArrayOf('.', '?', '!', ';', ':', ',', ' ')
            )
            val cut = if (boundary >= TARGET_CHARS / 2) boundary + 1 else HARD_CHARS
            val piece = remaining.substring(0, cut).trim()
            if (piece.isNotEmpty()) out += piece
            remaining = remaining.substring(cut).trimStart()
        }

        if (remaining.isNotBlank()) out += remaining
        return out
    }
}
