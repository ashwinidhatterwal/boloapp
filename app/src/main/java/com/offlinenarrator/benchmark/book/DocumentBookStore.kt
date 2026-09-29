package com.offlinenarrator.benchmark.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import android.util.Xml
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.io.MemoryUsageSetting
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.io.StringReader
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private data class ManifestItem(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: String,
)

private data class XhtmlExtraction(
    val text: String,
    val heading: String?,
)

private enum class ImportFormat(val label: String) {
    EPUB("EPUB"),
    PDF("PDF"),
    DOCX("DOCX"),
    TXT("TXT"),
    HTML("HTML"),
}

data class DocumentImportProgress(
    val phase: String,
    val detail: String = "",
    val current: Int = 0,
    val total: Int? = null,
    val fraction: Float? = null,
)

class DocumentBookStore(
    private val context: Context,
) {
    private val root = File(context.filesDir, "books").apply { mkdirs() }
    private val progressPrefs = context.getSharedPreferences(
        "bolo_book_progress",
        Context.MODE_PRIVATE,
    )

    suspend fun listBooks(): List<BookRecord> = withContext(Dispatchers.IO) {
        root.listFiles()
            ?.asSequence()
            ?.filter { it.isDirectory && !it.name.endsWith(".importing") }
            ?.mapNotNull { loadMetadata(it) }
            ?.sortedByDescending { it.importedAt }
            ?.toList()
            ?: emptyList()
    }

    suspend fun importDocument(
        uri: Uri,
        onProgress: (DocumentImportProgress) -> Unit = {},
    ): Result<BookRecord> = withContext(Dispatchers.IO) {
        val sourceName = queryDisplayName(uri) ?: "Imported document"
        val mimeType = context.contentResolver.getType(uri).orEmpty()
        val sourceSize = querySize(uri)
        val temp = File(context.cacheDir, "bolo-import-${System.nanoTime()}.bin")
        temp.delete()

        var staging: File? = null

        try {
            currentCoroutineContext().ensureActive()
            onProgress(
                DocumentImportProgress(
                    phase = "Copying document",
                    detail = sourceName,
                    fraction = 0f,
                )
            )

            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var nextProgressAt = 0L

            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "Unable to open the selected document." }
                temp.outputStream().buffered().use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue

                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        copied += read

                        if (copied >= nextProgressAt) {
                            val fraction = sourceSize
                                ?.takeIf { it > 0L }
                                ?.let { (copied.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }
                            onProgress(
                                DocumentImportProgress(
                                    phase = "Copying document",
                                    detail = humanBytes(copied) +
                                        (sourceSize?.let { " / ${humanBytes(it)}" } ?: ""),
                                    fraction = fraction,
                                )
                            )
                            nextProgressAt = copied + COPY_PROGRESS_STEP_BYTES
                        }
                    }
                }
            }

            check(temp.length() > 0L) { "Selected document is empty." }
            currentCoroutineContext().ensureActive()

            val format = detectFormat(temp, sourceName, mimeType)
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val bookId = sha.take(20)
            val existingDir = File(root, bookId)

            loadMetadata(existingDir)?.let {
                temp.delete()
                onProgress(
                    DocumentImportProgress(
                        phase = "Already indexed",
                        detail = it.title,
                        fraction = 1f,
                    )
                )
                return@withContext Result.success(it)
            }

            val stagingDir = File(root, "$bookId.importing").apply {
                deleteRecursively()
                mkdirs()
            }
            staging = stagingDir
            val chaptersDir = File(stagingDir, "chapters").apply { mkdirs() }

            val parsed = when (format) {
                ImportFormat.EPUB -> parseEpub(
                    temp, bookId, sourceName, chaptersDir, onProgress
                )
                ImportFormat.PDF -> parsePdf(
                    temp, bookId, sourceName, chaptersDir, onProgress
                )
                ImportFormat.DOCX -> parseDocx(
                    temp, bookId, sourceName, chaptersDir, onProgress
                )
                ImportFormat.TXT -> parseTxt(
                    temp, bookId, sourceName, chaptersDir, onProgress
                )
                ImportFormat.HTML -> parseHtml(
                    temp, bookId, sourceName, chaptersDir, onProgress
                )
            }

            currentCoroutineContext().ensureActive()
            onProgress(
                DocumentImportProgress(
                    phase = "Saving index",
                    detail = "${parsed.chapters.size} sections · ${parsed.estimatedPages} pages",
                    fraction = 0.98f,
                )
            )

            writeMetadata(stagingDir, parsed)

            if (existingDir.exists()) existingDir.deleteRecursively()
            check(stagingDir.renameTo(existingDir)) {
                "Could not finish storing the imported document."
            }
            staging = null

            val reopened = loadMetadata(existingDir)
                ?: error("Document metadata could not be reopened.")

            onProgress(
                DocumentImportProgress(
                    phase = "Ready",
                    detail = reopened.title,
                    fraction = 1f,
                )
            )
            Result.success(reopened)
        } catch (cancelled: CancellationException) {
            staging?.deleteRecursively()
            temp.delete()
            throw cancelled
        } catch (t: Throwable) {
            staging?.deleteRecursively()
            temp.delete()
            Result.failure(t)
        } finally {
            temp.delete()
        }
    }

    suspend fun readChapter(
        bookId: String,
        chapterIndex: Int,
    ): String = withContext(Dispatchers.IO) {
        val book = loadMetadata(File(root, bookId))
            ?: error("Book not found.")
        val chapter = book.chapters.getOrNull(chapterIndex)
            ?: error("Section not found.")
        File(File(root, bookId), "chapters/${chapter.fileName}").readText()
    }

    suspend fun deleteBook(bookId: String) = withContext(Dispatchers.IO) {
        File(root, bookId).deleteRecursively()
        clearProgress(bookId)
    }

    fun loadProgress(bookId: String): BookProgress? {
        val base = "book:$bookId:"
        if (!progressPrefs.contains("${base}chapter")) return null

        return BookProgress(
            chapterIndex = progressPrefs.getInt("${base}chapter", 0),
            segmentStartWord = progressPrefs.getLong("${base}segmentWord", 0L),
            positionMs = progressPrefs.getLong("${base}positionMs", 0L),
            globalWord = progressPrefs.getLong("${base}globalWord", 0L),
            updatedAt = progressPrefs.getLong("${base}updatedAt", 0L),
        )
    }

    fun saveProgress(
        bookId: String,
        progress: BookProgress,
    ) {
        val base = "book:$bookId:"
        progressPrefs.edit()
            .putInt("${base}chapter", progress.chapterIndex)
            .putLong("${base}segmentWord", progress.segmentStartWord)
            .putLong("${base}positionMs", progress.positionMs)
            .putLong("${base}globalWord", progress.globalWord)
            .putLong("${base}updatedAt", progress.updatedAt)
            .apply()
    }

    fun clearProgress(bookId: String) {
        val base = "book:$bookId:"
        progressPrefs.edit()
            .remove("${base}chapter")
            .remove("${base}segmentWord")
            .remove("${base}positionMs")
            .remove("${base}globalWord")
            .remove("${base}updatedAt")
            .apply()
    }

    private fun detectFormat(
        file: File,
        sourceName: String,
        mimeType: String,
    ): ImportFormat {
        val lowerName = sourceName.lowercase(Locale.ROOT)
        val lowerMime = mimeType.lowercase(Locale.ROOT)

        when {
            lowerName.endsWith(".epub") || lowerMime == "application/epub+zip" ->
                return ImportFormat.EPUB
            lowerName.endsWith(".pdf") || lowerMime == "application/pdf" ->
                return ImportFormat.PDF
            lowerName.endsWith(".docx") ||
                lowerMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" ->
                return ImportFormat.DOCX
            lowerName.endsWith(".html") || lowerName.endsWith(".htm") ||
                lowerMime == "text/html" || lowerMime == "application/xhtml+xml" ->
                return ImportFormat.HTML
            lowerName.endsWith(".txt") || lowerMime.startsWith("text/plain") ->
                return ImportFormat.TXT
        }

        val header = file.inputStream().use { input ->
            ByteArray(8).also { input.read(it) }
        }
        if (header.copyOfRange(0, 5).toString(Charsets.US_ASCII) == "%PDF-") {
            return ImportFormat.PDF
        }

        if (header.size >= 4 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()) {
            runCatching {
                ZipFile(file).use { zip ->
                    when {
                        zip.getEntry("META-INF/container.xml") != null -> ImportFormat.EPUB
                        zip.getEntry("word/document.xml") != null -> ImportFormat.DOCX
                        else -> null
                    }
                }
            }.getOrNull()?.let { return it }
        }

        val prefix = file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
            CharArray(2048).let { chars ->
                val count = reader.read(chars)
                if (count > 0) String(chars, 0, count) else ""
            }
        }.trimStart().lowercase(Locale.ROOT)

        if (
            prefix.startsWith("<!doctype html") ||
            prefix.startsWith("<html") ||
            prefix.contains("<body")
        ) {
            return ImportFormat.HTML
        }

        if (lowerMime.startsWith("text/")) return ImportFormat.TXT

        error("Unsupported document type. Choose EPUB, PDF, DOCX, TXT, HTML or HTM.")
    }

    private suspend fun parseEpub(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
        onProgress: (DocumentImportProgress) -> Unit,
    ): BookRecord = ZipFile(file).use { zip ->
        val importContext = currentCoroutineContext()
        importContext.ensureActive()
        onProgress(
            DocumentImportProgress(
                phase = "Reading EPUB structure",
                detail = sourceName,
                fraction = 0.10f,
            )
        )

        val containerEntry = zip.getEntry("META-INF/container.xml")
            ?: error("This EPUB has no META-INF/container.xml.")
        val container = zip.getInputStream(containerEntry).use(::parseXml)

        val rootFileElement = elements(container, "rootfile").firstOrNull()
            ?: error("EPUB package document was not declared.")
        val opfPath = rootFileElement.getAttribute("full-path")
            .takeIf { it.isNotBlank() }
            ?: error("EPUB package path is empty.")

        val opfEntry = zip.getEntry(opfPath)
            ?: error("EPUB package document is missing.")
        val opf = zip.getInputStream(opfEntry).use(::parseXml)
        val opfDir = opfPath.substringBeforeLast('/', "")

        val title = elements(opf, "title")
            .firstOrNull()
            ?.textContent
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?: sourceName.substringBeforeLast('.')

        val author = elements(opf, "creator")
            .firstOrNull()
            ?.textContent
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown author"

        val manifest = elements(opf, "item").mapNotNull { item ->
            val id = item.getAttribute("id")
            val href = item.getAttribute("href")
            if (id.isBlank() || href.isBlank()) return@mapNotNull null

            ManifestItem(
                id = id,
                path = resolvePath(opfDir, href),
                mediaType = item.getAttribute("media-type"),
                properties = item.getAttribute("properties"),
            )
        }.associateBy { it.id }

        val tocTitles = buildTocTitleMap(zip, manifest.values)
        val spineItems = elements(opf, "itemref")
            .map { it.getAttribute("idref") }
            .filter { it.isNotBlank() }
            .mapNotNull(manifest::get)
            .filter { item ->
                item.mediaType.isBlank() ||
                    item.mediaType.contains("html", ignoreCase = true) ||
                    item.mediaType.contains("xhtml", ignoreCase = true)
            }

        check(spineItems.isNotEmpty()) { "This EPUB has no readable spine." }

        val writer = ChapterWriter(chaptersDir)
        val total = spineItems.size

        for ((index, item) in spineItems.withIndex()) {
            currentCoroutineContext().ensureActive()

            onProgress(
                DocumentImportProgress(
                    phase = "Indexing EPUB",
                    detail = "Chapter ${index + 1} of $total",
                    current = index + 1,
                    total = total,
                    fraction = (
                        0.12f +
                            0.83f * (index.toFloat() / total.toFloat())
                        ).coerceIn(0f, 0.95f),
                )
            )

            val entry = zip.getEntry(item.path) ?: continue
            val extraction = try {
                zip.getInputStream(entry).use { input ->
                    extractXhtml(input) { importContext.ensureActive() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                // Some EPUBs contain HTML that is not strict XML. Keep a
                // compatibility fallback, but only for the affected chapter.
                importContext.ensureActive()
                val html = zip.getInputStream(entry)
                    .bufferedReader()
                    .use { reader -> reader.readText() }
                XhtmlExtraction(
                    text = extractPlainText(html),
                    heading = extractHeading(html.take(32_768)),
                )
            }

            val chapterTitle = tocTitles[item.path]
                ?: extraction.heading
                ?: "Chapter ${writer.chapterCount + 1}"

            val added = writer.addCompleteSection(
                title = chapterTitle,
                text = extraction.text,
                checkCancelled = { importContext.ensureActive() },
                onChunkWritten = { indexedWords ->
                    onProgress(
                        DocumentImportProgress(
                            phase = "Indexing EPUB",
                            detail = "$chapterTitle · ${humanWordCount(indexedWords)} indexed",
                            current = index + 1,
                            total = total,
                            fraction = (
                                0.12f +
                                    0.83f * (index.toFloat() / total.toFloat())
                                ).coerceIn(0f, 0.95f),
                        )
                    )
                },
            )

            onProgress(
                DocumentImportProgress(
                    phase = "Indexing EPUB",
                    detail = if (added) chapterTitle else "Skipping empty section",
                    current = index + 1,
                    total = total,
                    fraction = (
                        0.12f +
                            0.83f * ((index + 1).toFloat() / total.toFloat())
                        ).coerceIn(0f, 0.95f),
                )
            )
        }

        val chapters = writer.finish()
        check(chapters.isNotEmpty()) { "No readable chapters were found in this EPUB." }

        BookRecord(
            id = bookId,
            title = title,
            author = author,
            sourceName = sourceName,
            format = ImportFormat.EPUB.label,
            importedAt = System.currentTimeMillis(),
            totalWords = writer.totalWords,
            chapters = chapters,
        )
    }

    private suspend fun parsePdf(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
        onProgress: (DocumentImportProgress) -> Unit,
    ): BookRecord {
        PDFBoxResourceLoader.init(context.applicationContext)

        PDDocument.load(file, MemoryUsageSetting.setupTempFileOnly()).use { document ->
            val pageCount = document.numberOfPages
            check(pageCount > 0) { "This PDF has no pages." }

            val info = document.documentInformation
            val title = info.title?.cleanText()?.takeIf { it.isNotBlank() }
                ?: sourceName.substringBeforeLast('.')
            val author = info.author?.cleanText()?.takeIf { it.isNotBlank() }
                ?: "Unknown author"

            val writer = ChapterWriter(chaptersDir)
            val pageAnchors = ArrayList<PageAnchor>(pageCount)
            val stripper = PDFTextStripper().apply {
                setSortByPosition(true)
            }

            for (pageIndex in 0 until pageCount) {
                currentCoroutineContext().ensureActive()
                val pageNumber = pageIndex + 1
                if (pageIndex % PDF_PAGES_PER_SECTION == 0) {
                    val end = minOf(pageCount, pageNumber + PDF_PAGES_PER_SECTION - 1)
                    writer.startSection(
                        if (end == pageNumber) "Page $pageNumber" else "Pages $pageNumber–$end"
                    )
                }

                pageAnchors += PageAnchor(
                    pageNumber = pageNumber,
                    globalWord = writer.nextGlobalWord(),
                )

                stripper.setStartPage(pageNumber)
                stripper.setEndPage(pageNumber)
                val pageText = stripper.getText(document)
                    .normalizePlainText()
                if (pageText.isNotBlank()) {
                    writer.addTextBlock(pageText)
                }

                if (
                    pageIndex == pageCount - 1 ||
                    pageIndex % PDF_PROGRESS_PAGE_STEP == 0
                ) {
                    onProgress(
                        DocumentImportProgress(
                            phase = "Indexing PDF",
                            detail = "Page $pageNumber of $pageCount",
                            current = pageNumber,
                            total = pageCount,
                            fraction = (
                                0.10f +
                                    0.85f * (pageNumber.toFloat() / pageCount.toFloat())
                                ).coerceIn(0f, 0.95f),
                        )
                    )
                }
            }

            val chapters = writer.finish()
            val totalWords = writer.totalWords
            val minimumUsefulWords = maxOf(20L, pageCount.toLong() * 3L)

            check(totalWords >= minimumUsefulWords && chapters.isNotEmpty()) {
                "This PDF has little or no extractable text. It appears to be scanned; OCR is required."
            }

            return BookRecord(
                id = bookId,
                title = title,
                author = author,
                sourceName = sourceName,
                format = ImportFormat.PDF.label,
                importedAt = System.currentTimeMillis(),
                totalWords = totalWords,
                chapters = chapters,
                pageAnchors = pageAnchors,
            )
        }
    }

    private suspend fun parseDocx(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
        onProgress: (DocumentImportProgress) -> Unit,
    ): BookRecord = ZipFile(file).use { zip ->
        val documentEntry = zip.getEntry("word/document.xml")
            ?: error("This DOCX has no word/document.xml.")

        var title = sourceName.substringBeforeLast('.')
        var author = "Unknown author"

        zip.getEntry("docProps/core.xml")?.let { coreEntry ->
            runCatching {
                val core = zip.getInputStream(coreEntry).use(::parseXml)
                title = elements(core, "title")
                    .firstOrNull()
                    ?.textContent
                    ?.cleanText()
                    ?.takeIf { it.isNotBlank() }
                    ?: title
                author = elements(core, "creator")
                    .firstOrNull()
                    ?.textContent
                    ?.cleanText()
                    ?.takeIf { it.isNotBlank() }
                    ?: author
            }
        }

        val writer = ChapterWriter(chaptersDir)
        var paragraphCount = 0
        zip.getInputStream(documentEntry).use { input ->
            streamDocxParagraphs(input) { paragraph, style ->
                val text = paragraph.normalizePlainText()
                if (text.isBlank()) return@streamDocxParagraphs

                if (isDocxHeading(style, text)) {
                    writer.startSection(text.take(180))
                } else {
                    writer.addParagraph(text)
                }

                paragraphCount += 1
                if (paragraphCount % DOCX_PROGRESS_PARAGRAPH_STEP == 0) {
                    currentCoroutineContext().ensureActive()
                    onProgress(
                        DocumentImportProgress(
                            phase = "Indexing DOCX",
                            detail = "$paragraphCount paragraphs",
                            current = paragraphCount,
                            total = null,
                            fraction = null,
                        )
                    )
                }
            }
        }
        currentCoroutineContext().ensureActive()
        onProgress(
            DocumentImportProgress(
                phase = "Indexing DOCX",
                detail = "$paragraphCount paragraphs",
                current = paragraphCount,
                total = null,
                fraction = 0.95f,
            )
        )

        val chapters = writer.finish()
        check(chapters.isNotEmpty()) { "No readable text was found in this DOCX." }

        BookRecord(
            id = bookId,
            title = title,
            author = author,
            sourceName = sourceName,
            format = ImportFormat.DOCX.label,
            importedAt = System.currentTimeMillis(),
            totalWords = writer.totalWords,
            chapters = chapters,
        )
    }

    private suspend fun parseTxt(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
        onProgress: (DocumentImportProgress) -> Unit,
    ): BookRecord {
        onProgress(
            DocumentImportProgress(
                phase = "Reading text",
                detail = sourceName,
                fraction = 0.25f,
            )
        )
        currentCoroutineContext().ensureActive()
        val writer = ChapterWriter(chaptersDir)
        val text = readTextFile(file)
        currentCoroutineContext().ensureActive()
        addStructuredPlainText(text, writer)
        onProgress(
            DocumentImportProgress(
                phase = "Indexing TXT",
                detail = "${writer.totalWords} words",
                fraction = 0.95f,
            )
        )
        val chapters = writer.finish()
        check(chapters.isNotEmpty()) { "No readable text was found in this TXT file." }

        return BookRecord(
            id = bookId,
            title = sourceName.substringBeforeLast('.'),
            author = "Unknown author",
            sourceName = sourceName,
            format = ImportFormat.TXT.label,
            importedAt = System.currentTimeMillis(),
            totalWords = writer.totalWords,
            chapters = chapters,
        )
    }

    private suspend fun parseHtml(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
        onProgress: (DocumentImportProgress) -> Unit,
    ): BookRecord {
        onProgress(
            DocumentImportProgress(
                phase = "Reading HTML",
                detail = sourceName,
                fraction = 0.20f,
            )
        )
        currentCoroutineContext().ensureActive()
        val html = readTextFile(file)
        val title = Regex(
            "<title\\b[^>]*>(.*?)</title>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html)?.groupValues?.getOrNull(1)
            ?.let(::htmlFragmentText)
            ?.takeIf { it.isNotBlank() }
            ?: sourceName.substringBeforeLast('.')

        val author = Regex(
            "<meta\\b[^>]*name\\s*=\\s*[\\\"']author[\\\"'][^>]*content\\s*=\\s*[\\\"']([^\\\"']+)[\\\"'][^>]*>",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.getOrNull(1)
            ?.cleanText()
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown author"

        val writer = ChapterWriter(chaptersDir)
        val headingRegex = Regex(
            "<h([1-3])\\b[^>]*>(.*?)</h\\1>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        )
        val headings = headingRegex.findAll(html).toList()

        if (headings.isEmpty()) {
            writer.addCompleteSection(title, extractPlainText(html))
        } else {
            val leading = html.substring(0, headings.first().range.first)
            val leadingText = extractPlainText(leading)
            if (leadingText.isNotBlank()) {
                writer.addCompleteSection(title, leadingText)
            }

            headings.forEachIndexed { index, match ->
                val sectionTitle = htmlFragmentText(match.groupValues[2])
                    .takeIf { it.isNotBlank() }
                    ?: "Section ${index + 1}"
                val contentStart = match.range.last + 1
                val contentEnd = headings.getOrNull(index + 1)?.range?.first ?: html.length
                val content = extractPlainText(html.substring(contentStart, contentEnd))
                writer.addCompleteSection(sectionTitle, content)
            }
        }

        onProgress(
            DocumentImportProgress(
                phase = "Indexing HTML",
                detail = "${writer.chapterCount} sections",
                fraction = 0.95f,
            )
        )

        val chapters = writer.finish()
        check(chapters.isNotEmpty()) { "No readable text was found in this HTML document." }

        return BookRecord(
            id = bookId,
            title = title,
            author = author,
            sourceName = sourceName,
            format = ImportFormat.HTML.label,
            importedAt = System.currentTimeMillis(),
            totalWords = writer.totalWords,
            chapters = chapters,
        )
    }

    private fun addStructuredPlainText(
        text: String,
        writer: ChapterWriter,
    ) {
        val paragraph = StringBuilder()

        fun flushParagraph() {
            val value = paragraph.toString().normalizePlainText()
            paragraph.setLength(0)
            if (value.isNotBlank()) writer.addParagraph(value)
        }

        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            if (line.isBlank()) {
                flushParagraph()
                continue
            }

            if (looksLikeHeading(line)) {
                flushParagraph()
                writer.startSection(line.take(180))
                continue
            }

            if (paragraph.isNotEmpty()) paragraph.append(' ')
            paragraph.append(line)
        }
        flushParagraph()
    }

    private suspend fun streamDocxParagraphs(
        input: InputStream,
        onParagraph: suspend (text: String, style: String?) -> Unit,
    ) {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, "UTF-8")
        }

        var inParagraph = false
        var inText = false
        var paragraphStyle: String? = null
        var paragraph = StringBuilder()
        var event = parser.eventType

        while (event != XmlPullParser.END_DOCUMENT) {
            currentCoroutineContext().ensureActive()
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name) {
                    "p" -> {
                        inParagraph = true
                        paragraphStyle = null
                        paragraph = StringBuilder()
                    }
                    "pStyle" -> if (inParagraph) {
                        paragraphStyle = attributeByLocalName(parser, "val")
                    }
                    "t" -> if (inParagraph) inText = true
                    "tab" -> if (inParagraph) paragraph.append('\t')
                    "br", "cr" -> if (inParagraph) paragraph.append('\n')
                }

                XmlPullParser.TEXT -> if (inParagraph && inText) {
                    paragraph.append(parser.text)
                }

                XmlPullParser.END_TAG -> when (parser.name) {
                    "t" -> inText = false
                    "p" -> {
                        if (inParagraph) {
                            onParagraph(paragraph.toString(), paragraphStyle)
                        }
                        inParagraph = false
                        inText = false
                    }
                }
            }
            event = parser.next()
        }
    }

    private fun attributeByLocalName(
        parser: XmlPullParser,
        localName: String,
    ): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == localName) {
                return parser.getAttributeValue(i)
            }
        }
        return null
    }

    private fun isDocxHeading(style: String?, text: String): Boolean {
        val normalizedStyle = style.orEmpty().replace(" ", "").lowercase(Locale.ROOT)
        return normalizedStyle.startsWith("heading") ||
            normalizedStyle == "title" ||
            normalizedStyle == "subtitle" ||
            looksLikeHeading(text)
    }

    private fun looksLikeHeading(text: String): Boolean {
        if (text.length !in 1..140) return false
        val compact = text.trim()
        return HEADING_REGEX.matches(compact) ||
            (compact.length <= 80 && compact.all { !it.isLetter() || it.isUpperCase() })
    }

    private fun buildTocTitleMap(
        zip: ZipFile,
        manifestItems: Collection<ManifestItem>,
    ): Map<String, String> {
        val result = linkedMapOf<String, String>()

        val nav = manifestItems.firstOrNull {
            it.properties.split(Regex("\\s+")).any { token -> token == "nav" }
        }
        if (nav != null) {
            zip.getEntry(nav.path)?.let { entry ->
                runCatching {
                    val doc = zip.getInputStream(entry).use(::parseXml)
                    val base = nav.path.substringBeforeLast('/', "")
                    for (anchor in elements(doc, "a")) {
                        val href = anchor.getAttribute("href")
                        val label = anchor.textContent.cleanText()
                        if (href.isNotBlank() && label.isNotBlank()) {
                            result[resolvePath(base, href)] = label
                        }
                    }
                }
            }
        }

        val ncx = manifestItems.firstOrNull {
            it.mediaType.equals("application/x-dtbncx+xml", ignoreCase = true)
        }
        if (ncx != null) {
            zip.getEntry(ncx.path)?.let { entry ->
                runCatching {
                    val doc = zip.getInputStream(entry).use(::parseXml)
                    val base = ncx.path.substringBeforeLast('/', "")
                    for (point in elements(doc, "navPoint")) {
                        val contents = point.getElementsByTagNameNS("*", "content")
                        val labels = point.getElementsByTagNameNS("*", "text")
                        if (contents.length <= 0 || labels.length <= 0) continue

                        val src = (contents.item(0) as? Element)
                            ?.getAttribute("src")
                            .orEmpty()
                        val label = labels.item(0)?.textContent?.cleanText().orEmpty()

                        if (src.isNotBlank() && label.isNotBlank()) {
                            result[resolvePath(base, src)] = label
                        }
                    }
                }
            }
        }

        return result
    }

    private fun writeMetadata(
        directory: File,
        book: BookRecord,
    ) {
        val chaptersJson = JSONArray()
        for (chapter in book.chapters) {
            val checkpointsJson = JSONArray()
            chapter.checkpoints.forEach { checkpoint ->
                checkpointsJson.put(
                    JSONObject()
                        .put("wordOffset", checkpoint.wordOffset)
                        .put("charOffset", checkpoint.charOffset)
                )
            }

            chaptersJson.put(
                JSONObject()
                    .put("index", chapter.index)
                    .put("title", chapter.title)
                    .put("fileName", chapter.fileName)
                    .put("wordCount", chapter.wordCount)
                    .put("startWord", chapter.startWord)
                    .put("checkpoints", checkpointsJson)
            )
        }

        val pagesJson = JSONArray()
        book.pageAnchors.forEach { anchor ->
            pagesJson.put(
                JSONObject()
                    .put("pageNumber", anchor.pageNumber)
                    .put("globalWord", anchor.globalWord)
            )
        }

        val json = JSONObject()
            .put("id", book.id)
            .put("title", book.title)
            .put("author", book.author)
            .put("sourceName", book.sourceName)
            .put("format", book.format)
            .put("importedAt", book.importedAt)
            .put("totalWords", book.totalWords)
            .put("chapters", chaptersJson)
            .put("pageAnchors", pagesJson)

        File(directory, METADATA_FILE).writeText(json.toString())
    }

    private fun loadMetadata(directory: File): BookRecord? {
        if (!directory.isDirectory) return null
        val metadata = File(directory, METADATA_FILE)
        if (!metadata.isFile) return null

        return runCatching {
            val json = JSONObject(metadata.readText())
            val chaptersJson = json.getJSONArray("chapters")
            val chapters = buildList {
                for (i in 0 until chaptersJson.length()) {
                    val c = chaptersJson.getJSONObject(i)
                    val checkpointsJson = c.optJSONArray("checkpoints")
                    val checkpoints = buildList {
                        if (checkpointsJson != null) {
                            for (j in 0 until checkpointsJson.length()) {
                                val point = checkpointsJson.getJSONObject(j)
                                add(
                                    WordCheckpoint(
                                        wordOffset = point.getLong("wordOffset"),
                                        charOffset = point.getInt("charOffset"),
                                    )
                                )
                            }
                        }
                    }

                    add(
                        BookChapter(
                            index = c.getInt("index"),
                            title = c.getString("title"),
                            fileName = c.getString("fileName"),
                            wordCount = c.getLong("wordCount"),
                            startWord = c.getLong("startWord"),
                            checkpoints = checkpoints,
                        )
                    )
                }
            }

            val pages = buildList {
                val pagesJson = json.optJSONArray("pageAnchors")
                if (pagesJson != null) {
                    for (i in 0 until pagesJson.length()) {
                        val p = pagesJson.getJSONObject(i)
                        add(
                            PageAnchor(
                                pageNumber = p.getInt("pageNumber"),
                                globalWord = p.getLong("globalWord"),
                            )
                        )
                    }
                }
            }

            val sourceName = json.optString("sourceName", "Imported document")
            BookRecord(
                id = json.getString("id"),
                title = json.getString("title"),
                author = json.optString("author", "Unknown author"),
                sourceName = sourceName,
                format = json.optString("format", inferFormatLabel(sourceName)),
                importedAt = json.getLong("importedAt"),
                totalWords = json.getLong("totalWords"),
                chapters = chapters,
                pageAnchors = pages,
            )
        }.getOrNull()
    }

    private fun inferFormatLabel(sourceName: String): String = when {
        sourceName.endsWith(".pdf", true) -> "PDF"
        sourceName.endsWith(".docx", true) -> "DOCX"
        sourceName.endsWith(".txt", true) -> "TXT"
        sourceName.endsWith(".html", true) || sourceName.endsWith(".htm", true) -> "HTML"
        else -> "EPUB"
    }

    private fun extractXhtml(
        input: InputStream,
        checkCancelled: () -> Unit = {},
    ): XhtmlExtraction {
        val parser = Xml.newPullParser().apply {
            setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            setInput(input, "UTF-8")
        }

        val text = StringBuilder()
        var heading: String? = null
        var headingBuffer: StringBuilder? = null
        var skipDepth = 0
        var event = parser.eventType
        var eventsSeen = 0

        fun appendBoundary() {
            if (text.isNotEmpty() && text.last() != '\n') {
                text.append('\n')
            }
        }

        while (event != XmlPullParser.END_DOCUMENT) {
            if (eventsSeen % 512 == 0) checkCancelled()
            eventsSeen += 1
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (name == "script" || name == "style") {
                        skipDepth += 1
                    } else if (skipDepth == 0) {
                        if (
                            name == "p" ||
                            name == "div" ||
                            name == "section" ||
                            name == "article" ||
                            name == "blockquote" ||
                            name == "li" ||
                            name == "br" ||
                            name.matches(Regex("h[1-6]"))
                        ) {
                            appendBoundary()
                        }
                        if (
                            heading == null &&
                            (name == "h1" || name == "h2" || name == "h3")
                        ) {
                            headingBuffer = StringBuilder()
                        }
                    }
                }

                XmlPullParser.TEXT -> if (skipDepth == 0) {
                    val value = parser.text
                    if (!value.isNullOrBlank()) {
                        text.append(value)
                        headingBuffer?.append(value)
                    }
                }

                XmlPullParser.END_TAG -> {
                    val name = parser.name.lowercase(Locale.ROOT)
                    if (name == "script" || name == "style") {
                        skipDepth = (skipDepth - 1).coerceAtLeast(0)
                    } else if (skipDepth == 0) {
                        if (
                            name == "p" ||
                            name == "div" ||
                            name == "section" ||
                            name == "article" ||
                            name == "blockquote" ||
                            name == "li" ||
                            name.matches(Regex("h[1-6]"))
                        ) {
                            appendBoundary()
                        }
                        if (
                            heading == null &&
                            (name == "h1" || name == "h2" || name == "h3")
                        ) {
                            heading = headingBuffer
                                ?.toString()
                                ?.cleanText()
                                ?.takeIf { it.isNotBlank() }
                            headingBuffer = null
                        }
                    }
                }
            }
            event = parser.next()
        }

        return XhtmlExtraction(
            text = text.toString().normalizePlainText(),
            heading = heading,
        )
    }

    private fun extractPlainText(html: String): String {
        val withoutNoise = html
            .replace(
                Regex(
                    "<script\\b[^>]*>.*?</script>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ),
                " ",
            )
            .replace(
                Regex(
                    "<style\\b[^>]*>.*?</style>",
                    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
                ),
                " ",
            )
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p\\s*>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("</h[1-6]\\s*>", RegexOption.IGNORE_CASE), "\n\n")

        return Html.fromHtml(withoutNoise, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .normalizePlainText()
    }

    private fun htmlFragmentText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .cleanText()

    private fun extractHeading(html: String): String? {
        val match = Regex(
            "<h[1-3]\\b[^>]*>(.*?)</h[1-3]>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html) ?: return null

        return htmlFragmentText(match.groupValues[1])
            .takeIf { it.isNotBlank() }
    }

    private fun readTextFile(file: File): String {
        val bytes = file.readBytes()
        if (bytes.isEmpty()) return ""

        val charset: Charset
        val offset: Int
        when {
            bytes.size >= 3 &&
                bytes[0] == 0xEF.toByte() &&
                bytes[1] == 0xBB.toByte() &&
                bytes[2] == 0xBF.toByte() -> {
                charset = Charsets.UTF_8
                offset = 3
            }
            bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte() -> {
                charset = Charsets.UTF_16LE
                offset = 2
            }
            bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte() -> {
                charset = Charsets.UTF_16BE
                offset = 2
            }
            else -> {
                charset = Charsets.UTF_8
                offset = 0
            }
        }

        val decoded = String(bytes, offset, bytes.size - offset, charset)
        if (charset == Charsets.UTF_8 && decoded.count { it == '\uFFFD' } > decoded.length / 100) {
            return runCatching {
                String(bytes, Charset.forName("windows-1252"))
            }.getOrDefault(decoded)
        }
        return decoded
    }

    private fun parseXml(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { isExpandEntityReferences = false }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
            runCatching {
                setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
            }
        }

        val builder = factory.newDocumentBuilder()
        builder.setEntityResolver { _, _ -> InputSource(StringReader("")) }
        return builder.parse(input)
    }

    private fun elements(
        document: Document,
        localName: String,
    ): List<Element> {
        val namespaced = document.getElementsByTagNameNS("*", localName)
        if (namespaced.length > 0) {
            return buildList {
                for (i in 0 until namespaced.length) {
                    (namespaced.item(i) as? Element)?.let(::add)
                }
            }
        }

        val plain = document.getElementsByTagName(localName)
        return buildList {
            for (i in 0 until plain.length) {
                (plain.item(i) as? Element)?.let(::add)
            }
        }
    }

    private fun resolvePath(
        baseDir: String,
        href: String,
    ): String {
        val decoded = Uri.decode(
            href.substringBefore('#')
                .substringBefore('?')
                .trim()
        )
        val joined = if (decoded.startsWith("/")) {
            decoded.removePrefix("/")
        } else if (baseDir.isBlank()) {
            decoded
        } else {
            "$baseDir/$decoded"
        }

        val stack = mutableListOf<String>()
        for (part in joined.replace('\\', '/').split('/')) {
            when {
                part.isBlank() || part == "." -> Unit
                part == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
                else -> stack.add(part)
            }
        }
        return stack.joinToString("/")
    }

    private fun String.cleanText(): String =
        replace(Regex("\\s+"), " ").trim()

    private fun String.normalizePlainText(): String {
        if (isEmpty()) return ""

        val out = StringBuilder(length)
        var pendingSpace = false
        var newlineRun = 0

        fun trimTrailingSpace() {
            if (out.isNotEmpty() && out.last() == ' ') {
                out.setLength(out.length - 1)
            }
        }

        for (raw in this) {
            val ch = if (raw == '\u00A0') ' ' else raw
            when {
                ch == '\r' -> Unit

                ch == '\n' -> {
                    trimTrailingSpace()
                    pendingSpace = false
                    if (out.isNotEmpty() && newlineRun < 2) {
                        out.append('\n')
                        newlineRun += 1
                    }
                }

                ch.isWhitespace() -> {
                    pendingSpace = true
                }

                else -> {
                    if (
                        pendingSpace &&
                        out.isNotEmpty() &&
                        out.last() != '\n'
                    ) {
                        out.append(' ')
                    }
                    pendingSpace = false
                    newlineRun = 0
                    out.append(ch)
                }
            }
        }

        return out.toString().trim()
    }

    private fun querySize(uri: Uri): Long? =
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            }
        }.getOrNull()

    private fun humanWordCount(words: Long): String = when {
        words >= 1_000_000L ->
            String.format(Locale.US, "%.1fM words", words / 1_000_000.0)
        words >= 1_000L ->
            String.format(Locale.US, "%.1fk words", words / 1_000.0)
        else -> "$words words"
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024L * 1024L ->
            String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0))
        bytes >= 1024L * 1024L ->
            String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024L ->
            String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun queryDisplayName(uri: Uri): String? =
        runCatching {
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

    private class ChapterWriter(
        private val chaptersDir: File,
    ) {
        private val _chapters = mutableListOf<BookChapter>()
        val chapterCount: Int get() = _chapters.size
        var totalWords: Long = 0L
            private set

        private var sectionTitle: String? = null
        private var sectionPart = 1
        private val buffer = StringBuilder()
        private var bufferWords = 0L
        private val bufferCheckpoints = mutableListOf<WordCheckpoint>()

        fun nextGlobalWord(): Long = totalWords + bufferWords

        fun startSection(title: String) {
            flush(continueSection = false)
            sectionTitle = title.cleanTitle()
            sectionPart = 1
        }

        /**
         * Fast path for EPUB/HTML sections that already arrive as one complete
         * text block. It scans word boundaries once, writes ~7,500-word chunks
         * directly, and creates navigation checkpoints during that same scan.
         */
        fun addCompleteSection(
            title: String,
            text: String,
            checkCancelled: () -> Unit = {},
            onChunkWritten: (Long) -> Unit = {},
        ): Boolean {
            flush(continueSection = false)
            sectionTitle = title.cleanTitle()
            sectionPart = 1

            val wrote = writeDirectChunks(
                text = text.trim(),
                checkCancelled = checkCancelled,
                onChunkWritten = onChunkWritten,
            )

            sectionTitle = null
            sectionPart = 1
            return wrote
        }

        fun addTextBlock(text: String) {
            text.split(Regex("\\n{2,}"))
                .asSequence()
                .map { it.normalizeBlock() }
                .filter { it.isNotBlank() }
                .forEach(::addParagraph)
        }

        fun addParagraph(text: String) {
            val normalized = text.normalizeBlock()
            if (normalized.isBlank()) return

            val words = countWords(normalized)
            if (words <= 0L) return

            if (words >= TARGET_SECTION_WORDS) {
                if (bufferWords > 0L) flush(continueSection = true)
                writeDirectChunks(normalized)
                return
            }

            if (
                bufferWords > 0L &&
                bufferWords + words > TARGET_SECTION_WORDS
            ) {
                flush(continueSection = true)
            }

            append(normalized, words)
        }

        fun finish(): List<BookChapter> {
            flush(continueSection = false)
            return _chapters.toList()
        }

        private fun append(
            text: String,
            words: Long,
        ) {
            val separatorLength = if (buffer.isEmpty()) 0 else 2
            if (separatorLength > 0) buffer.append("\n\n")
            val baseChar = buffer.length

            if (bufferWords == 0L) {
                val first = firstWordOffset(text)
                bufferCheckpoints += WordCheckpoint(
                    wordOffset = 0L,
                    charOffset = baseChar + first,
                )
            }

            var wordOffset = bufferWords
            var inWord = false
            text.forEachIndexed { index, ch ->
                val nonWhitespace = !ch.isWhitespace()
                if (nonWhitespace && !inWord) {
                    if (
                        wordOffset > 0L &&
                        wordOffset % CHECKPOINT_INTERVAL_WORDS == 0L
                    ) {
                        bufferCheckpoints += WordCheckpoint(
                            wordOffset = wordOffset,
                            charOffset = baseChar + index,
                        )
                    }
                    wordOffset += 1L
                }
                inWord = nonWhitespace
            }

            buffer.append(text)
            bufferWords += words
        }

        private fun flush(continueSection: Boolean) {
            val text = buffer.toString().trim()
            if (text.isNotBlank() && bufferWords > 0L) {
                writeChapter(
                    text = text,
                    words = bufferWords,
                    checkpoints = bufferCheckpoints.toList(),
                )
            }

            buffer.setLength(0)
            bufferWords = 0L
            bufferCheckpoints.clear()

            if (continueSection) {
                sectionPart += 1
            } else {
                sectionTitle = null
                sectionPart = 1
            }
        }

        private fun writeDirectChunks(
            text: String,
            checkCancelled: () -> Unit = {},
            onChunkWritten: (Long) -> Unit = {},
        ): Boolean {
            if (text.isBlank()) return false

            var chunkStart = -1
            var chunkWords = 0L
            var inWord = false
            var checkpoints = mutableListOf<WordCheckpoint>()
            var wrote = false

            fun flushAt(endExclusive: Int) {
                if (chunkStart < 0 || chunkWords <= 0L) return
                val chunk = text.substring(chunkStart, endExclusive)
                    .trimEnd()
                if (chunk.isBlank()) return

                writeChapter(
                    text = chunk,
                    words = chunkWords,
                    checkpoints = checkpoints.toList(),
                )
                wrote = true
                onChunkWritten(totalWords)
                sectionPart += 1
            }

            var index = 0
            while (index < text.length) {
                if (index % 65_536 == 0) checkCancelled()
                val nonWhitespace = !text[index].isWhitespace()

                if (nonWhitespace && !inWord) {
                    if (chunkStart < 0) {
                        chunkStart = index
                        chunkWords = 0L
                        checkpoints = mutableListOf(
                            WordCheckpoint(
                                wordOffset = 0L,
                                charOffset = 0,
                            )
                        )
                    } else if (chunkWords >= TARGET_SECTION_WORDS) {
                        flushAt(index)
                        chunkStart = index
                        chunkWords = 0L
                        checkpoints = mutableListOf(
                            WordCheckpoint(
                                wordOffset = 0L,
                                charOffset = 0,
                            )
                        )
                    }

                    if (
                        chunkWords > 0L &&
                        chunkWords % CHECKPOINT_INTERVAL_WORDS == 0L
                    ) {
                        checkpoints += WordCheckpoint(
                            wordOffset = chunkWords,
                            charOffset = index - chunkStart,
                        )
                    }

                    chunkWords += 1L
                }

                inWord = nonWhitespace
                index += 1
            }

            if (chunkStart >= 0 && chunkWords > 0L) {
                flushAt(text.length)
            }

            return wrote
        }

        private fun writeChapter(
            text: String,
            words: Long,
            checkpoints: List<WordCheckpoint>,
        ) {
            val index = _chapters.size
            val fileName = "%05d.txt".format(index)
            File(chaptersDir, fileName).writeText(text)

            val baseTitle = sectionTitle
                ?.takeIf { it.isNotBlank() }
                ?: "Section ${index + 1}"
            val title = if (sectionPart > 1) {
                "$baseTitle · Part $sectionPart"
            } else {
                baseTitle
            }

            _chapters += BookChapter(
                index = index,
                title = title.take(180),
                fileName = fileName,
                wordCount = words,
                startWord = totalWords,
                checkpoints = checkpoints,
            )
            totalWords += words
        }

        private fun firstWordOffset(text: String): Int {
            for (i in text.indices) {
                if (!text[i].isWhitespace()) return i
            }
            return 0
        }

        private fun String.cleanTitle(): String =
            replace(Regex("\\s+"), " ").trim().take(180)

        private fun String.normalizeBlock(): String =
            replace('\u00A0', ' ')
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n[ \\t]+"), "\n")
                .trim()
    }

    private companion object {
        const val METADATA_FILE = "book.json"
        const val TARGET_SECTION_WORDS = 7_500L
        const val CHECKPOINT_INTERVAL_WORDS = 500L
        const val PDF_PAGES_PER_SECTION = 20
        const val PDF_PROGRESS_PAGE_STEP = 10
        const val DOCX_PROGRESS_PARAGRAPH_STEP = 250
        const val COPY_PROGRESS_STEP_BYTES = 1L * 1024L * 1024L

        val HEADING_REGEX = Regex(
            """(?i)^(chapter|book|part|section|prologue|epilogue|introduction|appendix|अध्याय|भाग|खंड)\\b.*$"""
        )
    }
}
