// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import java.util.PriorityQueue
import java.util.Locale
import kotlin.math.abs

class BookmarkIndex {
    companion object {
        private const val USAGE_BOOST_PER_TIER = 80
        private const val MAX_USAGE_BOOST = 400
        private const val DESTINATION_TOLERANCE_POINTS = 8f
        private val REFERENCE_TOKEN = Regex("[\\p{L}\\p{N}]+(?:-[\\p{L}\\p{N}]+)+")
        private val LINK_WHITESPACE = Regex("\\s+")
    }

    data class DestinationMatch(
        val entry: BookmarkEntry,
        val exactDestination: Boolean,
    )

    data class SearchSnapshot(
        val entries: List<BookmarkEntry>,
        val totalMatches: Int,
    )

    private data class IndexedEntry(
        val entry: BookmarkEntry,
        val searchText: String,
        val normalizedSearchText: String,
        val visitKey: String,
        val ordinal: Int,
    )

    private data class ScoredEntry(
        val indexed: IndexedEntry,
        val fuzzyScore: Int,
        val visitCount: Int,
        val combinedScore: Int,
    )

    private val bestFirst = compareByDescending<ScoredEntry> { it.combinedScore }
        .thenByDescending { it.fuzzyScore }
        .thenByDescending { it.visitCount }
        .thenBy { it.indexed.entry.title.length }
        .thenBy { it.indexed.entry.pageIndex }
        .thenBy { it.indexed.ordinal }

    private val frequentFirst = compareByDescending<ScoredEntry> { it.visitCount }
        .thenBy { defaultResultRank(it.indexed.entry.source) }
        .thenBy { it.indexed.ordinal }

    private var entries: List<IndexedEntry> = emptyList()

    val size: Int
        get() = entries.size

    fun replace(pdfOutline: List<BookmarkEntry>, external: List<BookmarkEntry>) {
        val seen = HashSet<String>()
        entries = buildList {
            for (entry in pdfOutline + external) {
                if (seen.add(entry.identityKey)) {
                    add(
                        IndexedEntry(
                            entry = entry,
                            searchText = entry.searchText,
                            normalizedSearchText = FuzzyMatcher.normalize(entry.searchText),
                            visitKey = entry.visitKey,
                            ordinal = size,
                        ),
                    )
                }
            }
        }
    }

    fun search(
        query: String,
        limit: Int = 100,
        visitCounts: Map<String, Int> = emptyMap(),
        bookIds: Set<String>? = null,
    ): List<BookmarkEntry> = searchSnapshot(query, limit, visitCounts, bookIds).entries

    fun searchSnapshot(
        query: String,
        limit: Int = 100,
        visitCounts: Map<String, Int> = emptyMap(),
        bookIds: Set<String>? = null,
    ): SearchSnapshot {
        if (limit <= 0) return SearchSnapshot(emptyList(), 0)
        val caseSensitive = FuzzyMatcher.isSmartCase(query)
        val tokens = FuzzyMatcher.prepareQuery(query, caseSensitive)
        val resultOrder = if (tokens.isEmpty()) frequentFirst else bestFirst

        // Keep only the best [limit] matches while scanning. Large outlines no longer need
        // to allocate and sort an intermediate list containing every fuzzy match.
        val best = PriorityQueue(limit, resultOrder.reversed())
        var totalMatches = 0
        for (indexed in entries) {
            if (bookIds != null && indexed.entry.bookId !in bookIds) continue
            val fuzzyScore = if (tokens.isEmpty()) {
                0
            } else {
                val candidate = if (caseSensitive) indexed.searchText else indexed.normalizedSearchText
                FuzzyMatcher.scorePrepared(candidate, tokens) ?: continue
            }
            totalMatches++
            val visitCount = visitCounts[indexed.visitKey]?.coerceAtLeast(0) ?: 0
            val candidate = ScoredEntry(
                indexed = indexed,
                fuzzyScore = fuzzyScore,
                visitCount = visitCount,
                combinedScore = fuzzyScore + usageBoost(visitCount),
            )
            if (best.size < limit) {
                best += candidate
            } else if (resultOrder.compare(candidate, best.peek()) < 0) {
                best.poll()
                best += candidate
            }
        }
        return SearchSnapshot(
            entries = best.sortedWith(resultOrder).map { it.indexed.entry },
            totalMatches = totalMatches,
        )
    }

    fun count(bookIds: Set<String>? = null): Int =
        if (bookIds == null) entries.size else entries.count { it.entry.bookId in bookIds }

