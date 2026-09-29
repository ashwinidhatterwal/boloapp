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
import kotlinx.coroutines.Dispatchers
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

private enum class ImportFormat(val label: String) {
    EPUB("EPUB"),
    PDF("PDF"),
    DOCX("DOCX"),
    TXT("TXT"),
    HTML("HTML"),
}

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

    suspend fun importDocument(uri: Uri): Result<BookRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceName = queryDisplayName(uri) ?: "Imported document"
            val mimeType = context.contentResolver.getType(uri).orEmpty()
            val temp = File(context.cacheDir, "bolo-import-${System.nanoTime()}.bin")
            temp.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "Unable to open the selected document." }
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

            check(temp.length() > 0L) { "Selected document is empty." }

            val format = detectFormat(temp, sourceName, mimeType)
            val sha = digest.digest().joinToString("") { "%02x".format(it) }
            val bookId = sha.take(20)
            val existingDir = File(root, bookId)

            loadMetadata(existingDir)?.let {
                temp.delete()
                return@runCatching it
            }

            val staging = File(root, "$bookId.importing")
            staging.deleteRecursively()
            staging.mkdirs()
            val chaptersDir = File(staging, "chapters").apply { mkdirs() }

            try {
                val parsed = when (format) {
                    ImportFormat.EPUB -> parseEpub(temp, bookId, sourceName, chaptersDir)
                    ImportFormat.PDF -> parsePdf(temp, bookId, sourceName, chaptersDir)
                    ImportFormat.DOCX -> parseDocx(temp, bookId, sourceName, chaptersDir)
                    ImportFormat.TXT -> parseTxt(temp, bookId, sourceName, chaptersDir)
                    ImportFormat.HTML -> parseHtml(temp, bookId, sourceName, chaptersDir)
                }

                writeMetadata(staging, parsed)

                if (existingDir.exists()) existingDir.deleteRecursively()
                check(staging.renameTo(existingDir)) {
                    "Could not finish storing the imported document."
                }

                loadMetadata(existingDir)
                    ?: error("Document metadata could not be reopened.")
            } catch (t: Throwable) {
                staging.deleteRecursively()
                throw t
            } finally {
                temp.delete()
            }
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

    private fun parseEpub(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
    ): BookRecord = ZipFile(file).use { zip ->
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
        val spineIds = elements(opf, "itemref")
            .map { it.getAttribute("idref") }
            .filter { it.isNotBlank() }

        check(spineIds.isNotEmpty()) { "This EPUB has no readable spine." }

        val writer = ChapterWriter(chaptersDir)
        for (idref in spineIds) {
            val item = manifest[idref] ?: continue
            if (
                item.mediaType.isNotBlank() &&
                !item.mediaType.contains("html", ignoreCase = true) &&
                !item.mediaType.contains("xhtml", ignoreCase = true)
            ) {
                continue
            }

            val entry = zip.getEntry(item.path) ?: continue
            val html = zip.getInputStream(entry).bufferedReader().use { it.readText() }
            val text = extractPlainText(html)
            if (countWords(text) <= 0L) continue

            val chapterTitle = tocTitles[item.path]
                ?: extractHeading(html)
                ?: "Chapter ${writer.chapterCount + 1}"

            writer.startSection(chapterTitle)
            writer.addTextBlock(text)
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

    private fun parsePdf(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
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

    private fun parseDocx(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
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
        zip.getInputStream(documentEntry).use { input ->
            streamDocxParagraphs(input) { paragraph, style ->
                val text = paragraph.normalizePlainText()
                if (text.isBlank()) return@streamDocxParagraphs

                if (isDocxHeading(style, text)) {
                    writer.startSection(text.take(180))
                } else {
                    writer.addParagraph(text)
                }
            }
        }

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

    private fun parseTxt(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
    ): BookRecord {
        val writer = ChapterWriter(chaptersDir)
        val text = readTextFile(file)
        addStructuredPlainText(text, writer)
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

    private fun parseHtml(
        file: File,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
    ): BookRecord {
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
            writer.startSection(title)
            writer.addTextBlock(extractPlainText(html))
        } else {
            val leading = html.substring(0, headings.first().range.first)
            val leadingText = extractPlainText(leading)
            if (countWords(leadingText) > 0L) {
                writer.startSection(title)
                writer.addTextBlock(leadingText)
            }

            headings.forEachIndexed { index, match ->
                val sectionTitle = htmlFragmentText(match.groupValues[2])
                    .takeIf { it.isNotBlank() }
                    ?: "Section ${index + 1}"
                val contentStart = match.range.last + 1
                val contentEnd = headings.getOrNull(index + 1)?.range?.first ?: html.length
                val content = extractPlainText(html.substring(contentStart, contentEnd))
                writer.startSection(sectionTitle)
                writer.addTextBlock(content)
            }
        }

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

    private fun streamDocxParagraphs(
        input: InputStream,
        onParagraph: (text: String, style: String?) -> Unit,
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

    private fun String.normalizePlainText(): String =
        replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()

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

        fun nextGlobalWord(): Long = totalWords + bufferWords

        fun startSection(title: String) {
            flush(continueSection = false)
            sectionTitle = title.cleanTitle()
            sectionPart = 1
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

            if (words > HARD_SECTION_WORDS) {
                splitByWords(normalized, TARGET_SECTION_WORDS.toInt()).forEach { chunk ->
                    if (bufferWords > 0L) flush(continueSection = true)
                    append(chunk)
                    flush(continueSection = true)
                }
                return
            }

            if (
                bufferWords > 0L &&
                bufferWords + words > TARGET_SECTION_WORDS
            ) {
                flush(continueSection = true)
            }

            append(normalized)
        }

        fun finish(): List<BookChapter> {
            flush(continueSection = false)
            return _chapters.toList()
        }

        private fun append(text: String) {
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            buffer.append(text)
            bufferWords += countWords(text)
        }

        private fun flush(continueSection: Boolean) {
            val text = buffer.toString().trim()
            if (text.isNotBlank() && bufferWords > 0L) {
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
                    wordCount = bufferWords,
                    startWord = totalWords,
                    checkpoints = buildWordCheckpoints(text),
                )
                totalWords += bufferWords
            }

            buffer.setLength(0)
            bufferWords = 0L

            if (continueSection) {
                sectionPart += 1
            } else {
                sectionTitle = null
                sectionPart = 1
            }
        }

        private fun String.cleanTitle(): String =
            replace(Regex("\\s+"), " ").trim().take(180)

        private fun String.normalizeBlock(): String =
            replace('\u00A0', ' ')
                .replace(Regex("[ \\t]+"), " ")
                .replace(Regex("\\n[ \\t]+"), "\n")
                .trim()

        private fun splitByWords(text: String, maxWords: Int): List<String> {
            val matches = Regex("""\\S+""").findAll(text).toList()
            if (matches.size <= maxWords) return listOf(text)

            val chunks = mutableListOf<String>()
            var start = 0
            while (start < matches.size) {
                val endExclusive = minOf(matches.size, start + maxWords)
                val first = matches[start].range.first
                val last = matches[endExclusive - 1].range.last + 1
                chunks += text.substring(first, last).trim()
                start = endExclusive
            }
            return chunks
        }
    }

    private companion object {
        const val METADATA_FILE = "book.json"
        const val TARGET_SECTION_WORDS = 7_500L
        const val HARD_SECTION_WORDS = 12_000L
        const val PDF_PAGES_PER_SECTION = 20

        val HEADING_REGEX = Regex(
            """(?i)^(chapter|book|part|section|prologue|epilogue|introduction|appendix|अध्याय|भाग|खंड)\\b.*$"""
        )
    }
}
