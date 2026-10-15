// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.UUID

class ReaderHistoryRepositoryTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferenceNames = mutableSetOf<String>()
    private lateinit var context: Context
    private lateinit var repository: ReaderHistoryRepository

    @Before fun prepare() {
        val prefix = "history-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = prefix + name
                preferenceNames += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        repository = ReaderHistoryRepository(context)
    }

    @After fun cleanup() { preferenceNames.forEach(base::deleteSharedPreferences) }

    @Test fun searchesPreserveModeScopeAndCaseAndMoveRepeatsToTheFront() {
        repository.recordSearch(" dragon ", RecentSearchMode.BOOKMARKS, null)
        repository.recordSearch("dragon", RecentSearchMode.TEXT, null)
        repository.recordSearch("dragon", RecentSearchMode.BOOKMARKS, "book")
        repository.recordSearch("Dragon", RecentSearchMode.BOOKMARKS, null)
        repository.recordSearch("dragon", RecentSearchMode.BOOKMARKS, null)
        repository.recordSearch("  ", RecentSearchMode.TEXT, null)
        val restored = ReaderHistoryRepository(context).searches()
        assertEquals(4, restored.size)
        assertEquals(RecentSearch("dragon", RecentSearchMode.BOOKMARKS, null), restored.first())
        assertEquals("Dragon", restored[1].query)
        assertEquals("book", restored[2].scopeBookId)
        assertEquals(RecentSearchMode.TEXT, restored[3].mode)
    }

    @Test fun historiesAreBoundedAndClosedTabsAreSnapshots() {
        repeat(40) { repository.recordSearch("search $it", RecentSearchMode.TEXT, null) }
        assertEquals(ReaderHistoryRepository.MAX_ENTRIES, repository.searches().size)
        assertEquals("search 39", repository.searches().first().query)
        val tab = ReaderTab("book", 13, "", "origin", 11, TabLabelKind.PAGE, 11, originDestinationY = 123f)
        val entry = repository.recordClosedTabs(listOf(tab)).single()
        tab.pageIndex = 1
        tab.labelKind = TabLabelKind.TEXT
        val restored = ReaderHistoryRepository(context).closedTabs(mapOf("book" to 20)).single()
        assertEquals(entry, restored)
        assertEquals(13, restored.tab.pageIndex)
        assertEquals(TabLabelKind.PAGE, restored.tab.labelKind)
        assertEquals(123f, restored.tab.originDestinationY)
        repository.recordClosedTabs((0..35).map { ReaderTab("book", it, "Tab $it") })
        assertEquals(ReaderHistoryRepository.MAX_ENTRIES, repository.closedTabs(mapOf("book" to 100)).size)
    }

    @Test fun sameDestinationSessionsRemainDistinctAndCanBeRemovedIndividually() {
        val entries = repository.recordClosedTabs(listOf(ReaderTab.start("book"), ReaderTab.start("book")))
        assertNotEquals(entries[0].id, entries[1].id)
        repository.removeClosedTab(entries[0].id)
        assertEquals(listOf(entries[1]), repository.closedTabs(mapOf("book" to 10)))
        repository.clearClosedTabs()
        assertTrue(repository.closedTabs(mapOf("book" to 10)).isEmpty())
        repository.recordSearch("query", RecentSearchMode.TEXT, null)
        repository.clearSearches()
        assertTrue(repository.searches().isEmpty())
    }

    @Test fun forgettingABookRemovesItsHistoryAndKeepsOtherScopes() {
        repository.recordSearch("global", RecentSearchMode.TEXT, null)
        repository.recordSearch("forgotten", RecentSearchMode.TEXT, "forgotten")
        repository.recordSearch("retained", RecentSearchMode.BOOKMARKS, "retained")
        repository.recordClosedTabs(listOf(ReaderTab.start("forgotten"), ReaderTab.start("retained")))
        repository.forgetBooks(setOf("forgotten"))
        assertEquals(listOf("retained", "global"), repository.searches().map { it.query })
        assertEquals("retained", repository.closedTabs(mapOf("forgotten" to 5, "retained" to 5)).single().tab.bookId)
    }

    @Test fun unavailableBooksAreFilteredAndShrunkenBooksClampSavedPositions() {
        repository.recordClosedTabs(listOf(ReaderTab("book", 50, "Page", originPageIndex = 40), ReaderTab.start("missing")))
        val restored = repository.closedTabs(mapOf("book" to 10)).single().tab
        assertEquals(9, restored.pageIndex)
        assertEquals(9, restored.originPageIndex)
    }

    @Test fun damagedHistoryDoesNotPreventNewEntries() {
        context.getSharedPreferences("reader-history", Context.MODE_PRIVATE).edit()
            .putString("searches", "broken").putString("closed-tabs", "broken").commit()
        assertTrue(repository.searches().isEmpty())
        assertTrue(repository.closedTabs(mapOf("book" to 1)).isEmpty())
        repository.recordSearch("new", RecentSearchMode.TEXT, null)
        repository.recordClosedTabs(listOf(ReaderTab.start("book")))
        assertEquals("new", repository.searches().single().query)
        assertEquals("book", repository.closedTabs(mapOf("book" to 1)).single().tab.bookId)
    }
}
