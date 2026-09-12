// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

data class ExternalTocParseResult(
    val entries: List<BookmarkEntry>,
    val rejectedLines: Int,
)

object ExternalTocParser {
    private const val MAX_HEADING_DEPTH = 6
    private const val PATH_SEPARATOR = " › "
    private val headingPattern = Regex("^(#{1,$MAX_HEADING_DEPTH})\\s+(.+)$")

    fun parse(text: String, pageCount: Int, bookId: String): ExternalTocParseResult {
        val entries = ArrayList<BookmarkEntry>()
        val headings = arrayOfNulls<String>(MAX_HEADING_DEPTH)
        var rejected = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEach

            if (line == "---") {
                headings.fill(null)
                return@forEach
            }

            val headingMatch = headingPattern.matchEntire(line)
            if (headingMatch != null) {
                val level = headingMatch.groupValues[1].length
                val content = headingMatch.groupValues[2].trim()
                val separator = content.lastIndexOf("::")
                val title = if (separator >= 0) content.substring(0, separator).trim() else content
                val pageNumber = if (separator >= 0) {
                    content.substring(separator + 2).trim().toIntOrNull()
                } else {
                    null
                }

                if (
                    title.isEmpty() ||
                    (separator >= 0 && (pageNumber == null || pageNumber !in 1..pageCount))
                ) {
                    rejected++
                    return@forEach
                }

                headings[level - 1] = title
                for (index in level until headings.size) headings[index] = null

                if (pageNumber != null) {
                    entries += BookmarkEntry(
                        bookId = bookId,
                        title = title,
                        pageIndex = pageNumber - 1,
                        path = headings.take(level).filterNotNull().joinToString(PATH_SEPARATOR),
                        source = BookmarkSource.EXTERNAL_TOC,
                    )
                }
                return@forEach
            }

            val separator = line.lastIndexOf("::")
            if (separator <= 0 || separator + 2 >= line.length) {
                rejected++
                return@forEach
            }

            val title = line.substring(0, separator).trim()
            val pageNumber = line.substring(separator + 2).trim().toIntOrNull()
            if (title.isEmpty() || pageNumber == null || pageNumber !in 1..pageCount) {
                rejected++
                return@forEach
            }

            entries += BookmarkEntry(
                bookId = bookId,
                title = title,
                pageIndex = pageNumber - 1,
                path = (headings.filterNotNull() + title).joinToString(PATH_SEPARATOR),
                source = BookmarkSource.EXTERNAL_TOC,
            )
        }

        return ExternalTocParseResult(entries, rejected)
    }
}
