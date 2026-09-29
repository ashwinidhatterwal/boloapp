package com.offlinenarrator.benchmark.reader

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import java.io.File

class RollingAudioPlayer(
    context: Context,
    private val onStateChanged: () -> Unit,
) {
    private val player = ExoPlayer.Builder(context).build()
    private val durationsMs = mutableListOf<Long>()

    var started: Boolean = false
        private set

    var inputComplete: Boolean = false
        private set

    var underruns: Int = 0
        private set

    private var hasActuallyPlayed = false
    private var speed = 1.0f

    init {
        player.addListener(object : Player.Listener {
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
    }

    fun reset(playbackSpeed: Float) {
        player.stop()
        player.clearMediaItems()
        durationsMs.clear()
        started = false
        inputComplete = false
        underruns = 0
        hasActuallyPlayed = false
        setSpeed(playbackSpeed)
    }

    fun enqueue(file: File, durationMs: Long) {
        val wasEnded = player.playbackState == Player.STATE_ENDED
        val newIndex = player.mediaItemCount

        durationsMs += durationMs
        player.addMediaItem(MediaItem.fromUri(Uri.fromFile(file)))

        if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }

        if (started && wasEnded) {
            player.seekTo(newIndex, 0L)
            player.prepare()
            player.play()
        }

        onStateChanged()
    }

    fun start() {
        started = true
        if (player.mediaItemCount == 0) return

        if (player.playbackState == Player.STATE_ENDED) {
            player.seekToDefaultPosition(0)
            player.prepare()
        } else if (player.playbackState == Player.STATE_IDLE) {
            player.prepare()
        }

        player.play()
        onStateChanged()
    }

    fun pause() {
        player.pause()
        onStateChanged()
    }

    fun resume() {
        started = true
        player.play()
        onStateChanged()
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        durationsMs.clear()
        started = false
        inputComplete = false
        underruns = 0
        hasActuallyPlayed = false
        onStateChanged()
    }

    fun setSpeed(value: Float) {
        speed = value.coerceIn(0.75f, 2.0f)
        player.playbackParameters = PlaybackParameters(speed)
        onStateChanged()
    }

    fun markInputComplete() {
        inputComplete = true
        onStateChanged()
    }

    fun isPlaying(): Boolean = player.isPlaying

    fun isPaused(): Boolean =
        started && !player.isPlaying && player.playbackState != Player.STATE_ENDED

    fun isEnded(): Boolean =
        inputComplete &&
            player.mediaItemCount > 0 &&
            player.playbackState == Player.STATE_ENDED

    fun currentSegmentNumber(): Int {
        if (player.mediaItemCount == 0) return 0
        val index = player.currentMediaItemIndex
        return if (index >= 0) index + 1 else 0
    }

    /**
     * Source-audio milliseconds already generated but not yet consumed.
     * This deliberately ignores ExoPlayer's internal byte buffer and instead
     * measures all generated playlist audio.
     */
    fun bufferedSourceMs(): Long {
        if (durationsMs.isEmpty()) return 0L

        val index = player.currentMediaItemIndex
            .takeIf { it >= 0 }
            ?: 0

        if (index >= durationsMs.size) return 0L

        var remaining = durationsMs[index] - player.currentPosition
        for (i in (index + 1) until durationsMs.size) {
            remaining += durationsMs[i]
        }
        return remaining.coerceAtLeast(0L)
    }

    fun bufferedListeningMs(): Long =
        (bufferedSourceMs() / speed.coerceAtLeast(0.1f)).toLong()

    fun release() {
        player.release()
    }
}
