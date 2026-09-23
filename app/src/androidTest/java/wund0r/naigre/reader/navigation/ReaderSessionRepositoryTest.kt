// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class ReaderSessionRepositoryTest {
    private val preferenceNames = mutableSetOf<String>()
    private lateinit var base: Context
    private lateinit var context: Context
    private lateinit var repository: ReaderSessionRepository

    @Before
    fun setUp() {
        base = ApplicationProvider.getApplicationContext()
        val prefix = "session-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(base) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val isolated = prefix + name
                preferenceNames += isolated
                return base.getSharedPreferences(isolated, mode)
            }
        }
        repository = ReaderSessionRepository(context)
    }

    @After
    fun tearDown() {
        preferenceNames.forEach { base.deleteSharedPreferences(it) }
    }

    @Test
    fun generatedAndLiteralTitlesRoundTripThroughPreferencesAndBundle() {
        val tabs = listOf(
            ReaderTab.start("book"),
            ReaderTab("book", 8, "", "page-anchor", 4).apply {
                setTitle(TabTitle(kind = TabLabelKind.PAGE, pageIndex = 4))
            },
            ReaderTab("book", 12, "", "section-anchor", 10).apply {
                setTitle(TabTitle("Cavern", TabLabelKind.TEXT_WITH_PAGE, 10))
            },
            ReaderTab("book", 3, "Page 42"),
        )
        repository.saveTabs(tabs, 2)
        assertEquals(RestoredReaderTabs(tabs, 2), repository.restoreTabs(null, mapOf("book" to 20)))
        val state = Bundle()
        repository.saveTabsToInstanceState(state, tabs, 2)
        assertEquals(RestoredReaderTabs(tabs, 2), repository.restoreTabs(state, mapOf("book" to 20)))
    }

    @Test
    fun legacyLabelsAndVisitCountsArePreservedWithoutGuessing() {
        val legacy = JSONArray()
        listOf("Start", "Page 42", "Начало").forEach { label ->
            legacy.put(JSONObject().put("book", "book").put("page", 3).put("label", label))
        }
        context.getSharedPreferences("pdf-jump", Context.MODE_PRIVATE).edit()
            .putString("table-tabs-json", legacy.toString()).commit()
        repository.recordBookmarkVisit("book|visit:unchanged", 5)
        val restored = repository.restoreTabs(null, mapOf("book" to 20))
        assertEquals(listOf("Start", "Page 42", "Начало"), restored.tabs.map { it.label })
        assertEquals(listOf(TabLabelKind.TEXT, TabLabelKind.TEXT, TabLabelKind.TEXT), restored.tabs.map { it.labelKind })
        assertEquals(mapOf("book|visit:unchanged" to 6), repository.loadBookmarkVisits())
    }

    @Test
    fun titlePageIsClampedWhenDocumentShrinks() {
        repository.saveTabs(listOf(ReaderTab("book", 30, "").apply {
            setTitle(TabTitle(kind = TabLabelKind.PAGE, pageIndex = 25))
        }), 0)
        val restored = repository.restoreTabs(null, mapOf("book" to 10)).tabs.single()
        assertEquals(9, restored.pageIndex)
        assertEquals(9, restored.originPageIndex)
        assertEquals(9, restored.labelPageIndex)
    }
}
