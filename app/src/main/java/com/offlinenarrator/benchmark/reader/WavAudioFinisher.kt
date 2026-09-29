package com.offlinenarrator.benchmark.reader

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

data class BoundaryNormalizationResult(
    val durationAdjustmentMs: Long,
    val originalTrailingSilenceMs: Long,
    val finalTrailingSilenceMs: Long,
)

/**
 * Measures Kokoro's existing PCM tail and only edits silence when the boundary
 * is outside the requested natural range.
 */
object WavAudioFinisher {
    private const val SILENCE_AMPLITUDE_THRESHOLD = 420
    private const val MAX_EDIT_MS = 2_000

    fun normalizeTrailingSilence(
        file: File,
        minMs: Int,
        targetMs: Int,
        maxMs: Int,
    ): BoundaryNormalizationResult {
        if (!file.isFile) return BoundaryNormalizationResult(0L, 0L, 0L)

        val bytes = file.readBytes()
        val wav = parse(bytes) ?: return BoundaryNormalizationResult(0L, 0L, 0L)
        if (wav.bitsPerSample != 16 || wav.blockAlign <= 0 || wav.sampleRate <= 0) {
            return BoundaryNormalizationResult(0L, 0L, 0L)
        }

        val trailingFrames = countTrailingSilentFrames(bytes, wav)
        val originalMs = framesToMs(trailingFrames, wav.sampleRate)
        val safeMin = minMs.coerceIn(0, MAX_EDIT_MS)
        val safeTarget = targetMs.coerceIn(safeMin, MAX_EDIT_MS)
        val safeMax = maxMs.coerceIn(safeTarget, MAX_EDIT_MS)

        return when {
            originalMs < safeMin -> addSilence(
                file, bytes, wav, trailingFrames, originalMs, safeTarget,
            )
            originalMs > safeMax -> trimSilence(
                file, bytes, wav, trailingFrames, originalMs, safeTarget,
            )
            else -> BoundaryNormalizationResult(0L, originalMs, originalMs)
        }
    }

    /** Compatibility helper retained for old tests/callers. */
    fun appendSilence(file: File, pauseMs: Int): Long =
        normalizeTrailingSilence(file, pauseMs, pauseMs, pauseMs)
            .durationAdjustmentMs
            .coerceAtLeast(0L)

    private fun addSilence(
        file: File,
        bytes: ByteArray,
        wav: WavInfo,
        trailingFrames: Long,
        originalMs: Long,
        targetMs: Int,
    ): BoundaryNormalizationResult {
        val desiredFrames = msToFrames(targetMs.toLong(), wav.sampleRate)
        val addFrames = (desiredFrames - trailingFrames).coerceAtLeast(0L)
        val addBytesLong = addFrames * wav.blockAlign.toLong()
        if (addBytesLong <= 0L || addBytesLong > 4_000_000L) {
            return BoundaryNormalizationResult(0L, originalMs, originalMs)
        }

        val addBytes = addBytesLong.toInt()
        val dataEnd = wav.dataStart + wav.dataSize
        val output = ByteArray(bytes.size + addBytes)
        System.arraycopy(bytes, 0, output, 0, dataEnd)
        System.arraycopy(bytes, dataEnd, output, dataEnd + addBytes, bytes.size - dataEnd)
        putIntLe(output, wav.dataHeader + 4, wav.dataSize + addBytes)
        putIntLe(output, 4, output.size - 8)
        file.writeBytes(output)

        val addedMs = framesToMs(addFrames, wav.sampleRate)
        return BoundaryNormalizationResult(
            durationAdjustmentMs = addedMs,
            originalTrailingSilenceMs = originalMs,
            finalTrailingSilenceMs = originalMs + addedMs,
        )
    }

    private fun trimSilence(
        file: File,
        bytes: ByteArray,
        wav: WavInfo,
        trailingFrames: Long,
        originalMs: Long,
        targetMs: Int,
    ): BoundaryNormalizationResult {
        val keepFrames = msToFrames(targetMs.toLong(), wav.sampleRate)
            .coerceAtMost(trailingFrames)
        val removeFrames = (trailingFrames - keepFrames).coerceAtLeast(0L)
        val removeBytesLong = removeFrames * wav.blockAlign.toLong()
        if (removeBytesLong <= 0L || removeBytesLong >= wav.dataSize) {
            return BoundaryNormalizationResult(0L, originalMs, originalMs)
        }

        val removeBytes = removeBytesLong.toInt()
        val oldDataEnd = wav.dataStart + wav.dataSize
        val newDataSize = wav.dataSize - removeBytes
        val newDataEnd = wav.dataStart + newDataSize
        val output = ByteArray(bytes.size - removeBytes)
        System.arraycopy(bytes, 0, output, 0, newDataEnd)
        System.arraycopy(bytes, oldDataEnd, output, newDataEnd, bytes.size - oldDataEnd)
        putIntLe(output, wav.dataHeader + 4, newDataSize)
        putIntLe(output, 4, output.size - 8)
        file.writeBytes(output)

        val removedMs = framesToMs(removeFrames, wav.sampleRate)
        return BoundaryNormalizationResult(
            durationAdjustmentMs = -removedMs,
            originalTrailingSilenceMs = originalMs,
            finalTrailingSilenceMs = (originalMs - removedMs).coerceAtLeast(0L),
        )
    }

    private fun countTrailingSilentFrames(bytes: ByteArray, wav: WavInfo): Long {
        var frameStart = wav.dataStart + wav.dataSize - wav.blockAlign
        var frames = 0L
        while (frameStart >= wav.dataStart) {
            var silent = true
            var sampleOffset = frameStart
            val frameEnd = frameStart + wav.blockAlign
            while (sampleOffset + 1 < frameEnd) {
                val sample = shortLeSigned(bytes, sampleOffset)
                if (abs(sample.toInt()) > SILENCE_AMPLITUDE_THRESHOLD) {
                    silent = false
                    break
                }
                sampleOffset += 2
            }
            if (!silent) break
            frames += 1L
            frameStart -= wav.blockAlign
        }
        return frames
    }

    private fun parse(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            return null
        }

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

        if (dataHeader < 0 || dataStart < 0 || sampleRate <= 0 || blockAlign <= 0 || dataSize <= 0) {
            return null
        }
        return WavInfo(sampleRate, blockAlign, bitsPerSample, dataHeader, dataStart, dataSize)
    }

    private fun msToFrames(ms: Long, sampleRate: Int): Long =
        ((sampleRate.toLong() * ms) / 1000L).coerceAtLeast(0L)

    private fun framesToMs(frames: Long, sampleRate: Int): Long =
        if (sampleRate <= 0) 0L else (frames * 1000L) / sampleRate.toLong()

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > bytes.size) return ""
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortLeUnsigned(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun shortLeSigned(bytes: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short

    private fun putIntLe(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
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
