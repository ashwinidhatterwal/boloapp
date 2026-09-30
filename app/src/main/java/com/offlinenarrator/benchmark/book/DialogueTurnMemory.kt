package com.offlinenarrator.benchmark.book

/**
 * Conservative scene-local dialogue continuity.
 *
 * It only infers an unattributed line after two distinct speakers have already
 * been explicitly identified nearby. Long narration or a scene/chapter break
 * clears the context. This avoids inventing characters from ordinary prose.
 */
object DialogueTurnMemory {
    private const val MAX_NARRATOR_WORDS_BETWEEN_TURNS = 34L
    private const val RESET_AFTER_NARRATOR_WORDS = 70L

    fun resolve(units: List<NarrationUnit>): List<NarrationUnit> {
        if (units.isEmpty()) return units

        val recent = mutableListOf<String>()
        var lastDialogueSpeaker: String? = null
        var narratorWordsSinceDialogue = 0L

        fun remember(name: String) {
            recent.remove(name)
            recent.add(name)
            while (recent.size > 2) recent.removeAt(0)
        }

        fun reset() {
            recent.clear()
            lastDialogueSpeaker = null
            narratorWordsSinceDialogue = 0L
        }

        return units.map { unit ->
            if (
                unit.boundaryAfter == NarrationBoundary.SCENE ||
                unit.boundaryAfter == NarrationBoundary.CHAPTER
            ) {
                val result = if (unit.role == NarrationRole.DIALOGUE) {
                    unit.speakerKey?.let(::remember)
                    lastDialogueSpeaker = unit.speakerKey ?: lastDialogueSpeaker
                    narratorWordsSinceDialogue = 0L
                    unit
                } else unit
                reset()
                return@map result
            }

            if (unit.role == NarrationRole.NARRATOR) {
                narratorWordsSinceDialogue += unit.wordCount
                if (narratorWordsSinceDialogue > RESET_AFTER_NARRATOR_WORDS) reset()
                return@map unit
            }

            val explicit = unit.speakerKey
            if (explicit != null) {
                remember(explicit)
                lastDialogueSpeaker = explicit
                narratorWordsSinceDialogue = 0L
                return@map unit
            }

            val inferred = if (
                recent.size == 2 &&
                lastDialogueSpeaker != null &&
                narratorWordsSinceDialogue <= MAX_NARRATOR_WORDS_BETWEEN_TURNS
            ) {
                recent.firstOrNull { it != lastDialogueSpeaker }
            } else null

            if (inferred != null) {
                remember(inferred)
                lastDialogueSpeaker = inferred
                narratorWordsSinceDialogue = 0L
                unit.copy(speakerKey = inferred)
            } else {
                narratorWordsSinceDialogue = 0L
                unit
            }
        }
    }
}
