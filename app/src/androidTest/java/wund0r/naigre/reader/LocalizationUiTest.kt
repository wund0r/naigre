// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader

import android.app.Dialog
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.LocaleList
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ListView
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import wund0r.naigre.reader.markdown.MarkdownEngine
import wund0r.naigre.reader.navigation.*
import wund0r.naigre.reader.search.TextIndexCoordinator
import wund0r.naigre.reader.search.TextSearchIndexRepository
import wund0r.naigre.reader.table.*
import wund0r.naigre.reader.ui.BookColorPicker
import wund0r.naigre.reader.ui.LibraryPreviewLoader
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
class LocalizationUiTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private var scenario: ActivityScenario<MainActivity>? = null
    private lateinit var book: BookRecord
    private lateinit var source: File
    private val catalog get() = File(context.filesDir, "book-library.json")
    private val preferences = mutableMapOf<String, Map<String, *>>()

    @Before
    fun prepareSyntheticLibrary() {
        check(context.packageName == "wund0r.naigre.reader.verification")
        // Refuse to replace a manually populated verification library.
        if (catalog.exists()) {
            val state = (BookLibraryRepository(context).readCatalog() as? BookCatalogState.Loaded)?.library
            check(state != null && state.books.isEmpty() && state.selectedBookIds.isEmpty() &&
                state.folders.isEmpty() && state.tags.isEmpty()) {
                "UI tests require an empty verification library"
            }
        }
        listOf("pdf-jump", "bookmark-visits-v2").forEach {
            preferences[it] = context.getSharedPreferences(it, Context.MODE_PRIVATE).all
        }
        source = File(context.cacheDir, "localization-${UUID.randomUUID()}.md")
        source.writeText("# Лагерь\n\nЁж сторожит башню.\n\n## Подземелье\n\nЕщё одна башня.\n")
        val parsed = MarkdownEngine(context).parse(source.readText())
        val id = UUID.randomUUID().toString()
        book = BookRecord(
            id = id, title = "Заметки лагеря.md", uri = Uri.fromFile(source).toString(),
            kind = LibraryItemKind.MARKDOWN, color = 0xffb8bb26.toInt(), pageCount = parsed.sections.size,
            pdfBookmarks = parsed.sections.map {
                BookmarkEntry(id, it.title, it.index, it.path, BookmarkSource.MARKDOWN_HEADING, stableKey = it.stableKey)
            }, sourceSize = source.length(), sourceLastModified = source.lastModified(),
            sourceFingerprint = parsed.fingerprint, tagIds = setOf("campaign"),
        )
        BookLibraryRepository(context).apply {
            saveIndex(book)
            saveCatalog(BookLibraryState(listOf(book), linkedSetOf(id), emptyList(), listOf(LibraryTagRecord("campaign", "Кампания"))))
        }
        ReaderSessionRepository(context).apply {
            saveTabs(listOf(ReaderTab.start(id), ReaderTab(id, 1, "Page 42")), 0)
            recordBookmarkVisit(book.pdfBookmarks.first().visitKey, 20)
        }
    }

    @After
    fun cleanup() {
        scenario?.close()
        LocalizationTestRunner.activityConfiguration = null
        if (!::book.isInitialized) return
        TextIndexCoordinator.shared(context).delete(book.id)
        val drained = CountDownLatch(1)
        BookLibraryStorage.shared(context).loadCatalog { drained.countDown() }
        assertTrue(drained.await(10, TimeUnit.SECONDS))
        BookLibraryRepository(context).deleteIndex(book.id)
        catalog.delete()
        source.delete()
        preferences.forEach { (name, snapshot) ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().apply {
                clear()
                snapshot.forEach { (key, value) ->
                    when (value) {
                        is String -> putString(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Set<*> -> putStringSet(key, value.filterIsInstance<String>().toSet())
                    }
                }
            }.commit()
        }
    }

    @Test
    fun libraryControlsFitPhoneAndTabletWidthsInBothLanguages() {
        val failures = mutableListOf<String>()
        for (language in listOf("en", "ru")) {
            for ((width, fontScale, theme) in listOf(Triple(360, 1f, "DARK"), Triple(360, 1.3f, "LIGHT"), Triple(740, 1f, "LIGHT"))) {
                LocalizationTestRunner.activityConfiguration = Configuration().apply {
                    setLocale(Locale.forLanguageTag(language))
                    this.fontScale = fontScale
                }
                context.getSharedPreferences("pdf-jump", Context.MODE_PRIVATE).edit()
                    .putString("reader-theme", theme).commit()
                scenario = ActivityScenario.launch(MainActivity::class.java)
                awaitReady()
                scenario!!.onActivity { activity ->
                    invoke(activity, "showLibrary")
                }
                instrumentation.waitForIdleSync()
                scenario!!.onActivity { activity ->
                    val dialog = field<Dialog>(activity, "libraryDialog")
                    val content = dialog.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    val name = "$language-library-$width-$fontScale-$theme"
                    layout(content, width, 720)
                    capture(content, name)
                    for (id in listOf(R.string.library, R.string.add_file, R.string.add_folder, R.string.read)) {
                        val label = activity.getString(id)
                        val view = descendants(content).filterIsInstance<TextView>().first { it.text.toString() == label }
                        if (!textFits(view) || !inside(view, content)) failures += "$name: $label is clipped"
                    }
                    val menu = descendants(content).single { it.contentDescription == activity.getString(R.string.library_menu_description) }
                    val title = textView(content, activity.getString(R.string.library))
                    val controls = listOf(menu, title) + listOf(R.string.add_file, R.string.add_folder, R.string.read)
                        .map { textView(content, activity.getString(it)) }
                    if (!singleLineFits(title)) failures += "$name: Library title wraps"
                    if (!inside(menu, content) || menu.width < 48 * menu.resources.displayMetrics.density - 1) {
                        failures += "$name: menu touch target is too small or clipped"
                    }
                    for ((index, control) in controls.withIndex()) {
                        for (other in controls.drop(index + 1)) {
                            if (android.graphics.Rect.intersects(bounds(control, content), bounds(other, content))) {
                                failures += "$name: header controls overlap"
                            }
                        }
                    }
                    val strip = field<ViewGroup>(activity, "libraryTagStrip")
                    val filters = (0 until strip.childCount).map { strip.getChildAt(it) }.filterIsInstance<TextView>()
                    assertEquals(listOf(activity.getString(R.string.all), activity.getString(R.string.on_table),
                        activity.getString(R.string.untagged), "Кампания"), filters.map { it.text.toString() })
                    assertEquals(5, strip.childCount) // Three built-ins, divider, custom tag.
                    assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, strip.getChildAt(3).importantForAccessibility)
                    filters.take(3).forEach { assertNull(it.compoundDrawablesRelative[0]) }
                    assertNotNull(filters.last().compoundDrawablesRelative[0])
                    filters.forEach {
                        if (!singleLineFits(it) || it.height != menu.height || it.paddingLeft == 0 || it.paddingRight == 0) {
                            failures += "$name: tag filter is clipped or lost its height/padding: ${it.text}"
                        }
                    }
                    filters.last().performClick()
                    layout(content, width, 720)
                    (strip.parent as HorizontalScrollView).scrollTo(strip.width, 0)
                    capture(content, "$name-tag-selected")
                    val selectedTag = textView(strip, "Кампания")
                    assertTrue(selectedTag.typeface.isBold)
                    assertNotNull(selectedTag.compoundDrawablesRelative[0])
                    assertTrue(inside(selectedTag, content))
                    assertEquals(listOf(book.id), field<List<BookRecord>>(activity, "libraryVisibleBooks").map { it.id })
                    dialog.dismiss()
                }
                scenario!!.close()
                scenario = null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun libraryTagAndUntaggedFiltersKeepTheirLongPressActions() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitReady()
        scenario!!.onActivity { activity ->
            invoke(activity, "showLibrary")
            assertTrue(textView(field(activity, "libraryTagStrip"), "Кампания").performLongClick())
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { assertTrue(menuItems().contains(it.getString(R.string.edit_tagged_items))) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        scenario!!.onActivity { activity ->
            textView(field(activity, "libraryTagStrip"), activity.getString(R.string.untagged)).performClick()
            assertTrue(field<List<BookRecord>>(activity, "libraryVisibleBooks").isEmpty())
            assertTrue(textView(field(activity, "libraryTagStrip"), activity.getString(R.string.untagged)).performLongClick())
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { assertTrue(menuItems().contains(it.getString(R.string.use_only_untagged_on_table))) }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        scenario!!.onActivity { activity ->
            textView(field(activity, "libraryTagStrip"), activity.getString(R.string.all)).performClick()
            assertEquals(listOf(book.id), field<List<BookRecord>>(activity, "libraryVisibleBooks").map { it.id })
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun libraryMenuOffersGlobalPreferencesWithMarkdownOrNoBookAndReturnsToItsOwnMenu() {
        for (empty in listOf(false, true)) {
            if (empty) BookLibraryRepository(context).saveCatalog(BookLibraryState(emptyList(), linkedSetOf(), emptyList(), emptyList()))
            scenario = ActivityScenario.launch(MainActivity::class.java)
            if (!empty) awaitReady() else awaitLibrary()
            scenario!!.onActivity {
                invoke(it, "showLibrary")
                if (empty) assertEquals(3, field<ViewGroup>(it, "libraryTagStrip").childCount) // No dangling divider.
            }
            openLibraryMenu()
            assertLibraryMenu()
            clickMenuItem(0)
            scenario!!.onActivity { activity ->
                val items = menuItems()
                assertEquals(3, items.size)
                assertEquals(activity.getString(R.string.page_layout_summary,
                    activity.getString(if (field(activity, "spreadMode")) R.string.spread else R.string.single_page)), items[0])
                assertTrue(items[2].startsWith(activity.getString(R.string.page_turn_summary, "")))
                assertFalse(items.contains(activity.getString(R.string.reset_zoom)))
            }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            assertLibraryMenu()
            clickMenuItem(1)
            scenario!!.onActivity { assertEquals(3, menuItems().size) }
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            instrumentation.waitForIdleSync()
            assertLibraryMenu()
            instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            scenario!!.onActivity { assertTrue(field<Dialog>(it, "libraryDialog").isShowing) }
            scenario!!.close()
            scenario = null
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 30)
    fun libraryThemeChangeRestoresTagAndFullscreenAndReadReturnsToReader() {
        context.getSharedPreferences("pdf-jump", Context.MODE_PRIVATE).edit()
            .putString("reader-theme", "DARK").commit()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitReady()
        var original: MainActivity? = null
        var filter = ""
        scenario!!.onActivity { activity ->
            original = activity
            invoke(activity, "showLibrary")
            textView(field(activity, "libraryTagStrip"), "Кампания").performClick()
            filter = field<Any>(activity, "libraryFilter").toString()
        }
        openLibraryMenu()
        clickMenuItem(1) // Interface
        clickMenuItem(2) // Fullscreen
        awaitLibraryBars(visible = false)
        openLibraryMenu()
        clickMenuItem(1)
        clickMenuItem(0) // Theme
        clickMenuItem(1) // Light; triggers real Activity recreation.
        await {
            var restored = false
            scenario!!.onActivity { restored = it !== original && field<Dialog?>(it, "libraryDialog")?.isShowing == true }
            restored
        }
        scenario!!.onActivity { activity ->
            assertEquals("LIGHT", field<Any>(activity, "themeMode").toString())
            assertEquals(filter, field<Any>(activity, "libraryFilter").toString())
            assertTrue(field(activity, "immersive"))
            assertEquals(listOf(book.id), field<List<BookRecord>>(activity, "libraryVisibleBooks").map { it.id })
        }
        awaitLibraryBars(visible = false)
        openLibraryMenu()
        clickMenuItem(1)
        clickMenuItem(2) // Restore system bars on both windows.
        awaitLibraryBars(visible = true)
        scenario!!.onActivity { activity ->
            val content = field<Dialog>(activity, "libraryDialog").findViewById<View>(android.R.id.content)
            textView(content, activity.getString(R.string.read)).performClick()
        }
        await {
            var returned = false
            scenario!!.onActivity {
                returned = field<Dialog?>(it, "libraryDialog") == null &&
                    it.window.decorView.rootWindowInsets?.let { insets ->
                        insets.isVisible(WindowInsets.Type.statusBars()) && insets.isVisible(WindowInsets.Type.navigationBars())
                    } == true
            }
            returned
        }
        scenario!!.recreate()
        awaitReady()
        scenario!!.onActivity { assertNull(field<Dialog?>(it, "libraryDialog")) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun searchAndReferenceControlsFitAndKeepCyrillicQuery() {
        val failures = mutableListOf<String>()
        for (language in listOf("en", "ru")) {
            for ((width, fontScale, theme) in listOf(Triple(360, 1f, "DARK"), Triple(360, 1.3f, "LIGHT"), Triple(740, 1.3f, "LIGHT"))) {
                LocalizationTestRunner.activityConfiguration = Configuration().apply {
                    setLocale(Locale.forLanguageTag(language))
                    this.fontScale = fontScale
                }
                context.getSharedPreferences("pdf-jump", Context.MODE_PRIVATE).edit()
                    .putString("reader-theme", theme)
                    .putString("reader-layout", if (width < 600) "TALL" else "WIDE").commit()
                scenario = ActivityScenario.launch(MainActivity::class.java)
                awaitReady()
                scenario!!.onActivity { field<View>(it, "searchButton").performClick() }
                instrumentation.waitForIdleSync()
                scenario!!.onActivity { activity ->
                    val decor = WindowInspector.getGlobalWindowViews().first { root -> descendants(root).any { it is EditText } }
                    val content = decor.findViewById<ViewGroup>(android.R.id.content).getChildAt(0)
                    val input = descendants(content).filterIsInstance<EditText>().single()
                    input.showSoftInputOnFocus = false
                    activity.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(input.windowToken, 0)
                    input.setText("Лагерь")
                    val name = "$language-search-$width-$fontScale-$theme"
                    layout(content, width, 600)
                    capture(content, "$name-navigate")
                    for (id in listOf(R.string.navigate, R.string.full_text, R.string.all)) {
                        val view = textView(content, activity.getString(id))
                        if (!singleLineFits(view) || !inside(view, content)) failures += "$name: ${view.text} is clipped"
                    }
                    textView(content, activity.getString(R.string.full_text)).performClick()
                    assertEquals("Лагерь", input.text.toString())
                    layout(content, width, 600)
                    capture(content, "$name-fulltext")
                    val submit = textView(content, activity.getString(R.string.search_all_text))
                    if (!textFits(submit)) failures += "$name: ${submit.text} is clipped"
                    submit.performClick()
                }
                await {
                    var loaded = false
                    scenario!!.onActivity { loaded = field<ListView>(it, "referenceSearchList").adapter.count > 0 }
                    loaded
                }
                scenario!!.onActivity { activity ->
                    val root = field<View>(activity, "root")
                    layout(root, width, 720)
                    val name = "$language-reference-$width-$fontScale-$theme"
                    capture(root, name)
                    val edit = field<TextView>(activity, "referenceSearchEditButton")
                    if (!singleLineFits(edit)) failures += "$name: ${edit.text} is clipped"
                    val all = textView(field(activity, "referenceSearchScopeRow"), activity.getString(R.string.all))
                    if (!singleLineFits(all)) failures += "$name: ${all.text} is clipped"
                    assertTrue(singleLineFits(field(activity, "referenceIndicatorTitle")))
                    val indicator = field<View>(activity, "referenceIndicatorContainer")
                    val editBounds = bounds(edit, root)
                    val indicatorBounds = bounds(indicator, root)
                    if (android.graphics.Rect.intersects(editBounds, indicatorBounds)) failures += "$name: Edit overlaps reference indicator"
                    descendants(indicator).first { it.contentDescription == activity.getString(R.string.close_reference) }.performClick()
                }
                scenario!!.close()
                scenario = null
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun customBookColorOnlyCommitsOnApplyAndSurvivesRecreationWithoutReindexing() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitReady()
        val index = TextSearchIndexRepository.shared(context)
        await { index.progress(book.id)?.complete == true }
        val indexRun = index.progress(book.id)!!.indexRunId
        var before: BookRecord? = null
        scenario!!.onActivity { before = field<List<BookRecord>>(it, "books").single() }
        openCustomColor()
        scenario!!.onActivity { activity ->
            val picker = colorPicker()
            assertEquals(book.color, picker.color)
            val input = descendants(picker).filterIsInstance<EditText>().single()
            input.setText("#123")
            assertNull(picker.color)
            assertFalse(picker.rootView.findViewById<View>(android.R.id.button1).isEnabled)
            input.setText("#123aBc")
            assertEquals(0xff123abc.toInt(), picker.color)
            assertTrue(picker.rootView.findViewById<View>(android.R.id.button1).isEnabled)
            assertEquals(before, field<List<BookRecord>>(activity, "books").single())
            picker.rootView.findViewById<View>(android.R.id.button2).performClick()
            assertEquals(before, field<List<BookRecord>>(activity, "books").single())
        }
        openCustomColor()
        scenario!!.onActivity { activity ->
            val picker = colorPicker()
            assertEquals(book.color, picker.color)
            descendants(picker).filterIsInstance<EditText>().single().setText("#123aBc")
            picker.rootView.findViewById<View>(android.R.id.button1).performClick()
            val after = field<List<BookRecord>>(activity, "books").single()
            assertEquals(before!!.copy(color = 0xff123abc.toInt()), after)
            assertEquals(LibraryPreviewLoader.key(before!!), LibraryPreviewLoader.key(after))
        }
        await {
            (BookLibraryRepository(context).readCatalog() as? BookCatalogState.Loaded)
                ?.library?.books?.single()?.color == 0xff123abc.toInt()
        }
        scenario!!.recreate()
        awaitReady()
        scenario!!.onActivity { assertEquals(0xff123abc.toInt(), field<List<BookRecord>>(it, "books").single().color) }
        assertEquals(indexRun, index.progress(book.id)!!.indexRunId)
        openCustomColor()
        scenario!!.onActivity {
            val picker = colorPicker()
            assertEquals(0xff123abc.toInt(), picker.color)
            picker.rootView.findViewById<View>(android.R.id.button2).performClick()
        }
        scenario!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("showBookColorDialog", String::class.java)
                .apply { isAccessible = true }.invoke(activity, book.id)
        }
        instrumentation.waitForIdleSync()
        clickMenuItem(0) // Presets still work after selecting a custom color.
        scenario!!.onActivity { assertEquals(0xff79a7d3.toInt(), field<List<BookRecord>>(it, "books").single().color) }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun customColorControlsFitBothLanguagesAndThemesAndSlidersRecoverInvalidInput() {
        for ((language, fontScale, theme) in listOf(Triple("en", 1f, "DARK"), Triple("en", 1.3f, "LIGHT"),
            Triple("ru", 1.3f, "DARK"), Triple("ru", 1f, "LIGHT"))) {
            LocalizationTestRunner.activityConfiguration = Configuration().apply {
                setLocale(Locale.forLanguageTag(language))
                this.fontScale = fontScale
            }
            context.getSharedPreferences("pdf-jump", Context.MODE_PRIVATE).edit().putString("reader-theme", theme).commit()
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitReady()
            openCustomColor()
            scenario!!.onActivity { activity ->
                val picker = colorPicker()
                val input = descendants(picker).filterIsInstance<EditText>().single()
                input.setText("invalid")
                val sliders = descendants(picker).filterIsInstance<SeekBar>().toList()
                assertEquals(3, sliders.size)
                sliders.forEach { slider ->
                    val target = slider.max / 2
                    assertTrue(slider.performAccessibilityAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
                        Bundle().apply { putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, target.toFloat()) }))
                    assertEquals(target, slider.progress)
                    assertNotNull(picker.color)
                    assertTrue(picker.rootView.findViewById<View>(android.R.id.button1).isEnabled)
                    assertEquals(0xff, picker.color!! ushr 24)
                }
                assertEquals(0xff408080.toInt(), picker.color) // HSV(180°, 50%, 50%).
                capture(picker.rootView, "$language-custom-color-dialog-$fontScale-$theme")
                val width = (280 * picker.resources.displayMetrics.density).roundToInt()
                picker.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
                picker.layout(0, 0, width, picker.measuredHeight)
                capture(picker, "$language-custom-color-narrow-$fontScale-$theme")
                assertTrue(singleLineFits(input))
                for (label in listOf(R.string.color_hue, R.string.color_saturation, R.string.color_brightness)) {
                    assertTrue(textFits(textView(picker, activity.getString(label))))
                }
                sliders.forEach { assertTrue(inside(it, picker)) }
                picker.rootView.findViewById<View>(android.R.id.button2).performClick()
                assertEquals(book.color, field<List<BookRecord>>(activity, "books").single().color)
            }
            scenario!!.close()
            scenario = null
        }
    }

    @android.annotation.TargetApi(29)
    private fun colorPicker(): BookColorPicker = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::descendants).filterIsInstance<BookColorPicker>().last { it.isShown }

    @android.annotation.TargetApi(29)
    private fun openCustomColor() {
        scenario!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("showBookColorDialog", String::class.java)
                .apply { isAccessible = true }.invoke(activity, book.id)
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity {
            assertEquals(9, menuList().count)
            assertEquals(8, menuList().checkedItemPosition) // Fixture and applied color are both non-preset.
        }
        clickMenuItem(8)
    }

    @Test
    fun localeRecreationKeepsLiteralTitlesLibraryAndVisits() {
        LocalizationTestRunner.activityConfiguration = Configuration().apply { setLocale(Locale.ENGLISH) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitReady()
        scenario!!.onActivity { assertTitles(it, "Start") }
        val before = ReaderSessionRepository(context).restoreTabs(null, mapOf(book.id to book.pageCount))
        LocalizationTestRunner.activityConfiguration = Configuration().apply { setLocale(Locale.forLanguageTag("ru")) }
        scenario!!.recreate()
        awaitReady()
        scenario!!.onActivity { assertTitles(it, "Начало") }
        assertSavedState(before)
    }

    @Test
    @SdkSuppress(minSdkVersion = 33)
    fun nativePerAppLanguageChangeRecreatesReaderWithoutChangingDeviceLanguage() {
        val manager = context.getSystemService(LocaleManager::class.java)
        val originalLocales = manager.applicationLocales
        val systemLocales = manager.systemLocales
        try {
            manager.applicationLocales = LocaleList.forLanguageTags("en")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            awaitReady()
            scenario!!.onActivity { assertTitles(it, "Start") }
            val before = ReaderSessionRepository(context).restoreTabs(null, mapOf(book.id to book.pageCount))
            val index = TextSearchIndexRepository.shared(context)
            await { index.progress(book.id)?.complete == true }
            val indexRun = index.progress(book.id)!!.indexRunId
            for ((language, title) in listOf("ru" to "Начало", "en" to "Start")) {
                var original: MainActivity? = null
                scenario!!.onActivity { original = it }
                manager.applicationLocales = LocaleList.forLanguageTags(language)
                await {
                    var changed = false
                    scenario!!.onActivity { changed = it !== original && it.resources.configuration.locales[0].language == language }
                    changed
                }
                awaitReady()
                scenario!!.onActivity { assertTitles(it, title) }
                assertSavedState(before)
                assertEquals(indexRun, index.progress(book.id)!!.indexRunId)
            }
            assertEquals(systemLocales, manager.systemLocales)
        } finally {
            scenario?.close()
            scenario = null
            manager.applicationLocales = originalLocales
        }
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun russianLayoutChoicesAndForgetConfirmationFitWithoutPerformingDestructiveAction() {
        LocalizationTestRunner.activityConfiguration = Configuration().apply {
            setLocale(Locale.forLanguageTag("ru"))
            fontScale = 1.3f
        }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        awaitReady()
        scenario!!.onActivity { invoke(it, "showReaderLayoutDialog") }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { activity ->
            val decor = WindowInspector.getGlobalWindowViews().first { root ->
                descendants(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.layout_wide) }
            }
            val content = decor.findViewById<ViewGroup>(android.R.id.content)
            capture(content, "ru-layout-dialog-1.3")
            for (id in listOf(R.string.layout_automatic, R.string.layout_wide, R.string.layout_tall)) {
                assertTrue(textFits(textView(content, activity.getString(id))))
            }
        }
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        scenario!!.onActivity { activity ->
            MainActivity::class.java.getDeclaredMethod("confirmForgetBook", String::class.java)
                .apply { isAccessible = true }.invoke(activity, book.id)
        }
        instrumentation.waitForIdleSync()
        scenario!!.onActivity { activity ->
            val decor = WindowInspector.getGlobalWindowViews().first { root ->
                descendants(root).filterIsInstance<TextView>().any { it.text == activity.getString(R.string.forget_item_message) }
            }
            val content = decor.findViewById<ViewGroup>(android.R.id.content)
            capture(content, "ru-forget-confirmation-1.3")
            for (id in listOf(R.string.forget_item_message, R.string.forget, R.string.cancel)) {
                assertTrue(textFits(textView(content, activity.getString(id))))
            }
            textView(content, activity.getString(R.string.cancel)).performClick()
        }
        assertTrue(source.exists())
        assertTrue(catalog.exists())
    }

    private fun assertSavedState(before: RestoredReaderTabs) {
        val repository = ReaderSessionRepository(context)
        assertEquals(before, repository.restoreTabs(null, mapOf(book.id to book.pageCount)))
        assertEquals(21, repository.loadBookmarkVisits()[book.pdfBookmarks.first().visitKey])
        val saved = (BookLibraryRepository(context).readCatalog() as BookCatalogState.Loaded).library
        assertEquals(linkedSetOf(book.id), saved.selectedBookIds)
        assertEquals(book.tagIds, saved.books.single().tagIds)
        assertEquals(book.sourceRevisionKey(), saved.books.single().sourceRevisionKey())
    }

    private fun assertTitles(activity: MainActivity, start: String) {
        val labels = descendants(field(activity, "tabBar")).filterIsInstance<TextView>().map { it.text.toString() }.toList()
        assertTrue(labels.toString(), start in labels)
        assertTrue(labels.toString(), "Page 42" in labels)
    }

    private fun awaitReady() = await {
        var ready = false
        scenario!!.onActivity { ready = field<View>(it, "searchButton").isEnabled && field<List<BookRecord>>(it, "books").all { book -> book.indexLoaded } }
        ready
    }

    private fun awaitLibrary() = await {
        var open = false
        scenario!!.onActivity { open = field<Dialog?>(it, "libraryDialog")?.isShowing == true }
        open
    }

    @android.annotation.TargetApi(30)
    private fun awaitLibraryBars(visible: Boolean) = await {
        var matches = false
        scenario!!.onActivity {
            matches = field<Dialog>(it, "libraryDialog").window?.decorView?.rootWindowInsets
                ?.let { insets ->
                    insets.isVisible(WindowInsets.Type.statusBars()) == visible &&
                        insets.isVisible(WindowInsets.Type.navigationBars()) == visible
                } == true
        }
        matches
    }

    @android.annotation.TargetApi(29)
    private fun openLibraryMenu() {
        scenario!!.onActivity { activity ->
            val content = field<Dialog>(activity, "libraryDialog").findViewById<View>(android.R.id.content)
            descendants(content).single { it.contentDescription == activity.getString(R.string.library_menu_description) }.performClick()
        }
        instrumentation.waitForIdleSync()
    }

    @android.annotation.TargetApi(29)
    private fun menuList(): ListView = WindowInspector.getGlobalWindowViews().asSequence()
        .flatMap(::descendants).filterIsInstance<ListView>().last { it.isShown }

    @android.annotation.TargetApi(29)
    private fun menuItems(): List<String> = menuList().adapter.let { adapter ->
        (0 until adapter.count).map { adapter.getItem(it).toString() }
    }

    @android.annotation.TargetApi(29)
    private fun clickMenuItem(index: Int) {
        scenario!!.onActivity {
            val list = menuList()
            list.performItemClick(list.getChildAt(index), index, list.adapter.getItemId(index))
        }
        instrumentation.waitForIdleSync()
    }

    @android.annotation.TargetApi(29)
    private fun assertLibraryMenu() {
        scenario!!.onActivity { activity ->
            val items = menuItems()
            assertEquals(3, items.size)
            assertTrue(items[0].startsWith(activity.getString(R.string.reading_view)))
            assertTrue(items[1].startsWith(activity.getString(R.string.interface_menu)))
            assertTrue(items[2].startsWith(activity.getString(R.string.diagnostics)))
        }
    }

    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (!condition()) {
            check(SystemClock.uptimeMillis() < deadline) { "Timed out waiting for the verification UI" }
            SystemClock.sleep(50)
        }
        instrumentation.waitForIdleSync()
    }

    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    @Suppress("UNCHECKED_CAST")
    private fun <T> field(activity: MainActivity, name: String): T = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity) as T

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }

    private fun textView(root: View, text: String): TextView = descendants(root).filterIsInstance<TextView>()
        .first { it.text.toString() == text }

    private fun textFits(view: TextView): Boolean {
        val textLayout = view.layout ?: return false
        return view.width > 0 && textLayout.lineCount > 0 &&
            textLayout.height <= view.height - view.compoundPaddingTop - view.compoundPaddingBottom &&
            textLayout.getLineEnd(textLayout.lineCount - 1) == view.text.length &&
            (0 until textLayout.lineCount).all { textLayout.getEllipsisCount(it) == 0 && textLayout.getLineWidth(it) <= textLayout.width + 1 }
    }

    private fun singleLineFits(view: TextView): Boolean = textFits(view) && view.layout.lineCount == 1

    private fun inside(view: View, root: View): Boolean {
        val bounds = bounds(view, root)
        return bounds.left >= 0 && bounds.right <= root.width && bounds.top >= 0 && bounds.bottom <= root.height
    }

    private fun bounds(view: View, root: View): android.graphics.Rect {
        val bounds = android.graphics.Rect()
        view.getDrawingRect(bounds)
        (root as ViewGroup).offsetDescendantRectToMyCoords(view, bounds)
        return bounds
    }

    private fun layout(view: View, widthDp: Int, heightDp: Int) {
        val density = view.resources.displayMetrics.density
        val width = (widthDp * density).roundToInt()
        val height = (heightDp * density).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
    }

    private fun capture(view: View, name: String) {
        val output = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")
            ?.let(::File) ?: File(context.getExternalFilesDir(null), "localization-review")
        output.mkdirs()
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(output, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}
