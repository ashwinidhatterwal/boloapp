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

class PocketTtsEngine(
    private val context: Context,
) : TtsEngine {

    override val id: String = "pocket"
    override val displayName: String = "Pocket TTS (local)"
    override val description: String =
        "Pocket TTS runs in its own on-device Android TTS service. v0.9 records native-engine and first-audio timing."

    private var tts: TextToSpeech? = null
    private var ready = false

    private val requestStartedMs = AtomicLong(0L)
    private val engineStartedMs = AtomicLong(-1L)
    private val firstAudioMs = AtomicLong(-1L)
    private val completedMs = AtomicLong(-1L)
    private val lastStage = AtomicReference("idle")

    override suspend fun initialize(): Result<Unit> = runCatching {
        release()

        val serviceIntent = Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
            .setPackage(ENGINE_PACKAGE)
        val available = context.packageManager.queryIntentServices(serviceIntent, 0).isNotEmpty()
        check(available) {
            "Pocket TTS engine is not installed."
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
            "Pocket TTS is installed, but no English model pack/voice is available."
        }

        instance.voice = availableVoices.first()
        ready = true
        lastStage.set("Android TTS connection ready; Pocket voice discovered")
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
        val engine = checkNotNull(tts) { "Pocket TTS is not initialized" }
        check(ready) { "Pocket TTS is not ready" }
        check(request.text.isNotBlank()) { "Text is empty" }

        request.voiceId?.let { requested ->
            engine.voices?.firstOrNull { it.name == requested }?.let { engine.voice = it }
        }
        engine.setSpeechRate(request.speed.coerceIn(0.5f, 2.0f))

        val outDir = File(context.cacheDir, "benchmark-audio").apply { mkdirs() }
        val output = File(outDir, "pocket-${System.currentTimeMillis()}.wav")
        val utteranceId = UUID.randomUUID().toString()
        val completion = CompletableDeferred<Unit>()

        val start = SystemClock.elapsedRealtime()
        requestStartedMs.set(start)
        engineStartedMs.set(-1L)
        firstAudioMs.set(-1L)
        completedMs.set(-1L)
        lastStage.set("request submitted; waiting for Pocket native engine")

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id == utteranceId) {
                    engineStartedMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    lastStage.set("Pocket native engine ready; waiting for audio")
                }
            }

            override fun onBeginSynthesis(
                utteranceIdValue: String?,
                sampleRateInHz: Int,
                audioFormat: Int,
                channelCount: Int,
            ) {
                if (utteranceIdValue == utteranceId) {
                    engineStartedMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    lastStage.set("synthesis began at ${sampleRateInHz} Hz; waiting for first audio")
                }
            }

            override fun onAudioAvailable(id: String?, audio: ByteArray?) {
                if (id == utteranceId && !audio.isNullOrEmpty()) {
                    firstAudioMs.compareAndSet(-1L, SystemClock.elapsedRealtime())
                    lastStage.set("audio is streaming; waiting for completion")
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
                    lastStage.set("Pocket service reported synthesis error")
                    completion.completeExceptionally(
                        IllegalStateException("Pocket TTS synthesis failed")
                    )
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId && !completion.isCompleted) {
                    lastStage.set("Pocket service error $errorCode")
                    completion.completeExceptionally(
                        IllegalStateException("Pocket TTS synthesis failed ($errorCode)")
                    )
                }
            }

            override fun onStop(id: String?, interrupted: Boolean) {
                if (id == utteranceId && !completion.isCompleted) {
                    lastStage.set("Pocket synthesis stopped; interrupted=$interrupted")
                    completion.completeExceptionally(
                        IllegalStateException("Pocket TTS synthesis stopped")
                    )
                }
            }
        })

        val callStarted = System.nanoTime()
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
        val generationMs = (System.nanoTime() - callStarted) / 1_000_000L
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
        val native = engineStartedMs.get()
        val audio = firstAudioMs.get()
        val done = completedMs.get()

        fun delta(value: Long): String =
            if (value < 0L) "not reached" else "${(value - start) / 1000.0}s"

        return buildString {
            append(lastStage.get())
            append(" | native-start=").append(delta(native))
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
        const val ENGINE_PACKAGE = "org.pockettts.android.engine"
    }
}
