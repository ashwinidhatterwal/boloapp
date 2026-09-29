package com.offlinenarrator.benchmark.book

import java.util.Locale

/**
 * Deterministic sentence/line segmentation used by both narration and the
 * selectable reading view. It is intentionally conservative: a boundary is
 * emitted only when punctuation/newline context strongly indicates the end of
 * a sentence or paragraph.
 */
object SentenceSegmenter {
    private const val HARD_CHARS = 560
    private const val CLAUSE_TARGET = 360

    private val abbreviations = setOf(
        "mr", "mrs", "ms", "dr", "prof", "sr", "jr", "st", "vs", "etc",
        "e.g", "i.e", "approx", "dept", "fig", "vol", "ch", "pp", "p",
        "no", "nos", "jan", "feb", "mar", "apr", "jun", "jul", "aug",
        "sep", "sept", "oct", "nov", "dec", "inc", "ltd", "co", "mt",
        "gen", "rep", "sen", "gov", "pres", "capt", "lt", "col", "sgt",
    )

    data class Slice(
        val text: String,
        val paragraphBreakAfter: Boolean,
    )

    fun split(text: String): List<Slice> {
        if (text.isBlank()) return emptyList()

        val source = text
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        val out = mutableListOf<Slice>()
        var start = 0
        var i = 0

        fun emit(endExclusive: Int, paragraphBreak: Boolean) {
            if (endExclusive <= start) {
                start = endExclusive
                return
            }

            val raw = source.substring(start, endExclusive)
            val cleaned = normalizeHorizontalWhitespace(raw)
            if (cleaned.isNotBlank()) {
                splitOversized(cleaned, paragraphBreak).forEach(out::add)
            }
            start = endExclusive
        }

        while (i < source.length) {
            val ch = source[i]

            if (ch == '\n') {
                var end = i + 1
                while (end < source.length && source[end] == '\n') end += 1
                val runLength = end - i
                if (runLength >= 2 || shouldBreakAtSingleNewline(source, start, i, end)) {
                    emit(i, paragraphBreak = true)
                    start = end
                }
                i = end
                continue
            }

            if (ch == '.' || ch == '?' || ch == '!' || ch == '…' || ch == '।' || ch == '॥' || ch == '。' || ch == '？' || ch == '！') {
                val terminalEnd = terminalClusterEnd(source, i)
                if (isSentenceBoundary(source, i, terminalEnd)) {
                    emit(
                        terminalEnd,
                        paragraphBreak = hasNewlineBeforeNextText(source, terminalEnd),
                    )
                    start = skipBoundaryWhitespace(source, terminalEnd)
                    i = start
                    continue
                }
                i = terminalEnd
                continue
            }

            i += 1
        }

        if (start < source.length) {
            emit(source.length, paragraphBreak = true)
        }

        return out
    }

    fun readerLines(text: String): List<ReaderLine> {
        var wordOffset = 0L
        var index = 0
        val lines = mutableListOf<ReaderLine>()

        for (slice in split(text)) {
            val words = countWords(slice.text)
            if (words <= 0L) continue
            lines += ReaderLine(
                index = index,
                text = slice.text,
                startWord = wordOffset,
                wordCount = words,
                paragraphBreakAfter = slice.paragraphBreakAfter,
            )
            wordOffset += words
            index += 1
        }

        return lines
    }

    private fun shouldBreakAtSingleNewline(
        text: String,
        sentenceStart: Int,
        newlineIndex: Int,
        nextIndex: Int,
    ): Boolean {
        val line = text.substring(sentenceStart, newlineIndex).trim()
        if (line.isBlank()) return true
        if (nextIndex >= text.length) return true

        // EPUB headings and short standalone lines should remain selectable,
        // while soft PDF/text line wraps are folded back into the sentence.
        val wordCount = line.split(Regex("\\s+")).size
        val last = line.lastOrNull()
        val next = text.getOrNull(nextIndex)
        val looksStandalone = wordCount <= 10 && line.length <= 90 &&
            next?.isUpperCase() == true &&
            (last != null && (last.isLetterOrDigit() || last in CLOSERS))
        return looksStandalone
    }

    private fun hasNewlineBeforeNextText(text: String, from: Int): Boolean {
        for (i in from until text.length) {
            if (text[i] == '\n') return true
            if (!text[i].isWhitespace()) return false
        }
        return false
    }

    private fun isSentenceBoundary(
        text: String,
        punctuationIndex: Int,
        terminalEnd: Int,
    ): Boolean {
        val ch = text[punctuationIndex]

        if (ch == '.') {
            if (isDecimalPoint(text, punctuationIndex)) return false
            if (isProtectedPeriod(text, punctuationIndex)) return false
        }

        // Dialogue punctuation followed by an attribution belongs to the same
        // written sentence: “Stop!” she said.  We keep the line intact even
        // though NarrationDirector may still synthesize dialogue/attribution
        // as separate voices.
        if (followedByDialogueAttribution(text, terminalEnd)) return false

        if (terminalEnd >= text.length) return true
        val next = nextNonWhitespaceIndex(text, terminalEnd)
        if (next < 0) return true

        val gap = text.substring(terminalEnd, next)
        if (gap.any(Char::isWhitespace)) return true

        return text[next].isUpperCase() ||
            text[next].isDigit() ||
            text[next] == '“' || text[next] == '‘' || text[next] == '"'
    }

