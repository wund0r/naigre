// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class RecentSearchMode { BOOKMARKS, TEXT }

data class RecentSearch(val query: String, val mode: RecentSearchMode, val scopeBookId: String?)
data class ClosedReaderTab(val id: String, val tab: ReaderTab)

/** Bounded, local histories. Reading sessions are snapshots, never live tab objects. */
class ReaderHistoryRepository(context: Context) {
    companion object {
        const val MAX_ENTRIES = 30
        private const val SEARCHES = "searches"
        private const val CLOSED_TABS = "closed-tabs"
    }

    private val preferences = context.getSharedPreferences("reader-history", Context.MODE_PRIVATE)
    private val sessions = ReaderSessionRepository(context)

    fun searches(): List<RecentSearch> = readArray(SEARCHES).mapNotNull { item ->
        runCatching {
            RecentSearch(
                item.getString("query").takeIf { it.isNotBlank() } ?: return@mapNotNull null,
                RecentSearchMode.valueOf(item.getString("mode")),
                item.optString("scope").takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }.take(MAX_ENTRIES)

    fun recordSearch(query: String, mode: RecentSearchMode, scopeBookId: String?) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return
        val search = RecentSearch(trimmed, mode, scopeBookId)
        val updated = listOf(search) + searches().filter { it != search }
        writeArray(SEARCHES, updated.take(MAX_ENTRIES).map { entry ->
            JSONObject().put("query", entry.query).put("mode", entry.mode.name).put("scope", entry.scopeBookId.orEmpty())
        })
    }

    fun closedTabs(availablePageCounts: Map<String, Int>): List<ClosedReaderTab> =
        readArray(CLOSED_TABS).mapNotNull { item ->
            runCatching {
                sessions.restoreTab(item.getJSONObject("tab"), availablePageCounts)?.let {
                    ClosedReaderTab(item.getString("id"), it)
                }
            }.getOrNull()
        }.take(MAX_ENTRIES)

    fun recordClosedTabs(tabs: List<ReaderTab>): List<ClosedReaderTab> {
        val entries = tabs.map { ClosedReaderTab(UUID.randomUUID().toString(), it.copy()) }
        val additions = entries.map { JSONObject().put("id", it.id).put("tab", sessions.tabJson(it.tab)) }
        writeArray(CLOSED_TABS, (additions + readArray(CLOSED_TABS)).take(MAX_ENTRIES))
        return entries
    }

    fun removeClosedTab(id: String) =
        writeArray(CLOSED_TABS, readArray(CLOSED_TABS).filter { it.optString("id") != id })

    fun clearSearches() { preferences.edit().remove(SEARCHES).apply() }
    fun clearClosedTabs() { preferences.edit().remove(CLOSED_TABS).apply() }

    fun forgetBooks(bookIds: Set<String>) {
        writeArray(SEARCHES, readArray(SEARCHES).filter { it.optString("scope") !in bookIds })
        writeArray(CLOSED_TABS, readArray(CLOSED_TABS).filter { it.optJSONObject("tab")?.optString("book") !in bookIds })
    }

    private fun readArray(key: String): List<JSONObject> = runCatching {
        val array = JSONArray(preferences.getString(key, null) ?: "[]")
        (0 until array.length()).mapNotNull { array.optJSONObject(it) }
    }.getOrDefault(emptyList())

    private fun writeArray(key: String, entries: List<JSONObject>) {
        preferences.edit().putString(key, JSONArray(entries).toString()).apply()
    }
}
