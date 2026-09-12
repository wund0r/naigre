// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import java.util.Locale

enum class BookmarkSource(val label: String) {
    PDF_OUTLINE("PDF"),
    EXTERNAL_TOC("TXT"),
    MARKDOWN_HEADING("Markdown"),
    IMAGE_FILE("Image"),
    FILE_ROOT("File"),
}

data class BookmarkEntry(
    val bookId: String,
    val title: String,
    val pageIndex: Int,
    val path: String = title,
    val source: BookmarkSource,
    val destinationY: Float? = null,
    val destinationKey: String? = null,
    val stableKey: String? = null,
) {
    companion object {
        private val WHITESPACE = Regex("\\s+")
    }

    val pageNumber: Int
        get() = pageIndex + 1

    /** Stable across PDF/TXT duplicates while remaining scoped to one book. */
    val identityKey: String
        get() = stableKey?.let { "$bookId|anchor:$it" }
            ?: "$bookId|$pageIndex:${title.trim().lowercase(Locale.ROOT)}"

    /** Stable usage identity when a revised PDF moves the same outline entry to another page. */
    val visitKey: String
        get() {
            stableKey?.let { return "$bookId|visit:$it" }
            val semanticPath = path
                .trim()
                .lowercase(Locale.ROOT)
                .replace(WHITESPACE, " ")
                .ifEmpty { title.trim().lowercase(Locale.ROOT) }
            return "$bookId|visit:$semanticPath"
        }

    val searchText: String
        get() = if (path == title) title else "$title $path"
}
