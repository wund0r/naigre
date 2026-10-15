// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tables.TableTheme
import org.junit.Assert.*
import org.junit.Test
import wund0r.naigre.reader.render.MarkdownSurface

class WikiLinkRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun labelsSearchAndHighlightsUseTheSameTextAndFoldingRetainsLinks() = onMain {
        val engine = MarkdownEngine(instrumentation.targetContext)
        val content = engine.parse("# Heading\n\n[[downtime.pdf#page=12|Pazuzu]]\n\n# Other\n\nMore.")
        assertTrue(content.plainText.contains("Pazuzu"))
        assertFalse(content.plainText.contains("downtime.pdf"))
        assertTrue(content.searchTextBySection.first().contains("Pazuzu"))
        val surface = MarkdownSurface(instrumentation.targetContext)
        surface.showContent(engine, content, 0, MarkdownSurface.ReadingState(), highlightTerms = listOf("Pazuzu"))
        layout(surface)
        val text = text(surface).text as Spanned
        val link = text.getSpans(0, text.length, WikiLinkSpan::class.java).single()
        val highlight = text.getSpans(0, text.length, BackgroundColorSpan::class.java).single()
        assertEquals(text.getSpanStart(link), text.getSpanStart(highlight))
        assertEquals(text.getSpanEnd(link), text.getSpanEnd(highlight))
        surface.toggleHeading(0)
        layout(surface)
        assertFalse(text(surface).text.toString().contains("Pazuzu"))
        surface.toggleHeading(0)
        layout(surface)
        assertEquals(1, links(text(surface)).size)
        surface.clear()
    }

    @Test fun tappingLinkInsideHeadingNavigatesWithoutFoldingOrTogglingChrome() = onMain {
        val surface = surface("# Heading [[downtime.pdf#page=12|Pazuzu]]\n\nBody.")
        val actions = mutableListOf<Pair<WikiLinkSpec, Boolean>>()
        var blankTaps = 0
        surface.onWikiLink = { spec, alongside -> actions += spec to alongside }
        surface.onBlankTap = { blankTaps++ }
        touchLink(surface, hold = false)
        assertEquals(listOf(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu") to false), actions)
        assertTrue(text(surface).text.toString().contains("Body."))
        assertEquals(0, blankTaps)
        surface.clear()
    }

    @Test fun holdingLinkOpensReferenceAndConsumesRelease() = onMain {
        val surface = surface("# Maps\n\n[[maps/level 3.jpg]]")
        val actions = mutableListOf<Boolean>()
        surface.onWikiLink = { _, alongside -> actions += alongside }
        touchLink(surface, hold = true)
        assertEquals(listOf(true), actions)
        surface.clear()
    }

    @Test fun draggingOrCancellingLinkDoesNotNavigate() = onMain {
        val surface = surface("# Maps\n\n[[maps/level 3.jpg]]")
        var actions = 0
        surface.onWikiLink = { _, _ -> actions++ }
        touchLink(surface, hold = false, drag = true)
        touchLink(surface, hold = false, cancel = true)
        assertEquals(0, actions)
        surface.clear()
    }

    @Test fun wrappedLinkCanBeTappedOnItsFirstLine() = onMain {
        val label = (1..20).joinToString(" ") { "word$it" }
        val surface = surface("# Maps\n\nPrefix [[maps/level 3.jpg|$label]]")
        val target = text(surface)
        val spanned = target.text as Spanned
        val span = links(target).single()
        val start = spanned.getSpanStart(span)
        val line = target.layout.getLineForOffset(start)
        assertTrue(target.layout.getLineForOffset(spanned.getSpanEnd(span)) > line)
        var taps = 0
        surface.onWikiLink = { _, _ -> taps++ }
        val x = target.totalPaddingLeft + (target.layout.getPrimaryHorizontal(start) + target.layout.getLineRight(line)) / 2f
        val y = target.totalPaddingTop + (target.layout.getLineTop(line) + target.layout.getLineBottom(line)) / 2f
        val now = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEachIndexed { index, action ->
            val event = MotionEvent.obtain(now, now + index * 40, action, x, y, 0)
            target.dispatchTouchEvent(event)
            event.recycle()
        }
        assertEquals(1, taps)
        surface.clear()
    }

    @Test fun tableCellLinkSupportsTapAndHoldWithAlignmentAndWrappedRows() = onMain {
        val surface = surface("# Maps\n\n| Details | Destination |\n| --- | ---: |\n" +
            "| A long description that wraps onto several lines in this narrow table column. | [[maps/level 3.jpg\\|Map]] |")
        val target = text(surface)
        val bitmap = Bitmap.createBitmap(700, 900, Bitmap.Config.ARGB_8888)
        try {
            // First draw creates the cell layouts; measure again with their actual heights.
            surface.draw(Canvas(bitmap))
            target.text = target.text
            layout(surface)
            surface.draw(Canvas(bitmap))
            val spanned = target.text as Spanned
            val row = spanned.getSpans(0, spanned.length, TableRowSpan::class.java).maxBy { spanned.getSpanStart(it) }
            val width = row.cellWidth()
            val cell = requireNotNull(row.findLayoutForHorizontalOffset(width))
            val cellText = cell.text as Spanned
            assertEquals("Map", cellText.toString())
            val link = cellText.getSpans(0, cellText.length, WikiLinkSpan::class.java).single()
            val firstCell = requireNotNull(row.findLayoutForHorizontalOffset(0))
            assertTrue(firstCell.lineCount > 1)
            val line = target.layout.getLineForOffset(spanned.getSpanStart(row))
            val padding = TableTheme.create(instrumentation.targetContext).tableCellPadding()
            val rowTop = target.layout.getLineTop(line)
            val heightDiff = (target.layout.getLineBottom(line) - rowTop - maxOf(firstCell.height, cell.height)) / 4
            val x = target.totalPaddingLeft + target.layout.getPrimaryHorizontal(spanned.getSpanStart(row)) +
                width + padding + (cell.getPrimaryHorizontal(cellText.getSpanStart(link)) +
                cell.getPrimaryHorizontal(cellText.getSpanEnd(link))) / 2f
            val y = target.totalPaddingTop + rowTop + padding + heightDiff + cell.height / 2f
            val actions = mutableListOf<Pair<WikiLinkSpec, Boolean>>()
            surface.onWikiLink = { spec, alongside -> actions += spec to alongside }
            val now = SystemClock.uptimeMillis()
            repeat(2) { gesture ->
                val start = now + gesture * 100
                val down = MotionEvent.obtain(start, start, MotionEvent.ACTION_DOWN, x, y, 0)
                target.dispatchTouchEvent(down)
                down.recycle()
                if (gesture == 1) assertTrue(target.performLongClick())
                val up = MotionEvent.obtain(start, start + 40, MotionEvent.ACTION_UP, x, y, 0)
                target.dispatchTouchEvent(up)
                up.recycle()
            }
            assertEquals(listOf(WikiLinkSpec("maps/level 3.jpg", "Map") to false,
                WikiLinkSpec("maps/level 3.jpg", "Map") to true), actions)
        } finally {
            bitmap.recycle()
            surface.clear()
        }
    }

    private fun surface(source: String): MarkdownSurface {
        val engine = MarkdownEngine(instrumentation.targetContext)
        return MarkdownSurface(instrumentation.targetContext).also {
            it.showContent(engine, engine.parse(source), 0, MarkdownSurface.ReadingState())
            layout(it)
        }
    }

    private fun touchLink(surface: MarkdownSurface, hold: Boolean, drag: Boolean = false, cancel: Boolean = false) {
        val target = text(surface)
        val spanned = target.text as Spanned
        val span = links(target).single()
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        val line = target.layout.getLineForOffset(start)
        val x = target.totalPaddingLeft + (target.layout.getPrimaryHorizontal(start) + target.layout.getPrimaryHorizontal(end)) / 2f
        val y = target.totalPaddingTop + (target.layout.getLineTop(line) + target.layout.getLineBottom(line)) / 2f
        val now = SystemClock.uptimeMillis()
        fun send(action: Int, at: Long, targetY: Float = y) {
            val event = MotionEvent.obtain(now, at, action, x, targetY, 0)
            target.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, now)
        if (hold) assertTrue(target.performLongClick())
        if (drag) send(MotionEvent.ACTION_MOVE, now + 20, y + 100f)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, now + 40, if (drag) y + 100f else y)
    }

    private fun text(surface: MarkdownSurface) = (surface.getChildAt(0) as ScrollView).getChildAt(0) as TextView
    private fun links(target: TextView): List<WikiLinkSpan> = (target.text as Spanned).getSpans(0, target.text.length, WikiLinkSpan::class.java).toList()
    private fun layout(surface: MarkdownSurface) {
        surface.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        surface.layout(0, 0, 700, 900)
        text(surface).viewTreeObserver.dispatchOnPreDraw()
    }
    private fun onMain(test: () -> Unit) = instrumentation.runOnMainSync(test)
}
