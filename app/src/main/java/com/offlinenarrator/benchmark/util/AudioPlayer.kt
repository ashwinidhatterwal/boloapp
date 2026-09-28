package com.offlinenarrator.benchmark.util

import android.media.MediaPlayer
import java.io.File

class AudioPlayer {
    private var player: MediaPlayer? = null

    fun play(file: File, onComplete: (() -> Unit)? = null) {
        stop()
        player = MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                onComplete?.invoke()
                release()
                if (player === this) player = null
            }
            setOnErrorListener { mp, _, _ ->
                mp.release()
                if (player === mp) player = null
                true
            }
            prepare()
            start()
        }
    }

    fun stop() {
        val current = player ?: return
        runCatching { current.stop() }
        runCatching { current.release() }
        player = null
    }

    fun release() = stop()
}
