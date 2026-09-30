package com.offlinenarrator.benchmark.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavQualityInspectorTest {
    @Test
    fun healthySpeechLikePcmPassesGrossQc() {
        val file = wavFile(sampleRate = 24_000, seconds = 1, amplitude = 4_000)
        try {
            val report = WavQualityInspector.inspect(
                file = file,
                wordCount = 2,
                allowLongPause = false,
            )
            assertTrue(report.acceptable)
            assertTrue(report.score >= 90)
        } finally {
            file.delete()
        }
    }

    @Test
    fun silentRenderIsRejected() {
        val file = wavFile(sampleRate = 24_000, seconds = 1, amplitude = 0)
        try {
            val report = WavQualityInspector.inspect(
                file = file,
                wordCount = 2,
                allowLongPause = false,
            )
            assertFalse(report.acceptable)
            assertTrue(report.reasons.isNotEmpty())
        } finally {
            file.delete()
        }
    }

    private fun wavFile(sampleRate: Int, seconds: Int, amplitude: Int): File {
        val sampleCount = sampleRate * seconds
        val pcmBytes = sampleCount * 2
        val bytes = ByteArray(44 + pcmBytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + pcmBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)
        buffer.putShort(1)
        buffer.putShort(1)
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)
        buffer.putShort(2)
        buffer.putShort(16)
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(pcmBytes)
        repeat(sampleCount) {
            buffer.putShort(amplitude.toShort())
        }
        return File.createTempFile("bolo-qc-", ".wav").apply { writeBytes(bytes) }
    }
}
