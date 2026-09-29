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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Adapter for Bolo's separately installed Supertonic 3 LiteRT companion engine.
 *
 * Keeping the Supertonic runtime in a separate process avoids native-runtime
 * conflicts with the benchmark Kokoro/ONNX stack and keeps the Bolo UI/app
 * small. The companion app owns model download and storage.
 */
class SupertonicTtsEngine(
    private val context: Context,
) : TtsEngine {

    override val id: String = "supertonic"
    override val displayName: String = "Supertonic 3"
    override val description: String =
        "LiteRT · 44.1 kHz · local multilingual neural TTS."

    private var tts: TextToSpeech? = null
    private var ready = false
    private val lastSampleRate = AtomicInteger(44_100)

    override suspend fun initialize(): Result<Unit> = runCatching {
        release()

        val serviceIntent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            .setPackage(ENGINE_PACKAGE)

        val available = context.packageManager
            .queryIntentServices(serviceIntent, 0)
            .isNotEmpty()

        check(available) {
            "Install the Bolo Supertonic Engine APK, then open it once to download the model."
        }

        val init = CompletableDeferred<Int>()
        val instance = TextToSpeech(
            context.applicationContext,
            { status -> if (!init.isCompleted) init.complete(status) },
            ENGINE_PACKAGE,
        )

        val status = withTimeout(20_000L) { init.await() }
        check(status == TextToSpeech.SUCCESS) {
            "Supertonic engine initialization failed ($status)"
        }

        tts = instance
        val voices = englishVoices(instance)
        check(voices.isNotEmpty()) {
            "Supertonic engine is installed but exposes no English voices."
        }

        val preferred = voices.firstOrNull { it.name.endsWith("-F3") }
            ?: voices.first()
        instance.voice = preferred
        ready = true
    }.onFailure {
        ready = false
        runCatching { tts?.shutdown() }
        tts = null
    }

    override fun isReady(): Boolean = ready && tts != null

    override fun voices(): List<TtsVoice> =
        tts?.let(::englishVoices)
            .orEmpty()
            .map { voice ->
                val short = voice.name.substringAfterLast("-")
                TtsVoice(
                    id = voice.name,
                    name = short,
                    language = "en-US",
                )
            }

    override suspend fun synthesize(request: SpeechRequest): Result<SynthesisResult> = runCatching {
        val engine = checkNotNull(tts) { "Supertonic is not initialized" }
        check(ready) { "Supertonic is not ready" }
        check(request.text.isNotBlank()) { "Text is empty" }

        request.voiceId?.let { wanted ->
            engine.voices?.firstOrNull { it.name == wanted }?.let { engine.voice = it }
        }

        // The Supertonic companion uses its model-native speed for a fair
        // quality/efficiency comparison. Playback speed belongs in the reader.
        engine.setSpeechRate(1.0f)

        val outDir = File(context.cacheDir, "benchmark-audio").apply { mkdirs() }
        val output = File(outDir, "supertonic-${System.currentTimeMillis()}.wav")
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit

            override fun onBeginSynthesis(
                id: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                if (id == utteranceId && sampleRateInHz > 0) {
                    lastSampleRate.set(sampleRateInHz)
                }
            }

            override fun onAudioAvailable(id: String?, audio: ByteArray) = Unit

            override fun onDone(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.complete(Unit)
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onError(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(
                        IllegalStateException(
                            "Supertonic synthesis failed. Open the Supertonic Engine app and verify the model first."
                        )
                    )
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(
                        IllegalStateException("Supertonic synthesis failed ($errorCode)")
                    )
                }
            }

            override fun onStop(id: String?, interrupted: Boolean) {
                if (id == utteranceId && !completion.isCompleted) {
                    completion.completeExceptionally(
                        IllegalStateException("Supertonic synthesis stopped")
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
            "Supertonic rejected the synthesis request ($resultCode)"
        }

        withTimeout(180_000L) { completion.await() }

        val generationMs = (System.nanoTime() - started) / 1_000_000L
        val durationMs = withContext(Dispatchers.IO) { mediaDuration(output) }

        SynthesisResult(
            audioFile = output,
            audioDurationMs = durationMs,
            generationTimeMs = generationMs,
            sampleRate = lastSampleRate.get(),
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

    private fun englishVoices(engine: TextToSpeech) =
        engine.voices
            .orEmpty()
            .filter {
                it.locale?.language.equals("en", ignoreCase = true) &&
                    it.name.contains("supertonic", ignoreCase = true)
            }
            .sortedBy { it.name }

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
        const val ENGINE_PACKAGE = "com.offlinenarrator.supertonicengine"
    }
}
