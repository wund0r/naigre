// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import wund0r.naigre.reader.navigation.BookmarkEntry
import wund0r.naigre.reader.navigation.BookmarkSource
import wund0r.naigre.reader.table.BookRecord
import wund0r.naigre.reader.table.LibraryItemKind
import java.util.Locale

/** Changes to visible Markdown also invalidate cached headings and extracted text. */
const val MARKDOWN_CONTENT_VERSION = 1

data class WikiLinkSpec(val target: String, val alias: String? = null) {
    val path: String get() = target.substringBefore('#')
    val label: String get() = alias?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/').ifBlank { target }
    val pageNumber: Int? get() = target.substringAfter('#', "").removePrefix("page=").toIntOrNull()
    val hasPage: Boolean get() = '#' in target

    val valid: Boolean get() = normalizeWikiPath(path) != null &&
        path.substringAfterLast('.', "").lowercase(Locale.ROOT) in setOf("pdf", "md", "markdown", "jpg", "jpeg", "png", "webp") &&
        (!hasPage || target.substringAfter('#').startsWith("page=") && (pageNumber ?: 0) > 0) &&
        (!hasPage || path.endsWith(".pdf", ignoreCase = true))

    companion object {
        fun parse(body: String): WikiLinkSpec? {
            val target = body.substringBefore('|').trim()
            if (target.isBlank() || body.any { it in "\r\n[]" }) return null
            return WikiLinkSpec(target, body.substringAfter('|', "").trim().takeIf { it.isNotEmpty() })
        }
    }
}

internal fun normalizeWikiPath(path: String): String? {
    val parts = path.removePrefix("./").split('/')
    if (path.startsWith('/') || ':' in path || parts.any { it.isBlank() || it == ".." || it == "." }) return null
    return parts.joinToString("/").lowercase(Locale.ROOT)
}

data class WikiLinkDestination(val bookId: String, val imageUri: String? = null) {
    /** Re-resolve the image's page index after an album refresh/reorder. */
    fun entry(book: BookRecord, spec: WikiLinkSpec): BookmarkEntry? {
        if (book.id != bookId || !spec.valid) return null
        val page = if (imageUri != null) book.imageFiles.indexOfFirst { it.uri == imageUri } else (spec.pageNumber ?: 1) - 1
        if (page !in 0 until book.pageCount || spec.hasPage && book.kind != LibraryItemKind.PDF) return null
        return BookmarkEntry(
            bookId = bookId,
            title = spec.label,
            pageIndex = page,
            source = if (imageUri != null) BookmarkSource.IMAGE_FILE else BookmarkSource.FILE_ROOT,
            stableKey = when {
                imageUri != null -> "image:$imageUri"
                spec.hasPage && page > 0 -> "wiki-page:$page"
                else -> "file-root"
            },
        )
    }
}

sealed interface WikiLinkResolution {
    data class Matches(val destinations: List<WikiLinkDestination>, val onTable: Boolean) : WikiLinkResolution
    data object Missing : WikiLinkResolution
    data object Invalid : WikiLinkResolution
}

/** Catalogue lookup only: never resolves a filesystem path or opens a document provider. */
object WikiLinkResolver {
    fun resolve(spec: WikiLinkSpec, books: List<BookRecord>, selectedIds: Set<String>): WikiLinkResolution {
        if (!spec.valid) return WikiLinkResolution.Invalid
        val selected = candidates(spec, books.filter { it.id in selectedIds })
        if (selected.isNotEmpty()) return WikiLinkResolution.Matches(selected, onTable = true)
        val library = candidates(spec, books.filter { it.id !in selectedIds })
        return if (library.isEmpty()) WikiLinkResolution.Missing else WikiLinkResolution.Matches(library, onTable = false)
    }

    fun mayContain(spec: WikiLinkSpec, book: BookRecord): Boolean {
        val path = normalizeWikiPath(spec.path) ?: return false
        if (book.kind != LibraryItemKind.IMAGE_COLLECTION) {
            return path == normalizeWikiPath(book.sourceRelativePath ?: book.fileName) ||
                '/' !in path && path == normalizeWikiPath(book.fileName)
        }
        if (spec.hasPage || path.substringAfterLast('.', "") !in setOf("jpg", "jpeg", "png", "webp")) return false
        if ('/' !in path) return true
        val album = normalizeWikiPath(book.sourceRelativePath?.takeIf { it.isNotBlank() } ?: book.fileName) ?: return false
        return path.substringBeforeLast('/') == album
    }

    private fun candidates(spec: WikiLinkSpec, books: List<BookRecord>): List<WikiLinkDestination> {
        val path = checkNotNull(normalizeWikiPath(spec.path))
        return books.flatMap { book ->
            when {
                !mayContain(spec, book) -> emptyList()
                book.kind != LibraryItemKind.IMAGE_COLLECTION -> listOf(WikiLinkDestination(book.id))
                spec.hasPage -> emptyList()
                else -> book.imageFiles.filter { image ->
                    val album = book.sourceRelativePath?.takeIf { it.isNotBlank() } ?: book.fileName
                    path == normalizeWikiPath("$album/${image.relativePath}") ||
                        '/' !in path && path == normalizeWikiPath(image.fileName)
                }.map { WikiLinkDestination(book.id, it.uri) }
            }
        }.distinct()
    }
}
