package com.offlinenarrator.benchmark.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel

class BoloViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = BookReaderRuntime.get(application.applicationContext)

    val state = runtime.state

    fun importDocument(uri: Uri) = runtime.importDocument(uri)
    fun cancelImport() = runtime.cancelImport()
    fun importKokoroModel(uri: Uri) = runtime.importKokoroModel(uri)

    fun openBook(bookId: String) = runtime.openBook(bookId)
    fun backToLibrary() = runtime.backToLibrary()
    fun returnToPlayer() = runtime.returnToPlayer()
    fun removeBook(bookId: String) = runtime.removeBook(bookId)

    fun selectVoice(id: String) = runtime.selectVoice(id)
    fun setPlaybackSpeed(speed: Float) = runtime.setPlaybackSpeed(speed)

    fun togglePlayback() = runtime.togglePlayback()
    fun stopNarration() = runtime.stopNarration()

    fun jumpToFraction(fraction: Float) = runtime.jumpToFraction(fraction)
    fun jumpToPage(page: Int) = runtime.jumpToPage(page)
    fun jumpByPages(deltaPages: Int) = runtime.jumpByPages(deltaPages)
    fun jumpToChapter(chapterIndex: Int) = runtime.jumpToChapter(chapterIndex)
    fun playFromLine(lineIndex: Int) = runtime.playFromLine(lineIndex)
    fun previousChapter() = runtime.previousChapter()
    fun nextChapter() = runtime.nextChapter()

    fun clearPreparedAudio() = runtime.clearPreparedAudio()
}
