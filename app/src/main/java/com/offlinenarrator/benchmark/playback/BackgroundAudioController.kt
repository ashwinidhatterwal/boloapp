package com.offlinenarrator.benchmark.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class PlaybackDescriptor(
    val bookId: String,
    val chapterIndex: Int,
    val segmentStartWord: Long,
    val segmentWordCount: Long,
    val chapterGlobalStart: Long,
    val durationMs: Long,
    val positionMs: Long,
    val wordAnchorOffsets: LongArray = LongArray(0),
    val timeAnchorMs: LongArray = LongArray(0),
) {
    fun wordOffsetAtPosition(): Long {
        if (segmentWordCount <= 0L || durationMs <= 0L) return 0L
        if (wordAnchorOffsets.isEmpty() || wordAnchorOffsets.size != timeAnchorMs.size) {
            val fraction = (positionMs.toDouble() / durationMs.toDouble()).coerceIn(0.0, 1.0)
            return (segmentWordCount * fraction).toLong().coerceIn(0L, segmentWordCount)
        }

        var previousWord = 0L
        var previousTime = 0L
        for (i in timeAnchorMs.indices) {
            val nextTime = timeAnchorMs[i].coerceIn(previousTime, durationMs)
            val nextWord = wordAnchorOffsets[i].coerceIn(previousWord, segmentWordCount)
            if (positionMs <= nextTime) {
                val spanMs = (nextTime - previousTime).coerceAtLeast(1L)
                val fraction = ((positionMs - previousTime).toDouble() / spanMs.toDouble())
                    .coerceIn(0.0, 1.0)
                return (previousWord + ((nextWord - previousWord) * fraction).toLong())
                    .coerceIn(0L, segmentWordCount)
            }
            previousWord = nextWord
            previousTime = nextTime
        }

        val spanMs = (durationMs - previousTime).coerceAtLeast(1L)
        val fraction = ((positionMs - previousTime).toDouble() / spanMs.toDouble())
            .coerceIn(0.0, 1.0)
        return (previousWord + ((segmentWordCount - previousWord) * fraction).toLong())
            .coerceIn(0L, segmentWordCount)
    }
}

