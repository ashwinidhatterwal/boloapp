package com.offlinenarrator.benchmark.tts

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class SystemTtsEngine(
    private val context: Context,
) : TtsEngine {

    override val id: String = "system"
    override val displayName: String = "Android System TTS"
    override val description: String = "Baseline only. Quality depends on the TTS engine installed on this phone."

    private var tts: TextToSpeech? = null
    private var ready = false

    override suspend fun initialize(): Result<Unit> = runCatching {
        release()
        val init = CompletableDeferred<Int>()
        val instance = TextToSpeech(context.applicationContext) { status ->
            if (!init.isCompleted) init.complete(status)
        }
        val status = init.await()
        check(status == TextToSpeech.SUCCESS) { "Android TTS initialization failed ($status)" }
        tts = instance
        ready = true
    }

    override fun isReady(): Boolean = ready && tts != null

    override fun voices(): List<TtsVoice> = tts?.voices
        ?.sortedWith(
            compareBy<android.speech.tts.Voice> { voicePriority(it.locale?.toLanguageTag()) }
                .thenBy { it.name }
        )
        ?.map { voice ->
            TtsVoice(
                id = voice.name,
                name = voice.name,
                language = voice.locale?.toLanguageTag(),
            )
        }
        .orEmpty()

    private fun voicePriority(tag: String?): Int {
        val normalized = tag?.lowercase().orEmpty()
        return when {
            normalized == "en-in" -> 0
            normalized == "en-us" -> 1
            normalized == "en-gb" -> 2
            normalized.startsWith("en-") || normalized == "en" -> 3
            else -> 10
        }
    }

    override suspend fun synthesize(request: SpeechRequest): Result<SynthesisResult> = runCatching {
        val engine = checkNotNull(tts) { "Android TTS is not initialized" }
        check(ready) { "Android TTS is not ready" }
        check(request.text.isNotBlank()) { "Text is empty" }

        request.voiceId?.let { requested ->
            engine.voices?.firstOrNull { it.name == requested }?.let { engine.voice = it }
        }
        engine.setSpeechRate(request.speed.coerceIn(0.5f, 2.0f))

        val outDir = File(context.cacheDir, "benchmark-audio").apply { mkdirs() }
        val output = File(outDir, "system-${System.currentTimeMillis()}.wav")
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) {
                if (id == utteranceId && !completion.isCompleted) completion.complete(Unit)
            }
            @Deprecated("Deprecated in Android")
            override fun onError(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(IllegalStateException("System TTS synthesis failed"))
                }
            }
            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(
                        IllegalStateException("System TTS synthesis failed ($errorCode)")
                    )
                }
            }
        })

        val started = System.nanoTime()
        val resultCode = engine.synthesizeToFile(
            request.text,
            Bundle.EMPTY,
            output,
            utteranceId,
        )
        check(resultCode == TextToSpeech.SUCCESS) { "synthesizeToFile rejected request ($resultCode)" }
        completion.await()
        val generationMs = (System.nanoTime() - started) / 1_000_000L
        val durationMs = withContext(Dispatchers.IO) { mediaDuration(output) }

        SynthesisResult(
            audioFile = output,
            audioDurationMs = durationMs,
            generationTimeMs = generationMs,
            sampleRate = null,
        )
    }

    private fun mediaDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    override fun cancel() {
        runCatching { tts?.stop() }
    }

    override fun release() {
        ready = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
    }
}
