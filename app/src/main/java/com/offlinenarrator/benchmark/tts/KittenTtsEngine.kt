package com.offlinenarrator.benchmark.tts

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Phase-0 adapter for the separately installed KittenTTS Android engine.
 *
 * The Kitten engine app owns the Nano model, ONNX runtime, phonemizer and native
 * lifecycle. Bolo only benchmarks it through the shared TtsEngine contract.
 */
class KittenTtsEngine(
    private val context: Context,
) : TtsEngine {

    override val id: String = "kitten"
    override val displayName: String = "KittenTTS Nano (local)"
    override val description: String =
        "15M-parameter KittenTTS Nano through a local Android TTS service. Model is bundled by the Kitten engine app."

    private var tts: TextToSpeech? = null
    private var ready = false

    private val requestStartedMs = AtomicLong(0L)
    private val serviceStartedMs = AtomicLong(-1L)
    private val firstAudioMs = AtomicLong(-1L)
    private val completedMs = AtomicLong(-1L)
    private val lastStage = AtomicReference("idle")

    override suspend fun initialize(): Result<Unit> = runCatching {
        release()

        val serviceIntent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            .setPackage(ENGINE_PACKAGE)
        val available = context.packageManager.queryIntentServices(serviceIntent, 0).isNotEmpty()
        check(available) {
            "KittenTTS engine is not installed. Install the Kitten Android engine first."
        }

        val init = CompletableDeferred<Int>()
        val instance = TextToSpeech(
            context.applicationContext,
            { status -> if (!init.isCompleted) init.complete(status) },
            ENGINE_PACKAGE,
        )

        val status = withTimeout(15_000L) { init.await() }
        check(status == TextToSpeech.SUCCESS) {
            "KittenTTS initialization failed ($status)"
        }

        tts = instance

        val voices = instance.voices.orEmpty()
            .filter { it.locale?.language.equals("en", ignoreCase = true) }
            .sortedBy { it.name }

        check(voices.isNotEmpty()) {
            "KittenTTS installed, but no English voices were exposed."
        }

        val preferred = voices.firstOrNull { it.name.equals("Bella", ignoreCase = true) }
            ?: voices.first()
        instance.voice = preferred
        ready = true
        lastStage.set("Android TTS connection ready; Kitten voice discovered")
    }.onFailure {
        ready = false
        runCatching { tts?.shutdown() }
        tts = null
        lastStage.set("initialization failed: ${it.message}")
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
        val engine = checkNotNull(tts) { "KittenTTS is not initialized" }
        check(ready) { "KittenTTS is not ready" }
        check(request.text.isNotBlank()) { "Text is empty" }

        request.voiceId?.let { requested ->
            engine.voices?.firstOrNull { it.name == requested }?.let { engine.voice = it }
        }
        engine.setSpeechRate(request.speed.coerceIn(0.5f, 2.0f))

        val outDir = File(context.cacheDir, "benchmark-audio").apply { mkdirs() }
        val output = File(outDir, "kitten-${System.currentTimeMillis()}.wav")
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()

        val start = SystemClock.elapsedRealtime()
        requestStartedMs.set(start)
        serviceStartedMs.set(-1L)
        firstAudioMs.set(-1L)
        completedMs.set(-1L)
        lastStage.set("request submitted; waiting for Kitten service")

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id == utteranceId) {
                    serviceStartedMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    lastStage.set("Kitten service accepted request; waiting for audio")
                }
            }

            override fun onBeginSynthesis(
                utteranceIdValue: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                if (utteranceIdValue == utteranceId) {
                    serviceStartedMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    lastStage.set("synthesis began at ${sampleRateInHz} Hz; waiting for first audio")
                }
            }

            override fun onAudioAvailable(id: String?, audio: ByteArray) {
                if (id == utteranceId && audio.isNotEmpty()) {
                    firstAudioMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    lastStage.set("audio streaming; waiting for completion")
                }
            }

            override fun onDone(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    completedMs.set(SystemClock.elapsedRealtime())
                    lastStage.set("completed")
                    completion.complete(Unit)
                }
            }

            @Deprecated("Deprecated in Android")
            override fun onError(id: String?) {
                if (id == utteranceId && !completion.isCompleted) {
                    lastStage.set("Kitten service reported synthesis error")
                    completion.completeExceptionally(
                        IllegalStateException("KittenTTS synthesis failed")
                    )
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && !completion.isCompleted) {
                    lastStage.set("Kitten service error $errorCode")
                    completion.completeExceptionally(
                        IllegalStateException("KittenTTS synthesis failed ($errorCode)")
                    )
                }
            }

            override fun onStop(id: String?, interrupted: Boolean) {
                if (id == utteranceId && !completion.isCompleted) {
                    lastStage.set("Kitten synthesis stopped; interrupted=$interrupted")
                    completion.completeExceptionally(
                        IllegalStateException("KittenTTS synthesis stopped")
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
            "KittenTTS rejected synthesizeToFile ($resultCode)"
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
        lastStage.set("cancel requested")
        runCatching { tts?.stop() }
    }

    override fun diagnosticStatus(): String {
        val start = requestStartedMs.get()
        if (start <= 0L) return lastStage.get()

        val now = SystemClock.elapsedRealtime()
        val accepted = serviceStartedMs.get()
        val audio = firstAudioMs.get()
        val done = completedMs.get()

        fun delta(value: Long): String =
            if (value < 0L) "not reached" else "${(value - start) / 1000.0}s"

        return buildString {
            append(lastStage.get())
            append(" | service-start=").append(delta(accepted))
            append(" | first-audio=").append(delta(audio))
            append(" | done=").append(delta(done))
            append(" | elapsed=").append((now - start) / 1000.0).append("s")
        }
    }

    override fun release() {
        ready = false
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        lastStage.set("released")
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
        const val ENGINE_PACKAGE = "com.stellonlabs.kittentts"
    }
}
