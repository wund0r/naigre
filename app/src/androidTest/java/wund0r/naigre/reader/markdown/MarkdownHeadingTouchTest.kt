// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.content.Context
import android.content.res.Configuration
import android.os.SystemClock
import android.text.Spanned
import android.view.MotionEvent
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import wund0r.naigre.reader.render.MarkdownSurface

class MarkdownHeadingTouchTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val headingLink = "[[downtime.pdf#page=12|Pazuzu]]"

    @Test fun arrowFoldsAHeadingConsistingEntirelyOfALink() = onMain {
        val surface = surface("# $headingLink\n\nBody.\n\n# Next\n\nMore.")
        var navigations = 0
        var blankTaps = 0
        surface.onWikiLink = { _, _ -> navigations++ }
        surface.onBlankTap = { blankTaps++ }
        repeat(2) { step ->
            val target = text(surface)
            val line = target.layout.getLineForOffset(target.text.indexOf("Pazuzu"))
            touch(target, 4f, middleY(target, line))
            layout(surface)
            assertEquals(step == 1, text(surface).text.contains("Body."))
        }
        assertEquals(0, navigations)
        assertEquals(0, blankTaps)
        surface.clear()
    }

    @Test fun arrowGutterHasRoomForTapsNearBothEdgesAtDifferentFontSizes() = onMain {
        for (fontScale in listOf(1f, 2f)) {
            val scaled = context.createConfigurationContext(Configuration(context.resources.configuration).apply { this.fontScale = fontScale })
            for (edge in listOf("left", "right")) {
                val surface = surface("# $headingLink\n\nBody.", scaled)
                val target = text(surface)
                val line = target.layout.getLineForOffset(target.text.indexOf("Pazuzu"))
                val labelStart = target.totalPaddingLeft + target.layout.getPrimaryHorizontal(target.text.indexOf("Pazuzu"))
                assertTrue("The folding gutter must be at least 48 dp wide", labelStart >= dp(48))
                var navigations = 0
                surface.onWikiLink = { _, _ -> navigations++ }
                touch(target, if (edge == "left") 1f else labelStart - 1f, middleY(target, line))
                layout(surface)
                assertFalse("$fontScale/$edge must fold", text(surface).text.contains("Body."))
                assertEquals(0, navigations)
                surface.clear()
            }
        }
    }

    @Test fun linkTextStillSupportsTapAndHoldInAnEntirelyLinkedHeading() = onMain {
        val surface = surface("# $headingLink\n\nBody.")
        val actions = mutableListOf<Pair<WikiLinkSpec, Boolean>>()
        var blankTaps = 0
        surface.onWikiLink = { spec, alongside -> actions += spec to alongside }
        surface.onBlankTap = { blankTaps++ }
        val target = text(surface)
        val span = links(target).single()
        val spanned = target.text as Spanned
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        val line = target.layout.getLineForOffset(start)
        val x = target.totalPaddingLeft + (target.layout.getPrimaryHorizontal(start) + target.layout.getPrimaryHorizontal(end)) / 2f
        touch(target, x, middleY(target, line))
        touch(target, x, middleY(target, line), hold = true)
        assertEquals(listOf(WikiLinkSpec("downtime.pdf#page=12", "Pazuzu") to false,
            WikiLinkSpec("downtime.pdf#page=12", "Pazuzu") to true), actions)
        assertTrue(target.text.contains("Body."))
        assertEquals(0, blankTaps)
        surface.clear()
    }

    @Test fun blankSpaceAboveAndBelowTheHeadingIsAnEasierFoldingTarget() = onMain {
        for (above in listOf(true, false)) {
            val surface = surface("Intro.\n\n#### $headingLink\n\nBody.\n\n# Next\n\nMore.")
            val target = text(surface)
            val line = target.layout.getLineForOffset(target.text.indexOf("Pazuzu"))
            val y = if (above) target.layout.getLineTop(line) - 2f else target.layout.getLineBottom(line) + 2f
            val adjacent = target.layout.getLineForVertical(y.toInt())
            assertNotEquals("Test point must be outside the heading line", line, adjacent)
            assertTrue(target.text.subSequence(target.layout.getLineStart(adjacent), target.layout.getLineEnd(adjacent)).isBlank())
            var navigations = 0
            var blankTaps = 0
            surface.onWikiLink = { _, _ -> navigations++ }
            surface.onBlankTap = { blankTaps++ }
            touch(target, target.width - 4f, y)
            layout(surface)
            assertFalse(text(surface).text.contains("Body."))
            assertEquals(0, navigations)
            assertEquals(0, blankTaps)
            surface.clear()
        }
    }

    @Test fun enlargedHeadingTargetDoesNotStealAnAdjacentBodyLink() = onMain {
        val surface = surface("###### $headingLink\n\n[[downtime.pdf#page=13|Body link]]\n\nMore.")
        val target = text(surface)
        val spanned = target.text as Spanned
        val span = links(target).single { it.spec.pageNumber == 13 }
        val start = spanned.getSpanStart(span)
        val end = spanned.getSpanEnd(span)
        val line = target.layout.getLineForOffset(start)
        val actions = mutableListOf<WikiLinkSpec>()
        surface.onWikiLink = { spec, _ -> actions += spec }
        val x = target.totalPaddingLeft + (target.layout.getPrimaryHorizontal(start) + target.layout.getPrimaryHorizontal(end)) / 2f
        touch(target, x, target.layout.getLineTop(line) + 1f)
        assertEquals(listOf(WikiLinkSpec("downtime.pdf#page=13", "Body link")), actions)
        assertTrue(target.text.contains("Body link"))
        surface.clear()
    }

    @Test fun wrappedHeadingGutterFoldsAndContinuationTextNavigates() = onMain {
        val label = (1..16).joinToString(" ") { "word$it" }
        val surface = surface("# [[downtime.pdf#page=12|$label]]\n\nBody.")
        var navigations = 0
        surface.onWikiLink = { _, _ -> navigations++ }
        val target = text(surface)
        val spanned = target.text as Spanned
        val span = links(target).single()
        val first = target.layout.getLineForOffset(spanned.getSpanStart(span))
        val last = target.layout.getLineForOffset(spanned.getSpanEnd(span) - 1)
        assertTrue(last > first)
        val continuation = first + 1
        val x = target.totalPaddingLeft + (target.layout.getPrimaryHorizontal(target.layout.getLineStart(continuation)) + target.layout.getLineRight(continuation)) / 2f
        touch(target, x, middleY(target, continuation))
        assertEquals(1, navigations)
        assertTrue(target.text.contains("Body."))
        touch(target, 4f, middleY(target, continuation))
        layout(surface)
        assertFalse(text(surface).text.contains("Body."))
        assertEquals(1, navigations)
        surface.clear()
    }

    @Test fun leftGutterArrowWorksForARightToLeftLinkedHeading() = onMain {
        val surface = surface("# [[downtime.pdf#page=12|קרב]]\n\nBody.")
        val target = text(surface)
        val line = target.layout.getLineForOffset(target.text.indexOf("קרב"))
        assertEquals(-1, target.layout.getParagraphDirection(line))
        var navigations = 0
        surface.onWikiLink = { _, _ -> navigations++ }
        touch(target, 4f, middleY(target, line))
        layout(surface)
        assertFalse(text(surface).text.contains("Body."))
        assertEquals(0, navigations)
        surface.clear()
    }

    @Test fun draggingOrCancellingAnArrowTapDoesNotFoldOrNavigate() = onMain {
        val surface = surface("# $headingLink\n\nBody.\n\n# Next\n\nMore.")
        val target = text(surface)
        val line = target.layout.getLineForOffset(target.text.indexOf("Pazuzu"))
        var navigations = 0
        surface.onWikiLink = { _, _ -> navigations++ }
        touch(target, 4f, middleY(target, line), drag = true)
        touch(target, 4f, middleY(target, line), cancel = true)
        layout(surface)
        assertTrue(text(surface).text.contains("Body."))
        assertEquals(0, navigations)
        surface.clear()
    }

    private fun surface(source: String, surfaceContext: Context = context): MarkdownSurface {
        val engine = MarkdownEngine(surfaceContext)
        return MarkdownSurface(surfaceContext).also {
            it.showContent(engine, engine.parse(source), 0, MarkdownSurface.ReadingState())
            layout(it)
        }
    }

    private fun touch(target: TextView, x: Float, y: Float, hold: Boolean = false, drag: Boolean = false, cancel: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        fun send(action: Int, time: Long, atY: Float = y) {
            val event = MotionEvent.obtain(now, time, action, x, atY, 0)
            target.dispatchTouchEvent(event)
            event.recycle()
        }
        send(MotionEvent.ACTION_DOWN, now)
        if (hold) assertTrue(target.performLongClick())
        if (drag) send(MotionEvent.ACTION_MOVE, now + 20, y + 100f)
        send(if (cancel) MotionEvent.ACTION_CANCEL else MotionEvent.ACTION_UP, now + 40, if (drag) y + 100f else y)
    }

    private fun middleY(target: TextView, line: Int) = (target.layout.getLineTop(line) + target.layout.getLineBottom(line)) / 2f
    private fun text(surface: MarkdownSurface) = (surface.getChildAt(0) as ScrollView).getChildAt(0) as TextView
    private fun links(target: TextView) = (target.text as Spanned).getSpans(0, target.text.length, WikiLinkSpan::class.java).toList()
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun layout(surface: MarkdownSurface) {
        surface.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        surface.layout(0, 0, 700, 900)
        text(surface).viewTreeObserver.dispatchOnPreDraw()
    }
    private fun onMain(test: () -> Unit) = instrumentation.runOnMainSync(test)
}
