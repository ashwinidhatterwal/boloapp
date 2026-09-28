package com.offlinenarrator.benchmark.benchmark

object BenchmarkPassages {
    val narration = """
        The rain had stopped just before midnight. Beyond the old station, the road disappeared into a ribbon of silver mist. Arjun stood beneath the yellow lamp and listened. Somewhere across the fields, a train sounded its horn, low and distant, and for a moment the whole town seemed to hold its breath.
    """.trimIndent()

    val dialogue = """
        Sarah looked toward the staircase. "Wait... did you hear that?" she whispered. John lowered the book in his hands. "Hear what?" A sudden crash came from downstairs. Neither of them moved. "That," Sarah said quietly. "I definitely heard that."
    """.trimIndent()

    val numbers = """
        Doctor Sharma paid ₹1,25,750 on 28 September 2026 at 10:30 AM. The invoice included a 12.5 percent discount, and the remaining ₹18,750 is due within 30 days. Please call him before 5 PM and confirm order number 2048.
    """.trimIndent()

    val longForm = buildString {
        repeat(8) { index ->
            append("Chapter sample ${index + 1}. ")
            append(narration)
            append(' ')
            append(dialogue)
            append("\n\n")
        }
    }.trim()
}
