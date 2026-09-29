package com.offlinenarrator.benchmark.tts

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID

/**
 * Benchmark adapter for the separately-installed Pocket TTS Android system engine.
 *
 * Keeping Pocket in a separate Android TTS service is intentional for Phase 0:
 * its native runtime/model lifecycle remains isolated from Bolo, while Bolo still
 * benchmarks it through the same TtsEngine contract used by Kokoro.
 */
class PocketTtsEngine(
    private val context: Context,
) : TtsEngine {

    override val id: String = "pocket"
    override val displayName: String = "Pocket TTS (local)"
    override val description: String =
        "Pocket TTS runs in its own on-device Android TTS service. Install the Pocket engine and import the English model pack once."

    private var tts: TextToSpeech? = null
    private var ready = false

    override suspend fun initialize(): Result<Unit> = runCatching {
        release()

        val serviceIntent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            .setPackage(ENGINE_PACKAGE)
        val available = context.packageManager.queryIntentServices(serviceIntent, 0).isNotEmpty()
        check(available) {
            "Pocket TTS engine is not installed. Use the Pocket setup buttons below."
        }

        val init = CompletableDeferred<Int>()
        val instance = TextToSpeech(
            context.applicationContext,
            { status -> if (!init.isCompleted) init.complete(status) },
            ENGINE_PACKAGE,
        )

        val status = withTimeout(10_000L) { init.await() }
        check(status == TextToSpeech.SUCCESS) {
            "Pocket TTS initialization failed ($status)"
        }

        tts = instance

        val availableVoices = instance.voices.orEmpty()
            .filter { it.locale?.language.equals("en", ignoreCase = true) }
            .sortedBy { it.name }

        check(availableVoices.isNotEmpty()) {
            "Pocket TTS is installed, but no English model pack/voice is available. Open Pocket TTS and import the English FP32 pack."
        }

        instance.voice = availableVoices.first()
        ready = true
    }.onFailure {
        ready = false
        runCatching { tts?.shutdown() }
        tts = null
    }

    override fun isReady(): Boolean = ready && tts != null

    override fun voices(): List<TtsVoice> = tts?.voices
        .orEmpty()
        .filter { it.locale?.language.equals("en", ignoreCase = true) }
        .sortedBy { it.name }
        .map { voice ->
            TtsVoice(
                id = voice.name,
                name = voice.name,
                language = voice.locale?.toLanguageTag(),
            )
        }

    override suspend fun synthesize(request: SpeechRequest): Result<SynthesisResult> = runCatching {
        val engine = checkNotNull(tts) { "Pocket TTS is not initialized" }
        check(ready) { "Pocket TTS is not ready" }
        check(request.text.isNotBlank()) { "Text is empty" }

        request.voiceId?.let { requested ->
            engine.voices?.firstOrNull { it.name == requested }?.let { engine.voice = it }
        }

        // The Pocket service controls its own generation parameters. Android's
        // speech-rate parameter is still forwarded for API consistency.
        engine.setSpeechRate(request.speed.coerceIn(0.5f, 2.0f))

        val outDir = File(context.cacheDir, "benchmark-audio").apply { mkdirs() }
        val output = File(outDir, "pocket-${System.currentTimeMillis()}.wav")
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.complete(Unit)
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onError(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(
                        IllegalStateException("Pocket TTS synthesis failed")
                    )
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(
                        IllegalStateException("Pocket TTS synthesis failed ($errorCode)")
                    )
                }
            }

            override fun onStop(id: String?, interrupted: Boolean) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(
                        IllegalStateException("Pocket TTS synthesis stopped")
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
        check(resultCode == TextToSpeech.SUCCESS) {
            "Pocket TTS rejected synthesizeToFile ($resultCode)"
        }

        completion.await()
        val generationMs = (System.nanoTime() - started) / 1_000_000L
        val durationMs = withContext(Dispatchers.IO) { mediaDuration(output) }

        SynthesisResult(
            audioFile = output,
            audioDurationMs = durationMs,
            generationTimeMs = generationMs,
            sampleRate = 24_000,
        )
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

    private fun mediaDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    companion object {
        const val ENGINE_PACKAGE = "org.pockettts.android.engine"
    }
}
