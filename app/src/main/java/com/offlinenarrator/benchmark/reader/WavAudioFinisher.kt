package com.offlinenarrator.benchmark.reader

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Result of editing only silent PCM at a generated batch boundary. */
data class BoundaryNormalizationResult(
    /** Signed duration change: positive = silence added, negative = trimmed. */
    val durationAdjustmentMs: Long,
    val originalLeadingSilenceMs: Long,
    val finalLeadingSilenceMs: Long,
    val originalTrailingSilenceMs: Long,
    val finalTrailingSilenceMs: Long,
)

/**
 * PCM-WAV post processor for long-form narration.
 *
 * v0.24 measures an adaptive per-file noise floor, trims excessive silence at
 * the beginning, and normalizes the ending to a structural target. It never
 * cuts non-silent speech. It can also find likely sentence valleys inside one
 * multi-sentence Kokoro batch so reader highlighting tracks the audio better.
 */
object WavAudioFinisher {
    private const val MAX_EDIT_MS = 2_000
    private const val LEADING_MAX_MS = 105
    private const val LEADING_TARGET_MS = 35
    private const val EDGE_FADE_MS = 7

    fun normalizeBoundarySilence(
        file: File,
        minTrailingMs: Int,
        targetTrailingMs: Int,
        maxTrailingMs: Int,
    ): BoundaryNormalizationResult {
        if (!file.isFile) return BoundaryNormalizationResult(0, 0, 0, 0, 0)

        val original = file.readBytes()
        val wav = parse(original)
            ?: return BoundaryNormalizationResult(0, 0, 0, 0, 0)
        if (wav.bitsPerSample != 16 || wav.blockAlign <= 0 || wav.sampleRate <= 0) {
            return BoundaryNormalizationResult(0, 0, 0, 0, 0)
        }

        val threshold = adaptiveSilenceThreshold(original, wav)
        val leadingFrames = countLeadingSilentFrames(original, wav, threshold)
        val trailingFrames = countTrailingSilentFrames(original, wav, threshold)
        val originalLeadingMs = framesToMs(leadingFrames, wav.sampleRate)
        val originalTrailingMs = framesToMs(trailingFrames, wav.sampleRate)

        val safeMin = minTrailingMs.coerceIn(0, MAX_EDIT_MS)
        val safeTarget = targetTrailingMs.coerceIn(safeMin, MAX_EDIT_MS)
        val safeMax = maxTrailingMs.coerceIn(safeTarget, MAX_EDIT_MS)

        val keepLeadingFrames = if (originalLeadingMs > LEADING_MAX_MS) {
            msToFrames(LEADING_TARGET_MS.toLong(), wav.sampleRate).coerceAtMost(leadingFrames)
        } else {
            leadingFrames
        }
        val removeLeadingFrames = (leadingFrames - keepLeadingFrames).coerceAtLeast(0L)

        var keepTrailingFrames = trailingFrames
        var addTrailingFrames = 0L
        when {
            originalTrailingMs < safeMin -> {
                val desired = msToFrames(safeTarget.toLong(), wav.sampleRate)
                addTrailingFrames = (desired - trailingFrames).coerceAtLeast(0L)
            }
            originalTrailingMs > safeMax -> {
                keepTrailingFrames = msToFrames(safeTarget.toLong(), wav.sampleRate)
                    .coerceAtMost(trailingFrames)
            }
        }
        val removeTrailingFrames = (trailingFrames - keepTrailingFrames).coerceAtLeast(0L)

        val removeLeadingBytes = (removeLeadingFrames * wav.blockAlign).toInt()
        val removeTrailingBytes = (removeTrailingFrames * wav.blockAlign).toInt()
        val addTrailingBytes = (addTrailingFrames * wav.blockAlign).toInt()

        if (
            removeLeadingBytes == 0 &&
            removeTrailingBytes == 0 &&
            addTrailingBytes == 0
        ) {
            return BoundaryNormalizationResult(
                durationAdjustmentMs = 0,
                originalLeadingSilenceMs = originalLeadingMs,
                finalLeadingSilenceMs = originalLeadingMs,
                originalTrailingSilenceMs = originalTrailingMs,
                finalTrailingSilenceMs = originalTrailingMs,
            )
        }

        val oldDataEnd = wav.dataStart + wav.dataSize
        val keptDataStart = wav.dataStart + removeLeadingBytes
        val keptDataEnd = oldDataEnd - removeTrailingBytes
        if (keptDataStart >= keptDataEnd) {
            return BoundaryNormalizationResult(0, originalLeadingMs, originalLeadingMs, originalTrailingMs, originalTrailingMs)
        }

        val keptDataSize = keptDataEnd - keptDataStart
        val newDataSize = keptDataSize + addTrailingBytes
        val suffixSize = original.size - oldDataEnd
        val output = ByteArray(wav.dataStart + newDataSize + suffixSize)

        // Header/chunks before PCM data.
        System.arraycopy(original, 0, output, 0, wav.dataStart)
        // Kept PCM data after leading/trailing trim.
        System.arraycopy(original, keptDataStart, output, wav.dataStart, keptDataSize)
        // addTrailingBytes remain zero-filled by ByteArray.
        // Preserve chunks after the data chunk, if any.
        if (suffixSize > 0) {
            System.arraycopy(
                original,
                oldDataEnd,
                output,
                wav.dataStart + newDataSize,
                suffixSize,
            )
        }

        putIntLe(output, wav.dataHeader + 4, newDataSize)
        putIntLe(output, 4, output.size - 8)

        applyEdgeFade(output, wav.copy(dataSize = newDataSize), threshold)
        file.writeBytes(output)

        val removedMs = framesToMs(removeLeadingFrames + removeTrailingFrames, wav.sampleRate)
        val addedMs = framesToMs(addTrailingFrames, wav.sampleRate)
        val finalLeadingMs = framesToMs(keepLeadingFrames, wav.sampleRate)
        val finalTrailingMs = framesToMs(keepTrailingFrames + addTrailingFrames, wav.sampleRate)

        return BoundaryNormalizationResult(
            durationAdjustmentMs = addedMs - removedMs,
            originalLeadingSilenceMs = originalLeadingMs,
            finalLeadingSilenceMs = finalLeadingMs,
            originalTrailingSilenceMs = originalTrailingMs,
            finalTrailingSilenceMs = finalTrailingMs,
        )
    }

