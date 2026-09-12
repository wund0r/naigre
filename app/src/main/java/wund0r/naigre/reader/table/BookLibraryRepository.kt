// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import wund0r.naigre.reader.navigation.BookmarkEntry
import wund0r.naigre.reader.navigation.BookmarkSource
import java.io.File
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

data class BookLibraryState(
    val books: List<BookRecord>,
    val selectedBookIds: LinkedHashSet<String>,
    val folders: List<LibraryFolderRecord>,
    val tags: List<LibraryTagRecord>,
)

class BookLibraryRepository(context: Context) {
    private val catalog = File(context.filesDir, "book-library.json")
    private val indexDirectory = File(context.filesDir, "book-index")

    @Synchronized
    fun load(): BookLibraryState {
        if (!catalog.isFile) {
            return BookLibraryState(emptyList(), linkedSetOf(), emptyList(), emptyList())
        }
        val root = JSONObject(catalog.readText(Charsets.UTF_8))
        val tags = buildList<LibraryTagRecord> {
            val tagArray = root.optJSONArray("tags") ?: JSONArray()
            for (index in 0 until tagArray.length()) {
                val item = tagArray.getJSONObject(index)
                val id = item.optString("id")
                val name = item.optString("name").trim()
                if (id.isNotBlank() && name.isNotBlank() && none { it.id == id }) {
                    add(LibraryTagRecord(id, name))
                }
            }
        }
        val knownTagIds = tags.mapTo(HashSet()) { it.id }
        val selected = linkedSetOf<String>().apply {
            val array = root.optJSONArray("selected")
            if (array != null) {
                for (index in 0 until array.length()) add(array.getString(index))
            }
        }
        val array = root.optJSONArray("books") ?: JSONArray()
        val books = buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val id = item.getString("id")
                val kind = runCatching {
                    LibraryItemKind.valueOf(item.optString("kind", LibraryItemKind.PDF.name))
                }.getOrDefault(LibraryItemKind.PDF)
                val summary = BookRecord(
                    id = id,
                    title = item.getString("title"),
                    fileName = item.optString("fileName", item.getString("title")),
                    uri = item.getString("uri"),
                    kind = kind,
                    color = item.getInt("color"),
                    pageCount = item.getInt("pages"),
                    pdfBookmarks = emptyList(),
                    externalBookmarks = emptyList(),
                    externalTocLabel = item.optNullableString("tocLabel"),
                    externalTocUri = item.optNullableString("tocUri"),
                    externalTocSize = item.optNullableLong("tocSize"),
                    externalTocLastModified = item.optNullableLong("tocModified"),
                    externalTocFingerprint = item.optNullableString("tocFingerprint"),
                    indexVersion = item.optInt("indexVersion", 1),
                    storedBookmarkCount = item.optInt("bookmarkCount", 0),
                    indexLoaded = false,
                    sourceSize = item.optNullableLong("sourceSize"),
                    sourceLastModified = item.optNullableLong("sourceModified"),
                    sourceFingerprint = item.optNullableString("sourceFingerprint"),
                    sourceRevisionToken = item.optNullableString("sourceRevision"),
                    tagIds = item.optJSONArray("tagIds").toStringSet().filterTo(linkedSetOf()) {
                        it in knownTagIds
                    },
                )
                add(if (id in selected) runCatching { hydrate(summary) }.getOrDefault(summary) else summary)
            }
        }
        selected.retainAll(books.mapTo(HashSet()) { it.id })
        val folders = buildList<LibraryFolderRecord> {
            val folderArray = root.optJSONArray("folders") ?: JSONArray()
            for (index in 0 until folderArray.length()) {
                val item = folderArray.getJSONObject(index)
                val uri = item.optString("uri")
                if (uri.isNotBlank() && none { it.uri == uri }) {
                    add(LibraryFolderRecord(uri, item.optString("label", "Folder")))
                }
            }
        }
        return BookLibraryState(books, selected, folders, tags)
    }

    @Synchronized
    fun hydrate(book: BookRecord): BookRecord {
        if (book.indexLoaded) return book
        val file = indexFile(book.id)
        if (!file.isFile) return book
        val root = JSONObject(file.readText(Charsets.UTF_8))
        return book.copy(
            pdfBookmarks = decodeBookmarks(
                root.optJSONArray("outline"),
                book.id,
                when (book.kind) {
                    LibraryItemKind.IMAGE_COLLECTION -> BookmarkSource.IMAGE_FILE
                    LibraryItemKind.MARKDOWN -> BookmarkSource.MARKDOWN_HEADING
                    LibraryItemKind.PDF -> BookmarkSource.PDF_OUTLINE
                },
            ),
            externalBookmarks = decodeBookmarks(root.optJSONArray("toc"), book.id, BookmarkSource.EXTERNAL_TOC),
            imageFiles = decodeImageFiles(root.optJSONArray("images")),
            indexLoaded = true,
        )
    }

    @Synchronized
    fun save(
        books: List<BookRecord>,
        selectedBookIds: Set<String>,
        folders: List<LibraryFolderRecord>,
        tags: List<LibraryTagRecord>,
    ) {
        indexDirectory.mkdirs()
        val knownIds = books.mapTo(HashSet()) { it.id }
        val root = JSONObject()
            .put("version", 6)
            .put(
                "selected",
                JSONArray().apply {
                    selectedBookIds.filter { it in knownIds }.forEach(::put)
                },
            )
            .put(
                "books",
                JSONArray().apply {
                    books.forEach { book ->
                        put(
                            JSONObject()
                                .put("id", book.id)
                                .put("title", book.title)
                                .put("fileName", book.fileName)
                                .put("uri", book.uri)
                                .put("kind", book.kind.name)
                                .put("color", book.color)
                                .put("pages", book.pageCount)
                                .put("bookmarkCount", book.bookmarkCount)
                                .put("tocLabel", book.externalTocLabel ?: JSONObject.NULL)
                                .put("tocUri", book.externalTocUri ?: JSONObject.NULL)
                                .put("tocSize", book.externalTocSize ?: JSONObject.NULL)
                                .put("tocModified", book.externalTocLastModified ?: JSONObject.NULL)
                                .put("tocFingerprint", book.externalTocFingerprint ?: JSONObject.NULL)
                                .put("indexVersion", book.indexVersion)
                                .put("sourceSize", book.sourceSize ?: JSONObject.NULL)
                                .put("sourceModified", book.sourceLastModified ?: JSONObject.NULL)
                                .put("sourceFingerprint", book.sourceFingerprint ?: JSONObject.NULL)
                                .put("sourceRevision", book.sourceRevisionToken ?: JSONObject.NULL)
                                .put("tagIds", JSONArray(book.tagIds.toList())),
                        )
                    }
                },
            )
            .put(
                "tags",
                JSONArray().apply {
                    tags.distinctBy { it.id }.forEach { tag ->
                        put(JSONObject().put("id", tag.id).put("name", tag.name))
                    }
                },
            )
            .put(
                "folders",
                JSONArray().apply {
                    folders.distinctBy { it.uri }.forEach { folder ->
                        put(JSONObject().put("uri", folder.uri).put("label", folder.label))
                    }
                },
            )
        atomicWrite(catalog, root.toString())
    }

    @Synchronized
    fun saveIndex(book: BookRecord) {
        require(book.indexLoaded) { "Cannot save an unloaded book index" }
        indexDirectory.mkdirs()
        writeIndex(book)
    }

    @Synchronized
    fun delete(bookId: String) {
        Files.deleteIfExists(indexFile(bookId).toPath())
    }

    private fun writeIndex(book: BookRecord) {
        val root = JSONObject()
            .put("outline", encodeBookmarks(book.pdfBookmarks))
            .put("toc", encodeBookmarks(book.externalBookmarks))
            .put("images", encodeImageFiles(book.imageFiles))
        atomicWrite(indexFile(book.id), root.toString())
    }

    private fun indexFile(bookId: String): File = File(indexDirectory, "$bookId.json")

    private fun atomicWrite(target: File, contents: String) {
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary, false).use { output ->
            output.write(contents.toByteArray(Charsets.UTF_8))
            output.fd.sync()
        }
        try {
            Files.move(
                temporary.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encodeBookmarks(entries: List<BookmarkEntry>): JSONArray = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject()
                    .put("title", entry.title)
                    .put("page", entry.pageIndex)
                    .put("path", entry.path)
                    .put("y", entry.destinationY ?: JSONObject.NULL)
                    .put("destination", entry.destinationKey ?: JSONObject.NULL)
                    .put("stableKey", entry.stableKey ?: JSONObject.NULL),
            )
        }
    }

    private fun encodeImageFiles(entries: List<ImageFileRecord>): JSONArray = JSONArray().apply {
        entries.forEach { entry ->
            put(
                JSONObject()
                    .put("uri", entry.uri)
                    .put("fileName", entry.fileName)
                    .put("relativePath", entry.relativePath)
                    .put("size", entry.size ?: JSONObject.NULL)
                    .put("modified", entry.lastModified ?: JSONObject.NULL),
            )
        }
    }

    private fun decodeBookmarks(
        array: JSONArray?,
        bookId: String,
        source: BookmarkSource,
    ): List<BookmarkEntry> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                BookmarkEntry(
                    bookId = bookId,
                    title = item.getString("title"),
                    pageIndex = item.getInt("page"),
                    path = item.optString("path", item.getString("title")),
                    source = source,
                    destinationY = if (item.isNull("y")) null else item.getDouble("y").toFloat(),
                    destinationKey = item.optNullableString("destination"),
                    stableKey = item.optNullableString("stableKey"),
                ),
            )
        }
    }

    private fun decodeImageFiles(array: JSONArray?): List<ImageFileRecord> = buildList {
        if (array == null) return@buildList
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            add(
                ImageFileRecord(
                    uri = item.getString("uri"),
                    fileName = item.getString("fileName"),
                    relativePath = item.optString("relativePath", item.getString("fileName")),
                    size = item.optNullableLong("size"),
                    lastModified = item.optNullableLong("modified"),
                ),
            )
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (isNull(key) || !has(key)) null else optLong(key)

    private fun JSONArray?.toStringSet(): Set<String> = buildSet {
        val array = this@toStringSet ?: return@buildSet
        for (index in 0 until array.length()) {
            array.optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}