    /** Page-level section context for results that do not yet have a precise destination Y. */
    fun contextAtOrBefore(bookId: String, pageIndex: Int): BookmarkEntry? {
        val samePage = entries.filter {
            isContextEntry(it.entry) && it.entry.bookId == bookId && it.entry.pageIndex == pageIndex
        }
        if (samePage.isNotEmpty()) {
            // Without a hit Y, selecting the final heading on a busy page repeats the old
            // link-naming failure mode. The first positioned heading (or first outline item)
            // is the conservative page-level context; the UI labels it as merely "near".
            return samePage
                .filter { it.entry.destinationY != null }
                .minWithOrNull(
                    compareBy<IndexedEntry> { it.entry.destinationY ?: Float.POSITIVE_INFINITY }
                        .thenByDescending { pathDepth(it.entry) },
                )
                ?.entry
                ?: samePage.first().entry
        }

        var best: IndexedEntry? = null
        for (indexed in entries) {
            val entry = indexed.entry
            if (!isContextEntry(entry) || entry.bookId != bookId || entry.pageIndex >= pageIndex) continue
            val current = best
            if (
                current == null ||
                entry.pageIndex > current.entry.pageIndex ||
                entry.pageIndex == current.entry.pageIndex && isMoreSpecific(indexed, current)
            ) {
                best = indexed
            }
        }
        return best?.entry
    }

    /** Finds the best outline identity from destination metadata and optional source text. */
    fun matchDestination(
        bookId: String,
        pageIndex: Int,
        targetY: Float?,
        destinationKey: String?,
        sourceText: String? = null,
    ): DestinationMatch? {
        if (!destinationKey.isNullOrBlank()) {
            var exactKeyMatch: IndexedEntry? = null
            for (indexed in entries) {
                if (
                    !isContextEntry(indexed.entry) ||
                    indexed.entry.bookId != bookId ||
                    indexed.entry.pageIndex != pageIndex ||
                    indexed.entry.destinationKey != destinationKey
                ) continue
                val current = exactKeyMatch
                if (current == null || isMoreSpecific(indexed, current)) exactKeyMatch = indexed
            }
            exactKeyMatch?.let { return DestinationMatch(it.entry, exactDestination = true) }
        }

        var samePage: IndexedEntry? = null
        var nearestFollowingOnPage: IndexedEntry? = null
        var nearestFollowingDistance = Float.POSITIVE_INFINITY
        var hasSamePageBookmark = false
        val targetPosition = targetY?.takeIf { it.isFinite() }
        for (indexed in entries) {
            if (
                !isContextEntry(indexed.entry) ||
                indexed.entry.bookId != bookId || indexed.entry.pageIndex != pageIndex
            ) continue
            hasSamePageBookmark = true
            if (targetPosition == null) continue
            val entryY = indexed.entry.destinationY ?: continue
            if (entryY > targetPosition + DESTINATION_TOLERANCE_POINTS) {
                val distance = entryY - targetPosition
                val currentFollowing = nearestFollowingOnPage
                if (
                    distance < nearestFollowingDistance ||
                    distance == nearestFollowingDistance &&
                    (currentFollowing == null || isMoreSpecific(indexed, currentFollowing))
                ) {
                    nearestFollowingOnPage = indexed
                    nearestFollowingDistance = distance
                }
                continue
            }

            val current = samePage
            val currentY = current?.entry?.destinationY
            if (current == null || currentY == null || entryY > currentY || entryY == currentY && isMoreSpecific(indexed, current)) {
                samePage = indexed
            }
        }
        samePage?.let { indexed ->
            return DestinationMatch(
                entry = indexed.entry,
                exactDestination = abs(indexed.entry.destinationY!! - targetPosition!!) <= DESTINATION_TOLERANCE_POINTS,
            )
        }

        // Producers do not agree consistently on whether a destination sits immediately
        // before or on its heading. If every positioned heading follows the target, the
        // closest one on that same page is still safer than a previous-page bookmark.
        nearestFollowingOnPage?.let { indexed ->
            return DestinationMatch(entry = indexed.entry, exactDestination = false)
        }

        matchSourceText(bookId, pageIndex, sourceText)?.let { return it }

        // If the page has bookmarks but none can be positioned confidently, a physical-page
        // tab name is safer than claiming the link belongs to a previous-page section.
        if (hasSamePageBookmark) return null

        var previousPage: IndexedEntry? = null
        for (indexed in entries) {
            if (
                !isContextEntry(indexed.entry) ||
                indexed.entry.bookId != bookId || indexed.entry.pageIndex >= pageIndex
            ) continue
            val current = previousPage
            if (
                current == null ||
                indexed.entry.pageIndex > current.entry.pageIndex ||
                indexed.entry.pageIndex == current.entry.pageIndex && isMoreSpecific(indexed, current)
            ) {
                previousPage = indexed
            }
        }
        return previousPage?.let { DestinationMatch(it.entry, exactDestination = false) }
    }

