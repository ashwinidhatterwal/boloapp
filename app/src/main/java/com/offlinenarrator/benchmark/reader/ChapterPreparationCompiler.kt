package com.offlinenarrator.benchmark.reader

import android.content.Context
import com.offlinenarrator.benchmark.book.*
import com.offlinenarrator.benchmark.model.KokoroModelStore
import com.offlinenarrator.benchmark.tts.KokoroTtsEngine
import com.offlinenarrator.benchmark.tts.SpeechRequest
import com.offlinenarrator.benchmark.util.DeviceDiagnostics
import dev.ffmpegkit.kokoro.KokoroRuntimeProfile
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import kotlin.math.pow
import kotlin.math.sqrt

class PreparationDeferred : Exception()
object NarrationCoordinator { val mutex = Mutex() }
data class CompilationProgress(val completed: Int, val total: Int, val retries: Int = 0,
    val rtf: Double? = null, val etaMs: Long? = null, val status: String = "")

class ChapterPreparationCompiler(private val context: Context) {
    private val books = DocumentBookStore(context)
    private val models = KokoroModelStore(context, "fp32")
    private val store = AudiobookStore(context)
    suspend fun find(book: BookRecord, chapterIndex: Int, settings: DirectorSettings): PreparedChapter? = withContext(Dispatchers.IO) {
        val text = books.readChapter(book.id, chapterIndex)
        store.load(directory(book, chapterIndex, text, settings))
    }
    private suspend fun directory(book: BookRecord, chapterIndex: Int, text: String, settings: DirectorSettings, selectedVoice: String = voiceId()): File =
        store.chapterDir(book.id, chapterIndex, "compiler-v2|${models.metadata()?.sha256}|${AudiobookStore.digest(text)}|${settings.signature}|$selectedVoice")
    private fun voiceId(): String = context.getSharedPreferences("bolo_reader", Context.MODE_PRIVATE)
        .getString("voice_id", "af_heart") ?: "af_heart"

