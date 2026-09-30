package com.offlinenarrator.benchmark.book

import java.util.Locale

/**
 * Natural Narrator v2.
 *
 * Sentence-level source locations are kept for seeking/highlighting while the
 * spoken copy preserves author punctuation and book structure for Kokoro.
 */
object NarrationDirector {
    private const val CONTEXT_CHARS = 180

    private val speechVerbs = listOf(
        "said", "asked", "replied", "answered", "whispered", "shouted",
        "murmured", "cried", "called", "yelled", "added", "continued",
        "remarked", "responded", "muttered", "exclaimed", "insisted",
        "warned", "promised", "admitted", "suggested", "told",
    )

    private val speechVerbAlternation = speechVerbs.joinToString("|") { Regex.escape(it) }
    private val namePattern =
        """((?:(?:Mr|Mrs|Ms|Dr|Prof|Sir|Lady)\.\s+)?[A-Z][\p{L}'’\-]{1,30}(?:\s+[A-Z][\p{L}'’\-]{1,30}){0,2})"""

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
        "he", "she", "they", "we", "i", "you", "it", "the", "a", "an",
        "his", "her", "their", "him", "them", "someone", "somebody",
    )

    fun plan(
        chapterText: String,
        startWord: Long = 0L,
        checkpoints: List<WordCheckpoint> = emptyList(),
        sourceFormat: String = "EPUB",
    ): List<NarrationUnit> {
        val remaining = dropWords(
            text = chapterText,
            wordsToDrop = startWord,
            checkpoints = checkpoints,
        )
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()

        if (remaining.isBlank()) return emptyList()

        val singleNewlineIsParagraph =
            sourceFormat.equals("EPUB", true) ||
                sourceFormat.equals("HTML", true)

        val spans = splitQuotedSpans(remaining)
        val rawUnits = mutableListOf<RawUnit>()

        for (span in spans) {
            val speaker = if (span.dialogue) {
                inferSpeaker(span.beforeContext, span.afterContext)
            } else null

            val slices = SentenceSegmenter.split(
                span.text,
                singleNewlineIsParagraph = singleNewlineIsParagraph,
            )

            for (slice in slices) {
                if (slice.text.isBlank()) continue

                if (NarrationTextNormalizer.isSceneMarker(slice.text)) {
                    if (rawUnits.isNotEmpty()) {
                        val previous = rawUnits.last()
                        rawUnits[rawUnits.lastIndex] = previous.copy(
                            boundaryAfter = NarrationBoundary.SCENE,
                        )
                    }
                    // Keep location width but never pronounce "asterisk asterisk".
                    rawUnits += RawUnit(
                        text = slice.text,
                        spokenText = "",
                        role = NarrationRole.NARRATOR,
                        speakerKey = null,
                        deliveryCue = DeliveryCue.NEUTRAL,
                        boundaryAfter = NarrationBoundary.SCENE,
                    )
                    continue
                }

                val sourceText = slice.text
                val speechSource = if (span.dialogue) {
                    wrapDialogueForSpeech(sourceText)
                } else {
                    sourceText
                }

                rawUnits += RawUnit(
                    text = sourceText,
                    spokenText = NarrationTextNormalizer.normalize(speechSource),
                    role = if (span.dialogue) NarrationRole.DIALOGUE else NarrationRole.NARRATOR,
                    speakerKey = speaker,
                    deliveryCue = deliveryCue(sourceText),
                    boundaryAfter = if (slice.paragraphBreakAfter) {
                        NarrationBoundary.PARAGRAPH
                    } else {
                        NarrationBoundary.SENTENCE
                    },
                )
            }
        }

        if (rawUnits.isNotEmpty()) {
            val last = rawUnits.last()
            rawUnits[rawUnits.lastIndex] = last.copy(
                boundaryAfter = NarrationBoundary.CHAPTER,
            )
        }

        var nextWord = startWord
        val located = rawUnits.mapNotNull { raw ->
            val words = countWords(raw.text)
            if (words <= 0L) {
                null
            } else {
                NarrationUnit(
                    text = raw.text,
                    spokenText = raw.spokenText,
                    startWord = nextWord,
                    wordCount = words,
                    role = raw.role,
                    speakerKey = raw.speakerKey,
                    deliveryCue = raw.deliveryCue,
                    boundaryAfter = raw.boundaryAfter,
                    pauseAfterMs = 0,
                ).also { nextWord += words }
            }
        }
        return DialogueTurnMemory.resolve(located)
    }

    private fun wrapDialogueForSpeech(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return trimmed
        return when {
            trimmed.startsWith("“") || trimmed.startsWith("\"") -> trimmed
            else -> "“$trimmed”"
        }
    }

    private fun splitQuotedSpans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var cursor = 0

        while (cursor < text.length) {
            val open = findNextQuote(text, cursor)
            if (open < 0) {
                addSpan(out, text.substring(cursor), false, text, cursor, text.length)
                break
            }

            if (open > cursor) {
                addSpan(out, text.substring(cursor, open), false, text, cursor, open)
            }

            val close = findClosingQuote(text, open + 1, text[open])
            if (close < 0) {
                addSpan(out, text.substring(open), false, text, open, text.length)
                break
            }

            var sourceEnd = close + 1
            while (sourceEnd < text.length && text[sourceEnd] in OUTSIDE_QUOTE_PUNCTUATION) {
                sourceEnd += 1
            }

            val dialogue = buildString {
                append(text.substring(open + 1, close))
                if (sourceEnd > close + 1) append(text.substring(close + 1, sourceEnd))
            }

            addSpan(
                out = out,
                value = dialogue,
                dialogue = true,
                source = text,
                sourceStart = open,
                sourceEnd = sourceEnd,
            )
            cursor = sourceEnd
        }

        if (out.isEmpty() && text.isNotBlank()) {
            addSpan(out, text, false, text, 0, text.length)
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

    private fun findClosingQuote(text: String, from: Int, open: Char): Int {
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

    private fun inferSpeaker(before: String, after: String): String? {
        val afterCandidate =
            afterVerbThenName.find(after)?.groupValues?.getOrNull(1)
                ?: afterNameThenVerb.find(after)?.groupValues?.getOrNull(1)
        normalizeSpeaker(afterCandidate)?.let { return it }

        val beforeCandidate = beforeNameThenVerb.find(before)?.groupValues?.getOrNull(1)
        return normalizeSpeaker(beforeCandidate)
    }

    private fun normalizeSpeaker(candidate: String?): String? {
        val value = candidate
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: return null

        val lower = value.lowercase(Locale.ROOT)
        if (lower in pronounsAndNoise || value.length > 64) return null
        return value
    }

    private fun deliveryCue(text: String): DeliveryCue {
        val trimmed = text.trimEnd()
        if (trimmed.endsWith("…") || trimmed.endsWith("...")) {
            return DeliveryCue.HESITATION
        }
        if (trimmed.endsWith("—") || trimmed.endsWith("–")) {
            return DeliveryCue.INTERRUPTION
        }

        return when (lastMeaningfulPunctuation(text)) {
            '?', '？' -> DeliveryCue.QUESTION
            '!', '！' -> DeliveryCue.EXCLAMATION
            else -> DeliveryCue.NEUTRAL
        }
    }

    private fun lastMeaningfulPunctuation(text: String): Char? {
        for (i in text.lastIndex downTo 0) {
            val ch = text[i]
            if (ch.isWhitespace() || ch in CLOSING_MARKS) continue
            return ch
        }
        return null
    }

    private data class Span(
        val text: String,
        val dialogue: Boolean,
        val beforeContext: String,
        val afterContext: String,
    )

    private data class RawUnit(
        val text: String,
        val spokenText: String,
        val role: NarrationRole,
        val speakerKey: String?,
        val deliveryCue: DeliveryCue,
        val boundaryAfter: NarrationBoundary,
    )

    private val OUTSIDE_QUOTE_PUNCTUATION = charArrayOf(',', '.', ';', ':', '?', '!')
    private val CLOSING_MARKS = charArrayOf('"', '”', '’', '\'', ')', ']', '}')
}
