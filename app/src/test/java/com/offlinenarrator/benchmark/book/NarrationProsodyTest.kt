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
            )
        )
        assertEquals(NarrationCadence.HESITANT, plan.cadence)
        assertTrue(plan.synthesisSpeed in 0.95f..1.0f)
    }

    @Test
    fun dialogueDoesNotGetArtificialTempoActing() {
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
            )
        )
        assertEquals(1.0f, plan.synthesisSpeed)
    }
}
