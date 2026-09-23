// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.os.SystemClock
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import io.noties.markwon.core.spans.HeadingSpan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wund0r.naigre.reader.render.MarkdownSurface

class MarkdownFoldingRenderingTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun parserKeepsSearchSectionsSeparateFromNestedFoldBoundaries() = onMain {
        val content = engine().parse(note)
        val parent = content.sections.first { it.title == "Campaign" }
        val child = content.sections.first { it.title == "Clues" }
        val peer = content.sections.first { it.title == "Next session" }
        assertEquals(child.start, parent.end)
        assertEquals(peer.start, parent.subtreeEnd)
        assertEquals(1, parent.headingLevel)
        assertEquals(2, child.headingLevel)
        assertTrue(content.searchTextBySection[child.index].contains("needle"))
        val repeats = engine().parse("# Same\n\nA\n\n# Same\n\nB\n").sections
        assertEquals(2, repeats.map { it.stableKey }.distinct().size)
        assertFalse(engine().parse("# Empty\n").sections.single().canFold)
        assertFalse(engine().parse("").sections.single().canFold)
    }

    @Test
    fun foldingPreservesTablesAndFormattingAndDoesNotMutateIndexedContent() = onMain {
        val engine = engine()
        val content = engine.parse(note)
        val surface = MarkdownSurface(instrumentation.targetContext)
        val state = MarkdownSurface.ReadingState()
        surface.showContent(engine, content, 0, state)
        layout(surface)
        val original = text(surface).text as Spanned
        val tableSpans = tables(original)
        assertTrue(tableSpans > 0)
        val campaign = content.sections.first { it.title == "Campaign" }
        surface.toggleHeading(campaign.index)
        layout(surface)
        val folded = text(surface).text as Spanned
        assertFalse(folded.toString().contains("Clues"))
        assertTrue(folded.toString().contains("Next session"))
        assertEquals(0, tables(folded))
        assertEquals(2, folded.getSpans(0, folded.length, HeadingSpan::class.java).size)
        assertTrue(content.searchTextBySection.any { it.contains("needle") })
        surface.toggleHeading(campaign.index)
        layout(surface)
        val outline = text(surface).text as Spanned
        assertTrue(outline.toString().contains("Clues"))
        assertTrue(outline.toString().contains("Secret"))
        assertFalse(outline.toString().contains("Opening notes"))
        assertFalse(outline.toString().contains("hidden in the room"))
        assertFalse(outline.toString().contains("Hidden room"))
        assertEquals(0, tables(outline))
        assertEquals(4, outline.getSpans(0, outline.length, HeadingSpan::class.java).size)
        surface.toggleHeading(campaign.index)
        layout(surface)
        assertEquals(original.toString(), text(surface).text.toString())
        assertEquals(tableSpans, tables(text(surface).text as Spanned))
        surface.clear()
    }

    @Test
    fun searchNavigationRevealsHiddenSectionWhileTabSwitchesPreserveIndependentFolds() = onMain {
        val engine = engine()
        val content = engine.parse(note)
        val surface = MarkdownSurface(instrumentation.targetContext)
        val first = MarkdownSurface.ReadingState()
        val second = MarkdownSurface.ReadingState()
        val campaign = content.sections.first { it.title == "Campaign" }
        val clues = content.sections.first { it.title == "Clues" }
        surface.showContent(engine, content, campaign.index, first)
        layout(surface)
        surface.toggleHeading(campaign.index)
        layout(surface)
        surface.toggleHeading(campaign.index)
        layout(surface)
        surface.showContent(engine, content, campaign.index, second)
        layout(surface)
        assertTrue(text(surface).text.toString().contains("Opening notes"))
        surface.showContent(engine, content, campaign.index, first)
        layout(surface)
        assertTrue(text(surface).text.toString().contains("Clues"))
        assertFalse(text(surface).text.toString().contains("Opening notes"))
        first.requestNavigation()
        surface.showContent(engine, content, clues.index, first, highlightTerms = listOf("needle"))
        layout(surface)
        assertTrue(text(surface).text.toString().contains("Clues"))
        assertTrue(backgrounds(text(surface).text as Spanned) > 0)
        val before = surface.captureViewportState()
        surface.clearSearchHighlights()
        layout(surface)
        assertEquals(0, backgrounds(text(surface).text as Spanned))
        assertEquals(before, surface.captureViewportState())
        surface.toggleHeading(campaign.index)
        layout(surface)
        assertFalse(text(surface).text.toString().contains("Clues"))
        surface.clear()
    }

    @Test
    fun headingTapFoldsWithoutTogglingReaderChrome() = onMain {
        val engine = engine()
        val content = engine.parse("# Heading\n\nBody\n\n# Next\n\nMore\n")
        val surface = MarkdownSurface(instrumentation.targetContext)
        val state = MarkdownSurface.ReadingState()
        var blankTaps = 0
        surface.onBlankTap = { blankTaps++ }
        surface.showContent(engine, content, 0, state)
        layout(surface)
        val target = text(surface)
        val now = SystemClock.uptimeMillis()
        val y = (target.layout.getLineTop(0) + target.layout.getLineBottom(0)) / 2f
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 4f, y, 0)
        val up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 4f, y, 0)
        target.dispatchTouchEvent(down)
        target.dispatchTouchEvent(up)
        down.recycle()
        up.recycle()
        layout(surface)
        assertEquals(MarkdownFoldMode.COLLAPSED, state.folds.displayModes(content.sections)[content.sections.first().stableKey])
        assertEquals(0, blankTaps)
        assertFalse(target.text.toString().contains("Body"))
        surface.clear()
    }

    private fun engine() = MarkdownEngine(instrumentation.targetContext)
    private fun text(surface: MarkdownSurface) = (surface.getChildAt(0) as ScrollView).getChildAt(0) as TextView
    private fun layout(surface: MarkdownSurface) {
        surface.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        surface.layout(0, 0, 700, 900)
        text(surface).viewTreeObserver.dispatchOnPreDraw()
    }
    private fun tables(text: Spanned) = text.getSpans(0, text.length, Any::class.java)
        .count { it.javaClass.simpleName == "TableRowSpan" }
    private fun backgrounds(text: Spanned) = text.getSpans(0, text.length, BackgroundColorSpan::class.java).size
    private fun onMain(test: () -> Unit) = instrumentation.runOnMainSync(test)

    private val note = """
        # Campaign

        Opening notes.

        ## Clues

        A **needle** hidden in the room.

        | Clue | Location |
        | --- | --- |
        | needle | cellar |

        ### Secret

        Hidden room.

        # Next session

        Later events.
    """.trimIndent()
}
