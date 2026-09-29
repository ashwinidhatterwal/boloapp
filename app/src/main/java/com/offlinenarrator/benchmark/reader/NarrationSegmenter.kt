package com.offlinenarrator.benchmark.reader

object NarrationSegmenter {
    private const val TARGET_CHARS = 280
    private const val HARD_CHARS = 420

    fun split(text: String): List<String> {
        val normalized = text
            .replace("\r\n", "\n")
            .replace(Regex("[\\t ]+"), " ")
            .trim()

        if (normalized.isBlank()) return emptyList()

        val paragraphs = normalized
            .split(Regex("\\n{2,}"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val out = mutableListOf<String>()

        for (paragraph in paragraphs) {
            val sentences = Regex("(?<=[.!?])\\s+|\\n+")
                .split(paragraph)
                .map { it.trim() }
                .filter { it.isNotEmpty() }

            var current = ""

            fun flush() {
                if (current.isNotBlank()) {
                    out += current.trim()
                    current = ""
                }
            }

            for (sentence in sentences) {
                if (sentence.length > HARD_CHARS) {
                    flush()
                    out += splitOversized(sentence)
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

        return out.filter { it.isNotBlank() }
    }

    private fun splitOversized(text: String): List<String> {
        val result = mutableListOf<String>()
        var remaining = text.trim()

        while (remaining.length > HARD_CHARS) {
            val searchEnd = HARD_CHARS.coerceAtMost(remaining.length)
            val prefix = remaining.substring(0, searchEnd)
            val boundary = prefix.lastIndexOfAny(
                charArrayOf('.', '?', '!', ';', ':', ',', ' ')
            )
            val cut = if (boundary >= TARGET_CHARS / 2) boundary + 1 else searchEnd
            val piece = remaining.substring(0, cut).trim()
            if (piece.isNotEmpty()) result += piece
            remaining = remaining.substring(cut).trimStart()
        }

        if (remaining.isNotBlank()) result += remaining
        return result
    }
}
