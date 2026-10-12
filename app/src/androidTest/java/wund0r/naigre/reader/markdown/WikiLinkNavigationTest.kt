// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.ListView
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
import wund0r.naigre.reader.search.TextIndexCoordinator
import wund0r.naigre.reader.search.TextSearchIndexRepository
import wund0r.naigre.reader.table.*
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
        listOf("pdf-jump", "bookmark-visits-v2").forEach { name ->
            preferences[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        fun document(name: String, kind: LibraryItemKind): BookRecord {
            val source = File(directory, name).apply { parentFile!!.mkdirs() }
            if (kind == LibraryItemKind.PDF) writePdf(source, 20) else source.writeText("# Notes\n\n[[downtime.pdf#page=12|Pazuzu]]\n\n[[maps/level 3.jpg]]\n\nSome notes.")
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
