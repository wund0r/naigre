// SPDX-License-Identifier: AGPL-3.0-or-later
package wund0r.naigre.reader.ui

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import wund0r.naigre.reader.R
import wund0r.naigre.reader.navigation.BookmarkEntry
import wund0r.naigre.reader.navigation.BookmarkSource
import wund0r.naigre.reader.table.*
import wund0r.naigre.reader.theme.ReaderThemeMode
import wund0r.naigre.reader.theme.UiPalette
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class LibraryPreviewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val loader get() = LibraryPreviewLoader.shared(context)
    private val requested = mutableListOf<BookRecord>()
    private val files = mutableListOf<File>()
    private val indexIds = mutableListOf<String>()

    @Before fun setup() { check(context.packageName == "wund0r.naigre.reader.verification") }

    @After fun cleanup() {
        instrumentation.runOnMainSync { loader.trimMemory() }
        for (book in requested) for (extension in listOf("png", "json")) {
            File(context.cacheDir, "library-previews-v1/${LibraryPreviewLoader.key(book)}.$extension").delete()
        }
        indexIds.forEach { BookLibraryRepository(context).deleteIndex(it) }
        files.forEach { it.delete() }
    }

    private fun file(extension: String) = File(context.cacheDir, "preview-test-${UUID.randomUUID()}.$extension").also { files += it }

    private fun pdf(): BookRecord {
        val source = file("pdf")
        val document = android.graphics.pdf.PdfDocument()
        try {
            // A very tall page catches width-only thumbnail allocation.
            val page = document.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(240, 1800, 1).create())
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawRect(20f, 20f, 220f, 1700f, Paint().apply { color = Color.RED })
            document.finishPage(page)
            source.outputStream().use(document::writeTo)
        } finally { document.close() }
        return book(source, LibraryItemKind.PDF)
    }

    private fun album(): BookRecord {
        val source = file("png")
        val bitmap = Bitmap.createBitmap(1600, 160, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLUE)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return book(source, LibraryItemKind.IMAGE_COLLECTION).copy(imageFiles = listOf(
            ImageFileRecord(Uri.fromFile(source).toString(), "Map.png", "Map.png")))
    }

    private fun note(): BookRecord {
        val book = book(file("md"), LibraryItemKind.MARKDOWN)
        return book.copy(pdfBookmarks = listOf(BookmarkEntry(book.id, "Start", 0,
            source = BookmarkSource.MARKDOWN_HEADING, stableKey = "markdown:start")) +
            (1..6).map { BookmarkEntry(book.id, "Глава $it", it, source = BookmarkSource.MARKDOWN_HEADING, stableKey = "heading-$it") })
    }

    private fun book(source: File, kind: LibraryItemKind) = BookRecord(UUID.randomUUID().toString(),
        "Очень длинное название документа для проверки размещения", fileName = "Длинное название.${source.extension}",
        uri = Uri.fromFile(source).toString(), kind = kind, color = Color.BLUE, pageCount = 1,
        pdfBookmarks = emptyList(), sourceRevisionToken = UUID.randomUUID().toString())

    private fun load(book: BookRecord): LibraryPreview? {
        requested += book
        val done = CountDownLatch(1)
        var result: LibraryPreview? = null
        instrumentation.runOnMainSync { loader.load(book) { result = it; done.countDown() } }
        assertTrue("Preview did not complete", done.await(10, TimeUnit.SECONDS))
        return result
    }

    @Test fun smallPdfAndImagePreviewsPreserveAspectRatioAndSurviveUnavailableSourceInDiskCache() {
        for (book in listOf(pdf(), album())) {
            val preview = load(book)!!.bitmap!!
            assertTrue(preview.width in 1..LibraryPreviewLoader.WIDTH)
            assertTrue(preview.height in 1..LibraryPreviewLoader.HEIGHT)
            if (book.kind == LibraryItemKind.PDF) assertTrue(preview.height > preview.width * 6)
            else assertTrue(preview.width > preview.height * 8)
            assertTrue(File(Uri.parse(book.uri).path!!).delete()) // Only our generated fixture.
            instrumentation.runOnMainSync { loader.trimMemory() }
            val cached = load(book)!!.bitmap!!
            assertEquals(preview.width, cached.width)
            assertEquals(preview.height, cached.height)
            assertNull(load(book.copy(sourceRevisionToken = "changed")))
        }
    }

    @Test fun markdownUsesStoredHeadingsWithoutReadingOrIndexingTheNote() {
        val storedNote = note() // Source intentionally does not exist.
        val storage = BookLibraryStorage.shared(context)
        val ready = CountDownLatch(1)
        storage.loadCatalog { ready.countDown() }
        assertTrue(ready.await(10, TimeUnit.SECONDS))
        val catalogBefore = storage.currentCatalogState()
        storage.saveIndex(storedNote)
        indexIds += storedNote.id
        val lazy = storedNote.copy(indexLoaded = false, pdfBookmarks = emptyList())
        assertEquals(listOf("Глава 1", "Глава 2", "Глава 3", "Глава 4"), load(lazy)!!.headings)
        assertSame(catalogBefore, storage.currentCatalogState())
        assertTrue(load(note().copy(pdfBookmarks = emptyList()))!!.headings.isEmpty())
    }

    @Test fun cancellingOneSubscriptionDoesNotCancelAnotherOrDeliverToTheOldCard() {
        val book = pdf()
        requested += book
        val done = CountDownLatch(1)
        var cancelledCalled = false
        var result: LibraryPreview? = null
        instrumentation.runOnMainSync {
            val cancel = loader.load(book) { cancelledCalled = true }
            loader.load(book) { result = it; done.countDown() }
            cancel()
        }
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNotNull(result?.bitmap)
        assertFalse(cancelledCalled)
    }

    @Test fun forgettingRemovesAllCachedRevisions() {
        val first = pdf()
        val second = first.copy(sourceRevisionToken = "next")
        assertNotNull(load(first))
        assertNotNull(load(second))
        assertTrue(File(Uri.parse(first.uri).path!!).delete())
        instrumentation.runOnMainSync { loader.forget(first.id) }
        assertNull(load(first))
        assertNull(load(second))
    }

    @Test fun cardsHaveUniformGeometryForAllPreviewsMissingContentAndLargeRussianText() {
        val books = listOf(pdf(), album(), note())
        val previews = books.map { load(it) }
        for (theme in listOf(ReaderThemeMode.DARK, ReaderThemeMode.LIGHT)) {
            for (fontScale in listOf(1f, 1.3f)) instrumentation.runOnMainSync {
                val config = Configuration(context.resources.configuration).apply {
                    setLocale(Locale.forLanguageTag("ru")); this.fontScale = fontScale
                }
                val themed = ContextThemeWrapper(context.createConfigurationContext(config),
                    if (theme == ReaderThemeMode.DARK) R.style.AppThemeDark else R.style.AppThemeLight)
                val palette = UiPalette.resolve(themed, theme)
                val root = LinearLayout(themed).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(palette.background) }
                val cards = books.mapIndexed { index, book ->
                    LibraryCardView(themed, palette, loader, ::ColorDrawable, {}, {}).apply {
                        bind(book, true, "Кампания · Другой тег", "1122 страницы · 123 закладки", null)
                        root.addView(this, LinearLayout.LayoutParams(-1, cardHeight))
                        showPreview(previews[index])
                    }
                }
                val density = themed.resources.displayMetrics.density
                val width = (280 * density).roundToInt()
                val height = cards.sumOf { it.cardHeight }
                fun measure() {
                    root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    root.layout(0, 0, width, height)
                }
                measure()
                assertEquals(1, cards.map { it.height }.distinct().size)
                assertEquals(1, cards.map { it.width }.distinct().size)
                fun assertTextFits(view: View) {
                    if (view.visibility != View.VISIBLE) return
                    if (view is TextView) {
                        assertTrue("Clipped text: ${view.text}", view.layout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                    }
                    if (view is ViewGroup) for (index in 0 until view.childCount) assertTextFits(view.getChildAt(index))
                }
                assertTextFits(root)
                val cardHeight = cards.first().height
                cards.first().bind(books.first(), false, "", "", themed.getString(R.string.source_unavailable))
                cards.first().showPreview(null)
                measure()
                assertEquals(cardHeight, cards.first().height)
                assertTextFits(root)
                cards.first().bind(books.first(), true, "Кампания", "1122 страницы · 123 закладки", null)
                cards.first().showPreview(previews.first())
                measure()
                cards.forEach { it.jumpDrawablesToCurrentState() }
                val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
                    ?.let(::File) ?: context.cacheDir
                output.mkdirs()
                val snapshot = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                root.draw(Canvas(snapshot))
                File(output, "library-cards-${theme.name}-$fontScale.png").outputStream().use {
                    snapshot.compress(Bitmap.CompressFormat.PNG, 100, it)
                }
                snapshot.recycle()
            }
        }
    }
}
