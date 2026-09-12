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

const val CURRENT_LIBRARY_CATALOG_VERSION = 6

enum class CatalogFailureKind {
    READ,
    INVALID,
    UNSUPPORTED_VERSION,
}

data class CatalogLoadFailure(
    val kind: CatalogFailureKind,
    val message: String,
    val cause: Throwable? = null,
)

sealed interface BookCatalogState {
    data object Loading : BookCatalogState
    data object Missing : BookCatalogState
    data class Loaded(val library: BookLibraryState) : BookCatalogState
    data class Failed(val failure: CatalogLoadFailure) : BookCatalogState
}

sealed interface BookIndexLoadResult {
    val book: BookRecord

    data class Loaded(override val book: BookRecord) : BookIndexLoadResult
    data class Missing(override val book: BookRecord) : BookIndexLoadResult
    data class Failed(
        override val book: BookRecord,
        val message: String,
        val cause: Throwable? = null,
    ) : BookIndexLoadResult
}

interface BookLibraryStore {
    fun readCatalog(): BookCatalogState
    fun readIndex(book: BookRecord): BookIndexLoadResult
    fun saveCatalog(library: BookLibraryState)
    fun saveIndex(book: BookRecord)
    fun deleteIndex(bookId: String)
}

class BookLibraryRepository internal constructor(
    private val catalog: File,
    private val indexDirectory: File,
) : BookLibraryStore {
    constructor(context: Context) : this(
        File(context.applicationContext.filesDir, "book-library.json"),
        File(context.applicationContext.filesDir, "book-index"),
    )

    override fun readCatalog(): BookCatalogState {
        if (!catalog.isFile) {
            return BookCatalogState.Missing
        }
        val contents = try {
            catalog.readText(Charsets.UTF_8)
        } catch (failure: Throwable) {
            return BookCatalogState.Failed(
                CatalogLoadFailure(
                    CatalogFailureKind.READ,
                    failure.message ?: failure.javaClass.simpleName,
                    failure,
                ),
            )
        }
        val root = try {
            JSONObject(contents)
        } catch (failure: Throwable) {
            return BookCatalogState.Failed(
                CatalogLoadFailure(
                    CatalogFailureKind.INVALID,
                    "The library catalog is not valid JSON: ${failure.message ?: failure.javaClass.simpleName}",
                    failure,
                ),
            )
        }
        val version = runCatching { root.getInt("version") }.getOrElse { failure ->
            return BookCatalogState.Failed(
                CatalogLoadFailure(
                    CatalogFailureKind.UNSUPPORTED_VERSION,
                    "The library catalog has no supported version",
                    failure,
                ),
            )
        }
        if (version != CURRENT_LIBRARY_CATALOG_VERSION) {
            return BookCatalogState.Failed(
                CatalogLoadFailure(
                    CatalogFailureKind.UNSUPPORTED_VERSION,
                    "Library catalog version $version is unsupported; expected $CURRENT_LIBRARY_CATALOG_VERSION",
                ),
            )
        }
        return try {
            BookCatalogState.Loaded(parseCatalog(root))
        } catch (failure: Throwable) {
            BookCatalogState.Failed(
                CatalogLoadFailure(
                    CatalogFailureKind.INVALID,
                    "The library catalog is invalid: ${failure.message ?: failure.javaClass.simpleName}",
                    failure,
                ),
            )
        }
    }

    private fun parseCatalog(root: JSONObject): BookLibraryState {
        val tagArray = root.getJSONArray("tags")
        val selectedArray = root.getJSONArray("selected")
        val bookArray = root.getJSONArray("books")
        val folderArray = root.getJSONArray("folders")
        val tags = buildList<LibraryTagRecord> {
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
            for (index in 0 until selectedArray.length()) add(selectedArray.getString(index))
        }
        val books = buildList<BookRecord> {
            for (index in 0 until bookArray.length()) {
                val item = bookArray.getJSONObject(index)
                val id = item.getString("id")
                require(id.isNotBlank()) { "Library item $index has no ID" }
                require(none { it.id == id }) { "Duplicate library item ID: $id" }
                val kind = LibraryItemKind.valueOf(item.getString("kind"))
                val pageCount = item.getInt("pages")
                require(pageCount > 0) { "${item.optString("title", id)} has no readable pages" }
                val summary = BookRecord(
                    id = id,
                    title = item.getString("title"),
                    fileName = item.optString("fileName", item.getString("title")),
                    uri = item.getString("uri"),
                    kind = kind,
                    color = item.getInt("color"),
                    pageCount = pageCount,
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
                add(summary)
            }
        }
        selected.retainAll(books.mapTo(HashSet()) { it.id })
        val folders = buildList<LibraryFolderRecord> {
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

    override fun readIndex(book: BookRecord): BookIndexLoadResult {
        if (book.indexLoaded) return BookIndexLoadResult.Loaded(book)
        val file = indexFile(book.id)
        if (!file.isFile) return BookIndexLoadResult.Missing(book)
        return try {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val outline = root.getJSONArray("outline")
            val toc = root.getJSONArray("toc")
            val images = root.getJSONArray("images")
            BookIndexLoadResult.Loaded(
                book.copy(
                    pdfBookmarks = decodeBookmarks(
                        outline,
                        book.id,
                        when (book.kind) {
                            LibraryItemKind.IMAGE_COLLECTION -> BookmarkSource.IMAGE_FILE
                            LibraryItemKind.MARKDOWN -> BookmarkSource.MARKDOWN_HEADING
                            LibraryItemKind.PDF -> BookmarkSource.PDF_OUTLINE
                        },
                    ),
                    externalBookmarks = decodeBookmarks(
                        toc,
                        book.id,
                        BookmarkSource.EXTERNAL_TOC,
                    ),
                    imageFiles = decodeImageFiles(images),
                    indexLoaded = true,
                ),
            )
        } catch (failure: Throwable) {
            BookIndexLoadResult.Failed(
                book,
                failure.message ?: failure.javaClass.simpleName,
                failure,
            )
        }
    }

    override fun saveCatalog(library: BookLibraryState) {
        val books = library.books
        val selectedBookIds = library.selectedBookIds
        val folders = library.folders
        val tags = library.tags
        indexDirectory.mkdirs()
        val knownIds = books.mapTo(HashSet()) { it.id }
        val root = JSONObject()
            .put("version", CURRENT_LIBRARY_CATALOG_VERSION)
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

    override fun saveIndex(book: BookRecord) {
        require(book.indexLoaded) { "Cannot save an unloaded book index" }
        indexDirectory.mkdirs()
        writeIndex(book)
    }

    override fun deleteIndex(bookId: String) {
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
