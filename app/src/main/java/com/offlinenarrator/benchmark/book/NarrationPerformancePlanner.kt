package com.offlinenarrator.benchmark.book

import java.util.Locale
import kotlin.math.abs

/**
 * Chapter-level narration preparation.
 *
 * Human narrators prepare from context, not isolated sentence labels. Bolo does
 * the same with deliberately conservative heuristics: explicit speech verbs and
 * adverbs are strong evidence; nearby action/emotion language is weaker; bare
 * punctuation is only a small hint. Scene state moves gradually and resets at
 * scene/chapter boundaries.
 *
 * The result is not intended to make Kokoro "act" dramatically. It provides
 * tiny, stable pacing adjustments while the author's punctuation remains the
 * primary prosody instruction.
 */
object NarrationPerformancePlanner {
    private const val NEUTRAL_ENERGY = 0.50f
    private const val NEUTRAL_TENSION = 0.25f
    private const val NEUTRAL_WARMTH = 0.50f

    private val whisperVerbs = setOf(
        "whispered", "whisper", "murmured", "murmur", "breathed",
        "mumbled", "muttered",
    )
    private val loudVerbs = setOf(
        "shouted", "shout", "yelled", "yell", "screamed", "scream",
        "roared", "roar", "bellowed", "bellow", "called",
    )
    private val angryVerbs = setOf(
        "snapped", "snap", "demanded", "demand", "barked", "bark",
        "growled", "growl", "hissed", "hiss", "retorted", "retort",
    )
    private val fearfulVerbs = setOf(
        "pleaded", "plead", "begged", "beg", "stammered", "stammer",
        "stuttered", "stutter", "gasped", "gasp",
    )
    private val somberVerbs = setOf(
        "sobbed", "sob", "weep", "wept",
    )
    private val reflectiveVerbs = setOf(
        "sighed", "sigh", "wondered", "wonder", "remembered", "recalled",
    )
    private val lightVerbs = setOf(
        "laughed", "laugh", "chuckled", "chuckle", "giggled", "giggle",
        "teased", "tease", "joked", "joke",
    )

    private val softAdverbs = setOf(
        "softly", "quietly", "gently", "tenderly", "warmly", "calmly",
        "fondly", "kindly",
    )
    private val tenseAdverbs = setOf(
        "nervously", "uneasily", "anxiously", "warily", "tensely",
        "carefully", "cautiously",
    )
    private val angryAdverbs = setOf(
        "angrily", "sharply", "coldly", "bitterly", "furiously",
        "harshly", "roughly",
    )
    private val hesitantAdverbs = setOf(
        "hesitantly", "reluctantly", "uncertainly", "slowly", "tentatively",
    )
    private val urgentAdverbs = setOf(
        "urgently", "quickly", "frantically", "desperately", "immediately",
        "rapidly",
    )

    private val actionWords = setOf(
        "ran", "run", "rushed", "rush", "dashed", "sprinted", "leapt",
        "jumped", "lunged", "grabbed", "struck", "hit", "fired", "shot",
        "slammed", "burst", "charged", "chased", "fled", "fought", "kicked",
    )
    private val reflectiveWords = setOf(
        "thought", "wondered", "remembered", "remember", "realized", "realised",
        "seemed", "perhaps", "maybe", "memory", "dreamed", "dreamt", "considered",
        "understood", "imagined", "recalled",
    )
    private val fearWords = setOf(
        "afraid", "fear", "terrified", "frightened", "panic", "panicked",
        "dread", "trembled", "trembling", "shivered",
    )
    private val angerWords = setOf(
        "angry", "anger", "furious", "rage", "raged", "hate", "hated",
        "fist", "fists", "glared", "scowled",
    )
    private val tenderWords = setOf(
        "love", "loved", "dear", "gently", "tender", "embraced", "kissed",
        "smiled", "warm", "kind",
    )
    private val somberWords = setOf(
        "grief", "grieved", "sad", "sorrow", "died", "death", "funeral",
        "tears", "wept", "lonely", "alone",
    )

