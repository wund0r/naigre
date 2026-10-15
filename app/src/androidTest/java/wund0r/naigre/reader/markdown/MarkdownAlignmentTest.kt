// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import android.widget.ScrollView
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import wund0r.naigre.reader.render.MarkdownSurface
import java.io.File

class MarkdownAlignmentTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val note = "# Foldable\n\nParagraph.\n\n# Next\n\nMore.\n\n# Empty"

    @Test fun foldableAndPlainHeadingsAlignWithParagraphsBeforeAndAfterFolding() = onMain {
        val engine = MarkdownEngine(context)
        val content = engine.parse(note)
        assertTrue(content.sections.first().canFold)
        assertFalse(content.sections.last().canFold)
        val surface = surface(engine, content)
        val target = text(surface)
        val column = textX(target, "Paragraph.")
        assertTrue("The gutter reserves a separate touch target", column >= dp(48))
        for (label in listOf("Foldable", "Next", "More.", "Empty")) assertEquals(column, textX(target, label), 0.01f)
        surface.toggleHeading(0)
        layout(surface)
        assertFalse(target.text.contains("Paragraph."))
        for (label in listOf("Foldable", "Next", "More.", "Empty")) assertEquals(column, textX(target, label), 0.01f)
        surface.toggleHeading(0)
        layout(surface)
        assertEquals(column, textX(target, "Paragraph."), 0.01f)
        surface.clear()
    }

    @Test fun arrowsAreDrawnOnlyInTheGutterBesideFoldableHeadings() = onMain {
        val engine = MarkdownEngine(context)
        val surface = surface(engine, engine.parse(note))
        surface.setUiColors(Color.BLACK, Color.WHITE)
        val bitmap = Bitmap.createBitmap(surface.width, surface.height, Bitmap.Config.ARGB_8888)
        try {
            surface.draw(Canvas(bitmap))
            val target = text(surface)
            assertTrue("The fold arrow must be visible outside the text column", gutterPixels(bitmap, target, "Foldable") > 10)
            assertEquals("A heading that cannot fold must have an empty gutter", 0, gutterPixels(bitmap, target, "Empty"))
            InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let { output ->
                val folder = File(output).apply { mkdirs() }
                File(folder, "markdown-shared-gutter.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        } finally {
            bitmap.recycle()
            surface.clear()
        }
    }

    @Test fun wrappedHeadingLinesUseTheSameColumnAsBodyText() = onMain {
        val title = (1..14).joinToString(" ") { "heading$it" }
        val engine = MarkdownEngine(context)
        val content = engine.parse("## $title\n\nParagraph.\n\n# Empty")
        val surface = surface(engine, content)
        val target = text(surface)
        val first = target.layout.getLineForOffset(target.text.indexOf("heading1"))
        val last = target.layout.getLineForOffset(content.sections.first().headingEnd - 1)
        assertTrue(last > first)
        val column = textX(target, "Paragraph.")
        for (line in first..last) {
            val x = target.totalPaddingLeft + target.layout.getPrimaryHorizontal(target.layout.getLineStart(line))
            assertEquals("Wrapped heading line $line must align with paragraphs", column, x, 0.01f)
        }
        assertEquals(column, textX(target, "Empty"), 0.01f)
        surface.clear()
    }

    @Test fun listAndBlockquoteIndentationStaysInsideTheCommonTextColumn() = onMain {
        val engine = MarkdownEngine(context)
        val surface = surface(engine, engine.parse("# Heading\n\nParagraph.\n\n- Bullet\n\n> Quote\n\n# Empty"))
        val target = text(surface)
        val column = textX(target, "Paragraph.")
        assertEquals(column, textX(target, "Heading"), 0.01f)
        assertEquals(column, textX(target, "Empty"), 0.01f)
        assertTrue(textX(target, "Bullet") > column)
        assertTrue(textX(target, "Quote") > column)
        surface.clear()
    }

    private fun gutterPixels(bitmap: Bitmap, target: TextView, label: String): Int {
        val line = target.layout.getLineForOffset(target.text.indexOf(label))
        val top = target.top + target.totalPaddingTop + target.layout.getLineTop(line)
        val bottom = minOf(bitmap.height, target.top + target.totalPaddingTop + target.layout.getLineBottom(line))
        var count = 0
        for (y in top until bottom) for (x in target.left until target.left + target.totalPaddingLeft) {
            if (Color.red(bitmap.getPixel(x, y)) > 128) count++
        }
        return count
    }

    private fun surface(engine: MarkdownEngine, content: MarkdownContent) = MarkdownSurface(context).also {
        it.showContent(engine, content, 0, MarkdownSurface.ReadingState())
        layout(it)
    }
    private fun textX(target: TextView, label: String) = target.totalPaddingLeft + target.layout.getPrimaryHorizontal(target.text.indexOf(label))
    private fun text(surface: MarkdownSurface) = (surface.getChildAt(0) as ScrollView).getChildAt(0) as TextView
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private fun layout(surface: MarkdownSurface) {
        surface.measure(View.MeasureSpec.makeMeasureSpec(700, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY))
        surface.layout(0, 0, 700, 900)
        text(surface).viewTreeObserver.dispatchOnPreDraw()
    }
    private fun onMain(test: () -> Unit) = instrumentation.runOnMainSync(test)
}
