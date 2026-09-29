package com.offlinenarrator.benchmark.book

import android.content.Context
import com.offlinenarrator.benchmark.tts.TtsVoice
import java.util.Locale

/**
 * Stable per-book character voice assignments. Character identity comes only
 * from NarrationDirector's conservative explicit-attribution detector.
 */
class CharacterVoiceStore(context: Context) {
    private val prefs = context.getSharedPreferences(
        "bolo_character_voices",
        Context.MODE_PRIVATE,
    )

    fun voiceFor(
        bookId: String,
        speakerKey: String,
        narratorVoiceId: String,
        availableVoices: List<TtsVoice>,
    ): String {
        val key = prefKey(bookId, speakerKey)
        val stored = prefs.getString(key, null)

        val candidates = orderedCandidates(availableVoices)
            .filter { it.id != narratorVoiceId }

        if (
            stored != null &&
            stored != narratorVoiceId &&
            candidates.any { it.id == stored }
        ) {
            return stored
        }

        if (candidates.isEmpty()) return narratorVoiceId

        val hash = "${bookId.lowercase(Locale.ROOT)}|${speakerKey.lowercase(Locale.ROOT)}"
            .hashCode()
            .toLong()
            .let { if (it < 0L) -it else it }
        val selected = candidates[(hash % candidates.size.toLong()).toInt()].id

        prefs.edit().putString(key, selected).apply()
        return selected
    }

    fun clearBook(bookId: String) {
        val prefix = "book:$bookId:"
        val editor = prefs.edit()
        prefs.all.keys
            .filter { it.startsWith(prefix) }
            .forEach(editor::remove)
        editor.apply()
    }

    private fun orderedCandidates(voices: List<TtsVoice>): List<TtsVoice> {
        val preferredIds = listOf(
            "af_heart",
            "am_michael",
            "bm_george",
            "am_fenrir",
            "am_onyx",
        )

        return preferredIds
            .mapNotNull { id -> voices.firstOrNull { it.id == id } }
            .plus(voices.filter { voice -> preferredIds.none { it == voice.id } })
            .distinctBy { it.id }
    }

    private fun prefKey(bookId: String, speakerKey: String): String =
        "book:$bookId:${speakerKey.lowercase(Locale.ROOT).trim()}"
}
