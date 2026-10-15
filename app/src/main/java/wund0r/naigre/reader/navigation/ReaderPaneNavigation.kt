// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

/** A pane change retains the original session instances and their viewport state. */
data class ReaderPaneTransition(
    val tabs: List<ReaderTab>,
    val activeTabIndex: Int,
    val reference: ReaderTab?,
)

object ReaderPaneNavigation {
    fun moveToReference(
        tabs: List<ReaderTab>,
        activeTabIndex: Int,
        reference: ReaderTab?,
        index: Int,
    ): ReaderPaneTransition? {
        if (index !in tabs.indices || tabs.size == 1 && reference == null) return null
        val moved = tabs[index]
        val active = tabs.getOrNull(activeTabIndex)
        val remaining = tabs.toMutableList()
        if (reference != null) remaining[index] = reference else remaining.removeAt(index)
        val nextActive = remaining.indexOfFirst { it === active }.takeIf { it >= 0 }
            ?: index.coerceIn(remaining.indices)
        return ReaderPaneTransition(remaining, nextActive, moved)
    }

    fun makeRegularTab(tabs: List<ReaderTab>, reference: ReaderTab): ReaderPaneTransition =
        ReaderPaneTransition(tabs + reference, tabs.size, null)
}
