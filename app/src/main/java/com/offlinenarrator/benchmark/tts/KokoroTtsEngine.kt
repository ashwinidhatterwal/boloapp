package com.offlinenarrator.benchmark.tts

import android.content.Context
import com.offlinenarrator.benchmark.model.KokoroModelStore
import dev.ffmpegkit.kokoro.KokoroConfig
import dev.ffmpegkit.kokoro.KokoroTTS
import dev.ffmpegkit.kokoro.KokoroRuntimeProfile
import java.io.File

class KokoroTtsEngine(
    private val context: Context,
    private val modelStore: KokoroModelStore,
    private val runtimeProfile: KokoroRuntimeProfile = KokoroRuntimeProfile.CPU_BASELINE,
) : TtsEngine {

    override val id: String = "kokoro"
    override val displayName: String = "Kokoro 82M"
    override val description: String = "Natural on-device narration."

    private var ready = false

    override suspend fun initialize(): Result<Unit> = runCatching {
        check(modelStore.exists()) { "Import a Kokoro ONNX model first" }
        runCatching { KokoroTTS.release() }
        KokoroTTS.initialize(
            context.applicationContext,
            modelStore.modelFile.absolutePath,
            runtimeProfile = runtimeProfile,
        )
        ready = true
    }.onFailure {
        ready = false
        runCatching { KokoroTTS.release() }
    }

    override fun isReady(): Boolean = ready

    override fun voices(): List<TtsVoice> =
        KokoroTTS.getAvailableVoices().map {
            TtsVoice(id = it.id, name = it.name, language = it.language)
        }

    override suspend fun synthesize(request: SpeechRequest): Result<SynthesisResult> = runCatching {
        check(ready) { "Kokoro is not initialized" }
        check(request.text.isNotBlank()) { "Text is empty" }

        request.voiceId?.let { requested ->
            val blend = parseBlendVoiceId(requested)
            if (blend != null) {
                val voices = KokoroTTS.getAvailableVoices()
                val base = voices.firstOrNull { it.id == blend.baseVoiceId }
                val accent = voices.firstOrNull { it.id == blend.accentVoiceId }
                if (base != null && accent != null) {
                    KokoroTTS.setVoiceBlend(base, accent, blend.accentWeight)
                } else {
                    base?.let(KokoroTTS::setVoice)
                }
            } else {
                KokoroTTS.getAvailableVoices()
                    .firstOrNull { it.id == requested }
                    ?.let(KokoroTTS::setVoice)
            }
        }

        val started = System.nanoTime()
        val result = KokoroTTS.speak(
            request.text,
            KokoroConfig(speed = request.speed.coerceIn(0.5f, 2.0f)),
        )
        val generationMs = (System.nanoTime() - started) / 1_000_000L

        val outDir = File(context.cacheDir, "reader-render").apply { mkdirs() }
        val output = File(outDir, "kokoro-${System.currentTimeMillis()}.wav")
        output.writeBytes(result.audioData)

        SynthesisResult(
            audioFile = output,
            audioDurationMs = result.durationMs,
            generationTimeMs = generationMs,
            sampleRate = result.sampleRate,
        )
    }

    fun subtleCharacterVoiceId(
        narratorVoiceId: String,
        characterVoiceId: String,
        accentWeight: Float = 0.14f,
    ): String {
        if (narratorVoiceId == characterVoiceId) return narratorVoiceId
        val percent = (accentWeight.coerceIn(0.05f, 0.30f) * 100f).toInt()
        return "blend:$narratorVoiceId:$characterVoiceId:$percent"
    }

    private fun parseBlendVoiceId(value: String): BlendVoice? {
        if (!value.startsWith("blend:")) return null
        val parts = value.split(':')
        if (parts.size != 4) return null
        val weight = parts[3].toIntOrNull()?.div(100f) ?: return null
        return BlendVoice(
            baseVoiceId = parts[1],
            accentVoiceId = parts[2],
            accentWeight = weight.coerceIn(0.05f, 0.30f),
        )
    }

    private data class BlendVoice(
        val baseVoiceId: String,
        val accentVoiceId: String,
        val accentWeight: Float,
    )

    override fun release() {
        ready = false
        runCatching { KokoroTTS.release() }
    }
}
