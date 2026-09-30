package com.offlinenarrator.benchmark.reader

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** AAC-LC 64 kbps mono in M4A; WAV fallback is handled by the caller. */
object AacAudioEncoder {
    suspend fun encode(wav: File, output: File) {
        val bytes = wav.readBytes()
        fun int(offset: Int) = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.LITTLE_ENDIAN).int
        fun short(offset: Int) = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 65535
        var offset = 12; var rate = 0; var channels = 0; var start = -1; var size = 0
        while (offset + 8 <= bytes.size) {
            val count = int(offset + 4); require(count >= 0 && count <= bytes.size - offset - 8)
            when (String(bytes, offset, 4, Charsets.US_ASCII)) {
                "fmt " -> { require(count >= 16 && short(offset + 8) == 1 && short(offset + 22) == 16)
                    channels = short(offset + 10); rate = int(offset + 12) }
                "data" -> { start = offset + 8; size = count; break }
            }
            offset += count + 8 + (count and 1)
        }
        require(start >= 0 && size > 0 && channels == 1 && rate > 0)
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null; var muxerStarted = false; var codecStarted = false
        try {
            codec.configure(MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start(); codecStarted = true
            val targetMuxer = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = targetMuxer
            var track = -1; var cursor = 0; var inputDone = false; var outputDone = false
            val info = MediaCodec.BufferInfo()
            var lastProgress = System.nanoTime()
            while (!outputDone) {
                currentCoroutineContext().ensureActive()
                check((System.nanoTime() - lastProgress) < 30_000_000_000L) { "AAC encoder stalled" }
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!; buffer.clear()
                        val count = minOf(buffer.remaining(), size - cursor).let { it - it % 2 }
                        if (count > 0) buffer.put(bytes, start + cursor, count)
                        val pts = cursor.toLong() * 1_000_000L / (rate * channels * 2L)
                        val flags = if (cursor + count >= size) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                        codec.queueInputBuffer(index, 0, count, pts, flags)
                        cursor += count; inputDone = flags != 0; lastProgress = System.nanoTime()
                    }
                }
                when (val index = codec.dequeueOutputBuffer(info, 10_000)) {
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted); track = targetMuxer.addTrack(codec.outputFormat)
                        targetMuxer.start(); muxerStarted = true; lastProgress = System.nanoTime()
                    }
                    else -> if (index >= 0) {
                        val buffer = codec.getOutputBuffer(index)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            check(muxerStarted); buffer.position(info.offset); buffer.limit(info.offset + info.size)
                            targetMuxer.writeSampleData(track, buffer, info)
                        }
                        outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        codec.releaseOutputBuffer(index, false); lastProgress = System.nanoTime()
                    }
                }
            }
            check(muxerStarted && output.length() > 44)
        } finally {
            if (codecStarted) runCatching { codec.stop() }; codec.release()
            if (muxerStarted) runCatching { muxer?.stop() }; muxer?.release()
        }
    }
}