    fun plan(units: List<NarrationUnit>): List<NarrationUnit> {
        if (units.isEmpty()) return units

        var sceneEnergy = NEUTRAL_ENERGY
        var sceneTension = NEUTRAL_TENSION
        var sceneWarmth = NEUTRAL_WARMTH

        return units.mapIndexed { index, unit ->
            val before = units.getOrNull(index - 1)?.text.orEmpty()
            val after = units.getOrNull(index + 1)?.text.orEmpty()
            val evidence = evidenceFor(unit, before, after)

            // Explicit evidence can affect the current line quickly. Weak
            // evidence only nudges the slowly-moving scene state.
            val evidenceWeight = when {
                evidence.confidence >= 0.85f -> 0.56f
                evidence.confidence >= 0.60f -> 0.38f
                else -> 0.20f
            }
            sceneEnergy = lerp(sceneEnergy, evidence.energy, evidenceWeight)
            sceneTension = lerp(sceneTension, evidence.tension, evidenceWeight)
            sceneWarmth = lerp(sceneWarmth, evidence.warmth, evidenceWeight)

            val confidence = evidence.confidence.coerceIn(0f, 1f)
            val speed = synthesisSpeed(
                mood = evidence.mood,
                confidence = confidence,
                energy = sceneEnergy,
                tension = sceneTension,
                role = unit.role,
            )

            val planned = unit.copy(
                performance = NarrationPerformance(
                    mood = if (confidence >= 0.52f) evidence.mood else NarrationMood.NEUTRAL,
                    confidence = confidence,
                    energy = sceneEnergy,
                    tension = sceneTension,
                    warmth = sceneWarmth,
                    synthesisSpeed = speed,
                )
            )

            if (
                unit.boundaryAfter == NarrationBoundary.SCENE ||
                unit.boundaryAfter == NarrationBoundary.CHAPTER
            ) {
                // New scenes should inherit a little tonal continuity, but not
                // stale emotion from an unrelated scene.
                sceneEnergy = lerp(sceneEnergy, NEUTRAL_ENERGY, 0.72f)
                sceneTension = lerp(sceneTension, NEUTRAL_TENSION, 0.78f)
                sceneWarmth = lerp(sceneWarmth, NEUTRAL_WARMTH, 0.68f)
            }

            planned
        }
    }

