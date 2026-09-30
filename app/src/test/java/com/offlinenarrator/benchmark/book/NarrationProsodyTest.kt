package com.offlinenarrator.benchmark.book

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NarrationProsodyTest {
    @Test
    fun hesitationIsSlightlySlowerNotDramatic() {
        val plan = NarrationProsody.plan(
            NarrationBatch(
                text = "She waited… wondering whether the memory was real.",
                startWord = 0,
                wordCount = 8,
                role = NarrationRole.NARRATOR,
                speakerKey = null,
                deliveryCue = DeliveryCue.HESITATION,
                boundaryAfter = NarrationBoundary.PARAGRAPH,
                unitCount = 1,
                performance = NarrationPerformance(
                    mood = NarrationMood.HESITANT,
                    confidence = 0.8f,
                    synthesisSpeed = 0.982f,
                ),
            )
        )
        assertEquals(NarrationCadence.HESITANT, plan.cadence)
        assertTrue(plan.synthesisSpeed in 0.972f..1.0f)
    }

    @Test
    fun neutralDialogueDoesNotGetArtificialTempoActing() {
        val plan = NarrationProsody.plan(
            NarrationBatch(
                text = "Where have you been?",
                startWord = 0,
                wordCount = 4,
                role = NarrationRole.DIALOGUE,
                speakerKey = "Maya",
                deliveryCue = DeliveryCue.QUESTION,
                boundaryAfter = NarrationBoundary.SENTENCE,
                unitCount = 1,
                dialogueUnitCount = 1,
                performance = NarrationPerformance(
                    mood = NarrationMood.NEUTRAL,
                    confidence = 0.2f,
                    synthesisSpeed = 1.0f,
                ),
            )
        )
        assertEquals(1.0f, plan.synthesisSpeed)
    }
}