    private fun isDecimalPoint(text: String, index: Int): Boolean =
        index > 0 && index + 1 < text.length &&
            text[index - 1].isDigit() && text[index + 1].isDigit()

    private fun isProtectedPeriod(text: String, index: Int): Boolean {
        val token = tokenBeforePeriod(text, index)
        if (token.isBlank()) return false

        val normalized = token.lowercase(Locale.ROOT).trimEnd('.')
        if (normalized in abbreviations) return true

        // Initials: J. K. Rowling, A. P. J. Abdul Kalam.
        if (normalized.length == 1 && normalized[0].isLetter()) {
            val next = nextNonWhitespaceIndex(text, index + 1)
            if (next >= 0 && text[next].isUpperCase()) return true
        }

        // Multi-initial abbreviations/acronyms: U.S., U.S.A., Ph.D.
        val acronymStart = (index - 12).coerceAtLeast(0)
        val tail = text.substring(acronymStart, index + 1)
        if (Regex("(?:[A-Za-z]\\.){2,}$").containsMatchIn(tail)) return true

        return false
    }

    private fun tokenBeforePeriod(text: String, period: Int): String {
        var start = period - 1
        while (start >= 0) {
            val ch = text[start]
            if (!(ch.isLetter() || ch == '.')) break
            start -= 1
        }
        return text.substring(start + 1, period)
    }

    private fun followedByDialogueAttribution(text: String, terminalEnd: Int): Boolean {
        var i = terminalEnd
        while (i < text.length && text[i].isWhitespace() && text[i] != '\n') i += 1
        if (i >= text.length || text[i] == '\n') return false

        val lookaheadEnd = (i + 110).coerceAtMost(text.length)
        val lookahead = text.substring(i, lookaheadEnd)
        val pattern = """^(?:(?i:he|she|they|we|i|you)\s+|(?:(?:Mr|Mrs|Ms|Dr|Prof|Sir|Lady)\.\s+)?[A-Z][\p{L}'’\-]{1,30}(?:\s+[A-Z][\p{L}'’\-]{1,30}){0,2}\s+)(?:said|asked|replied|answered|whispered|shouted|murmured|cried|called|yelled|added|continued|remarked|responded|muttered|exclaimed|insisted|warned|promised|admitted|suggested|told)\b"""
        return Regex(pattern).containsMatchIn(lookahead)
    }

    private fun terminalClusterEnd(text: String, index: Int): Int {
        var i = index + 1

        // Consume ellipsis / ?! / !!! clusters.
        while (i < text.length && (
                text[i] == '.' || text[i] == '?' || text[i] == '!' || text[i] == '…' ||
                text[i] == '।' || text[i] == '॥' || text[i] == '。' || text[i] == '？' || text[i] == '！'
            )
        ) {
            i += 1
        }

        // Keep closing quote/bracket punctuation attached to the sentence.
        while (i < text.length && text[i] in CLOSERS) i += 1
        return i
    }

    private fun nextNonWhitespaceIndex(text: String, from: Int): Int {
        for (i in from until text.length) {
            if (!text[i].isWhitespace()) return i
        }
        return -1
    }

    private fun skipBoundaryWhitespace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i += 1
        return i
    }

    private fun splitOversized(
        text: String,
        paragraphBreakAfter: Boolean,
    ): List<Slice> {
        if (text.length <= HARD_CHARS) {
            return listOf(Slice(text.trim(), paragraphBreakAfter))
        }

        val out = mutableListOf<Slice>()
        var remaining = text.trim()
        while (remaining.length > HARD_CHARS) {
            val window = remaining.take(HARD_CHARS)
            val preferred = lastClauseBoundary(window, CLAUSE_TARGET)
            val cut = when {
                preferred > 0 -> preferred
                else -> window.lastIndexOf(' ').takeIf { it >= CLAUSE_TARGET / 2 }
                    ?.plus(1)
                    ?: HARD_CHARS
            }
            val piece = remaining.substring(0, cut).trim()
            if (piece.isNotBlank()) out += Slice(piece, paragraphBreakAfter = false)
            remaining = remaining.substring(cut).trimStart()
        }
        if (remaining.isNotBlank()) {
            out += Slice(remaining, paragraphBreakAfter)
        }
        return out
    }

    private fun lastClauseBoundary(text: String, minimum: Int): Int {
        for (i in text.lastIndex downTo minimum.coerceAtMost(text.lastIndex)) {
            if (text[i] == ';' || text[i] == ':' || text[i] == ',' ||
                text[i] == '—' || text[i] == '–'
            ) {
                return i + 1
            }
        }
        return -1
    }

    private fun normalizeHorizontalWhitespace(value: String): String =
        value
            .replace(Regex("[\\t ]+"), " ")
            .replace(Regex("\\s*\\n\\s*"), " ")
            .trim()

    private val CLOSERS = charArrayOf('"', '”', '’', '\'', ')', ']', '}')
}
