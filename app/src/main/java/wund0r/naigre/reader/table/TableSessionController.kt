// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import wund0r.naigre.reader.navigation.ReaderTab
import java.util.Collections

data class TableSelectionTransition(
    val selectedBookIds: LinkedHashSet<String>,
    val addedBookIds: Set<String>,
    val removedBookIds: Set<String>,
    val survivingTabs: List<ReaderTab>,
    val activeTabIndex: Int,
    val closeReference: Boolean,
    val fallbackBookId: String?,
)

data class BookOperationToken(
    val bookId: String,
    val version: Long,
)

/** Owns table membership intent and per-book asynchronous-work identities. */
class TableSessionController(initialSelection: Collection<String> = emptyList()) {
    private val selectedIds = linkedSetOf<String>().apply { addAll(initialSelection) }
    private val selectedView: Set<String> = Collections.unmodifiableSet(selectedIds)
    private val selectionVersions = HashMap<String, Long>()
    private val contentVersions = HashMap<String, Long>()

    @Synchronized
    fun selectedBookIds(): LinkedHashSet<String> = LinkedHashSet(selectedIds)

    val selectedBookIds: Set<String>
        get() = selectedView

    @Synchronized
    fun restoreSelection(bookIds: Collection<String>, availableBookIds: Collection<String>) {
        val available = availableBookIds.toHashSet()
        val desired = bookIds.filterTo(linkedSetOf()) { it in available }
        updateSelectionVersions(desired)
        selectedIds.clear()
        selectedIds += desired
    }

    /**
     * Records the complete desired membership immediately and calculates navigation fallout.
     * Existing tab instances are returned unchanged so identity-keyed viewport state survives.
     */
    @Synchronized
    fun transitionTo(
        desiredBookIds: Collection<String>,
        availableBookIds: List<String>,
        tabs: List<ReaderTab>,
        activeTabIndex: Int,
        referenceBookId: String?,
    ): TableSelectionTransition {
        val available = availableBookIds.toHashSet()
        val desiredSet = desiredBookIds.filterTo(HashSet()) { it in available }
        val desired = availableBookIds.filterTo(linkedSetOf()) { it in desiredSet }
        val previous = LinkedHashSet(selectedIds)
        val removed = previous.filterTo(linkedSetOf()) { it !in desired }
        val added = desired.filterTo(linkedSetOf()) { it !in previous }
        updateSelectionVersions(desired)
        selectedIds.clear()
        selectedIds += desired

        val previousActive = tabs.getOrNull(activeTabIndex)
        val surviving = tabs.filter { it.bookId in desired }
        val nextActive = previousActive
            ?.let { active -> surviving.indexOfFirst { it === active } }
            ?.takeIf { it >= 0 }
            ?: if (surviving.isEmpty()) 0 else activeTabIndex.coerceIn(surviving.indices)

        return TableSelectionTransition(
            selectedBookIds = LinkedHashSet(desired),
            addedBookIds = added,
            removedBookIds = removed,
            survivingTabs = surviving,
            activeTabIndex = nextActive,
            closeReference = referenceBookId in removed,
            fallbackBookId = desired.firstOrNull().takeIf { surviving.isEmpty() },
        )
    }

    @Synchronized
    fun selectionVersion(bookId: String): Long = selectionVersions[bookId] ?: 0L

    @Synchronized
    fun selectionStillCurrent(bookId: String, version: Long, selected: Boolean): Boolean =
        selectionVersion(bookId) == version && (bookId in selectedIds) == selected

    /** Captures the current content generation without superseding existing work. */
    @Synchronized
    fun captureContentOperation(bookId: String): BookOperationToken =
        BookOperationToken(bookId, contentVersions[bookId] ?: 0L)

    /** Starts authoritative source/index work and invalidates older completions. */
    @Synchronized
    fun beginContentOperation(bookId: String): BookOperationToken {
        val version = (contentVersions[bookId] ?: 0L) + 1L
        contentVersions[bookId] = version
        return BookOperationToken(bookId, version)
    }

    /** Claims a passive snapshot (such as a folder scan) only if nothing newer has started. */
    @Synchronized
    fun claimContentOperation(expected: BookOperationToken): BookOperationToken? {
        if (!isContentOperationCurrent(expected)) return null
        return beginContentOperation(expected.bookId)
    }

    @Synchronized
    fun isContentOperationCurrent(token: BookOperationToken): Boolean =
        (contentVersions[token.bookId] ?: 0L) == token.version

    /** Invalidates every pending selection hydration and content write for a forgotten item. */
    @Synchronized
    fun invalidateForgottenBook(bookId: String): BookOperationToken {
        selectionVersions[bookId] = (selectionVersions[bookId] ?: 0L) + 1L
        return beginContentOperation(bookId)
    }

    private fun updateSelectionVersions(desired: Set<String>) {
        (selectedIds union desired).forEach { bookId ->
            if ((bookId in selectedIds) != (bookId in desired)) {
                selectionVersions[bookId] = (selectionVersions[bookId] ?: 0L) + 1L
            }
        }
    }
}
