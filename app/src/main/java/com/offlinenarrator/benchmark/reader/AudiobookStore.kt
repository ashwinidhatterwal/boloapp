package com.offlinenarrator.benchmark.reader

import android.content.Context
import android.util.AtomicFile
import com.offlinenarrator.benchmark.book.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Durable user-owned audio. Nothing here is silently evicted. */
class AudiobookStore(context: Context) {
    private val root = File(context.filesDir, "audiobooks-v2").apply { mkdirs() }
    fun chapterDir(bookId: String, chapter: Int, signature: String): File =
        File(root, "${digest(bookId)}/$chapter-${digest(signature)}").apply { mkdirs() }

    fun load(dir: File): PreparedChapter? = runCatching {
        val json = JSONObject(AtomicFile(File(dir, "manifest.json")).openRead().bufferedReader().use { it.readText() })
        require(json.getInt("version") == 2)
        val entries = json.getJSONArray("entries")
        PreparedChapter(dir, List(entries.length()) { i ->
            val j = entries.getJSONObject(i)
            val batch = NarrationBatch(
                text = j.getString("text"), startWord = j.getLong("startWord"), wordCount = j.getLong("words"),
                role = NarrationRole.valueOf(j.getString("role")), speakerKey = j.optString("speaker").ifBlank { null },
                deliveryCue = DeliveryCue.valueOf(j.getString("cue")), boundaryAfter = NarrationBoundary.valueOf(j.getString("boundary")),
                unitCount = j.getInt("units"), modelTokenCount = j.getInt("tokens"),
                unitWordEnds = j.getJSONArray("wordEnds").let { a -> List(a.length()) { a.getLong(it) } },
                performance = NarrationPerformance(
                    mood = NarrationMood.valueOf(j.getString("mood")), confidence = j.getDouble("confidence").toFloat(),
                    synthesisSpeed = j.getDouble("speed").toFloat(), gainDb = j.optDouble("gainDb", 0.0).toFloat(),
                    pauseScale = j.optDouble("pauseScale", 1.0).toFloat(), intent = j.optString("intent"), evidence = j.optString("evidence"),
                ), dialogueUnitCount = j.getInt("dialogues"),
            )
            PreparedChunk(batch, j.optString("file"), j.optLong("duration"),
                j.getJSONArray("times").let { a -> LongArray(a.length()) { a.getLong(it) } },
                j.optString("sha256"))
        }, json.optString("director", "offline"), json.optString("warning"))
    }.getOrNull()

    fun save(chapter: PreparedChapter) {
        val entries = JSONArray()
        for (entry in chapter.entries) {
            val b = entry.batch
            entries.put(JSONObject().put("text", b.text).put("startWord", b.startWord).put("words", b.wordCount)
                .put("role", b.role.name).put("speaker", b.speakerKey.orEmpty()).put("cue", b.deliveryCue.name)
                .put("boundary", b.boundaryAfter.name).put("units", b.unitCount).put("tokens", b.modelTokenCount ?: -1)
                .put("wordEnds", JSONArray(b.unitWordEnds)).put("mood", b.performance.mood.name)
                .put("confidence", b.performance.confidence.toDouble()).put("speed", b.performance.synthesisSpeed.toDouble())
                .put("gainDb", b.performance.gainDb.toDouble()).put("pauseScale", b.performance.pauseScale.toDouble())
                .put("intent", b.performance.intent).put("evidence", b.performance.evidence)
                .put("dialogues", b.dialogueUnitCount).put("file", entry.fileName).put("duration", entry.durationMs)
                .put("times", JSONArray(entry.timeAnchors.toList())).put("sha256", entry.sha256))
        }
        val bytes = JSONObject().put("version", 2).put("director", chapter.director).put("warning", chapter.warning)
            .put("entries", entries).toString().toByteArray()
        val atomic = AtomicFile(File(chapter.dir, "manifest.json"))
        val stream = atomic.startWrite()
        try { stream.write(bytes); atomic.finishWrite(stream) }
        catch (t: Throwable) { atomic.failWrite(stream); throw t }
    }

    fun valid(chapter: PreparedChapter, chunk: PreparedChunk): Boolean {
        if (chunk.fileName.isBlank() || chunk.durationMs <= 0 || chunk.sha256.isBlank()) return false
        val file = File(chapter.dir, chunk.fileName)
        if (!file.isFile || file.length() <= 44) return false
        val fingerprint = "${file.absolutePath}|${file.length()}|${file.lastModified()}|${chunk.sha256}"
        if (verified.containsKey(fingerprint)) return true
        if (fileDigest(file) != chunk.sha256) return false
        if (verified.size > 4096) verified.clear()
        verified[fingerprint] = true
        return true
    }
    fun complete(chapter: PreparedChapter): Boolean = chapter.entries.isNotEmpty() && chapter.entries.all { valid(chapter, it) }
    fun clear() { root.listFiles()?.forEach { it.deleteRecursively() } }
    fun deleteBook(bookId: String) { File(root, digest(bookId)).deleteRecursively() }
    fun sizeBytes(): Long = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    companion object {
        private val verified = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
        fun digest(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        fun fileDigest(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input -> val bytes = ByteArray(65536); while (true) {
                val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count)
            } }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

data class PreparedChunk(val batch: NarrationBatch, val fileName: String = "", val durationMs: Long = 0,
    val timeAnchors: LongArray = LongArray(0), val sha256: String = "")
data class PreparedChapter(val dir: File, val entries: List<PreparedChunk>, val director: String = "offline",
    val warning: String = "")
