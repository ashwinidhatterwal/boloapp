package com.offlinenarrator.supertonicengine

import android.media.AudioFormat
import android.speech.tts.SynthesisCallback
import android.speech.tts.SynthesisRequest
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeechService
import android.speech.tts.Voice
import audio.soniqo.speech.ModelManager
import audio.soniqo.speech.SpeechSynthesizer
import audio.soniqo.speech.SpeechSynthesizerConfig
import audio.soniqo.speech.TtsModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.util.Locale

class SupertonicTextToSpeechService : TextToSpeechService() {
    private val lock = Any()
    private var synthesizer: SpeechSynthesizer? = null

    private fun getSynthesizer(): SpeechSynthesizer = synchronized(lock) {
        synthesizer ?: runBlocking(Dispatchers.IO) {
            val dir = ModelManager.ensureTtsModels(
                applicationContext,
                TtsModel.SUPERTONIC,
            )
            SpeechSynthesizer(
                SpeechSynthesizerConfig(
                    modelDir = dir,
                    useNnapi = false,
                    ttsModel = TtsModel.SUPERTONIC,
                )
            ).also { synthesizer = it }
        }
    }

    override fun onGetLanguage(): Array<String> =
        arrayOf("eng", "USA", "")

    override fun onIsLanguageAvailable(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int {
        val base = lang?.lowercase(Locale.ROOT) ?: return TextToSpeech.LANG_NOT_SUPPORTED
        return if (base.startsWith("en") || base.startsWith("eng")) {
            TextToSpeech.LANG_COUNTRY_AVAILABLE
        } else {
            TextToSpeech.LANG_NOT_SUPPORTED
        }
    }

    override fun onLoadLanguage(
        lang: String?,
        country: String?,
        variant: String?,
    ): Int = onIsLanguageAvailable(lang, country, variant)

    override fun onGetVoices(): List<Voice> =
        (listOf("F1", "F2", "F3", "F4", "F5") +
            listOf("M1", "M2", "M3", "M4", "M5"))
            .map { id ->
                Voice(
                    "en-supertonic-$id",
                    Locale.US,
                    Voice.QUALITY_VERY_HIGH,
                    Voice.LATENCY_NORMAL,
                    false,
                    emptySet(),
                )
            }

    override fun onGetDefaultVoiceNameFor(
        lang: String?,
        country: String?,
        variant: String?,
    ): String = "en-supertonic-F3"

    override fun onLoadVoice(voiceName: String?): Int =
        if (voiceName != null && onGetVoices().any { it.name == voiceName }) {
            TextToSpeech.SUCCESS
        } else {
            TextToSpeech.ERROR
        }

    override fun onSynthesizeText(
        request: SynthesisRequest?,
        callback: SynthesisCallback?,
    ) {
        if (request == null || callback == null) return

        try {
            val text = request.charSequenceText?.toString().orEmpty()
            if (text.isBlank()) {
                callback.error()
                return
            }

            val voice = request.voiceName
                ?.substringAfterLast("-")
                ?.takeIf { it.matches(Regex("[FM][1-5]")) }
                ?: "F3"

            val synth = getSynthesizer()

            val result = runBlocking(Dispatchers.IO) {
                synth.synthesize(text, "en", voice)
            }

            callback.start(
                result.sampleRate,
                AudioFormat.ENCODING_PCM_16BIT,
                1,
            )

            val maxChunk = runCatching { callback.maxBufferSize }
                .getOrDefault(8192)
                .coerceAtLeast(1024)

            var offset = 0
            while (offset < result.pcm16.size) {
                val length = minOf(maxChunk, result.pcm16.size - offset)
                val accepted = callback.audioAvailable(
                    result.pcm16,
                    offset,
                    length,
                )
                if (accepted != TextToSpeech.SUCCESS) {
                    callback.error()
                    return
                }
                offset += length
            }

            callback.done()
        } catch (_: Throwable) {
            callback.error()
        }
    }

    override fun onStop() {
        synchronized(lock) {
            runCatching { synthesizer?.stop() }
        }
    }

    override fun onDestroy() {
        synchronized(lock) {
            runCatching { synthesizer?.close() }
            synthesizer = null
        }
        super.onDestroy()
    }
}
