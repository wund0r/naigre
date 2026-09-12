// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

data class RestoredReaderTabs(
    val tabs: List<ReaderTab>,
    val activeTabIndex: Int,
)

/**
 * Owns the durable representation of reader tabs and bookmark visit counts.
 *
 * Navigation and rendering decisions remain in the activity. Keeping the storage format here
 * prevents lifecycle restoration, normal persistence, and book removal from each implementing
 * their own preference/JSON handling.
 */
class ReaderSessionRepository(context: Context) {
    companion object {
        private const val SESSION_PREFS = "pdf-jump"
        private const val BOOKMARK_VISIT_PREFS = "bookmark-visits-v2"
        private const val PREF_TABS_JSON = "table-tabs-json"
        private const val PREF_ACTIVE_TAB = "table-active-tab"
        private const val STATE_TAB_PAGES = "tab-pages"
        private const val STATE_TAB_BOOKS = "tab-books"
        private const val STATE_TAB_LABELS = "tab-labels"
        private const val STATE_TAB_ANCHORS = "tab-anchors"
        private const val STATE_TAB_ORIGINS = "tab-origins"
        private const val STATE_ACTIVE_TAB = "active-tab"
    }

    private val sessionPrefs = context.getSharedPreferences(SESSION_PREFS, Context.MODE_PRIVATE)
    private val visitPrefs = context.getSharedPreferences(BOOKMARK_VISIT_PREFS, Context.MODE_PRIVATE)

    fun restoreTabs(
        state: Bundle?,
        availablePageCounts: Map<String, Int>,
    ): RestoredReaderTabs {
        restoreTabsFromInstanceState(state, availablePageCounts)?.let { return it }
        return restorePersistedTabs(availablePageCounts)
    }

    fun saveTabs(tabs: List<ReaderTab>, activeTabIndex: Int) {
        val array = JSONArray().apply {
            tabs.forEach { tab ->
                put(
                    JSONObject()
                        .put("book", tab.bookId)
                        .put("page", tab.pageIndex)
                        .put("label", tab.label)
                        .put("anchor", tab.anchorKey ?: "")
                        .put("originPage", tab.originPageIndex),
                )
            }
        }
        sessionPrefs.edit()
            .putString(PREF_TABS_JSON, array.toString())
            .putInt(PREF_ACTIVE_TAB, activeTabIndex)
            .apply()
    }

    fun saveTabsToInstanceState(
        outState: Bundle,
        tabs: List<ReaderTab>,
        activeTabIndex: Int,
    ) {
        outState.putIntegerArrayList(STATE_TAB_PAGES, ArrayList(tabs.map { it.pageIndex }))
        outState.putStringArrayList(STATE_TAB_BOOKS, ArrayList(tabs.map { it.bookId }))
        outState.putStringArrayList(STATE_TAB_LABELS, ArrayList(tabs.map { it.label }))
        outState.putStringArrayList(STATE_TAB_ANCHORS, ArrayList(tabs.map { it.anchorKey.orEmpty() }))
        outState.putIntegerArrayList(STATE_TAB_ORIGINS, ArrayList(tabs.map { it.originPageIndex }))
        outState.putInt(STATE_ACTIVE_TAB, activeTabIndex)
    }

    fun loadBookmarkVisits(): Map<String, Int> = buildMap {
        visitPrefs.all.forEach { (identityKey, value) ->
            val count = value as? Int
            if (count != null && count > 0) put(identityKey, count)
        }
    }

    fun recordBookmarkVisit(identityKey: String, currentCount: Int): Int {
        if (currentCount == Int.MAX_VALUE) return currentCount
        val updated = currentCount + 1
        visitPrefs.edit().putInt(identityKey, updated).apply()
        return updated
    }

    fun deleteBookmarkVisits(bookId: String) {
        val prefix = "$bookId|"
        visitPrefs.edit().also { editor ->
            visitPrefs.all.keys.filter { it.startsWith(prefix) }.forEach(editor::remove)
        }.apply()
    }

    private fun restoreTabsFromInstanceState(
        state: Bundle?,
        availablePageCounts: Map<String, Int>,
    ): RestoredReaderTabs? {
        val pages = state?.getIntegerArrayList(STATE_TAB_PAGES)
        val bookIds = state?.getStringArrayList(STATE_TAB_BOOKS)
        val labels = state?.getStringArrayList(STATE_TAB_LABELS)
        val anchors = state?.getStringArrayList(STATE_TAB_ANCHORS)
        val origins = state?.getIntegerArrayList(STATE_TAB_ORIGINS)
        if (
            pages == null || bookIds == null || labels == null || pages.isEmpty() ||
            pages.size != labels.size || pages.size != bookIds.size
        ) {
            return null
        }
        val restored = pages.indices.mapNotNull { index ->
            val pageCount = availablePageCounts[bookIds[index]]?.takeIf { it > 0 }
                ?: return@mapNotNull null
            ReaderTab(
                bookId = bookIds[index],
                pageIndex = pages[index].coerceIn(0, pageCount - 1),
                label = labels[index],
                anchorKey = anchors?.getOrNull(index)?.takeIf { it.isNotBlank() },
                originPageIndex = (origins?.getOrNull(index) ?: pages[index])
                    .coerceIn(0, pageCount - 1),
            )
        }
        if (restored.isEmpty()) return null
        return RestoredReaderTabs(
            tabs = restored,
            activeTabIndex = state.getInt(STATE_ACTIVE_TAB, 0).coerceIn(restored.indices),
        )
    }

    private fun restorePersistedTabs(
        availablePageCounts: Map<String, Int>,
    ): RestoredReaderTabs {
        val restored = mutableListOf<ReaderTab>()
        val json = sessionPrefs.getString(PREF_TABS_JSON, null)
        if (!json.isNullOrBlank()) {
            try {
                val array = JSONArray(json)
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val bookId = item.getString("book")
                    val pageCount = availablePageCounts[bookId]?.takeIf { it > 0 } ?: continue
                    val pageIndex = item.getInt("page")
                    restored += ReaderTab(
                        bookId = bookId,
                        pageIndex = pageIndex.coerceIn(0, pageCount - 1),
                        label = item.optString("label", "Page"),
                        anchorKey = item.optString("anchor", "").takeIf { it.isNotBlank() },
                        originPageIndex = item.optInt("originPage", pageIndex)
                            .coerceIn(0, pageCount - 1),
                    )
                }
            } catch (_: Throwable) {
                restored.clear()
            }
        }
        val activeTabIndex = if (restored.isEmpty()) {
            0
        } else {
            sessionPrefs.getInt(PREF_ACTIVE_TAB, 0).coerceIn(restored.indices)
        }
        return RestoredReaderTabs(restored, activeTabIndex)
    }
}
