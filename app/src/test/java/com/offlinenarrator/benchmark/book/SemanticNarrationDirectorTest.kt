package com.offlinenarrator.benchmark.book

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SemanticNarrationDirectorTest {
    private val settings = DirectorSettings(enabled = true, endpoint = "https://example.invalid/v1", model = "test")
    private fun provider(units: List<NarrationUnit>, evidence: String, speaker: String = "Maya"): (JSONObject) -> JSONObject = { request ->
        if (request.getString("task") == "chapter_overview") JSONObject().put("summary", "Maya responds calmly")
        else JSONObject().put("units", JSONArray().apply {
            for (i in request.getInt("target_start") until request.getInt("target_end_exclusive"))
                put(JSONObject().put("index", i).put("speaker", speaker).put("mood", "TENDER")
                    .put("confidence", 0.9).put("manner", "quiet").put("intent", "reassurance")
                    .put("evidence", evidence))
        })
    }
    @Test fun semanticDirectionNeverRewritesBookOrLocations() = runBlocking {
        val source = NarrationDirector.plan("\"Please,\" Maya said gently.")
        val output = SemanticNarrationDirector(settings, provider(source, "gently")).direct(source)
        assertEquals(source.map { it.text }, output.map { it.text })
        assertEquals(source.map { it.spokenText }, output.map { it.spokenText })
        assertEquals(source.map { it.startWord to it.wordCount }, output.map { it.startWord to it.wordCount })
        assertEquals(NarrationMood.TENDER, output.first().performance.mood)
        assertTrue(output.first().performance.gainDb < 0f)
    }
    @Test fun hallucinatedEvidenceCannotForceConfidentEmotion() = runBlocking {
        val source = NarrationDirector.plan("\"Fine.\" Maya said.")
        val output = SemanticNarrationDirector(settings, provider(source, "she was delighted")).direct(source)
        assertTrue(output.all { it.performance.confidence <= 0.4f })
        assertTrue(output.all { it.performance.mood == NarrationMood.NEUTRAL })
    }
    @Test fun inventedCharacterIsNotAccepted() = runBlocking {
        val source = NarrationDirector.plan("\"Please,\" Maya said gently.")
        val output = SemanticNarrationDirector(settings, provider(source, "gently", "InventedCharacter")).direct(source)
        assertEquals("Maya", output.first().speakerKey)
    }
    @Test fun missingUnitResponseFailsInsteadOfSilentlyDroppingSource() {
        val source = NarrationDirector.plan("One sentence. Another sentence.")
        val director = SemanticNarrationDirector(settings) { request ->
            if (request.getString("task") == "chapter_overview") JSONObject().put("summary", "overview")
            else JSONObject().put("units", JSONArray())
        }
        try { runBlocking { director.direct(source) }; fail("Missing annotations must fail validation") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun disabledSemanticDirectionNeverCallsProvider() = runBlocking {
        val source = NarrationDirector.plan("A quiet room.")
        val output = SemanticNarrationDirector(DirectorSettings()) { error("No provider call expected") }.direct(source)
        assertEquals(source, output)
    }
}
