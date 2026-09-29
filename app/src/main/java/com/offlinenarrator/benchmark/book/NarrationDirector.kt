package com.offlinenarrator.benchmark.book

import java.util.Locale

/**
 * Conservative deterministic narration director.
 *
 * V1 deliberately avoids guessing. Quoted text is marked as dialogue, but a
 * character identity is attached only when a nearby explicit attribution such
 * as "Arjun said" or "said Arjun" is found.
 */
object NarrationDirector {
    private const val TARGET_CHARS = 280
    private const val HARD_CHARS = 420
    private const val CONTEXT_CHARS = 140

    private val speechVerbs = listOf(
        "said",
        "asked",
        "replied",
        "answered",
        "whispered",
        "shouted",
        "murmured",
        "cried",
        "called",
        "yelled",
        "added",
        "continued",
        "remarked",
        "responded",
        "muttered",
    )

    private val speechVerbAlternation = speechVerbs.joinToString("|") { Regex.escape(it) }
    private val namePattern =
        """([A-Z][\p{L}'’\-]{1,30}(?:\s+[A-Z][\p{L}'’\-]{1,30})?)"""

    private val afterVerbThenName = Regex(
        """^\s*[,;:—–-]*\s*(?i:$speechVerbAlternation)\s+$namePattern\b"""
    )
    private val afterNameThenVerb = Regex(
        """^\s*[,;:—–-]*\s*$namePattern\s+(?i:$speechVerbAlternation)\b"""
    )
    private val beforeNameThenVerb = Regex(
        """\b$namePattern\s+(?i:$speechVerbAlternation)\s*[,;:—–-]*\s*$"""
    )

    private val pronounsAndNoise = setOf(
        "he", "she", "they", "we", "i", "you", "it",
        "the", "a", "an", "his", "her", "their",
    )

    fun plan(
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

        val spans = splitQuotedSpans(remaining)
        val rawUnits = mutableListOf<RawUnit>()

        for (span in spans) {
            val speaker = if (span.dialogue) {
                inferSpeaker(span.beforeContext, span.afterContext)
            } else {
                null
            }

            splitForTts(span.text).forEach { chunk ->
                if (chunk.isNotBlank()) {
                    rawUnits += RawUnit(
                        text = chunk,
                        role = if (span.dialogue) {
                            NarrationRole.DIALOGUE
                        } else {
                            NarrationRole.NARRATOR
                        },
                        speakerKey = speaker,
                        deliveryCue = deliveryCue(chunk),
                    )
                }
            }
        }

        var nextWord = startWord
        return rawUnits.mapNotNull { raw ->
            val words = countWords(raw.text)
            if (words <= 0L) {
                null
            } else {
                NarrationUnit(
                    text = raw.text,
                    startWord = nextWord,
                    wordCount = words,
                    role = raw.role,
                    speakerKey = raw.speakerKey,
                    deliveryCue = raw.deliveryCue,
                ).also {
                    nextWord += words
                }
            }
        }
    }

    private fun splitQuotedSpans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var cursor = 0

        while (cursor < text.length) {
            val open = findNextQuote(text, cursor)
            if (open < 0) {
                addSpan(out, text.substring(cursor), dialogue = false, text, cursor, text.length)
                break
            }

            if (open > cursor) {
                addSpan(
                    out = out,
                    value = text.substring(cursor, open),
                    dialogue = false,
                    source = text,
                    sourceStart = cursor,
                    sourceEnd = open,
                )
            }

            val close = findClosingQuote(text, open + 1, text[open])
            if (close < 0) {
                addSpan(
                    out = out,
                    value = text.substring(open),
                    dialogue = false,
                    source = text,
                    sourceStart = open,
                    sourceEnd = text.length,
                )
                break
            }

            val innerStart = open + 1
            addSpan(
                out = out,
                value = text.substring(innerStart, close),
                dialogue = true,
                source = text,
                sourceStart = open,
                sourceEnd = close + 1,
            )
            cursor = close + 1
        }

        if (out.isEmpty() && text.isNotBlank()) {
            addSpan(out, text, dialogue = false, text, 0, text.length)
        }
        return out
    }

    private fun addSpan(
        out: MutableList<Span>,
        value: String,
        dialogue: Boolean,
        source: String,
        sourceStart: Int,
        sourceEnd: Int,
    ) {
        val cleaned = value.trim()
        if (cleaned.isBlank()) return

        val beforeStart = (sourceStart - CONTEXT_CHARS).coerceAtLeast(0)
        val afterEnd = (sourceEnd + CONTEXT_CHARS).coerceAtMost(source.length)

        out += Span(
            text = cleaned,
            dialogue = dialogue,
            beforeContext = source.substring(beforeStart, sourceStart),
            afterContext = source.substring(sourceEnd, afterEnd),
        )
    }

    private fun findNextQuote(text: String, from: Int): Int {
        for (i in from until text.length) {
            if (text[i] == '"' || text[i] == '“' || text[i] == '‘') return i
        }
        return -1
    }

    private fun findClosingQuote(
        text: String,
        from: Int,
        open: Char,
    ): Int {
        val expected = when (open) {
            '“' -> '”'
            '‘' -> '’'
            else -> '"'
        }
        for (i in from until text.length) {
            if (text[i] == expected) return i
        }
        return -1
    }

    private fun inferSpeaker(
        before: String,
        after: String,
    ): String? {
        val afterCandidate =
            afterVerbThenName.find(after)?.groupValues?.getOrNull(1)
                ?: afterNameThenVerb.find(after)?.groupValues?.getOrNull(1)
        normalizeSpeaker(afterCandidate)?.let { return it }

        val beforeCandidate =
            beforeNameThenVerb.find(before)?.groupValues?.getOrNull(1)
        return normalizeSpeaker(beforeCandidate)
    }

    private fun normalizeSpeaker(candidate: String?): String? {
        val value = candidate
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val lower = value.lowercase(Locale.ROOT)
        if (lower in pronounsAndNoise) return null
        if (value.length > 64) return null
        return value
    }

    private fun splitForTts(text: String): List<String> {
        val rawChunks = mutableListOf<String>()
        val paragraphs = text
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

        return rawChunks
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

    private fun deliveryCue(text: String): DeliveryCue {
        val end = text.trimEnd().lastOrNull()
        return when (end) {
            '?' -> DeliveryCue.QUESTION
            '!' -> DeliveryCue.EXCLAMATION
            else -> DeliveryCue.NEUTRAL
        }
    }

    private data class Span(
        val text: String,
        val dialogue: Boolean,
        val beforeContext: String,
        val afterContext: String,
    )

    private data class RawUnit(
        val text: String,
        val role: NarrationRole,
        val speakerKey: String?,
        val deliveryCue: DeliveryCue,
    )
}
