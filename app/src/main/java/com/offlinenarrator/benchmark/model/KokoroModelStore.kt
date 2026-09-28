package com.offlinenarrator.benchmark.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class KokoroModelStore(private val context: Context) {
    private val modelDir: File = File(context.filesDir, "models").apply { mkdirs() }
    val modelFile: File = File(modelDir, "kokoro.onnx")

    fun exists(): Boolean = modelFile.isFile && modelFile.length() > 1_000_000L
    fun sizeBytes(): Long = if (exists()) modelFile.length() else 0L

    suspend fun importFrom(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(modelDir, "kokoro.importing")
            temp.delete()
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "Unable to open selected model" }
                temp.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            check(temp.length() > 1_000_000L) { "Selected file is too small to be a Kokoro model" }
            if (modelFile.exists()) modelFile.delete()
            check(temp.renameTo(modelFile)) { "Could not store model" }
            modelFile
        }
    }

    fun delete() {
        modelFile.delete()
    }
}