    suspend fun prepare(book: BookRecord, chapterIndex: Int, settings: DirectorSettings,
        maxNewChunks: Int = Int.MAX_VALUE, onProgress: suspend (CompilationProgress) -> Unit = {}): PreparedChapter =
        NarrationCoordinator.mutex.withLock {
            withContext(Dispatchers.IO) {
                if (PlaybackPreparationGate.playing) throw PreparationDeferred()
                val text = books.readChapter(book.id, chapterIndex)
                val narrationVoice = voiceId()
                val dir = directory(book, chapterIndex, text, settings, narrationVoice)
                var chapter = store.load(dir)
                if (chapter != null && store.complete(chapter)) return@withContext chapter
                // Energy is a preparation concern. Use a conservative CPU profile,
                // release all native resources before ordinary audio playback.
                val engine = KokoroTtsEngine(context, models, KokoroRuntimeProfile.CPU_BASELINE)
                var retries = 0; var synthesisMs = 0L; var renderedMs = 0L; var newChunks = 0
                try {
                    currentCoroutineContext().ensureActive()
                    engine.initialize().getOrThrow()
                    if (chapter == null) {
                        onProgress(CompilationProgress(0, 0, status = "Analysing chapter context…"))
                        val base = AudiobookNarrationCompiler.planChapter(text, sourceFormat = book.format, tokenCounter = engine::countModelTokens)
                        var units = base.units; var warning = ""; var director = "offline"
                        if (settings.enabled) {
                            try { units = withTimeout(180_000) { SemanticNarrationDirector(settings).direct(units) }; director = "semantic" }
                            catch (_: TimeoutCancellationException) { warning = "Semantic analysis exceeded three minutes; offline plan used." }
                            catch (cancelled: CancellationException) { throw cancelled }
                            catch (failure: Exception) { warning = "Semantic director unavailable; offline plan used: ${failure.message}" }
                        }
                        units = applyPronunciations(units, settings.pronunciation)
                        val batches = NarrationBatcher.batch(units, engine::countModelTokens)
                        require(batches.sumOf { it.wordCount } == countWords(text)) { "Source locations changed during direction" }
                        require(batches.all { (it.modelTokenCount ?: 501) <= 500 })
                        chapter = PreparedChapter(dir, batches.map { PreparedChunk(it) }, director, warning)
                        store.save(chapter!!)
                    }
                    var prepared = chapter!!
                    val entries = prepared.entries.toMutableList()
                    val startMs = System.currentTimeMillis()
                    for (index in entries.indices) {
                        currentCoroutineContext().ensureActive()
                        if (store.valid(prepared, entries[index])) continue
                        if (newChunks >= maxNewChunks) break
                        while (DeviceDiagnostics.capture(context).thermalStatus in setOf("Severe", "Critical", "Emergency", "Shutdown")) {
                            onProgress(CompilationProgress(entries.count { store.valid(prepared, it) }, entries.size,
                                retries, status = "Cooling before preparation resumes…")); delay(2000)
                        }
                        check(android.os.StatFs(dir.absolutePath).availableBytes > 24L * 1024L * 1024L) { "Not enough free storage to prepare audio. Completed chunks were preserved." }
                        val batch = entries[index].batch
                        val boundary = if (index == entries.lastIndex) NarrationBoundary.CHAPTER else batch.boundaryAfter
                        val timing = NarrationPacing.timing(boundary, batch.deliveryCue)
                        val scale = batch.performance.pauseScale.coerceIn(0.9f, 1.15f)
                        var selectedFile: File? = null
                        var duration = 0L
                        try {
                            for (attempt in 0..1) {
                                currentCoroutineContext().ensureActive()
                                if (attempt == 1) retries++
                                val result = withTimeout(90_000) { engine.synthesize(SpeechRequest(
                                    text = batch.text, speed = if (attempt == 0) batch.performance.synthesisSpeed else 1f,
                                    voiceId = narrationVoice)).getOrThrow() }
                                synthesisMs += result.generationTimeMs
                                val file = result.audioFile
                                var keep = false
                                try {
                                    val finish = WavAudioFinisher.normalizeBoundarySilence(file,
                                        (timing.minMs * scale).toInt(), (timing.targetMs * scale).toInt(), (timing.maxMs * scale).toInt())
                                    val spokenWords = Regex("[\\p{L}\\p{N}]+(?:['’][\\p{L}]+)?").findAll(batch.text).count().toLong()
                                    val qc = WavQualityInspector.inspect(file, spokenWords, boundary >= NarrationBoundary.PARAGRAPH, finish.finalTrailingSilenceMs)
                                    if (qc.acceptable) {
                                        master(file, batch.performance.gainDb)
                                        val masteredQc = WavQualityInspector.inspect(file, spokenWords, boundary >= NarrationBoundary.PARAGRAPH, finish.finalTrailingSilenceMs)
                                        check(masteredQc.acceptable) { "Mastering failed audio QC: ${masteredQc.reasons.joinToString()}" }
                                        selectedFile = file; duration = result.audioDurationMs + finish.durationAdjustmentMs
                                        keep = true; break
                                    }
                                    if (attempt == 1) error("Chunk ${index + 1} failed both QC attempts: ${qc.reasons.joinToString()}. Completed audio was preserved.")
                                } finally { if (!keep) file.delete() }
                            }
                            val wav = checkNotNull(selectedFile)
                            val times = WavAudioFinisher.detectBoundaryTimes(wav, batch.unitWordEnds, batch.wordCount)
                            val staging = File(dir, "$index.pending.m4a")
                            val finalFile = try {
                                AacAudioEncoder.encode(wav, staging)
                                currentCoroutineContext().ensureActive()
                                java.io.FileOutputStream(staging, true).use { it.fd.sync() }
                                val target = File(dir, "$index.m4a")
                                check(staging.renameTo(target)) { "Cannot commit compressed audio" }; target
                            } catch (cancelled: CancellationException) { staging.delete(); throw cancelled }
                            catch (_: Exception) {
                                staging.delete()
                                val pending = File(dir, "$index.pending.wav")
                                wav.copyTo(pending, overwrite = true)
                                java.io.FileOutputStream(pending, true).use { it.fd.sync() }
                                val target = File(dir, "$index.wav")
                                check(pending.renameTo(target)) { "Cannot commit WAV fallback" }; target
                            }
                            val committed = PreparedChunk(batch, finalFile.name, duration.coerceAtLeast(1), times, AudiobookStore.fileDigest(finalFile))
                            entries[index] = committed; prepared = prepared.copy(entries = entries.toList()); store.save(prepared)
                            renderedMs += duration; newChunks++
                            val done = entries.count { it.fileName.isNotBlank() }
                            val elapsed = System.currentTimeMillis() - startMs
                            onProgress(CompilationProgress(done, entries.size, retries,
                                if (renderedMs > 0) synthesisMs.toDouble() / renderedMs else null,
                                if (newChunks > 0) elapsed / newChunks * (entries.size - done) else null,
                                if (prepared.warning.isNotBlank()) prepared.warning else "Preparing ${done}/${entries.size} · ${prepared.director} direction"))
                        } finally { selectedFile?.delete() }
                    }
                    prepared
                } finally { engine.release() }
            }
        }

