package com.offlinenarrator.benchmark.book

/**
 * Conservative normalization for spoken novels. Source text and location
 * accounting stay untouched; only NarrationUnit.spokenText is normalized.
 */
object NarrationTextNormalizer {
    private val sceneMarker = Regex(
        """^\s*(?:(?:\*\s*){3,}|(?:#\s*){3,}|(?:[-—–]\s*){3,}|(?:•\s*){3,}|(?:·\s*){3,})\s*$"""
    )

    private val chapterRoman = Regex(
        """^(?i)(chapter|part|book)\s+([IVXLCDM]{1,12})\s*$"""
    )

    fun normalize(text: String): String {
        if (text.isBlank()) return ""

        var value = text
            .replace('\u00A0', ' ')
            .replace('\u2007', ' ')
            .replace('\u202F', ' ')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace(Regex("""\.{3,}"""), "…")
            .replace("．．．", "…")
            .replace('―', '—')
            .replace('–', '—')

        chapterRoman.matchEntire(value.trim())?.let { match ->
            romanToInt(match.groupValues[2])?.let { number ->
                value = "${match.groupValues[1].lowercase().replaceFirstChar { it.uppercase() }} $number."
            }
        }

        return value
            .replace(Regex("""[ \t]+"""), " ")
            .replace(Regex("""\s+([,;:.!?…])"""), "$1")
            .replace(Regex("""([“«])\s+"""), "$1")
            .replace(Regex("""\s+([”»])"""), "$1")
            .trim()
    }

    fun isSceneMarker(text: String): Boolean =
        sceneMarker.matches(text.trim())

    private fun romanToInt(raw: String): Int? {
        val values = mapOf(
            'I' to 1, 'V' to 5, 'X' to 10, 'L' to 50,
            'C' to 100, 'D' to 500, 'M' to 1000,
        )
        var total = 0
        var previous = 0
        for (ch in raw.uppercase().reversed()) {
            val current = values[ch] ?: return null
            if (current < previous) total -= current else {
                total += current
                previous = current
            }
        }
        return total.takeIf { it in 1..3999 }
    }
}