    private fun evidenceFor(
        unit: NarrationUnit,
        before: String,
        after: String,
    ): Evidence {
        val ownWords = words(unit.text)
        val nearbyWords = words("$before $after")
        val all = ownWords + nearbyWords.take(48)

        fun hasAny(values: Set<String>): Boolean = all.any { it in values }
        fun ownHas(values: Set<String>): Boolean = ownWords.any { it in values }

        // Dialogue tags usually live directly before/after the quote, so use
        // nearby text as strong evidence only for direct speech.
        if (unit.role == NarrationRole.DIALOGUE) {
            // Separate *delivery manner* from *emotion*. Whispering is strong
            // evidence for low energy, not automatically tenderness; shouting
            // is high energy, not automatically anger. Emotional labels are
            // only applied when the surrounding words support them.
            when {
                hasAny(angryVerbs) || hasAny(angryAdverbs) ||
                    (hasAny(loudVerbs) && (ownHas(angerWords) || nearbyWords.any { it in angerWords })) ->
                    return Evidence(NarrationMood.ANGRY, 0.92f, 0.78f, 0.78f, 0.22f)

                hasAny(fearfulVerbs) ||
                    (hasAny(whisperVerbs) && (ownHas(fearWords) || nearbyWords.any { it in fearWords })) ->
                    return Evidence(NarrationMood.FEARFUL, 0.88f, 0.48f, 0.78f, 0.32f)

                hasAny(somberVerbs) || ownHas(somberWords) ->
                    return Evidence(NarrationMood.SOMBER, 0.84f, 0.28f, 0.48f, 0.32f)

                hasAny(lightVerbs) ->
                    return Evidence(NarrationMood.LIGHT, 0.82f, 0.62f, 0.18f, 0.72f)

                ownHas(tenderWords) && (hasAny(whisperVerbs) || hasAny(softAdverbs)) ->
                    return Evidence(NarrationMood.TENDER, 0.80f, 0.32f, 0.24f, 0.74f)

                hasAny(reflectiveVerbs) || hasAny(hesitantAdverbs) ||
                    unit.deliveryCue == DeliveryCue.HESITATION ->
                    return Evidence(NarrationMood.HESITANT, 0.72f, 0.30f, 0.44f, 0.45f)

                hasAny(urgentAdverbs) ||
                    (hasAny(loudVerbs) && nearbyWords.any { it in actionWords }) ->
                    return Evidence(NarrationMood.URGENT, 0.78f, 0.80f, 0.62f, 0.38f)

                hasAny(whisperVerbs) || hasAny(softAdverbs) ->
                    return Evidence(NarrationMood.NEUTRAL, 0.78f, 0.30f, 0.34f, 0.54f)

                hasAny(loudVerbs) ->
                    return Evidence(NarrationMood.NEUTRAL, 0.76f, 0.78f, 0.48f, 0.46f)

                hasAny(tenseAdverbs) ->
                    return Evidence(NarrationMood.TENSE, 0.72f, 0.52f, 0.70f, 0.38f)
            }
        }

        // Narrative prose is intentionally harder to push away from neutral.
        val actionHits = ownWords.count { it in actionWords }
        val reflectiveHits = ownWords.count { it in reflectiveWords }
        val fearHits = ownWords.count { it in fearWords }
        val angerHits = ownWords.count { it in angerWords }
        val tenderHits = ownWords.count { it in tenderWords }
        val somberHits = ownWords.count { it in somberWords }

        return when {
            actionHits >= 2 -> Evidence(NarrationMood.URGENT, 0.66f, 0.76f, 0.60f, 0.38f)
            fearHits >= 2 -> Evidence(NarrationMood.FEARFUL, 0.68f, 0.52f, 0.74f, 0.30f)
            angerHits >= 2 -> Evidence(NarrationMood.TENSE, 0.66f, 0.66f, 0.70f, 0.26f)
            somberHits >= 2 -> Evidence(NarrationMood.SOMBER, 0.64f, 0.26f, 0.44f, 0.30f)
            reflectiveHits >= 2 -> Evidence(NarrationMood.REFLECTIVE, 0.62f, 0.34f, 0.24f, 0.52f)
            tenderHits >= 2 -> Evidence(NarrationMood.TENDER, 0.60f, 0.36f, 0.20f, 0.70f)
            unit.deliveryCue == DeliveryCue.HESITATION ->
                Evidence(NarrationMood.HESITANT, 0.55f, 0.34f, 0.42f, 0.46f)
            unit.deliveryCue == DeliveryCue.EXCLAMATION ->
                Evidence(NarrationMood.URGENT, 0.38f, 0.62f, 0.52f, 0.42f)
            unit.deliveryCue == DeliveryCue.QUESTION ->
                Evidence(NarrationMood.NEUTRAL, 0.25f, 0.50f, 0.30f, 0.50f)
            else -> Evidence(NarrationMood.NEUTRAL, 0.12f, 0.50f, 0.25f, 0.50f)
        }
    }

    private fun synthesisSpeed(
        mood: NarrationMood,
        confidence: Float,
        energy: Float,
        tension: Float,
        role: NarrationRole,
    ): Float {
        if (confidence < 0.45f) return 1.0f

        val moodDelta = when (mood) {
            NarrationMood.REFLECTIVE -> -0.016f
            NarrationMood.TENDER -> -0.012f
            NarrationMood.SOMBER -> -0.018f
            NarrationMood.HESITANT -> -0.014f
            NarrationMood.FEARFUL -> -0.006f
            NarrationMood.TENSE -> 0.004f
            NarrationMood.ANGRY -> 0.010f
            NarrationMood.URGENT -> 0.016f
            NarrationMood.LIGHT -> 0.006f
            NarrationMood.NEUTRAL -> 0f
        }
        val stateDelta = ((energy - 0.5f) * 0.018f) + ((tension - 0.4f) * 0.006f)
        val dialogueDamping = if (role == NarrationRole.DIALOGUE) 0.82f else 1.0f
        val delta = (moodDelta + stateDelta) * confidence * dialogueDamping

        // More than a few percent starts sounding like a different narrator.
        return (1.0f + delta).coerceIn(0.972f, 1.025f)
    }

    private fun words(text: String): List<String> = text
        .lowercase(Locale.ROOT)
        .split(Regex("[^\\p{L}']+"))
        .filter { it.isNotBlank() }

    private fun lerp(from: Float, to: Float, amount: Float): Float =
        (from + (to - from) * amount.coerceIn(0f, 1f)).coerceIn(0f, 1f)

    private data class Evidence(
        val mood: NarrationMood,
        val confidence: Float,
        val energy: Float,
        val tension: Float,
        val warmth: Float,
    )
}
