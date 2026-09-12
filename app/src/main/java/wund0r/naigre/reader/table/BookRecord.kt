// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import wund0r.naigre.reader.navigation.BookmarkEntry
import java.security.MessageDigest

const val CURRENT_BOOK_INDEX_VERSION = 6

enum class LibraryItemKind {
    PDF,
    MARKDOWN,
    IMAGE_COLLECTION,
}

data class ImageFileRecord(
    val uri: String,
    val fileName: String,
    val relativePath: String,
    val size: Long? = null,
    val lastModified: Long? = null,
)

data class LibraryFolderRecord(
    val uri: String,
    val label: String,
)

data class LibraryTagRecord(
    val id: String,
    val name: String,
)

data class BookRecord(
    val id: String,
    val title: String,
    val fileName: String = title,
    val uri: String,
    val kind: LibraryItemKind = LibraryItemKind.PDF,
    val color: Int,
    val pageCount: Int,
    val pdfBookmarks: List<BookmarkEntry>,
    val imageFiles: List<ImageFileRecord> = emptyList(),
    val externalBookmarks: List<BookmarkEntry> = emptyList(),
    val externalTocLabel: String? = null,
    val externalTocUri: String? = null,
    val externalTocSize: Long? = null,
    val externalTocLastModified: Long? = null,
    val externalTocFingerprint: String? = null,
    val indexVersion: Int = CURRENT_BOOK_INDEX_VERSION,
    val storedBookmarkCount: Int = 0,
    val indexLoaded: Boolean = true,
    val sourceSize: Long? = null,
    val sourceLastModified: Long? = null,
    val sourceFingerprint: String? = null,
    val sourceRevisionToken: String? = null,
    val tagIds: Set<String> = emptySet(),
) {
    val bookmarkCount: Int
        get() = if (indexLoaded) {
            (pdfBookmarks + externalBookmarks).distinctBy { it.identityKey }.size
        } else {
            storedBookmarkCount
    }
}

/** Applies only the separately persisted index payload to the current catalog record. */
fun mergeHydratedBookIndex(current: BookRecord, hydrated: BookRecord): BookRecord {
    require(current.id == hydrated.id)
    return current.copy(
        pdfBookmarks = hydrated.pdfBookmarks,
        externalBookmarks = hydrated.externalBookmarks,
        imageFiles = hydrated.imageFiles,
        storedBookmarkCount = hydrated.bookmarkCount,
        indexLoaded = hydrated.indexLoaded,
    )
}

/** Source work owns the derived record, while color and tags remain user-owned. */
fun mergeSourceBookResult(current: BookRecord, sourceResult: BookRecord): BookRecord {
    require(current.id == sourceResult.id)
    return sourceResult.copy(color = current.color, tagIds = current.tagIds)
}

/**
 * Stable identity for the source generation represented by a library record.
 *
 * PDFs normally use provider size/modified stamps. Markdown adds its content fingerprint, while
 * image albums include every child stamp. Explicit refresh advances [sourceRevisionToken], so it
 * remains authoritative even when a provider exposes neither a useful size nor timestamp.
 */
fun BookRecord.sourceRevisionKey(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: Any?) {
        digest.update((value?.toString() ?: "?").toByteArray(Charsets.UTF_8))
        digest.update(0.toByte())
    }

    add(kind.name)
    add(uri)
    add(sourceRevisionToken)
    when (kind) {
        LibraryItemKind.PDF -> {
            add(sourceSize)
            add(sourceLastModified)
            add(sourceFingerprint)
        }
        LibraryItemKind.MARKDOWN -> {
            add(sourceSize)
            add(sourceLastModified)
            add(sourceFingerprint)
        }
        LibraryItemKind.IMAGE_COLLECTION -> imageFiles.forEach { image ->
            add(image.uri)
            add(image.size)
            add(image.lastModified)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
