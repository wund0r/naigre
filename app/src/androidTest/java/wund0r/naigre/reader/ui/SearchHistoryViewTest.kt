// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import wund0r.naigre.reader.R
import wund0r.naigre.reader.navigation.*
import wund0r.naigre.reader.theme.ReaderThemeMode
import wund0r.naigre.reader.theme.UiPalette
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

class SearchHistoryViewTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun phoneTabletAndEnlargedTextAlwaysUseTheSameFortySixtySplit() = onMain {
        for (widthDp in listOf(320, 700)) for (scale in listOf(1f, 2f)) for (locale in listOf(Locale.ENGLISH, Locale.forLanguageTag("ru"))) {
            val view = history(widthDp, scale, locale)
            val left = view.findViewWithTag<ViewGroup>("recent-searches-column")
            val right = view.findViewWithTag<ViewGroup>("recent-tabs-column")
            val split = view.findViewWithTag<LinearLayout>("search-history-split")
            assertEquals(LinearLayout.HORIZONTAL, split.orientation)
            assertEquals(left.top, right.top)
            assertTrue(left.right < right.left)
            val columnWidth = (left.width + right.width).toFloat()
            assertEquals(columnWidth * 0.4f, left.width.toFloat(), 1f)
            assertEquals(columnWidth * 0.6f, right.width.toFloat(), 1f)
            val leftRows = rows(left)
            val rightRows = rows(right)
            assertEquals(leftRows.map { it.top }, rightRows.map { it.top })
            assertEquals(1, (leftRows + rightRows).map { it.height }.distinct().size)
            for (row in rightRows) {
                assertEquals(LinearLayout.HORIZONTAL, row.orientation)
                assertEquals(row.height / 2f, row.plus.top + row.plus.height / 2f, 1f)
                assertEquals(row.height / 2f, row.alongside.top + row.alongside.height / 2f, 1f)
                assertEquals(row.width, row.alongside.right)
                assertEquals(row.alongside.left, row.plus.right)
                assertEquals(1, row.title.maxLines)
                assertEquals(1, row.detail.maxLines)
                assertTrue(row.title.measuredHeight + row.detail.measuredHeight <= row.height - row.paddingTop - row.paddingBottom)
                val titleBounds = Rect()
                row.title.getDrawingRect(titleBounds)
                row.offsetDescendantRectToMyCoords(row.title, titleBounds)
                assertTrue(titleBounds.right <= row.plus.left)
            }
            val more = view.findViewWithTag<Button>("more-history")
            assertEquals(View.VISIBLE, more.visibility)
            assertEquals(view.width / 2f, more.left + more.width / 2f, 1f)
            assertTrue(more.top >= split.bottom)
            assertTrue(more.height >= (48 * view.resources.displayMetrics.density).roundToInt())
            assertTrue("More history must remain readable with larger translated text",
                more.layout.height + more.totalPaddingTop + more.totalPaddingBottom <= more.height)
            val buttons = descendants(right).filterIsInstance<Button>().filter { it.text.toString() in listOf("+", "◨") }.toList()
            assertEquals(6, buttons.size)
            for (button in buttons) {
                val rect = Rect()
                button.getDrawingRect(rect)
                view.offsetDescendantRectToMyCoords(button, rect)
                assertTrue(rect.right <= right.right)
                assertTrue(rect.left >= right.left)
                assertTrue(button.width >= (48 * view.resources.displayMetrics.density).roundToInt())
                assertTrue(button.height >= (48 * view.resources.displayMetrics.density).roundToInt())
            }
            if (scale == 1f && locale == Locale.ENGLISH) preview(view, "search-history-${if (widthDp == 320) "phone" else "tablet"}.png")
        }
    }

    @Test fun tabButtonsDispatchOnceAndHoldingPlusOnlyOpensInBackground() = onMain {
        val view = history(320)
        val actions = mutableListOf<HistoryTabAction>()
        view.onTab = { _, action -> actions += action }
        val column = view.findViewWithTag<ViewGroup>("recent-tabs-column")
        val row = column.getChildAt(1)
        val buttons = descendants(row).filterIsInstance<Button>().toList()
        row.performClick()
        buttons[0].performClick()
        assertTrue(buttons[0].performLongClick())
        buttons[1].performClick()
        assertEquals(listOf(HistoryTabAction.CURRENT, HistoryTabAction.NEW_TAB, HistoryTabAction.BACKGROUND, HistoryTabAction.REFERENCE), actions)
    }

    @Test fun sharedToggleExpandsBothColumnsToTenAndCollapsesToThree() = onMain {
        val view = history(320, searchCount = 13, tabCount = 12)
        val left = view.findViewWithTag<ViewGroup>("recent-searches-column")
        val right = view.findViewWithTag<ViewGroup>("recent-tabs-column")
        val more = view.findViewWithTag<Button>("more-history")
        assertEquals(3, rows(left).size)
        assertEquals(3, rows(right).size)
        assertEquals(view.context.getString(R.string.more_history), more.text)
        more.performClick()
        assertEquals(10, rows(left).size)
        assertEquals(10, rows(right).size)
        assertEquals(view.context.getString(R.string.less_history), more.text)
        assertEquals(LinearLayout.HORIZONTAL, view.findViewWithTag<LinearLayout>("search-history-split").orientation)
        more.performClick()
        assertEquals(3, rows(left).size)
        assertEquals(3, rows(right).size)
        assertEquals(view.context.getString(R.string.more_history), more.text)
    }

    @Test fun sharedToggleTracksAvailableHistoryAndKeepsExpansionOnRefresh() = onMain {
        val view = history(320, searchCount = 0, tabCount = 3)
        val left = view.findViewWithTag<ViewGroup>("recent-searches-column")
        val right = view.findViewWithTag<ViewGroup>("recent-tabs-column")
        val more = view.findViewWithTag<Button>("more-history")
        assertEquals(View.GONE, more.visibility)
        val searches = (1..12).map { SearchHistoryItem(RecentSearch("Search $it", RecentSearchMode.BOOKMARKS, null), "Navigate · All") }
        view.showEntries(searches, emptyList(), "◨")
        assertEquals(View.VISIBLE, more.visibility)
        more.performClick()
        assertEquals(10, rows(left).size)
        assertTrue(rows(right).isEmpty())
        view.showEntries(searches.drop(1), emptyList(), "◨")
        assertEquals(10, rows(left).size)
        assertEquals("Search 2", rows(left).first().title.text)
        assertEquals(view.context.getString(R.string.less_history), more.text)
        view.showEntries(searches.take(3), emptyList(), "◨")
        assertEquals(View.GONE, more.visibility)
        view.showEntries(searches, emptyList(), "◨")
        assertEquals(3, rows(left).size)
        assertEquals(view.context.getString(R.string.more_history), more.text)
        view.showEntries(emptyList(), emptyList(), "◨")
        assertEquals(View.GONE, more.visibility)
    }

    @Test fun historyUsesResultTypographyAndAlternatingColorsWithInlineRippleButtons() = onMain {
        val view = history(700)
        val palette = UiPalette.resolve(view.context, ReaderThemeMode.DARK)
        val result = SearchResultRow(view.context, palette)
        for (column in listOf("recent-searches-column", "recent-tabs-column")) {
            rows(view.findViewWithTag(column)).forEachIndexed { index, row ->
                assertEquals(if (index % 2 == 0) palette.surface else palette.surfaceRaised, (row.background as ColorDrawable).color)
                assertEquals(result.title.textSize, row.title.textSize, 0f)
                assertEquals(result.detail.textSize, row.detail.textSize, 0f)
                assertEquals(result.title.typeface, row.title.typeface)
            }
        }
        val closed = rows(view.findViewWithTag("recent-tabs-column")).first()
        assertTrue(closed.plus.background is RippleDrawable)
        assertTrue(closed.alongside.background is RippleDrawable)
    }

    private fun history(widthDp: Int, fontScale: Float = 1f, locale: Locale = Locale.ENGLISH,
                        searchCount: Int = 5, tabCount: Int = 3): SearchHistoryView {
        val configuration = android.content.res.Configuration(instrumentation.targetContext.resources.configuration).apply {
            this.fontScale = fontScale
            setLocale(locale)
        }
        val context = instrumentation.targetContext.createConfigurationContext(configuration)
        val palette = UiPalette.resolve(context, ReaderThemeMode.DARK)
        val view = SearchHistoryView(context, palette).apply {
            setBackgroundColor(palette.surface)
            showEntries(
                (1..searchCount).map { SearchHistoryItem(RecentSearch("Search $it", RecentSearchMode.BOOKMARKS, null), "Navigate · All") },
                (0 until tabCount).map { index ->
                    val title = listOf("Pazuzu", "Level 3 map", "Long heading in campaign notes")[index % 3]
                    ClosedTabHistoryItem(ClosedReaderTab("tab-$index", ReaderTab("book", 13, title)), title,
                        "Downtime · Page 14", Color.GREEN)
                }, "◨",
            )
        }
        val width = (widthDp * context.resources.displayMetrics.density).roundToInt()
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(5000, View.MeasureSpec.AT_MOST))
        view.layout(0, 0, width, view.measuredHeight)
        return view
    }

    private fun preview(view: View, name: String) {
        InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            try {
                view.draw(Canvas(bitmap))
                File(output).mkdirs()
                File(output, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally { bitmap.recycle() }
        }
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
    private fun rows(column: ViewGroup) = (0 until column.childCount).map { column.getChildAt(it) }.filterIsInstance<SearchResultRow>()
    private fun onMain(test: () -> Unit) = instrumentation.runOnMainSync(test)
}
