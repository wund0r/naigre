// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import wund0r.naigre.reader.MainActivity
import wund0r.naigre.reader.navigation.BookmarkEntry
import wund0r.naigre.reader.navigation.BookmarkSource
import wund0r.naigre.reader.navigation.ReaderSessionRepository
import wund0r.naigre.reader.navigation.ReaderTab
import wund0r.naigre.reader.render.MarkdownSurface
import wund0r.naigre.reader.search.TextIndexCoordinator
import wund0r.naigre.reader.table.*
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@SdkSuppress(minSdkVersion = 30)
class MarkdownViewportTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private var originalCatalog: ByteArray? = null
    private val preferences = mutableMapOf<String, Map<String, *>>()
    private lateinit var source: File
    private lateinit var book: BookRecord
    private val catalog get() = File(context.filesDir, "book-library.json")

    @Before fun prepare() {
        check(context.packageName == "wund0r.naigre.reader.verification")
        if (catalog.exists()) {
            val saved = (BookLibraryRepository(context).readCatalog() as? BookCatalogState.Loaded)?.library
            check(saved != null && saved.books.isEmpty() && saved.selectedBookIds.isEmpty() && saved.folders.isEmpty() && saved.tags.isEmpty()) {
                "Markdown viewport tests require an empty verification library"
            }
            originalCatalog = catalog.readBytes()
        }
        listOf("pdf-jump", "bookmark-visits-v2").forEach { name ->
            preferences[name] = context.getSharedPreferences(name, Context.MODE_PRIVATE).all
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
        }
        source = File(context.cacheDir, "viewport-${UUID.randomUUID()}.md")
        val body = (1..40).joinToString("\n\n") { "Paragraph $it." }
        source.writeText("# Notes\n\n$body\n\n# Бой\n\n$body\n\n# End\n\nDone.")
        val content = MarkdownEngine(context).parse(source.readText())
        val id = UUID.randomUUID().toString()
        book = BookRecord(
            id = id, title = "Notes", fileName = source.name, uri = Uri.fromFile(source).toString(),
            kind = LibraryItemKind.MARKDOWN, color = Color.GREEN, pageCount = content.sections.size,
            pdfBookmarks = content.sections.map {
                BookmarkEntry(id, it.title, it.index, it.path, BookmarkSource.MARKDOWN_HEADING, stableKey = it.stableKey)
            }, sourceSize = source.length(), sourceLastModified = source.lastModified(), sourceFingerprint = content.fingerprint,
        )
        BookLibraryRepository(context).apply {
            saveIndex(book)
            saveCatalog(BookLibraryState(listOf(book), linkedSetOf(id), emptyList(), emptyList()))
        }
        ReaderSessionRepository(context).saveTabs(listOf(ReaderTab.start(id)), 0)
    }

    @After fun cleanup() {
        scenario?.close()
        if (!::book.isInitialized) return
        TextIndexCoordinator.shared(context).delete(book.id)
        val drained = CountDownLatch(1)
        BookLibraryStorage.shared(context).loadCatalog { drained.countDown() }
        assertTrue(drained.await(10, TimeUnit.SECONDS))
        BookLibraryRepository(context).deleteIndex(book.id)
        originalCatalog?.let(catalog::writeBytes) ?: catalog.delete()
        source.delete()
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
    }

    @Suppress("DEPRECATION") // Exercise fitted and overlapping bars on Android 30+.
    @Test fun hiddenTabsUseTheWholeViewportAndTheBottomHeadingIsDrawn() {
        launch()
        setChrome(false)
        for (fitsSystemBars in listOf(true, false)) {
            scenario!!.onActivity { activity ->
                activity.window.setDecorFitsSystemWindows(fitsSystemBars)
                field<View>(activity, "root").requestApplyInsets()
            }
            instrumentation.waitForIdleSync()
            assertBottomHeadingDrawn()
        }
    }

    private fun assertBottomHeadingDrawn() = scenario!!.onActivity { activity ->
        val surface = field<MarkdownSurface>(activity, "primaryMarkdownSurface")
        val scroll = scroll(surface)
        val target = text(surface)
        val root = field<View>(activity, "root")
        val bars = root.rootWindowInsets.getInsets(WindowInsets.Type.systemBars())
        val window = activity.windowManager.currentWindowMetrics.bounds
        val rootTop = screenY(root)
        val rootBottom = rootTop + root.height
        val remainingTop = (window.top + bars.top - rootTop).coerceAtLeast(0)
        val remainingBottom = (rootBottom - (window.bottom - bars.bottom)).coerceAtLeast(0)
        assertEquals(remainingTop + dp(activity, 20), scroll.paddingTop)
        assertEquals(remainingBottom, scroll.paddingBottom)

        surface.setUiColors(Color.BLACK, Color.WHITE)
        val line = target.layout.getLineForOffset(target.text.indexOf("Бой"))
        val headingY = surface.height - scroll.paddingBottom - dp(activity, 60)
        scroll.scrollTo(0, target.top + target.layout.getLineTop(line) - headingY)
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        try {
            surface.draw(Canvas(bitmap))
            var drawn = 0
            for (y in headingY until surface.height - scroll.paddingBottom) {
                for (x in target.left until minOf(target.right, target.left + dp(activity, 220))) {
                    val pixel = bitmap.getPixel(x, y)
                    if (Color.red(pixel) > 180 && Color.green(pixel) > 180 && Color.blue(pixel) > 180) drawn++
                }
            }
            assertTrue("Бой must be drawn in the formerly clipped bottom band", drawn > 50)
        } finally {
            bitmap.recycle()
        }
    }

    @Test fun togglingControlsKeepsTextFixedInBothPanesAndLayouts() {
        launch()
        scenario!!.onActivity { activity ->
            field<MarkdownSurface>(activity, "primaryMarkdownSurface").onWikiLink!!.invoke(WikiLinkSpec(book.fileName), true)
        }
        await { var ready = false; scenario!!.onActivity {
            val surface = field<MarkdownSurface>(it, "referenceMarkdownSurface")
            ready = surface.visibility == View.VISIBLE && surface.captureViewportState() != null
        }; ready }
        for (mode in listOf("WIDE", "TALL")) {
            scenario!!.onActivity { activity ->
                val method = MainActivity::class.java.declaredMethods.single { it.name == "setReaderLayoutMode" }.apply { isAccessible = true }
                val value = requireNotNull(method.parameterTypes.single().enumConstants).single { (it as Enum<*>).name == mode }
                method.invoke(activity, value)
            }
            instrumentation.waitForIdleSync()
            val panes = listOf("primaryMarkdownSurface", "referenceMarkdownSurface")
            for (position in listOf("start", "middle", "end")) {
                val before = mutableMapOf<String, TextPosition>()
                scenario!!.onActivity { activity ->
                    for (name in panes) {
                        val surface = field<MarkdownSurface>(activity, name)
                        moveTo(surface, position)
                        before[name] = captureTextPosition(surface)
                    }
                }
                for (visible in listOf(false, true)) {
                    setChrome(visible)
                    scenario!!.onActivity { activity ->
                        assertEquals(visible, field<View>(activity, "bottomChrome").visibility == View.VISIBLE)
                        for (name in panes) {
                            val surface = field<MarkdownSurface>(activity, name)
                            assertEquals("$mode/$position/$name moved when controls changed",
                                before.getValue(name), captureTextPosition(surface))
                            if (position == "end" && visible && (name == "primaryMarkdownSurface" || mode == "WIDE")) {
                                val target = text(surface)
                                val lastLine = target.layout.getLineForOffset(target.text.indexOf("Done."))
                                assertTrue("$mode/$name must scroll the final line above the tabs",
                                    screenY(target) + target.layout.getLineBottom(lastLine) <= screenY(field(activity, "bottomChrome")))
                            }
                        }
                    }
                }
            }
        }
    }

    @Test fun blankTapKeepsTextFixedAndLastLineCanClearVisibleControls() {
        launch()
        // Text selection consumes the first tap when the TextView takes focus.
        scenario!!.onActivity { activity ->
            assertTrue(text(field(activity, "primaryMarkdownSurface")).requestFocus())
        }
        instrumentation.waitForIdleSync()
        lateinit var before: TextPosition
        scenario!!.onActivity { activity ->
            val surface = field<MarkdownSurface>(activity, "primaryMarkdownSurface")
            moveTo(surface, "middle")
            before = captureTextPosition(surface)
            val target = text(surface)
            val x = target.width - 4f
            val y = scroll(surface).height / 2f + scroll(surface).scrollY - target.top
            val now = SystemClock.uptimeMillis()
            for ((index, action) in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).withIndex()) {
                val event = MotionEvent.obtain(now, now + index * 40, action, x, y, 0)
                target.dispatchTouchEvent(event)
                event.recycle()
            }
        }
        instrumentation.waitForIdleSync()
        // TextView focus/selection can start a smooth scroll after the tap.
        SystemClock.sleep(350)
        scenario!!.onActivity { activity ->
            val surface = field<MarkdownSurface>(activity, "primaryMarkdownSurface")
            scroll(surface).computeScroll()
            assertFalse(field(activity, "chromeVisible"))
            assertEquals("A blank tap must leave the same text at the same screen position", before, captureTextPosition(surface))
        }
        for (position in listOf("start", "middle", "end")) {
            scenario!!.onActivity { activity ->
                val surface = field<MarkdownSurface>(activity, "primaryMarkdownSurface")
                moveTo(surface, position)
                before = captureTextPosition(surface)
            }
            for (visible in listOf(true, false)) {
                setChrome(visible)
                scenario!!.onActivity { activity ->
                    val surface = field<MarkdownSurface>(activity, "primaryMarkdownSurface")
                    assertEquals("Standalone/$position moved when controls changed", before, captureTextPosition(surface))
                    if (position == "end") {
                        val target = text(surface)
                        val lastLine = target.layout.getLineForOffset(target.text.indexOf("Done."))
                        val lastLineBottom = screenY(target) + target.layout.getLineBottom(lastLine)
                        val searchTop = screenY(field(activity, "searchButton"))
                        val tabsTop = screenY(field<View>(activity, "bottomChrome"))
                        val controlsTop = if (visible) minOf(searchTop, tabsTop) else searchTop
                        assertTrue("The final text must scroll above the visible controls", lastLineBottom <= controlsTop)
                    }
                }
            }
        }
    }

    private data class TextPosition(
        val line: String,
        val baselineY: Int,
        val scrollY: Int,
        val textHeight: Int,
        val viewportTop: Int,
        val viewportBottom: Int,
    )

    private fun captureTextPosition(surface: MarkdownSurface): TextPosition {
        val target = text(surface)
        val scroll = scroll(surface)
        val line = target.layout.getLineForVertical(scroll.scrollY)
        return TextPosition(
            target.text.subSequence(target.layout.getLineStart(line), target.layout.getLineEnd(line)).toString(),
            screenY(target) + target.layout.getLineBaseline(line), scroll.scrollY, target.height,
            screenY(scroll) + scroll.paddingTop, screenY(scroll) + scroll.height - scroll.paddingBottom,
        )
    }

    private fun moveTo(surface: MarkdownSurface, position: String) {
        val y = when (position) { "start" -> 0; "end" -> Int.MAX_VALUE; else -> text(surface).height / 2 }
        scroll(surface).scrollTo(0, y)
    }

    private fun launch() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        await { var ready = false; scenario!!.onActivity { activity ->
            ready = field<String?>(activity, "pdfBookId") == book.id &&
                field<MarkdownSurface>(activity, "primaryMarkdownSurface").captureViewportState() != null
            if (ready) field<Dialog?>(activity, "libraryDialog")?.dismiss()
        }; ready }
    }

    private fun setChrome(visible: Boolean) {
        scenario!!.onActivity { activity ->
            if (field<Boolean>(activity, "chromeVisible") != visible) {
                MainActivity::class.java.getDeclaredMethod("toggleChrome").apply { isAccessible = true }.invoke(activity)
            }
        }
        instrumentation.waitForIdleSync()
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for Markdown layout" }
            SystemClock.sleep(50)
        }
        instrumentation.waitForIdleSync()
    }

    private fun scroll(surface: MarkdownSurface) = surface.getChildAt(0) as ScrollView
    private fun text(surface: MarkdownSurface) = scroll(surface).getChildAt(0) as TextView
    private fun screenY(view: View): Int = IntArray(2).also(view::getLocationOnScreen)[1]
    private fun dp(activity: MainActivity, value: Int) = (value * activity.resources.displayMetrics.density).roundToInt()
    @Suppress("UNCHECKED_CAST") private fun <T> field(activity: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
}