    private fun matchSourceText(
        bookId: String,
        pageIndex: Int,
        sourceText: String?,
    ): DestinationMatch? {
        val normalizedSource = sourceText?.let(::normalizeLinkText).orEmpty()
        if (normalizedSource.length < 2) return null

        val hints = LinkedHashSet<String>()
        hints += normalizedSource
        REFERENCE_TOKEN.findAll(normalizedSource).forEach { match ->
            if (match.value.length >= 3) hints += match.value
        }

        var best: IndexedEntry? = null
        var bestScore = 0
        var bestRawScore = 0
        var tiedBest = false
        for (indexed in entries) {
            if (!isContextEntry(indexed.entry) || indexed.entry.bookId != bookId) continue
            val pageDistance = abs(indexed.entry.pageIndex - pageIndex)
            if (pageDistance > 1) continue
            val title = normalizeLinkText(indexed.entry.title)
            val rawScore = hints.maxOfOrNull { linkTextScore(title, it) } ?: 0
            // Prefer the destination page when equally strong labels occur beside it.
            val score = rawScore - pageDistance * 100
            if (score > bestScore) {
                best = indexed
                bestScore = score
                bestRawScore = rawScore
                tiedBest = false
            } else if (score > 0 && score == bestScore) {
                tiedBest = true
            }
        }

        // A weak alphanumeric-prefix match (for example 3-89 -> 3-89A) is accepted only
        // when unique. Picking arbitrarily between 3-89A and 3-89B is worse than Page 193.
        val matched = best ?: return null
        if (bestRawScore < 7_000 || tiedBest && bestRawScore < 10_000) return null
        // Adjacent-page labels are useful naming evidence, but not proof that the bookmark
        // and link share an exact destination.
        if (matched.entry.pageIndex != pageIndex && bestRawScore < 10_000) return null
        return DestinationMatch(
            matched.entry,
            exactDestination = matched.entry.pageIndex == pageIndex && bestRawScore >= 10_000,
        )
    }

    private fun normalizeLinkText(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace('–', '-')
            .replace('—', '-')
            .replace('−', '-')
            .replace(LINK_WHITESPACE, " ")
            .trim { !it.isLetterOrDigit() }

    private fun linkTextScore(title: String, hint: String): Int {
        if (hint.length < 2 || title.isEmpty()) return 0
        if (title == hint) return 12_000 + hint.length

        if (title.startsWith(hint)) {
            val next = title.getOrNull(hint.length)
            if (next == null || !next.isLetterOrDigit()) return 11_000 + hint.length
            if ('-' in hint) return 7_000 + hint.length
        }

        val occurrence = title.indexOf(hint)
        if (occurrence >= 0) {
            val before = title.getOrNull(occurrence - 1)
            val after = title.getOrNull(occurrence + hint.length)
            if (
                (before == null || !before.isLetterOrDigit()) &&
                (after == null || !after.isLetterOrDigit())
            ) return 10_000 + hint.length
        }
        return 0
    }

    /**
     * Repeated visits should reorder similarly relevant matches without ever allowing usage
     * alone to bridge the large score gap between an exact/substring and a weak fuzzy match.
     */
    private fun usageBoost(visitCount: Int): Int {
        var remaining = visitCount.coerceAtLeast(0)
        var tier = 0
        while (remaining > 0) {
            tier++
            remaining = remaining ushr 1
        }
        return minOf(tier * USAGE_BOOST_PER_TIER, MAX_USAGE_BOOST)
    }

    private fun pathDepth(entry: BookmarkEntry): Int = entry.path.count { it == '›' }

    private fun isContextEntry(entry: BookmarkEntry): Boolean = entry.source != BookmarkSource.FILE_ROOT

    private fun defaultResultRank(source: BookmarkSource): Int = when (source) {
        BookmarkSource.FILE_ROOT -> 0
        BookmarkSource.PDF_OUTLINE, BookmarkSource.EXTERNAL_TOC, BookmarkSource.MARKDOWN_HEADING -> 1
        BookmarkSource.IMAGE_FILE -> 2
    }

    private fun isMoreSpecific(candidate: IndexedEntry, current: IndexedEntry): Boolean {
        val candidateDepth = pathDepth(candidate.entry)
        val currentDepth = pathDepth(current.entry)
        return candidateDepth > currentDepth || candidateDepth == currentDepth && candidate.ordinal > current.ordinal
    }
}
