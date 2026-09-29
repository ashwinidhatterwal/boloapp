package com.offlinenarrator.benchmark.book

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private data class ManifestItem(
    val id: String,
    val path: String,
    val mediaType: String,
    val properties: String,
)

class EpubBookStore(
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

    suspend fun importEpub(uri: Uri): Result<BookRecord> = withContext(Dispatchers.IO) {
        runCatching {
            val sourceName = queryDisplayName(uri) ?: "Imported book.epub"

            val temp = File(context.cacheDir, "bolo-import-${System.nanoTime()}.epub")
            temp.delete()

            val digest = MessageDigest.getInstance("SHA-256")
            context.contentResolver.openInputStream(uri).use { input ->
                checkNotNull(input) { "Unable to open selected EPUB." }
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

            check(temp.length() > 100L) { "Selected EPUB is empty." }

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
                val parsed = ZipFile(temp).use { zip ->
                    parseBook(
                        zip = zip,
                        bookId = bookId,
                        sourceName = sourceName,
                        chaptersDir = chaptersDir,
                    )
                }

                writeMetadata(staging, parsed)

                if (existingDir.exists()) existingDir.deleteRecursively()
                check(staging.renameTo(existingDir)) {
                    "Could not finish storing the imported book."
                }

                loadMetadata(existingDir)
                    ?: error("Book metadata could not be reopened.")
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
            ?: error("Chapter not found.")
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

    private fun parseBook(
        zip: ZipFile,
        bookId: String,
        sourceName: String,
        chaptersDir: File,
    ): BookRecord {
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

        check(spineIds.isNotEmpty()) {
            "This EPUB has no readable spine."
        }

        val chapters = mutableListOf<BookChapter>()
        var cumulativeWords = 0L

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
            val words = countWords(text)
            if (words <= 0L) continue
            val checkpoints = buildWordCheckpoints(text)

            val chapterIndex = chapters.size
            val fileName = "%05d.txt".format(chapterIndex)
            File(chaptersDir, fileName).writeText(text)

            val chapterTitle = tocTitles[item.path]
                ?: extractHeading(html)
                ?: "Chapter ${chapterIndex + 1}"

            chapters += BookChapter(
                index = chapterIndex,
                title = chapterTitle.cleanText().take(160),
                fileName = fileName,
                wordCount = words,
                startWord = cumulativeWords,
                checkpoints = checkpoints,
            )

            cumulativeWords += words
        }

        check(chapters.isNotEmpty()) {
            "No readable chapters were found in this EPUB."
        }

        return BookRecord(
            id = bookId,
            title = title,
            author = author,
            sourceName = sourceName,
            importedAt = System.currentTimeMillis(),
            totalWords = cumulativeWords,
            chapters = chapters,
        )
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

        val json = JSONObject()
            .put("id", book.id)
            .put("title", book.title)
            .put("author", book.author)
            .put("sourceName", book.sourceName)
            .put("importedAt", book.importedAt)
            .put("totalWords", book.totalWords)
            .put("chapters", chaptersJson)

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

            BookRecord(
                id = json.getString("id"),
                title = json.getString("title"),
                author = json.optString("author", "Unknown author"),
                sourceName = json.optString("sourceName", "Imported EPUB"),
                importedAt = json.getLong("importedAt"),
                totalWords = json.getLong("totalWords"),
                chapters = chapters,
            )
        }.getOrNull()
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
            .replace('\u00A0', ' ')
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    private fun extractHeading(html: String): String? {
        val match = Regex(
            "<h[1-3]\\b[^>]*>(.*?)</h[1-3]>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
        ).find(html) ?: return null

        return Html.fromHtml(match.groupValues[1], Html.FROM_HTML_MODE_LEGACY)
            .toString()
            .cleanText()
            .takeIf { it.isNotBlank() }
    }

    private fun parseXml(input: InputStream): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isXIncludeAware = false
            isExpandEntityReferences = false
            runCatching {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-general-entities", false)
            }
            runCatching {
                setFeature("http://xml.org/sax/features/external-parameter-entities", false)
            }
        }
        return factory.newDocumentBuilder().parse(input)
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

    private companion object {
        const val METADATA_FILE = "book.json"
    }
}
