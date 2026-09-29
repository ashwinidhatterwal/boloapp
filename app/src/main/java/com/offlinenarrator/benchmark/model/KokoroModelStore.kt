package com.offlinenarrator.benchmark.model

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

data class KokoroModelMetadata(
    val displayName: String,
    val sha256: String,
    val sizeBytes: Long,
)

class KokoroModelStore(
    private val context: Context,
    private val slot: String = "fp32",
) {
    private val modelDir: File = File(context.filesDir, "models").apply { mkdirs() }
    private val isPrimary = slot == "fp32"
    private val prefs = context.getSharedPreferences(
        if (isPrimary) "kokoro_model_store" else "kokoro_model_store_$slot",
        Context.MODE_PRIVATE,
    )
    val modelFile: File = File(
        modelDir,
        if (isPrimary) "kokoro.onnx" else "kokoro_$slot.onnx",
    )

    fun exists(): Boolean = modelFile.isFile && modelFile.length() > 1_000_000L
    fun sizeBytes(): Long = if (exists()) modelFile.length() else 0L

    suspend fun importFrom(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        runCatching {
            val temp = File(modelDir, "kokoro_$slot.importing")
            temp.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "Unable to open selected model" }
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
            }

            check(temp.length() > 1_000_000L) { "Selected file is too small to be a Kokoro model" }
            if (modelFile.exists()) modelFile.delete()
            check(temp.renameTo(modelFile)) { "Could not store model" }

            prefs.edit()
                .putString("display_name", queryDisplayName(uri) ?: modelFile.name)
                .putString("sha256", digest.digest().joinToString("") { "%02x".format(it) })
                .apply()

            modelFile
        }
    }

    suspend fun metadata(): KokoroModelMetadata? = withContext(Dispatchers.IO) {
        if (!exists()) return@withContext null

        val displayName = prefs.getString("display_name", null) ?: modelFile.name
        var sha = prefs.getString("sha256", null)

        if (sha.isNullOrBlank()) {
            val digest = MessageDigest.getInstance("SHA-256")
            modelFile.inputStream().buffered().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (read == 0) continue
                    digest.update(buffer, 0, read)
                }
            }
            sha = digest.digest().joinToString("") { "%02x".format(it) }
            prefs.edit().putString("sha256", sha).apply()
        }

        KokoroModelMetadata(
            displayName = displayName,
            sha256 = sha,
            sizeBytes = modelFile.length(),
        )
    }

    fun delete() {
        modelFile.delete()
        prefs.edit().clear().apply()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            }
        }.getOrNull()
    }
}
