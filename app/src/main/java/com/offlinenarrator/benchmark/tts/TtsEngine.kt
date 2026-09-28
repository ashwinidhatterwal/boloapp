package com.offlinenarrator.benchmark.tts

import java.io.File

data class TtsVoice(
    val id: String,
    val name: String,
    val language: String? = null,
)

data class SpeechRequest(
    val text: String,
    val speed: Float = 1.0f,
    val voiceId: String? = null,
)

data class SynthesisResult(
    val audioFile: File,
    val audioDurationMs: Long,
    val generationTimeMs: Long,
    val sampleRate: Int? = null,
) {
    val realTimeFactor: Double
        get() = if (audioDurationMs <= 0L) Double.NaN
        else generationTimeMs.toDouble() / audioDurationMs.toDouble()

    val generatedRealtimeMultiple: Double
        get() = if (generationTimeMs <= 0L) Double.NaN
        else audioDurationMs.toDouble() / generationTimeMs.toDouble()
}

interface TtsEngine {
    val id: String
    val displayName: String
    val description: String

    suspend fun initialize(): Result<Unit>
    fun isReady(): Boolean
    fun voices(): List<TtsVoice>
    suspend fun synthesize(request: SpeechRequest): Result<SynthesisResult>
    fun release()
}
