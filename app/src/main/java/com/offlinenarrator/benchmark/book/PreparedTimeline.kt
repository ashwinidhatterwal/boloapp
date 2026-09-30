package com.offlinenarrator.benchmark.book

/** Seek within a stable prepared chunk. Silence anchors are approximate, not forced alignment. */
object PreparedTimeline {
    fun timeForWord(word: Long, totalWords: Long, durationMs: Long, wordAnchors: LongArray, timeAnchors: LongArray): Long {
        if (totalWords <= 0 || durationMs <= 0) return 0
        val target = word.coerceIn(0, totalWords)
        var beforeWord = 0L; var beforeTime = 0L
        if (wordAnchors.size == timeAnchors.size) {
            for (i in wordAnchors.indices) {
                val afterWord = wordAnchors[i].coerceIn(beforeWord, totalWords)
                val afterTime = timeAnchors[i].coerceIn(beforeTime, durationMs)
                if (target <= afterWord && afterWord > beforeWord) return beforeTime +
                    ((target - beforeWord).toDouble() / (afterWord - beforeWord) * (afterTime - beforeTime)).toLong()
                beforeWord = afterWord; beforeTime = afterTime
            }
        }
        return if (totalWords == beforeWord) beforeTime else beforeTime +
            ((target - beforeWord).toDouble() / (totalWords - beforeWord) * (durationMs - beforeTime)).toLong()
    }
}
