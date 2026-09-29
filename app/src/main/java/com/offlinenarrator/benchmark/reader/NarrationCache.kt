package com.offlinenarrator.benchmark.reader

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class CachedNarration(
    val file: File,
    val durationMs: Long,
)

class NarrationCache(context: Context) {
    private val dir = File(context.filesDir, "narration-cache").apply { mkdirs() }

    suspend fun get(
        modelSha: String,
        voiceId: String,
        text: String,
    ): CachedNarration? = withContext(Dispatchers.IO) {
        val key = key(modelSha, voiceId, text)
        val wav = File(dir, "$key.wav")
        val meta = File(dir, "$key.duration")

        if (!wav.isFile || !meta.isFile) return@withContext null
        val duration = meta.readText().trim().toLongOrNull()
            ?.takeIf { it > 0L }
            ?: return@withContext null

        val now = System.currentTimeMillis()
        wav.setLastModified(now)
        meta.setLastModified(now)

        CachedNarration(wav, duration)
    }

    suspend fun put(
        modelSha: String,
        voiceId: String,
        text: String,
        source: File,
        durationMs: Long,
    ): CachedNarration = withContext(Dispatchers.IO) {
        val key = key(modelSha, voiceId, text)
        val wav = File(dir, "$key.wav")
        val meta = File(dir, "$key.duration")

        if (wav.exists()) wav.delete()
        source.copyTo(wav, overwrite = true)
        meta.writeText(durationMs.toString())
        source.delete()

        trimTo(MAX_CACHE_BYTES)
        CachedNarration(wav, durationMs)
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        dir.listFiles()?.forEach { it.delete() }
    }

    suspend fun sizeBytes(): Long = withContext(Dispatchers.IO) {
        dir.listFiles()?.sumOf { it.length() } ?: 0L
    }

    private fun trimTo(maxBytes: Long) {
        val wavs = dir.listFiles()
            ?.filter { it.isFile && it.extension == "wav" }
            ?.sortedByDescending { it.lastModified() }
            ?: return

        var total = wavs.sumOf { it.length() }
        if (total <= maxBytes) return

        for (wav in wavs.asReversed()) {
            if (total <= maxBytes) break
            total -= wav.length()
            val key = wav.nameWithoutExtension
            wav.delete()
            File(dir, "$key.duration").delete()
        }
    }

    private fun key(
        modelSha: String,
        voiceId: String,
        text: String,
    ): String {
        val payload = buildString {
            append("kokoro-reader-v3-natural-narrator\n")
            append(modelSha)
            append('\n')
            append(voiceId)
            append('\n')
            append(text)
        }

        val digest = MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_CACHE_BYTES = 512L * 1024L * 1024L
    }
}
