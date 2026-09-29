package com.offlinenarrator.benchmark.reader

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Adds deterministic trailing silence to a PCM WAV without re-encoding. */
object WavAudioFinisher {
    fun appendSilence(
        file: File,
        pauseMs: Int,
    ): Long {
        if (pauseMs <= 0 || !file.isFile) return 0L
        val bytes = file.readBytes()
        if (bytes.size < 44 || ascii(bytes, 0, 4) != "RIFF" || ascii(bytes, 8, 4) != "WAVE") {
            return 0L
        }

        var offset = 12
        var blockAlign = 0
        var sampleRate = 0
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
                    blockAlign = shortLe(bytes, payload + 12)
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

        if (dataHeader < 0 || dataStart < 0 || sampleRate <= 0 || blockAlign <= 0) return 0L

        val frames = ((sampleRate.toLong() * pauseMs.toLong()) / 1000L).coerceAtLeast(1L)
        val silenceBytesLong = frames * blockAlign.toLong()
        if (silenceBytesLong > 2_000_000L) return 0L
        val silenceBytes = silenceBytesLong.toInt()

        val dataEnd = (dataStart + dataSize).coerceAtMost(bytes.size)
        val output = ByteArray(bytes.size + silenceBytes)
        System.arraycopy(bytes, 0, output, 0, dataEnd)
        // New ByteArray bytes are zero: PCM silence.
        System.arraycopy(bytes, dataEnd, output, dataEnd + silenceBytes, bytes.size - dataEnd)

        putIntLe(output, dataHeader + 4, dataSize + silenceBytes)
        putIntLe(output, 4, output.size - 8)
        file.writeBytes(output)
        return (frames * 1000L) / sampleRate.toLong()
    }

    private fun ascii(bytes: ByteArray, offset: Int, length: Int): String {
        if (offset < 0 || offset + length > bytes.size) return ""
        return String(bytes, offset, length, Charsets.US_ASCII)
    }

    private fun intLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun shortLe(bytes: ByteArray, offset: Int): Int =
        ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun putIntLe(bytes: ByteArray, offset: Int, value: Int) {
        ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
    }
}