class BackgroundAudioController(
    private val context: Context,
    private val onStateChanged: () -> Unit,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var speed = 1.0f

    var started: Boolean = false
        private set

    var inputComplete: Boolean = false
        private set

    var underruns: Int = 0
        private set

    private var hasActuallyPlayed = false

    suspend fun connect() {
        if (controller != null) return

        val token = SessionToken(
            context,
            ComponentName(context, BoloPlaybackService::class.java),
        )

        controllerFuture?.let { previous ->
            runCatching { MediaController.releaseFuture(previous) }
        }

        val future = MediaController.Builder(context, token)
            .setListener(
                object : MediaController.Listener {
                    override fun onDisconnected(disconnectedController: MediaController) {
                        if (controller === disconnectedController) {
                            controller = null
                            started = false
                            inputComplete = false
                            onStateChanged()
                        }
                    }
                }
            )
            .buildAsync()
        controllerFuture = future

        suspendCancellableCoroutine<Unit> { continuation ->
            future.addListener(
                {
                    try {
                        val connected = future.get()
                        controller = connected
                        connected.playbackParameters = PlaybackParameters(speed)
                        started = connected.mediaItemCount > 0

                        connected.addListener(object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                if (isPlaying) hasActuallyPlayed = true
                                onStateChanged()
                            }

                            override fun onPlaybackStateChanged(playbackState: Int) {
                                if (
                                    started &&
                                    hasActuallyPlayed &&
                                    !inputComplete &&
                                    playbackState == Player.STATE_ENDED
                                ) {
                                    underruns += 1
                                }
                                onStateChanged()
                            }

                            override fun onMediaItemTransition(
                                mediaItem: MediaItem?,
                                reason: Int,
                            ) {
                                onStateChanged()
                            }
                        })

                        if (continuation.isActive) {
                            continuation.resume(Unit)
                        }
                    } catch (t: Throwable) {
                        if (continuation.isActive) {
                            continuation.resumeWithException(t)
                        }
                    }
                },
                ContextCompat.getMainExecutor(context),
            )

            continuation.invokeOnCancellation {
                MediaController.releaseFuture(future)
            }
        }
    }

    fun isConnected(): Boolean = controller != null

    fun reset(playbackSpeed: Float) {
        val c = controller ?: return
        c.stop()
        c.clearMediaItems()

        started = false
        inputComplete = false
        underruns = 0
        hasActuallyPlayed = false
        setSpeed(playbackSpeed)
        onStateChanged()
    }

    fun enqueue(
        file: File,
        durationMs: Long,
        bookId: String,
        bookTitle: String,
        chapterTitle: String,
        chapterIndex: Int,
        chapterGlobalStart: Long,
        segmentStartWord: Long,
        segmentWordCount: Long,
        wordAnchorOffsets: LongArray = LongArray(0),
        timeAnchorMs: LongArray = LongArray(0),
    ) {
        val c = controller ?: return
        val wasEnded = c.playbackState == Player.STATE_ENDED
        val newIndex = c.mediaItemCount

        val extras = Bundle().apply {
            putString(EXTRA_BOOK_ID, bookId)
            putInt(EXTRA_CHAPTER_INDEX, chapterIndex)
            putLong(EXTRA_CHAPTER_GLOBAL_START, chapterGlobalStart)
            putLong(EXTRA_SEGMENT_START_WORD, segmentStartWord)
            putLong(EXTRA_SEGMENT_WORD_COUNT, segmentWordCount)
            putLong(EXTRA_DURATION_MS, durationMs)
            putLongArray(EXTRA_WORD_ANCHORS, wordAnchorOffsets)
            putLongArray(EXTRA_TIME_ANCHORS, timeAnchorMs)
        }

        val item = MediaItem.Builder()
            .setMediaId(
                "$bookId|$chapterIndex|$segmentStartWord"
            )
            .setUri(Uri.fromFile(file))
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(chapterTitle)
                    .setArtist(bookTitle)
                    .setAlbumTitle(bookTitle)
                    .setExtras(extras)
                    .build()
            )
            .build()

        c.addMediaItem(item)

        if (c.playbackState == Player.STATE_IDLE) {
            c.prepare()
        }

        if (started && wasEnded) {
            c.seekTo(newIndex, 0L)
            c.prepare()
            c.play()
        }

        onStateChanged()
    }

    fun start() {
        val c = controller ?: return
        if (c.mediaItemCount <= 0) return

        started = true

        if (c.playbackState == Player.STATE_ENDED) {
            c.seekToDefaultPosition(0)
            c.prepare()
        } else if (c.playbackState == Player.STATE_IDLE) {
            c.prepare()
        }

        c.play()
        onStateChanged()
    }

    fun pause() {
        controller?.pause()
        onStateChanged()
    }

    fun resume() {
        started = true
        controller?.play()
        onStateChanged()
    }

    fun stop() {
        controller?.stop()
        controller?.clearMediaItems()

        started = false
        inputComplete = false
        underruns = 0
        hasActuallyPlayed = false
        onStateChanged()
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.75f, 2.0f)
        controller?.playbackParameters = PlaybackParameters(speed)
        onStateChanged()
    }

    fun markInputComplete() {
        inputComplete = true
        onStateChanged()
    }

    fun isPlaying(): Boolean = controller?.isPlaying == true

    fun isPaused(): Boolean {
        val c = controller ?: return false
        return started &&
            !c.isPlaying &&
            c.playbackState != Player.STATE_ENDED
    }

    fun isEnded(): Boolean {
        val c = controller ?: return false
        return inputComplete &&
            c.mediaItemCount > 0 &&
            c.playbackState == Player.STATE_ENDED
    }

    fun mediaItemCount(): Int = controller?.mediaItemCount ?: 0

    fun seekCurrent(positionMs: Long) {
        val c = controller ?: return
        val index = c.currentMediaItemIndex
        if (index < 0 || index >= c.mediaItemCount) return

        val duration = durationAt(index)
        val safe = if (duration > 0L) {
            positionMs.coerceIn(0L, maxOf(0L, duration - 50L))
        } else {
            positionMs.coerceAtLeast(0L)
        }

        c.seekTo(index, safe)
        onStateChanged()
    }

    fun bufferedSourceMs(): Long {
        val c = controller ?: return 0L
        if (c.mediaItemCount <= 0) return 0L

        val index = c.currentMediaItemIndex
            .takeIf { it >= 0 }
            ?: 0
        if (index >= c.mediaItemCount) return 0L

        var remaining = durationAt(index) - c.currentPosition
        for (i in (index + 1) until c.mediaItemCount) {
            remaining += durationAt(i)
        }
        return remaining.coerceAtLeast(0L)
    }

    fun bufferedListeningMs(): Long =
        (bufferedSourceMs() / speed.coerceAtLeast(0.1f)).toLong()

    fun currentDescriptor(): PlaybackDescriptor? {
        val c = controller ?: return null
        val item = c.currentMediaItem ?: return null
        val extras = item.mediaMetadata.extras ?: return null
        val bookId = extras.getString(EXTRA_BOOK_ID) ?: return null

        return PlaybackDescriptor(
            bookId = bookId,
            chapterIndex = extras.getInt(EXTRA_CHAPTER_INDEX, 0),
            segmentStartWord = extras.getLong(EXTRA_SEGMENT_START_WORD, 0L),
            segmentWordCount = extras.getLong(EXTRA_SEGMENT_WORD_COUNT, 0L),
            chapterGlobalStart = extras.getLong(EXTRA_CHAPTER_GLOBAL_START, 0L),
            durationMs = extras.getLong(EXTRA_DURATION_MS, 0L),
            positionMs = c.currentPosition.coerceAtLeast(0L),
            wordAnchorOffsets = extras.getLongArray(EXTRA_WORD_ANCHORS) ?: LongArray(0),
            timeAnchorMs = extras.getLongArray(EXTRA_TIME_ANCHORS) ?: LongArray(0),
        )
    }

    private fun durationAt(index: Int): Long {
        val c = controller ?: return 0L
        if (index < 0 || index >= c.mediaItemCount) return 0L
        return c.getMediaItemAt(index)
            .mediaMetadata
            .extras
            ?.getLong(EXTRA_DURATION_MS, 0L)
            ?: 0L
    }

    companion object {
        private const val EXTRA_BOOK_ID = "bolo_book_id"
        private const val EXTRA_CHAPTER_INDEX = "bolo_chapter_index"
        private const val EXTRA_CHAPTER_GLOBAL_START = "bolo_chapter_global_start"
        private const val EXTRA_SEGMENT_START_WORD = "bolo_segment_start_word"
        private const val EXTRA_SEGMENT_WORD_COUNT = "bolo_segment_word_count"
        private const val EXTRA_DURATION_MS = "bolo_duration_ms"
        private const val EXTRA_WORD_ANCHORS = "bolo_word_anchors"
        private const val EXTRA_TIME_ANCHORS = "bolo_time_anchors"
    }
}
