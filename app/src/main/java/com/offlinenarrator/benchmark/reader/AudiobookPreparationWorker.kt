package com.offlinenarrator.benchmark.reader

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.*
import com.offlinenarrator.benchmark.book.DirectorSettingsStore
import com.offlinenarrator.benchmark.book.DocumentBookStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

object PlaybackPreparationGate { @Volatile var playing = false }
private class PlaybackStarted : Exception()

/** Persisted constrained work; each completed chunk survives worker/process death. */
class AudiobookPreparationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val bookId = inputData.getString("book") ?: return Result.failure()
        val chapterIndex = inputData.getInt("chapter", -1)
        if (PlaybackPreparationGate.playing) return Result.retry()
        val book = DocumentBookStore(applicationContext).listBooks().firstOrNull { it.id == bookId } ?: return Result.failure()
        if (chapterIndex !in book.chapters.indices) return Result.failure()
        return try {
            setForeground(notification("Preparing ${book.title}", 0))
            val compiler = ChapterPreparationCompiler(applicationContext)
            val chapter = compiler.prepare(book, chapterIndex, DirectorSettingsStore(applicationContext).load(), maxNewChunks = 4) { p ->
                if (PlaybackPreparationGate.playing) throw PlaybackStarted()
                setProgress(workDataOf("completed" to p.completed, "total" to p.total, "status" to p.status))
                setForeground(notification("${book.title} · ${p.completed}/${p.total}", p.completed))
            }
            if (AudiobookStore(applicationContext).complete(chapter)) Result.success() else Result.retry()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: PreparationDeferred) { Result.retry() }
        catch (_: PlaybackStarted) { Result.retry() }
        catch (failure: Exception) {
            applicationContext.getSharedPreferences("bolo_preparation", Context.MODE_PRIVATE).edit()
                .putString("error", failure.message ?: "Preparation failed").apply()
            // Permanent QC/provider/source failures must be reviewable, not loop forever.
            Result.failure(workDataOf("error" to (failure.message ?: "Preparation failed").take(1500)))
        }
    }
    private fun notification(text: String, progress: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel("audiobook-preparation", "Audiobook preparation", NotificationManager.IMPORTANCE_LOW))
        val notification = NotificationCompat.Builder(applicationContext, "audiobook-preparation")
            .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("Bolo · preparing audio").setContentText(text)
            .setOngoing(true).setOnlyAlertOnce(true)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)).build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(2401, notification,
            if (Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(2401, notification)
    }
}

object AudiobookPreparationScheduler {
    fun schedule(context: Context, bookId: String, chapters: IntRange, chargingOnly: Boolean) {
        val manager = WorkManager.getInstance(context)
        val constraints = Constraints.Builder().setRequiresCharging(chargingOnly).setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true).build()
        val requests = chapters.map { chapter -> OneTimeWorkRequestBuilder<AudiobookPreparationWorker>()
            .setInputData(workDataOf("book" to bookId, "chapter" to chapter))
            .setConstraints(constraints).setBackoffCriteria(BackoffPolicy.LINEAR, 10, TimeUnit.SECONDS)
            .addTag("bolo-preparation").addTag("bolo-book-$bookId").build() }
        if (requests.isNotEmpty()) {
            var chain = manager.beginUniqueWork("bolo-book-$bookId", ExistingWorkPolicy.REPLACE, requests.first())
            for (request in requests.drop(1)) chain = chain.then(request)
            chain.enqueue()
        }
    }
    fun cancel(context: Context, bookId: String) { WorkManager.getInstance(context).cancelUniqueWork("bolo-book-$bookId") }
}
