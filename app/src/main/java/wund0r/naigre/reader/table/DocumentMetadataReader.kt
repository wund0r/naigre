// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns

data class DocumentMetadata(
    val fileName: String?,
    val size: Long?,
    val lastModified: Long?,
)

/** Reads the best source stamps exposed by an Android document provider. */
class DocumentMetadataReader(context: Context) {
    private val contentResolver = context.contentResolver

    fun displayName(uri: Uri): String? = query(uri).fileName

    fun query(uri: Uri): DocumentMetadata {
        var name: String? = null
        var size: Long? = null
        var modified: Long? = null
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(
                    OpenableColumns.DISPLAY_NAME,
                    OpenableColumns.SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                ),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { index ->
                    if (!cursor.isNull(index)) name = cursor.getString(index)
                }
                cursor.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 }?.let { index ->
                    if (!cursor.isNull(index)) size = cursor.getLong(index).takeIf { it >= 0L }
                }
                cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    .takeIf { it >= 0 }
                    ?.let { index ->
                        if (!cursor.isNull(index)) modified = cursor.getLong(index).takeIf { it > 0L }
                    }
            }
        }
        if (name == null) runCatching {
            // Some providers reject COLUMN_LAST_MODIFIED rather than returning it as absent.
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) name = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        size = cursor.getLong(sizeIndex).takeIf { it >= 0L }
                    }
                }
            }
        }
        return DocumentMetadata(name, size, modified)
    }
}

fun sourceMetadataChanged(book: BookRecord, metadata: DocumentMetadata): Boolean =
    book.sourceSize != null && metadata.size != null && book.sourceSize != metadata.size ||
        book.sourceLastModified != null && metadata.lastModified != null &&
        book.sourceLastModified != metadata.lastModified

fun sourceMetadataNeedsUpdate(book: BookRecord, metadata: DocumentMetadata): Boolean =
    metadata.fileName != null && metadata.fileName != book.fileName ||
        metadata.size != null && metadata.size != book.sourceSize ||
        metadata.lastModified != null && metadata.lastModified != book.sourceLastModified

fun sourceMetadataChangeSummary(book: BookRecord, metadata: DocumentMetadata): String =
    buildList {
        if (book.sourceSize != null && metadata.size != null && book.sourceSize != metadata.size) {
            add("size ${book.sourceSize}→${metadata.size}")
        }
        if (
            book.sourceLastModified != null && metadata.lastModified != null &&
            book.sourceLastModified != metadata.lastModified
        ) {
            add("modified ${book.sourceLastModified}→${metadata.lastModified}")
        }
    }.joinToString(", ").ifBlank { "an explicit source change" }