    /** Compatibility API retained for old tests/callers. */
    fun normalizeTrailingSilence(
        file: File,
        minMs: Int,
        targetMs: Int,
        maxMs: Int,
    ): BoundaryNormalizationResult = normalizeBoundarySilence(
        file = file,
        minTrailingMs = minMs,
        targetTrailingMs = targetMs,
        maxTrailingMs = maxMs,
    )

    /** Compatibility helper retained for earlier checkpoints. */
    fun appendSilence(file: File, pauseMs: Int): Long =
        normalizeBoundarySilence(file, pauseMs, pauseMs, pauseMs)
            .durationAdjustmentMs
            .coerceAtLeast(0L)

    /**
     * Locate likely acoustic sentence valleys near word-proportional estimates.
     * This does not alter audio. Returned times are monotonic and are used only
     * for UI/progress interpolation inside a multi-sentence batch.
     */
    fun detectBoundaryTimes(
        file: File,
        wordEnds: List<Long>,
        totalWords: Long,
    ): LongArray {
        if (!file.isFile || wordEnds.isEmpty() || totalWords <= 0L) return LongArray(0)
        val bytes = file.readBytes()
        val wav = parse(bytes) ?: return LongArray(0)
        if (wav.bitsPerSample != 16 || wav.sampleRate <= 0 || wav.blockAlign <= 0) {
            return LongArray(0)
        }

        val totalFrames = wav.dataSize / wav.blockAlign
        if (totalFrames <= 1) return LongArray(0)
        val durationMs = framesToMs(totalFrames.toLong(), wav.sampleRate)
        val windowFrames = max(1, msToFrames(24, wav.sampleRate).toInt())
        val stepFrames = max(1, msToFrames(8, wav.sampleRate).toInt())
        var previousMs = 0L

        return LongArray(wordEnds.size) { index ->
            val fraction = (wordEnds[index].toDouble() / totalWords.toDouble())
                .coerceIn(0.03, 0.97)
            val expectedFrame = (fraction * totalFrames).toInt()
            val radiusMs = max(420L, (durationMs * 0.075).toLong()).coerceAtMost(1_100L)
            val radiusFrames = msToFrames(radiusMs, wav.sampleRate).toInt()
            val from = (expectedFrame - radiusFrames).coerceAtLeast(windowFrames)
            val to = (expectedFrame + radiusFrames).coerceAtMost(totalFrames - windowFrames - 1)

            var bestFrame = expectedFrame.coerceIn(from, to)
            var bestEnergy = Long.MAX_VALUE
            var frame = from
            while (frame <= to) {
                val energy = windowEnergy(bytes, wav, frame, windowFrames)
                if (energy < bestEnergy) {
                    bestEnergy = energy
                    bestFrame = frame
                }
                frame += stepFrames
            }

            val rawMs = framesToMs(bestFrame.toLong(), wav.sampleRate)
            val minAllowed = previousMs + 45L
            val maxAllowed = (durationMs - 45L).coerceAtLeast(minAllowed)
            val resolved = rawMs.coerceIn(minAllowed, maxAllowed)
            previousMs = resolved
            resolved
        }
    }

