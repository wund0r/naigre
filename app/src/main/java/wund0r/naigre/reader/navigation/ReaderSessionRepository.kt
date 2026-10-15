// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import android.content.Context
import android.os.Bundle
import org.json.JSONArray
import org.json.JSONObject

data class RestoredReaderTabs(
    val tabs: List<ReaderTab>,
    val activeTabIndex: Int,
    val reference: ReaderTab? = null,
)

/**
 * Owns the durable representation of reader tabs, document references and bookmark visit counts.
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
        private const val PREF_REFERENCE_JSON = "table-reference-json"
        private const val STATE_TAB_PAGES = "tab-pages"
        private const val STATE_TAB_BOOKS = "tab-books"
        private const val STATE_TAB_LABELS = "tab-labels"
        private const val STATE_TAB_LABEL_KINDS = "tab-label-kinds"
        private const val STATE_TAB_LABEL_PAGES = "tab-label-pages"
        private const val STATE_TAB_ANCHORS = "tab-anchors"
        private const val STATE_TAB_ORIGINS = "tab-origins"
        private const val STATE_ACTIVE_TAB = "active-tab"
        private const val STATE_REFERENCE_JSON = "reference-json"
        private const val STATE_TAB_ORIGIN_Y = "tab-origin-y"
        private const val STATE_TAB_PENDING_Y = "tab-pending-y"
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

    fun saveTabs(tabs: List<ReaderTab>, activeTabIndex: Int, reference: ReaderTab? = null) {
        val array = JSONArray().apply {
            tabs.forEach { tab ->
                put(tabJson(tab))
            }
        }
        sessionPrefs.edit()
            .putString(PREF_TABS_JSON, array.toString())
            .putInt(PREF_ACTIVE_TAB, activeTabIndex)
            .putString(PREF_REFERENCE_JSON, reference?.let { tabJson(it).toString() })
            .apply()
    }

    fun saveTabsToInstanceState(
        outState: Bundle,
        tabs: List<ReaderTab>,
        activeTabIndex: Int,
        reference: ReaderTab? = null,
    ) {
        outState.putIntegerArrayList(STATE_TAB_PAGES, ArrayList(tabs.map { it.pageIndex }))
        outState.putStringArrayList(STATE_TAB_BOOKS, ArrayList(tabs.map { it.bookId }))
        outState.putStringArrayList(STATE_TAB_LABELS, ArrayList(tabs.map { it.label }))
        outState.putStringArrayList(STATE_TAB_LABEL_KINDS, ArrayList(tabs.map { it.labelKind.name }))
        outState.putIntegerArrayList(STATE_TAB_LABEL_PAGES, ArrayList(tabs.map { it.labelPageIndex }))
        outState.putStringArrayList(STATE_TAB_ANCHORS, ArrayList(tabs.map { it.anchorKey.orEmpty() }))
        outState.putIntegerArrayList(STATE_TAB_ORIGINS, ArrayList(tabs.map { it.originPageIndex }))
        outState.putInt(STATE_ACTIVE_TAB, activeTabIndex)
        outState.putFloatArray(STATE_TAB_ORIGIN_Y, tabs.map { it.originDestinationY ?: Float.NaN }.toFloatArray())
        outState.putFloatArray(STATE_TAB_PENDING_Y, tabs.map { it.pendingDestinationY ?: Float.NaN }.toFloatArray())
        outState.putString(STATE_REFERENCE_JSON, reference?.let { tabJson(it).toString() })
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
        val labelKinds = state?.getStringArrayList(STATE_TAB_LABEL_KINDS)
        val labelPages = state?.getIntegerArrayList(STATE_TAB_LABEL_PAGES)
        val anchors = state?.getStringArrayList(STATE_TAB_ANCHORS)
        val origins = state?.getIntegerArrayList(STATE_TAB_ORIGINS)
        val originY = state?.getFloatArray(STATE_TAB_ORIGIN_Y)
        val pendingY = state?.getFloatArray(STATE_TAB_PENDING_Y)
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
                labelKind = labelKind(labelKinds?.getOrNull(index)),
                labelPageIndex = (labelPages?.getOrNull(index) ?: pages[index]).coerceIn(0, pageCount - 1),
                anchorKey = anchors?.getOrNull(index)?.takeIf { it.isNotBlank() },
                originPageIndex = (origins?.getOrNull(index) ?: pages[index])
                    .coerceIn(0, pageCount - 1),
                originDestinationY = originY?.getOrNull(index)?.takeIf { it.isFinite() && (origins?.getOrNull(index) ?: pages[index]) in 0 until pageCount },
                pendingDestinationY = pendingY?.getOrNull(index)?.takeIf { it.isFinite() && pages[index] in 0 until pageCount },
            )
        }
        if (restored.isEmpty()) return null
        return RestoredReaderTabs(
            tabs = restored,
            activeTabIndex = state.getInt(STATE_ACTIVE_TAB, 0).coerceIn(restored.indices),
            reference = restoreReference(state.getString(STATE_REFERENCE_JSON), availablePageCounts),
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
                    restoreTab(item, availablePageCounts)?.let(restored::add)
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
        return RestoredReaderTabs(
            restored, activeTabIndex,
            restoreReference(sessionPrefs.getString(PREF_REFERENCE_JSON, null), availablePageCounts),
        )
    }

    internal fun tabJson(tab: ReaderTab): JSONObject = JSONObject()
        .put("book", tab.bookId)
        .put("page", tab.pageIndex)
        .put("label", tab.label)
        .put("labelKind", tab.labelKind.name)
        .put("labelPage", tab.labelPageIndex)
        .put("anchor", tab.anchorKey ?: "")
        .put("originPage", tab.originPageIndex)
        .put("originY", tab.originDestinationY?.takeIf(Float::isFinite))
        .put("pendingY", tab.pendingDestinationY?.takeIf(Float::isFinite))

    private fun restoreReference(json: String?, availablePageCounts: Map<String, Int>): ReaderTab? =
        json?.let { runCatching { restoreTab(JSONObject(it), availablePageCounts) }.getOrNull() }

    internal fun restoreTab(item: JSONObject, availablePageCounts: Map<String, Int>): ReaderTab? {
        val bookId = item.getString("book")
        val pageCount = availablePageCounts[bookId]?.takeIf { it > 0 } ?: return null
        val page = item.getInt("page")
        val origin = item.optInt("originPage", page)
        return ReaderTab(
            bookId = bookId,
            pageIndex = page.coerceIn(0, pageCount - 1),
            label = item.optString("label", ""),
            labelKind = if (item.has("label")) labelKind(item.optString("labelKind")) else TabLabelKind.PAGE,
            labelPageIndex = item.optInt("labelPage", page).coerceIn(0, pageCount - 1),
            anchorKey = item.optString("anchor", "").takeIf { it.isNotBlank() },
            originPageIndex = origin.coerceIn(0, pageCount - 1),
            originDestinationY = item.optDouble("originY").toFloat().takeIf { it.isFinite() && origin in 0 until pageCount },
            pendingDestinationY = item.optDouble("pendingY").toFloat().takeIf { it.isFinite() && page in 0 until pageCount },
        )
    }

    private fun labelKind(value: String?): TabLabelKind =
        TabLabelKind.entries.firstOrNull { it.name == value } ?: TabLabelKind.TEXT
}
