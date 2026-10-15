// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Canvas
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.WindowManager
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ListView
import android.widget.ScrollView
import android.widget.EditText
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import wund0r.naigre.reader.MainActivity
import wund0r.naigre.reader.R
import wund0r.naigre.reader.navigation.*
import wund0r.naigre.reader.render.MarkdownSurface
import wund0r.naigre.reader.render.ReaderSurface
import wund0r.naigre.reader.search.TextIndexCoordinator
import wund0r.naigre.reader.search.TextSearchIndexRepository
import wund0r.naigre.reader.table.*
import wund0r.naigre.reader.ui.SearchHistoryView
import wund0r.naigre.reader.ui.SearchResultRow
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@SdkSuppress(minSdkVersion = 29)
class WikiLinkNavigationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val catalog get() = File(context.filesDir, "book-library.json")
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalCatalog: ByteArray? = null
    private val preferences = mutableMapOf<String, Map<String, *>>()
    private lateinit var directory: File
    private lateinit var fixture: List<BookRecord>
    private lateinit var note: BookRecord
    private lateinit var pdf: BookRecord
    private lateinit var duplicate: BookRecord
    private lateinit var maps: BookRecord
    private lateinit var offMaps: BookRecord
    private lateinit var libraryNote: BookRecord

    @Before fun prepare() {
        check(context.packageName == "wund0r.naigre.reader.verification")
        if (catalog.exists()) {
            val saved = (BookLibraryRepository(context).readCatalog() as? BookCatalogState.Loaded)?.library
            check(saved != null && saved.books.isEmpty() && saved.selectedBookIds.isEmpty() && saved.folders.isEmpty() && saved.tags.isEmpty()) {
                "Wiki-link UI tests require an empty verification library"
            }
            originalCatalog = catalog.readBytes()
        }
        directory = File(context.cacheDir, "wiki-${UUID.randomUUID()}").apply { mkdirs() }
        listOf("pdf-jump", "bookmark-visits-v2", "reader-history").forEach { name ->
            preferences[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        fun document(name: String, kind: LibraryItemKind): BookRecord {
            val source = File(directory, name).apply { parentFile!!.mkdirs() }
            if (kind == LibraryItemKind.PDF) writePdf(source, 20) else source.writeText(
                "# Notes\n\n[[downtime.pdf#page=12|Pazuzu]]\n\n[[maps/level 3.jpg]]\n\nSome notes.\n\n" +
                    "## Fold me\n\nHidden body.\n\n## Long section\n\n" +
                    (1..80).joinToString("\n\n") { "Paragraph $it with enough text to scroll through the note." },
            )
            val content = if (kind == LibraryItemKind.MARKDOWN) MarkdownEngine(context).parse(source.readText()) else null
            val id = UUID.randomUUID().toString()
            return BookRecord(
                id = id, title = name, fileName = source.name, uri = Uri.fromFile(source).toString(),
                kind = kind, color = Color.GREEN, pageCount = content?.sections?.size ?: 20,
                pdfBookmarks = content?.sections?.map { BookmarkEntry(id, it.title, it.index, it.path, BookmarkSource.MARKDOWN_HEADING, stableKey = it.stableKey) }.orEmpty(),
                sourceSize = source.length(), sourceLastModified = source.lastModified(),
                sourceFingerprint = content?.fingerprint, sourceRelativePath = name,
            )
        }
        fun album(name: String): BookRecord {
            val folder = File(directory, name).apply { mkdirs() }
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
            val images = listOf("level 1.jpg", "level 3.jpg").map { name ->
                val file = File(folder, name)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
                ImageFileRecord(Uri.fromFile(file).toString(), name, name, file.length(), file.lastModified())
            }
            bitmap.recycle()
            val id = UUID.randomUUID().toString()
            return BookRecord(id, name, name, Uri.fromFile(folder).toString(), LibraryItemKind.IMAGE_COLLECTION,
                Color.BLUE, images.size, images.mapIndexed { index, image ->
                    BookmarkEntry(id, image.fileName, index, source = BookmarkSource.IMAGE_FILE, stableKey = "image:${image.uri}")
                }, imageFiles = images, sourceRelativePath = name)
        }
        note = document("notes.md", LibraryItemKind.MARKDOWN)
        pdf = document("downtime.pdf", LibraryItemKind.PDF)
        duplicate = document("other/downtime.pdf", LibraryItemKind.PDF)
        libraryNote = document("library.md", LibraryItemKind.MARKDOWN)
        maps = album("maps")
        offMaps = album("offmaps")
        fixture = listOf(note, pdf, duplicate, libraryNote, maps, offMaps)
        val repository = BookLibraryRepository(context)
        fixture.forEach(repository::saveIndex)
        repository.saveCatalog(BookLibraryState(fixture, linkedSetOf(note.id, pdf.id, maps.id), emptyList(), emptyList()))
        ReaderSessionRepository(context).saveTabs(listOf(ReaderTab.start(note.id)), 0)
    }

    @After fun cleanup() {
        scenario?.close()
        if (!::fixture.isInitialized) return
        fixture.forEach { TextIndexCoordinator.shared(context).delete(it.id) }
        val drained = CountDownLatch(1)
        BookLibraryStorage.shared(context).loadCatalog { drained.countDown() }
        assertTrue(drained.await(10, TimeUnit.SECONDS))
        fixture.forEach { BookLibraryRepository(context).deleteIndex(it.id) }
        originalCatalog?.let(catalog::writeBytes) ?: catalog.delete()
        preferences.forEach { (name, saved) ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().apply {
                clear()
                saved.forEach { (key, value) -> when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Float -> putFloat(key, value)
                    is Boolean -> putBoolean(key, value)
                    is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                } }
            }.commit()
        }
        directory.deleteRecursively()
    }

    @Test fun pdfPageLinkUsesTableMatchAndReusesItsTabAtTheCurrentPage() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        scenario!!.onActivity { activity ->
            assertEquals(2, tabs(activity).size)
            MainActivity::class.java.getDeclaredMethod("jumpCurrentTab", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }.invoke(activity, 13)
        }
        backToNotes()
        open(WikiLinkSpec("downtime.pdf#page=12", "Different alias"))
        awaitBook(pdf.id, 13)
        scenario!!.onActivity { assertEquals(2, tabs(it).size) }
        backToNotes()
        open(WikiLinkSpec("downtime.pdf"))
        awaitBook(pdf.id, 0)
        backToNotes()
        open(WikiLinkSpec("downtime.pdf#page=1"))
        awaitBook(pdf.id, 0)
        scenario!!.onActivity { assertEquals(3, tabs(it).size) }
    }

    @Test fun imageTapOpensTheRightAlbumPageAndHoldLeavesNotesInThePrimaryPane() {
        launch()
        open(WikiLinkSpec("level 3.jpg"))
        awaitBook(maps.id, 1)
        backToNotes()
        open(WikiLinkSpec("maps/level 3.jpg"), alongside = true)
        await { referenceBook() == maps.id }
        scenario!!.onActivity {
            assertEquals(note.id, field<String>(it, "pdfBookId"))
            val reference = field<Any>(it, "referenceLocation")
            assertEquals(1, reference.javaClass.getDeclaredField("pageIndex").apply { isAccessible = true }.getInt(reference))
            assertEquals(2, tabs(it).size)
        }
    }

    @Test fun pageTabMovesThroughBothLongPressMenusAndKeepsItsOrigin() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        scenario!!.onActivity { invoke(it, "jumpCurrentTab", 13) }
        awaitPrimarySurface()
        var moved: ReaderTab? = null
        scenario!!.onActivity {
            moved = tabs(it).last()
            setZoom(field(it, "readerSurface"), 2f)
            val strip = field<ViewGroup>(it, "tabBar")
            assertTrue(strip.getChildAt(1).performLongClick())
        }
        clickMenuAction(R.string.move_to_reference)
        awaitReference(pdf.id, 13)
        scenario!!.onActivity {
            assertSame(moved, field<ReaderTab>(it, "referenceLocation"))
            assertEquals(note.id, tabs(it).single().bookId)
            assertEquals(11, moved!!.originPageIndex)
            assertEquals(2f, field<ReaderSurface>(it, "referenceSurface").captureViewportState()!!.zoom, 0.001f)
            assertTrue(field<View>(it, "referenceIndicatorTitle").performLongClick())
        }
        clickMenuAction(R.string.make_regular_tab)
        awaitBook(pdf.id, 13)
        awaitPrimarySurface()
        scenario!!.onActivity {
            assertNull(field<ReaderTab?>(it, "referenceLocation"))
            assertSame(moved, tabs(it).last())
            assertEquals(2f, field<ReaderSurface>(it, "readerSurface").captureViewportState()!!.zoom, 0.001f)
            invoke(it, "returnTabToStart", 1)
        }
        awaitBook(pdf.id, 11)
    }

    @Test fun backgroundSwapKeepsActiveTabAndDistinctSessionsForTheSameBook() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        backToNotes()
        open(WikiLinkSpec("downtime.pdf#page=12", "Reference copy"), alongside = true)
        awaitReference(pdf.id, 11)
        var moved: ReaderTab? = null
        var previousReference: ReaderTab? = null
        scenario!!.onActivity {
            moved = tabs(it)[1]
            previousReference = field(it, "referenceLocation")
            invoke(it, "jumpReference", 16)
        }
        awaitReference(pdf.id, 16)
        scenario!!.onActivity { invoke(it, "moveTabToReference", 1) }
        awaitReference(pdf.id, 11)
        scenario!!.onActivity {
            assertEquals(0, field<Int>(it, "activeTabIndex"))
            assertEquals(note.id, field<String>(it, "pdfBookId"))
            assertSame(previousReference, tabs(it)[1])
            assertEquals(16, tabs(it)[1].pageIndex)
            assertSame(moved, field<ReaderTab>(it, "referenceLocation"))
            invoke(it, "moveTabToReference", 0)
        }
        awaitBook(pdf.id, 11)
        awaitReference(note.id, 0)
        scenario!!.onActivity {
            assertSame(moved, tabs(it)[0])
            assertEquals(2, tabs(it).size)
        }
    }

    @Test fun lastTabCannotMoveWithoutAReferenceButCanSwapWithOne() {
        launch()
        scenario!!.onActivity { invoke(it, "showTabActions", 0) }
        assertFalse(menuContains(R.string.move_to_reference))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        scenario!!.onActivity { invoke(it, "moveTabToReference", 0) }
        scenario!!.onActivity { assertNull(field<ReaderTab?>(it, "referenceLocation")) }
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"), alongside = true)
        awaitReference(pdf.id, 11)
        scenario!!.onActivity { invoke(it, "showTabActions", 0) }
        clickMenuAction(R.string.move_to_reference)
        awaitBook(pdf.id, 11)
        awaitReference(note.id, 0)
        scenario!!.onActivity { assertEquals(1, tabs(it).size) }
    }

    @Test fun referenceAndPromotedTabSurviveRecreationAndColdLaunch() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        scenario!!.onActivity { invoke(it, "moveTabToReference", 1) }
        awaitReference(pdf.id, 11)
        scenario!!.recreate()
        awaitReference(pdf.id, 11)
        scenario!!.onActivity {
            assertEquals("Pazuzu", field<ReaderTab>(it, "referenceLocation").label)
            invoke(it, "jumpReference", 15)
        }
        awaitReference(pdf.id, 15)
        scenario!!.close()
        launch()
        awaitReference(pdf.id, 15)
        scenario!!.onActivity { invoke(it, "makeReferenceRegularTab") }
        awaitBook(pdf.id, 15)
        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitBook(pdf.id, 15)
        scenario!!.onActivity {
            assertNull(field<ReaderTab?>(it, "referenceLocation"))
            assertEquals(11, tabs(it).last().originPageIndex)
            assertEquals(2, tabs(it).size)
        }
    }

    @Test fun closedReferenceAndTableRemovalDoNotResurrectAfterRestart() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"), alongside = true)
        awaitReference(pdf.id, 11)
        scenario!!.onActivity { invoke(it, "closeReference", true) }
        scenario!!.close()
        launch()
        scenario!!.onActivity { assertNull(field<ReaderTab?>(it, "referenceLocation")) }
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"), alongside = true)
        awaitReference(pdf.id, 11)
        scenario!!.onActivity {
            invoke(it, "applyTableSelectionIntent", selected(it) - pdf.id, true, true)
        }
        scenario!!.close()
        launch()
        scenario!!.onActivity {
            assertNull(field<ReaderTab?>(it, "referenceLocation"))
            assertFalse(pdf.id in selected(it))
        }
    }

    @Test fun sameImageSessionsRetainSeparateZoomWhenSwappedInWideAndTallLayouts() {
        launch()
        open(WikiLinkSpec("maps/level 3.jpg"))
        awaitBook(maps.id, 1)
        awaitPrimarySurface()
        scenario!!.onActivity { invoke(it, "openBookmarkAlongside", maps.pdfBookmarks[1]) }
        awaitReference(maps.id, 1)
        for (layout in listOf("WIDE", "TALL")) {
            scenario!!.onActivity {
                val type = field<Any>(it, "readerLayoutMode").javaClass
                invoke(it, "setReaderLayoutMode", type.enumConstants!!.single { value -> (value as Enum<*>).name == layout })
            }
            awaitPrimarySurface()
            awaitReference(maps.id, 1)
            scenario!!.onActivity {
                setZoom(field(it, "readerSurface"), 2f)
                setZoom(field(it, "referenceSurface"), 3f)
                invoke(it, "moveTabToReference", 1)
            }
            awaitPrimarySurface()
            awaitReference(maps.id, 1)
            scenario!!.onActivity {
                assertEquals(3f, field<ReaderSurface>(it, "readerSurface").captureViewportState()!!.zoom, 0.001f)
                assertEquals(2f, field<ReaderSurface>(it, "referenceSurface").captureViewportState()!!.zoom, 0.001f)
            }
        }
        scenario!!.onActivity { invoke(it, "makeReferenceRegularTab") }
        awaitPrimarySurface()
        scenario!!.onActivity {
            assertEquals(3, tabs(it).size)
            assertEquals(2f, field<ReaderSurface>(it, "readerSurface").captureViewportState()!!.zoom, 0.001f)
        }
    }

    @Test fun foldedNoteKeepsItsScrollAndFoldStateThroughMoveAndPromotion() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        backToNotes()
        var state: MarkdownSurface.ReadingState? = null
        var viewport: MarkdownSurface.ViewportState? = null
        scenario!!.onActivity {
            val surface = field<MarkdownSurface>(it, "primaryMarkdownSurface")
            surface.toggleHeading(1)
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity {
            val surface = field<MarkdownSurface>(it, "primaryMarkdownSurface")
            (surface.getChildAt(0) as ScrollView).scrollTo(0, 900)
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity {
            val surface = field<MarkdownSurface>(it, "primaryMarkdownSurface")
            state = field<Map<ReaderTab, MarkdownSurface.ReadingState>>(it, "markdownReadingStates")[tabs(it)[0]]!!
            viewport = surface.captureViewportState()!!
            assertTrue(viewport!!.sectionProgress > 0f)
            invoke(it, "moveTabToReference", 0)
        }
        awaitReference(note.id, viewport!!.sectionIndex)
        scenario!!.onActivity {
            assertTrue(field<MarkdownSurface>(it, "referenceMarkdownSurface").isShowing(state))
            assertTrue(state!!.folds.sectionModes.isNotEmpty())
            assertEquals(viewport!!.sectionProgress, state!!.viewport!!.sectionProgress, 0.03f)
            invoke(it, "makeReferenceRegularTab")
        }
        awaitBook(note.id, viewport!!.sectionIndex)
        await {
            var shown = false
            scenario!!.onActivity { shown = field<MarkdownSurface>(it, "primaryMarkdownSurface").isShowing(state) }
            shown
        }
        scenario!!.onActivity {
            assertTrue(state!!.folds.sectionModes.isNotEmpty())
            assertEquals(viewport!!.sectionProgress, state!!.viewport!!.sectionProgress, 0.03f)
        }
    }

    @Test fun movingTabReplacesSearchResultsAndSearchMenuCannotPromoteThem() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        backToNotes()
        scenario!!.onActivity { invoke(it, "showTextSearchReference", "Some") }
        scenario!!.onActivity { assertTrue(field<View>(it, "referenceIndicatorTitle").performLongClick()) }
        assertFalse(menuContains(R.string.make_regular_tab))
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        scenario!!.onActivity { invoke(it, "moveTabToReference", 1) }
        awaitReference(pdf.id, 11)
        scenario!!.onActivity {
            assertNull(field<Any?>(it, "textSearchSession"))
            assertEquals(note.id, field<String>(it, "pdfBookId"))
        }
    }

    @Test fun recentTabPlusRestoresTheClosedPositionAsADistinctSession() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        scenario!!.onActivity { invoke(it, "jumpCurrentTab", 13) }
        awaitPrimarySurface()
        scenario!!.onActivity { setZoom(field(it, "readerSurface"), 2f) }
        scenario!!.onActivity { invoke(it, "closeTab", 1) }
        awaitBook(note.id, 0)
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        val root = searchOverlay()
        saveOverlay(root)
        instrumentation.runOnMainSync { historyTabs(root).filterIsInstance<Button>().first { it.text == "+" }.performClick() }
        awaitBook(pdf.id, 13)
        awaitPrimarySurface()
        scenario!!.onActivity {
            assertEquals(3, tabs(it).size)
            assertEquals(11, tabs(it)[1].pageIndex)
            assertEquals(11, tabs(it).last().originPageIndex)
            assertEquals(2f, field<ReaderSurface>(it, "readerSurface").captureViewportState()!!.zoom, 0.001f)
        }
        assertTrue(ReaderHistoryRepository(context).closedTabs(mapOf(pdf.id to 20)).isEmpty())
    }

    @Test fun recentTabButtonsRestoreInBackgroundAndInTheReferencePane() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        scenario!!.onActivity { invoke(it, "jumpCurrentTab", 13) }
        awaitPrimarySurface()
        scenario!!.onActivity { invoke(it, "closeTab", 1) }
        awaitBook(note.id, 0)
        val root = searchOverlay()
        instrumentation.runOnMainSync { assertTrue(historyTabs(root).filterIsInstance<Button>().first { it.text == "+" }.performLongClick()) }
        scenario!!.onActivity {
            assertEquals(2, tabs(it).size)
            assertEquals(note.id, field<String>(it, "pdfBookId"))
            assertEquals(13, tabs(it)[1].pageIndex)
        }
        assertTrue(root.isShown)
        instrumentation.runOnMainSync { descendants(root).filterIsInstance<Button>()
            .first { it.contentDescription == context.getString(R.string.close_search_description) }.performClick() }
        scenario!!.onActivity { invoke(it, "closeTab", 1) }
        val next = searchOverlay()
        instrumentation.runOnMainSync { historyTabs(next).filterIsInstance<Button>().first { it.text.toString() in listOf("◨", "⬒") }.performClick() }
        awaitReference(pdf.id, 13)
        scenario!!.onActivity {
            assertEquals(1, tabs(it).size)
            assertEquals(11, field<ReaderTab>(it, "referenceLocation").originPageIndex)
        }
    }

    @Test fun sharedHistoryToggleKeepsBothColumnsExpandedAfterABackgroundRestore() {
        launch()
        val repository = ReaderHistoryRepository(context)
        (1..12).forEach { repository.recordSearch("Query $it", RecentSearchMode.BOOKMARKS, note.id) }
        repository.recordClosedTabs((0 until 12).map { ReaderTab(pdf.id, it, "Pazuzu $it") })
        val root = searchOverlay()
        fun rows(tag: String) = descendants(root.findViewWithTag(tag)).filterIsInstance<SearchResultRow>().toList()
        instrumentation.runOnMainSync {
            assertEquals(3, rows("recent-searches-column").size)
            assertEquals(3, rows("recent-tabs-column").size)
        }
        saveOverlay(root, "search-history-overlay-more.png")
        instrumentation.runOnMainSync {
            root.findViewWithTag<Button>("more-history").performClick()
            assertEquals(10, rows("recent-searches-column").size)
            assertEquals(10, rows("recent-tabs-column").size)
            assertTrue(rows("recent-tabs-column").first().plus.performLongClick())
            assertEquals(10, rows("recent-searches-column").size)
            assertEquals(10, rows("recent-tabs-column").size)
            assertEquals(context.getString(R.string.less_history), root.findViewWithTag<Button>("more-history").text)
        }
        scenario!!.onActivity {
            assertEquals(2, tabs(it).size)
            assertEquals(note.id, field<String>(it, "pdfBookId"))
        }
        assertEquals(11, repository.closedTabs(mapOf(pdf.id to 20)).size)
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            assertTrue(root.isShown)
            val list = descendants(root).filterIsInstance<ListView>().single()
            assertTrue(list.canScrollVertically(1))
            root.findViewWithTag<Button>("more-history").performClick()
            assertEquals(3, rows("recent-searches-column").size)
            assertEquals(3, rows("recent-tabs-column").size)
        }
    }

    @Test fun phoneWidthOverlayKeepsBothColumnsAndScrollsToTheResults() {
        launch()
        ReaderHistoryRepository(context).apply {
            recordSearch("Long section", RecentSearchMode.BOOKMARKS, note.id)
            recordClosedTabs(listOf(ReaderTab(pdf.id, 13, "Pazuzu", "anchor", 11)))
        }
        val root = searchOverlay()
        val density = context.resources.displayMetrics.density
        scenario!!.onActivity {
            val params = root.layoutParams as WindowManager.LayoutParams
            params.width = (320 * density).toInt()
            params.height = (420 * density).toInt()
            it.windowManager.updateViewLayout(root, params)
        }
        await { root.width == (320 * density).toInt() }
        instrumentation.runOnMainSync {
            val left = root.findViewWithTag<View>("recent-searches-column")
            val right = root.findViewWithTag<View>("recent-tabs-column")
            assertEquals(left.top, right.top)
            assertTrue(left.right < right.left)
            val summary = root.findViewWithTag<View>("search-summary")
            val summaryBounds = android.graphics.Rect()
            val historyBounds = android.graphics.Rect()
            summary.getGlobalVisibleRect(summaryBounds)
            root.findViewWithTag<View>("search-history").getGlobalVisibleRect(historyBounds)
            assertTrue("Result summary must separate history from main results", historyBounds.bottom <= summaryBounds.top)
            assertTrue(historyTabs(root).filterIsInstance<Button>().all { it.width >= (48 * density).toInt() })
            val list = descendants(root).filterIsInstance<ListView>().single()
            assertTrue(list.canScrollVertically(1))
            assertTrue(descendants(root).filterIsInstance<EditText>().single().isShown)
        }
        saveOverlay(root, "search-history-overlay-phone.png")
    }

    @Test fun closedMarkdownSessionKeepsItsFoldsAndScrollWhenReopenedAlongside() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        backToNotes()
        scenario!!.onActivity { field<MarkdownSurface>(it, "primaryMarkdownSurface").toggleHeading(1) }
        instrumentation.waitForIdleSync()
        var state: MarkdownSurface.ReadingState? = null
        var viewport: MarkdownSurface.ViewportState? = null
        scenario!!.onActivity {
            val surface = field<MarkdownSurface>(it, "primaryMarkdownSurface")
            (surface.getChildAt(0) as ScrollView).scrollTo(0, 900)
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity {
            state = field<Map<ReaderTab, MarkdownSurface.ReadingState>>(it, "markdownReadingStates")[tabs(it)[0]]
            viewport = field<MarkdownSurface>(it, "primaryMarkdownSurface").captureViewportState()
            invoke(it, "closeTab", 0)
        }
        awaitBook(pdf.id, 11)
        val root = searchOverlay()
        instrumentation.runOnMainSync { historyTabs(root).filterIsInstance<Button>().first { it.text.toString() in listOf("◨", "⬒") }.performClick() }
        awaitReference(note.id, viewport!!.sectionIndex)
        scenario!!.onActivity {
            assertTrue(field<MarkdownSurface>(it, "referenceMarkdownSurface").isShowing(state))
            assertTrue(state!!.folds.sectionModes.isNotEmpty())
            assertEquals(viewport!!.sectionProgress, state!!.viewport!!.sectionProgress, 0.03f)
        }
    }

    @Test fun recentTabTapAndHistorySurviveAColdLaunch() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        scenario!!.onActivity { invoke(it, "jumpCurrentTab", 13) }
        awaitPrimarySurface()
        scenario!!.onActivity { invoke(it, "closeTab", 1) }
        awaitBook(note.id, 0)
        scenario!!.close()
        launch()
        val root = searchOverlay()
        instrumentation.runOnMainSync { clickRow(historyTabs(root).filterIsInstance<TextView>().first { it.text == "Pazuzu" }) }
        awaitBook(pdf.id, 13)
        scenario!!.onActivity { assertEquals(1, tabs(it).size) }
    }

    @Test fun navigateHistoryRemembersUsedQueriesAndRestoresScopeWithoutSavingTyping() {
        launch()
        scenario!!.onActivity { invoke(it, "changeSearchScope", note.id) }
        val root = searchOverlay("Long section")
        instrumentation.runOnMainSync {
            descendants(root).filterIsInstance<TextView>().first { it !is EditText && it.text == "Long section" }
                .parent.parent.let { (it as View).performClick() }
        }
        awaitBook(note.id, 2)
        val saved = ReaderHistoryRepository(context).searches().single()
        assertEquals(RecentSearch("Long section", RecentSearchMode.BOOKMARKS, note.id), saved)
        val next = searchOverlay()
        instrumentation.runOnMainSync {
            descendants(next.findViewWithTag("recent-searches-column")).filterIsInstance<TextView>()
                .first { it.text == "Long section" }.let(::clickRow)
            val input = descendants(next).filterIsInstance<EditText>().single()
            assertEquals("Long section", input.text.toString())
            assertEquals(View.GONE, next.findViewWithTag<View>("search-history").visibility)
            input.setText("Partial unused query")
            input.setText("")
            assertEquals(View.VISIBLE, next.findViewWithTag<View>("search-history").visibility)
        }
        assertEquals(listOf(saved), ReaderHistoryRepository(context).searches())
        scenario!!.onActivity { assertEquals(note.id, field<String>(it, "searchScopeBookId")) }
    }

    @Test fun recentFullTextSearchRunsWithSavedScopeAndHidesOffTableHistory() {
        launch()
        val repository = ReaderHistoryRepository(context)
        repository.recordSearch("Paragraph", RecentSearchMode.TEXT, note.id)
        repository.recordSearch("Off table", RecentSearchMode.TEXT, libraryNote.id)
        repository.recordClosedTabs(listOf(ReaderTab.start(libraryNote.id)))
        val root = searchOverlay()
        instrumentation.runOnMainSync {
            val left = descendants(root.findViewWithTag("recent-searches-column")).filterIsInstance<TextView>().toList()
            assertFalse(left.any { it.text == "Off table" })
            assertTrue(historyTabs(root).filterIsInstance<Button>().none { it.text == "+" })
            clickRow(left.first { it.text == "Paragraph" })
        }
        await { var query: String? = null; scenario!!.onActivity {
            field<Any?>(it, "textSearchSession")?.let { session ->
                query = session.javaClass.getDeclaredField("query").apply { isAccessible = true }.get(session) as String
            }
        }; query == "Paragraph" }
        scenario!!.onActivity { assertEquals(note.id, field<String>(it, "searchScopeBookId")) }
    }

    @Test fun paneMovesStayOutOfHistoryAndCloseOthersRecordsTheActiveSessionFirst() {
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu"))
        awaitBook(pdf.id, 11)
        scenario!!.onActivity { invoke(it, "moveTabToReference", 1) }
        awaitReference(pdf.id, 11)
        assertTrue(ReaderHistoryRepository(context).closedTabs(mapOf(pdf.id to 20, note.id to note.pageCount)).isEmpty())
        open(WikiLinkSpec("maps/level 3.jpg"))
        awaitBook(maps.id, 1)
        backToNotes()
        open(WikiLinkSpec("downtime.pdf#page=5", "Earlier"))
        awaitBook(pdf.id, 4)
        scenario!!.onActivity { invoke(it, "closeOtherTabs", 1) }
        awaitBook(maps.id, 1)
        val closed = ReaderHistoryRepository(context).closedTabs(fixture.associate { it.id to it.pageCount })
        assertEquals(listOf(pdf.id, note.id), closed.map { it.tab.bookId })
        assertEquals(4, closed.first().tab.pageIndex)
    }

    @Test fun libraryBookPromptCanBeCancelledThenAccepted() {
        launch()
        open(WikiLinkSpec("library.md"))
        dialogButton(R.string.wiki_link_add_book, android.R.id.button2)
        scenario!!.onActivity {
            assertFalse(libraryNote.id in selected(it))
            assertEquals(1, tabs(it).size)
        }
        open(WikiLinkSpec("library.md"))
        dialogButton(R.string.wiki_link_add_book, android.R.id.button1)
        awaitBook(libraryNote.id, 0)
        scenario!!.onActivity { assertTrue(libraryNote.id in selected(it)) }
    }

    @Test fun libraryImageHoldPromptsForTheWholeAlbumThenOpensReference() {
        launch()
        open(WikiLinkSpec("offmaps/level 3.jpg"), alongside = true)
        dialogButton(R.string.wiki_link_add_album, android.R.id.button1)
        await { referenceBook() == offMaps.id }
        scenario!!.onActivity {
            assertTrue(offMaps.id in selected(it))
            assertEquals(note.id, field<String>(it, "pdfBookId"))
            assertEquals(1, tabs(it).size)
        }
    }

    @Test fun duplicateTableFilenamesShowAChooser() {
        val repository = BookLibraryRepository(context)
        repository.saveCatalog(BookLibraryState(fixture, linkedSetOf(note.id, pdf.id, duplicate.id, maps.id), emptyList(), emptyList()))
        launch()
        open(WikiLinkSpec("downtime.pdf#page=12"))
        var list: ListView? = null
        await { instrumentation.runOnMainSync {
            val root = WindowInspector.getGlobalWindowViews().firstOrNull { root ->
                descendants(root).filterIsInstance<android.widget.TextView>().any {
                    it.text.toString() == context.getString(R.string.wiki_link_choose_target)
                }
            }
            list = root?.let { descendants(it).filterIsInstance<ListView>().firstOrNull() }
        }; list != null }
        instrumentation.runOnMainSync { checkNotNull(list).apply { performItemClick(getChildAt(1), 1, adapter.getItemId(1)) } }
        awaitBook(duplicate.id, 11)
    }

    @Test fun removedLibraryTargetCannotBeAddedFromAnOldPrompt() {
        launch()
        open(WikiLinkSpec("library.md"))
        awaitDialog(R.string.wiki_link_add_book)
        scenario!!.onActivity { field<MutableList<BookRecord>>(it, "books").removeAll { it.id == libraryNote.id } }
        dialogButton(R.string.wiki_link_add_book, android.R.id.button1)
        scenario!!.onActivity {
            assertFalse(libraryNote.id in selected(it))
            assertEquals(note.id, field<String>(it, "pdfBookId"))
            assertEquals(1, tabs(it).size)
        }
    }

    @Test fun legacyMarkdownHeadingsAndFullTextRefreshToVisibleLinkLabels() {
        val source = File(directory, "notes.md")
        source.writeText("# [[downtime.pdf#page=12|Pazuzu]]\n\nSome notes.")
        val rawFingerprint = java.security.MessageDigest.getInstance("SHA-256")
            .digest(source.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val legacy = note.copy(
            sourceSize = source.length(), sourceLastModified = source.lastModified(), sourceFingerprint = rawFingerprint,
            markdownContentVersion = 0,
            pdfBookmarks = listOf(BookmarkEntry(note.id, "[[downtime.pdf#page=12|Pazuzu]]", 0,
                source = BookmarkSource.MARKDOWN_HEADING, stableKey = "markdown:old-heading#1")),
        )
        val inactiveSource = File(directory, "library.md")
        inactiveSource.writeText("# [[downtime.pdf#page=12|Library alias]]\n\nInactive note.")
        val inactive = libraryNote.copy(
            sourceSize = inactiveSource.length(), sourceLastModified = inactiveSource.lastModified(),
            sourceFingerprint = "legacy", markdownContentVersion = 0,
            pdfBookmarks = listOf(BookmarkEntry(libraryNote.id, "Old library heading", 0,
                source = BookmarkSource.MARKDOWN_HEADING, stableKey = "markdown:old-library#1")),
        )
        fixture = fixture.map { when (it.id) { note.id -> legacy; libraryNote.id -> inactive; else -> it } }
        BookLibraryRepository(context).apply {
            saveIndex(legacy)
            saveIndex(inactive)
            saveCatalog(BookLibraryState(fixture, linkedSetOf(note.id, pdf.id, maps.id, libraryNote.id), emptyList(), emptyList()))
        }
        launch()
        var refreshed: BookRecord? = null
        await {
            scenario!!.onActivity { refreshed = field<List<BookRecord>>(it, "books").first { it.id == note.id } }
            refreshed?.pdfBookmarks?.singleOrNull()?.title == "Pazuzu"
        }
        assertNotEquals(rawFingerprint, refreshed!!.sourceFingerprint)
        assertEquals(MARKDOWN_CONTENT_VERSION, refreshed!!.markdownContentVersion)
        await {
            var upgraded = false
            scenario!!.onActivity { activity ->
                val current = field<List<BookRecord>>(activity, "books").first { it.id == libraryNote.id }
                upgraded = current.markdownContentVersion == MARKDOWN_CONTENT_VERSION && current.pdfBookmarks.singleOrNull()?.title == "Library alias"
            }
            upgraded
        }
        val repository = TextSearchIndexRepository.shared(context)
        fun search(query: String) = repository.search(query, listOf(note.id), mapOf(note.id to refreshed!!.pageCount),
            mapOf(note.id to refreshed!!.sourceRevisionKey()))
        await { search("Pazuzu").totalMatches == 1 }
        assertEquals(0, search("downtime").totalMatches)
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitBook(note.id, 0)
        scenario!!.onActivity { field<Dialog?>(it, "libraryDialog")?.dismiss() }
        await { var visible = false; scenario!!.onActivity {
            val surface = field<MarkdownSurface>(it, "primaryMarkdownSurface")
            visible = surface.visibility == View.VISIBLE && surface.captureViewportState() != null
        }; visible }
    }
    private fun open(spec: WikiLinkSpec, alongside: Boolean = false) = scenario!!.onActivity {
        checkNotNull(field<MarkdownSurface>(it, "primaryMarkdownSurface").onWikiLink).invoke(spec, alongside)
    }
    private fun backToNotes() {
        scenario!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("switchToTab", Int::class.javaPrimitiveType).apply { isAccessible = true }
                .invoke(activity, tabs(activity).indexOfFirst { it.bookId == note.id })
        }
        awaitBook(note.id, 0)
        instrumentation.waitForIdleSync()
    }
    private fun awaitBook(id: String, page: Int) = await {
        var ready = false
        scenario!!.onActivity { ready = field<String?>(it, "pdfBookId") == id && field<Int>(it, "currentPage") == page && field<Any?>(it, "pdf") != null }
        ready
    }
    private fun referenceBook(): String? {
        var id: String? = null
        scenario!!.onActivity { activity -> field<Any?>(activity, "referenceLocation")?.let {
            id = it.javaClass.getDeclaredField("bookId").apply { isAccessible = true }.get(it) as String
        } }
        return id
    }
    private fun awaitDialog(label: Int): View {
        var root: View? = null
        await { instrumentation.runOnMainSync {
            root = WindowInspector.getGlobalWindowViews().firstOrNull { view ->
                descendants(view).filterIsInstance<Button>().any { it.text.toString() == context.getString(label) }
            }
        }; root != null }
        return checkNotNull(root)
    }
    private fun dialogButton(label: Int, id: Int) {
        val root = awaitDialog(label)
        instrumentation.runOnMainSync { root.findViewById<Button>(id).performClick() }
        instrumentation.waitForIdleSync()
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for wiki-link navigation" }
            SystemClock.sleep(50)
        }
        instrumentation.waitForIdleSync()
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(activity: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun tabs(activity: MainActivity) = field<MutableList<ReaderTab>>(activity, "tabs")
    private fun selected(activity: MainActivity) = field<TableSessionController>(activity, "tableSessionController").selectedBookIds

    private fun searchOverlay(query: String = ""): View {
        scenario!!.onActivity { invoke(it, "showBookmarkSearch", query) }
        var root: View? = null
        await {
            instrumentation.runOnMainSync {
                root = WindowInspector.getGlobalWindowViews().firstOrNull { descendants(it).any { view -> view is SearchHistoryView } }
            }
            root != null
        }
        return root!!
    }
    private fun historyTabs(root: View): Sequence<View> = descendants(root.findViewWithTag("recent-tabs-column"))
    private fun clickRow(view: View) {
        generateSequence(view) { it.parent as? View }.filterIsInstance<SearchResultRow>().first().performClick()
    }
    private fun saveOverlay(root: View, name: String = "search-history-overlay.png") = instrumentation.runOnMainSync {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
            val bitmap = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            try {
                root.draw(Canvas(bitmap))
                File(output).mkdirs()
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    private fun invoke(activity: MainActivity, name: String, vararg args: Any) {
        val method = MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
        method.isAccessible = true
        method.invoke(activity, *args)
    }
    private fun awaitPrimarySurface() = await {
        var ready = false
        scenario!!.onActivity { ready = field<ReaderSurface>(it, "readerSurface").captureViewportState() != null &&
            field<Set<*>>(it, "primaryPendingPageKeys").isEmpty() }
        ready
    }
    private fun awaitReference(bookId: String, page: Int) = await {
        var ready = false
        scenario!!.onActivity {
            val reference = field<ReaderTab?>(it, "referenceLocation")
            ready = reference?.bookId == bookId && reference.pageIndex == page &&
                if (bookId == note.id) field<MarkdownSurface>(it, "referenceMarkdownSurface")
                    .isShowing(field<Map<ReaderTab, MarkdownSurface.ReadingState>>(it, "markdownReadingStates")[reference])
                else field<ReaderSurface>(it, "referenceSurface").captureViewportState() != null &&
                    field<Set<*>>(it, "referencePendingPageKeys").isEmpty()
        }
        ready
    }
    private fun menuContains(label: Int): Boolean {
        var menu: ListView? = null
        await {
            instrumentation.runOnMainSync {
                menu = WindowInspector.getGlobalWindowViews().flatMap { descendants(it).toList() }
                    .filterIsInstance<ListView>().firstOrNull { it.adapter?.count?.let { count ->
                        (0 until count).any { index -> it.adapter.getItem(index).toString() == context.getString(R.string.close_reference) ||
                            it.adapter.getItem(index).toString().contains(" · ") }
                    } == true }
            }
            menu != null
        }
        var contains = false
        instrumentation.runOnMainSync { contains = (0 until menu!!.adapter.count).any {
            menu!!.adapter.getItem(it).toString() == context.getString(label)
        } }
        return contains
    }
    private fun clickMenuAction(label: Int) {
        var menu: ListView? = null
        var position = -1
        await {
            instrumentation.runOnMainSync {
                WindowInspector.getGlobalWindowViews().flatMap { descendants(it).toList() }.filterIsInstance<ListView>().forEach { list ->
                    val match = (0 until (list.adapter?.count ?: 0)).firstOrNull { list.adapter.getItem(it).toString() == context.getString(label) }
                    if (match != null) { menu = list; position = match }
                }
            }
            menu != null
        }
        instrumentation.runOnMainSync { menu!!.performItemClick(menu!!.getChildAt(position), position, menu!!.adapter.getItemId(position)) }
        instrumentation.waitForIdleSync()
    }
    private fun setZoom(surface: ReaderSurface, zoom: Float) {
        ReaderSurface::class.java.getDeclaredMethod("restoreViewportState", ReaderSurface.ViewportState::class.java)
            .apply { isAccessible = true }.invoke(surface, ReaderSurface.ViewportState(zoom, 0.5f, 0.5f))
        surface.invalidate()
    }

    private fun writePdf(file: File, pages: Int) {
        val output = ByteArrayOutputStream()
        fun write(value: String) { output.write(value.toByteArray(Charsets.US_ASCII)) }
        write("%PDF-1.4\n")
        val offsets = mutableListOf(0)
        fun obj(id: Int, value: String) { offsets += output.size(); write("$id 0 obj\n$value\nendobj\n") }
        obj(1, "<< /Type /Catalog /Pages 2 0 R >>")
        obj(2, "<< /Type /Pages /Count $pages /Kids [${(3 until 3 + pages).joinToString(" ") { "$it 0 R" }}] >>")
        for (index in 0 until pages) obj(index + 3, "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << >> >>")
        val xref = output.size()
        write("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { write("%010d 00000 n \n".format(java.util.Locale.ROOT, it)) }
        write("trailer\n<< /Size ${offsets.size} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        file.writeBytes(output.toByteArray())
    }
}