    private fun adaptiveSilenceThreshold(bytes: ByteArray, wav: WavInfo): Int {
        val sampleFrames = msToFrames(240, wav.sampleRate).toInt().coerceAtLeast(1)
        val totalFrames = wav.dataSize / wav.blockAlign
        val values = ArrayList<Int>(sampleFrames * 2)

        fun collect(startFrame: Int, endFrame: Int) {
            var frame = startFrame.coerceAtLeast(0)
            val end = endFrame.coerceAtMost(totalFrames)
            while (frame < end) {
                val frameStart = wav.dataStart + frame * wav.blockAlign
                var offset = frameStart
                val frameEnd = frameStart + wav.blockAlign
                while (offset + 1 < frameEnd) {
                    values += abs(shortLeSigned(bytes, offset).toInt())
                    offset += 2
                }
                frame += 2 // sample every other frame; enough for noise floor.
            }
        }

        collect(0, sampleFrames)
        collect((totalFrames - sampleFrames).coerceAtLeast(0), totalFrames)
        if (values.isEmpty()) return 420

        values.sort()
        val lowPercentile = values[(values.lastIndex * 0.18).toInt()]
        return (lowPercentile * 3 + 180).coerceIn(240, 720)
    }

    private fun countLeadingSilentFrames(bytes: ByteArray, wav: WavInfo, threshold: Int): Long {
        val totalFrames = wav.dataSize / wav.blockAlign
        var frames = 0L
        var frame = 0
        while (frame < totalFrames) {
            if (!frameIsSilent(bytes, wav, frame, threshold)) break
            frames += 1
            frame += 1
        }
        return frames
    }

    private fun countTrailingSilentFrames(bytes: ByteArray, wav: WavInfo, threshold: Int): Long {
        var frame = wav.dataSize / wav.blockAlign - 1
        var frames = 0L
        while (frame >= 0) {
            if (!frameIsSilent(bytes, wav, frame, threshold)) break
            frames += 1
            frame -= 1
        }
        return frames
    }

    private fun frameIsSilent(bytes: ByteArray, wav: WavInfo, frame: Int, threshold: Int): Boolean {
        val frameStart = wav.dataStart + frame * wav.blockAlign
        var offset = frameStart
        val frameEnd = frameStart + wav.blockAlign
        while (offset + 1 < frameEnd) {
            if (abs(shortLeSigned(bytes, offset).toInt()) > threshold) return false
            offset += 2
        }
        return true
    }

    private fun windowEnergy(bytes: ByteArray, wav: WavInfo, centerFrame: Int, halfWindow: Int): Long {
        val totalFrames = wav.dataSize / wav.blockAlign
        val start = (centerFrame - halfWindow).coerceAtLeast(0)
        val end = (centerFrame + halfWindow).coerceAtMost(totalFrames - 1)
        var energy = 0L
        var samples = 0L
        var frame = start
        while (frame <= end) {
            val frameStart = wav.dataStart + frame * wav.blockAlign
            var offset = frameStart
            val frameEnd = frameStart + wav.blockAlign
            while (offset + 1 < frameEnd) {
                energy += abs(shortLeSigned(bytes, offset).toInt()).toLong()
                samples += 1
                offset += 2
            }
            frame += 1
        }
        return if (samples == 0L) Long.MAX_VALUE else energy / samples
    }

