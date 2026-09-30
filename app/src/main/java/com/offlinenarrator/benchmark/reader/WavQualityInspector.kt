package com.offlinenarrator.benchmark.reader

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.sqrt

data class NarrationQcReport(
    val acceptable: Boolean,
    val score: Int,
    val durationMs: Long,
    val millisecondsPerWord: Double,
    val peakAmplitude: Int,
    val rmsAmplitude: Double,
    val clippedFraction: Double,
    val longestQuietRunMs: Long,
    val reasons: List<String>,
)

/**
 * Cheap, deterministic QC for prepared speech. It cannot judge acting, but it
 * catches the kinds of gross synthesis failures that should never reach the
 * listener: near-silence, extreme duration, clipping and implausibly long
 * internal gaps. Failed chunks can be regenerated before playback begins.
 */
object WavQualityInspector {
    private const val QUIET_THRESHOLD = 520

    fun inspect(
        file: File,
        wordCount: Long,
        allowLongPause: Boolean,
    ): NarrationQcReport {
        if (!file.isFile) return failed("audio file missing")
        val bytes = file.readBytes()
        val wav = parse(bytes) ?: return failed("invalid WAV")
        if (wav.bitsPerSample != 16 || wav.sampleRate <= 0 || wav.blockAlign <= 0) {
            return failed("unsupported PCM format")
        }

        val frames = wav.dataSize / wav.blockAlign
        if (frames <= 0) return failed("empty PCM")
        val durationMs = frames.toLong() * 1000L / wav.sampleRate.toLong()
        val safeWords = wordCount.coerceAtLeast(1L)
        val msPerWord = durationMs.toDouble() / safeWords.toDouble()

        var peak = 0
        var sumSquares = 0.0
        var sampleCount = 0L
        var clipped = 0L
        var currentQuietFrames = 0L
        var longestQuietFrames = 0L

        var frame = 0
        while (frame < frames) {
            val start = wav.dataStart + frame * wav.blockAlign
            val end = start + wav.blockAlign
            var off = start
            var framePeak = 0
            while (off + 1 < end) {
                val sample = shortLe(bytes, off).toInt()
                val amplitude = abs(sample)
                if (amplitude > peak) peak = amplitude
                if (amplitude > framePeak) framePeak = amplitude
                if (amplitude >= 32760) clipped += 1L
                sumSquares += sample.toDouble() * sample.toDouble()
                sampleCount += 1L
                off += 2
            }

            if (framePeak <= QUIET_THRESHOLD) {
                currentQuietFrames += 1L
                if (currentQuietFrames > longestQuietFrames) {
                    longestQuietFrames = currentQuietFrames
                }
            } else {
                currentQuietFrames = 0L
            }
            frame += 1
        }

        val rms = if (sampleCount > 0L) sqrt(sumSquares / sampleCount.toDouble()) else 0.0
        val clippedFraction = if (sampleCount > 0L) {
            clipped.toDouble() / sampleCount.toDouble()
        } else 0.0
        val longestQuietMs = longestQuietFrames * 1000L / wav.sampleRate.toLong()

        val reasons = mutableListOf<String>()
        // Wide limits on purpose. QC should catch broken synthesis, not reject
        // valid dramatic pacing.
        if (durationMs < 220L) reasons += "implausibly short audio"
        if (msPerWord < 170.0) reasons += "speech is implausibly rushed"
        if (msPerWord > 1_250.0) reasons += "speech is implausibly stretched"
        if (peak < 1_000) reasons += "audio is nearly silent"
        if (rms < 180.0) reasons += "audio energy is abnormally low"
        if (clippedFraction > 0.004) reasons += "excessive clipping"
        val quietLimit = if (allowLongPause) 2_400L else 1_650L
        if (longestQuietMs > quietLimit) reasons += "unexpected long internal silence"

        var score = 100
        score -= reasons.size * 22
        if (msPerWord in 300.0..900.0) score += 4
        if (clippedFraction < 0.0002) score += 2
        score = score.coerceIn(0, 100)

        return NarrationQcReport(
            acceptable = reasons.isEmpty(),
            score = score,
            durationMs = durationMs,
            millisecondsPerWord = msPerWord,
            peakAmplitude = peak,
            rmsAmplitude = rms,
            clippedFraction = clippedFraction,
            longestQuietRunMs = longestQuietMs,
            reasons = reasons,
        )
    }

    private fun failed(reason: String): NarrationQcReport = NarrationQcReport(
        acceptable = false,
        score = 0,
        durationMs = 0L,
        millisecondsPerWord = Double.NaN,
        peakAmplitude = 0,
        rmsAmplitude = 0.0,
        clippedFraction = 0.0,
        longestQuietRunMs = 0L,
        reasons = listOf(reason),
    )

    private fun parse(bytes: ByteArray): WavInfo? {
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            return null
        }
        var offset = 12
        var sampleRate = 0
        var blockAlign = 0
        var bitsPerSample = 0
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
                    blockAlign = shortUnsigned(bytes, payload + 12)
                    bitsPerSample = shortUnsigned(bytes, payload + 14)
                }
                "data" -> {
                    dataStart = payload
                    dataSize = size.coerceAtMost(bytes.size - payload)
                    break
                }
            }
            offset = payload + size + (size and 1)
        }

        if (sampleRate <= 0 || blockAlign <= 0 || dataStart < 0 || dataSize <= 0) return null
        return WavInfo(sampleRate, blockAlign, bitsPerSample, dataStart, dataSize)
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String =
        if (offset < 0 || offset + length > bytes.size) ""
        else String(bytes, offset, length, Charsets.US_ASCII)

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortUnsigned(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun shortLe(bytes: ByteArray, offset: Int): Short =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short

    private data class WavInfo(
        val sampleRate: Int,
        val blockAlign: Int,
        val bitsPerSample: Int,
        val dataStart: Int,
        val dataSize: Int,
    )
}
