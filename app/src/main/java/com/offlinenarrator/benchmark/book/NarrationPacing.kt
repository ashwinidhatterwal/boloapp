package com.offlinenarrator.benchmark.book

data class BoundaryTiming(
    val minMs: Int,
    val targetMs: Int,
    val maxMs: Int,
)

/** Total desired trailing silence, not extra silence to blindly append. */
object NarrationPacing {
    fun timing(
        boundary: NarrationBoundary,
        cue: DeliveryCue,
    ): BoundaryTiming = when (boundary) {
        NarrationBoundary.CONTINUE -> BoundaryTiming(40, 70, 120)

        NarrationBoundary.SENTENCE -> when (cue) {
            DeliveryCue.QUESTION,
            DeliveryCue.EXCLAMATION -> BoundaryTiming(180, 245, 340)
            DeliveryCue.HESITATION -> BoundaryTiming(270, 360, 500)
            DeliveryCue.INTERRUPTION -> BoundaryTiming(55, 90, 150)
            DeliveryCue.NEUTRAL -> BoundaryTiming(145, 210, 310)
        }

        NarrationBoundary.PARAGRAPH -> BoundaryTiming(330, 455, 650)
        NarrationBoundary.SCENE -> BoundaryTiming(650, 850, 1_100)
        NarrationBoundary.CHAPTER -> BoundaryTiming(850, 1_100, 1_450)
    }
}
