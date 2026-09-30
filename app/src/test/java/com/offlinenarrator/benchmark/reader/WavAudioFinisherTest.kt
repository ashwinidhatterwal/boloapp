package com.offlinenarrator.benchmark.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sin

class WavAudioFinisherTest {
    @Test
    fun trimsExcessLeadingAndTrailingSilenceWithoutCuttingSpeech() {
        val rate = 24_000
        val leading = rate / 4
        val speech = rate
        val trailing = rate
        val pcm = ShortArray(leading + speech + trailing)
        for (i in 0 until speech) {
            pcm[leading + i] = (sin(i / 13.0) * 7000).toInt().toShort()
        }
        val file = File.createTempFile("bolo-pacing", ".wav")
        file.writeBytes(wav(pcm, rate))

        val result = WavAudioFinisher.normalizeBoundarySilence(file, 145, 210, 310)
        assertTrue(result.originalLeadingSilenceMs >= 200)
        assertTrue(result.finalLeadingSilenceMs <= 105)
        assertTrue(result.originalTrailingSilenceMs >= 900)
        assertTrue(result.finalTrailingSilenceMs in 180..240)
        file.delete()
    }

    @Test
    fun findsAQuietInternalSentenceValley() {
        val rate = 24_000
        val speech = rate
        val valley = rate / 5
        val pcm = ShortArray(speech + valley + speech)
        for (i in 0 until speech) {
            pcm[i] = (sin(i / 11.0) * 7000).toInt().toShort()
            pcm[speech + valley + i] = (sin(i / 12.0) * 7000).toInt().toShort()
        }
        val file = File.createTempFile("bolo-anchor", ".wav")
        file.writeBytes(wav(pcm, rate))

        val anchors = WavAudioFinisher.detectBoundaryTimes(file, listOf(5L), 10L)
        assertEquals(1, anchors.size)
        assertTrue(anchors[0] in 850L..1_350L)
        file.delete()
    }

    private fun wav(pcm: ShortArray, rate: Int): ByteArray {
        val dataSize = pcm.size * 2
        val out = ByteArray(44 + dataSize)
        fun putInt(offset: Int, value: Int) {
            ByteBuffer.wrap(out, offset, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(value)
        }
        fun putShort(offset: Int, value: Short) {
            ByteBuffer.wrap(out, offset, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(value)
        }
        "RIFF".toByteArray().copyInto(out, 0)
        putInt(4, 36 + dataSize)
        "WAVE".toByteArray().copyInto(out, 8)
        "fmt ".toByteArray().copyInto(out, 12)
        putInt(16, 16)
        putShort(20, 1)
        putShort(22, 1)
        putInt(24, rate)
        putInt(28, rate * 2)
        putShort(32, 2)
        putShort(34, 16)
        "data".toByteArray().copyInto(out, 36)
        putInt(40, dataSize)
        pcm.forEachIndexed { index, value -> putShort(44 + index * 2, value) }
        return out
    }
}