    private fun applyPronunciations(units: List<NarrationUnit>, dictionary: String): List<NarrationUnit> {
        val rules = dictionary.lineSequence().mapNotNull { line ->
            val parts = line.split('=', limit = 2)
            if (parts.size == 2 && parts.all { it.trim().isNotBlank() }) parts[0].trim() to parts[1].trim() else null
        }.take(200).toList()
        return units.map { unit ->
            var spoken = unit.spokenText
            for ((word, replacement) in rules) spoken = Regex("(?<![\\p{L}\\p{N}])${Regex.escape(word)}(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                .replace(spoken) { replacement }
            unit.copy(spokenText = spoken)
        }
    }

    /** Restrained level mastering; never synthesize fake emotion by clipping. */
    private fun master(file: File, deliveryDb: Float) {
        val bytes = file.readBytes()
        var offset = 12; var start = -1; var size = 0
        fun int(p: Int): Int = (bytes[p].toInt() and 255) or ((bytes[p+1].toInt() and 255) shl 8) or
            ((bytes[p+2].toInt() and 255) shl 16) or (bytes[p+3].toInt() shl 24)
        while (offset + 8 <= bytes.size) {
            val length = int(offset + 4); if (length < 0 || length > bytes.size - offset - 8) return
            if (String(bytes, offset, 4, Charsets.US_ASCII) == "data") { start = offset + 8; size = length; break }
            offset += 8 + length + (length and 1)
        }
        if (start < 0 || size < 2) return
        fun sample(p: Int) = ((bytes[p].toInt() and 255) or (bytes[p+1].toInt() shl 8)).toShort().toInt()
        var squares = 0.0; var peak = 1
        for (p in start until start + size - 1 step 2) { val v = sample(p); squares += v.toDouble()*v; peak = maxOf(peak, kotlin.math.abs(v)) }
        val rms = sqrt(squares / (size / 2)).coerceAtLeast(1.0)
        val level = (2600.0 / rms).coerceIn(0.65, 1.6) * 10.0.pow(deliveryDb.coerceIn(-2f, 1f) / 20.0)
        val gain = minOf(level, 30000.0 / peak)
        for (p in start until start + size - 1 step 2) {
            val v = (sample(p) * gain).toInt().coerceIn(-32768, 32767)
            bytes[p] = v.toByte(); bytes[p+1] = (v shr 8).toByte()
        }
        file.writeBytes(bytes)
    }
}