    private fun applyEdgeFade(bytes: ByteArray, wav: WavInfo, threshold: Int) {
        val totalFrames = wav.dataSize / wav.blockAlign
        if (totalFrames <= 2) return
        val fadeFrames = msToFrames(EDGE_FADE_MS.toLong(), wav.sampleRate).toInt()
            .coerceIn(1, totalFrames / 4.coerceAtLeast(1))

        // Fade only around the first/last audible frames. Silence itself is left untouched.
        var firstAudible = 0
        while (firstAudible < totalFrames && frameIsSilent(bytes, wav, firstAudible, threshold)) {
            firstAudible += 1
        }
        var lastAudible = totalFrames - 1
        while (lastAudible > firstAudible && frameIsSilent(bytes, wav, lastAudible, threshold)) {
            lastAudible -= 1
        }

        for (i in 0 until fadeFrames) {
            val frame = firstAudible + i
            if (frame >= totalFrames) break
            scaleFrame(bytes, wav, frame, (i + 1).toFloat() / fadeFrames.toFloat())
        }
        for (i in 0 until fadeFrames) {
            val frame = lastAudible - i
            if (frame < 0) break
            scaleFrame(bytes, wav, frame, (i + 1).toFloat() / fadeFrames.toFloat())
        }
    }

    private fun scaleFrame(bytes: ByteArray, wav: WavInfo, frame: Int, gain: Float) {
        val frameStart = wav.dataStart + frame * wav.blockAlign
        var offset = frameStart
        val frameEnd = frameStart + wav.blockAlign
        while (offset + 1 < frameEnd) {
            val sample = shortLeSigned(bytes, offset).toInt()
            val scaled = (sample * gain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            putShortLe(bytes, offset, scaled.toShort())
            offset += 2
        }
    }

    private fun parse(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") return null
        var offset = 12
        var blockAlign = 0
        var sampleRate = 0
        var bitsPerSample = 0
        var dataHeader = -1
        var dataStart = -1
        var dataSize = 0

        while (offset + 8 <= bytes.size) {
            val id = ascii(bytes, offset, 4)
            val size = intLe(bytes, offset + 4).coerceAtLeast(0)
            val payload = offset + 8
            if (payload > bytes.size) break
            when (id) {
                "fmt " -> if (size >= 16 && payload + 16 <= bytes.size) {
                    sampleRate = intLe(bytes, payload + 4)
                    blockAlign = shortLeUnsigned(bytes, payload + 12)
                    bitsPerSample = shortLeUnsigned(bytes, payload + 14)
                }
                "data" -> {
                    dataHeader = offset
                    dataStart = payload
                    dataSize = size.coerceAtMost(bytes.size - payload)
                    break
                }
            }
            offset = payload + size + (size and 1)
        }

        if (dataHeader < 0 || dataStart < 0 || sampleRate <= 0 || blockAlign <= 0 || dataSize <= 0) return null
        return WavInfo(sampleRate, blockAlign, bitsPerSample, dataHeader, dataStart, dataSize)
    }

    private fun msToFrames(ms: Long, sampleRate: Int): Long =
        ((sampleRate.toLong() * ms) / 1000L).coerceAtLeast(0L)

    private fun framesToMs(frames: Long, sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else (frames * 1000L) / sampleRate.toLong()

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (offset < 0 || offset + length > bytes.size) "" else String(bytes, offset, length, Charsets.US_ASCII)

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortLeUnsigned(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 255) or ((bytes[offset + 1].toInt() and 255) shl 8)

    private fun shortLeSigned(bytes: ByteArray, offset: Int): Short =
        ((bytes[offset].toInt() and 255) or (bytes[offset + 1].toInt() shl 8)).toShort()

    private fun putIntLe(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }

    private fun putShortLe(bytes: ByteArray, offset: Int, value: Short) {
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value)
    }

    private data class WavInfo(
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val dataHeader: Int,
        val dataStart: Int,
        val dataSize: Int,
    )
}
