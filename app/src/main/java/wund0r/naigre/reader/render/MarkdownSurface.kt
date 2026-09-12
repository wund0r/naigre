// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

import android.content.Context
import android.graphics.Color
import android.text.SpannableString
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import wund0r.naigre.reader.markdown.MarkdownDocument
import wund0r.naigre.reader.markdown.MarkdownEngine
import java.util.Locale

class MarkdownSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    data class ViewportState(val sectionIndex: Int, val sectionProgress: Float)

    private val density = resources.displayMetrics.density
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        clipToPadding = true
    }
    private val textView = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setLineSpacing(3f * density, 1.08f)
        gravity = Gravity.TOP or Gravity.START
        setTextIsSelectable(true)
        isClickable = true
    }
    private var document: MarkdownDocument? = null
    private var contentInsets = intArrayOf(dp(20), dp(20), dp(20), dp(32))
    var onBlankTap: (() -> Unit)? = null

    init {
        scrollView.addView(
            textView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(scrollView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyInsets()
        textView.setOnClickListener { onBlankTap?.invoke() }
    }

    fun setUiColors(background: Int, text: Int) {
        setBackgroundColor(background)
        scrollView.setBackgroundColor(background)
        textView.setTextColor(text)
    }

    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val state = captureViewportState()
        contentInsets = intArrayOf(left, top, right, bottom)
        applyInsets()
        state?.let { post { restoreViewport(it) } }
    }

    fun showDocument(
        engine: MarkdownEngine,
        nextDocument: MarkdownDocument,
        sectionIndex: Int,
        restoredViewport: ViewportState? = null,
        highlightTerms: List<String> = emptyList(),
        caseSensitive: Boolean = false,
        highlightColor: Int = Color.YELLOW,
        highlightTextColor: Int = Color.BLACK,
    ) {
        document = nextDocument
        val rendered = SpannableString(nextDocument.content.rendered)
        val section = nextDocument.content.sections[sectionIndex.coerceIn(0, nextDocument.content.sections.lastIndex)]
        var firstMatch: Int? = null
        highlightTerms.filter { it.isNotBlank() }.distinct().forEach { term ->
            val haystack = if (caseSensitive) rendered.toString() else rendered.toString().lowercase(Locale.ROOT)
            val needle = if (caseSensitive) term else term.lowercase(Locale.ROOT)
            var from = section.start
            while (from < section.end) {
                val match = haystack.indexOf(needle, from)
                if (match < 0 || match >= section.end) break
                val end = (match + needle.length).coerceAtMost(section.end)
                rendered.setSpan(BackgroundColorSpan(highlightColor), match, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                rendered.setSpan(ForegroundColorSpan(highlightTextColor), match, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (firstMatch == null) firstMatch = match
                from = end.coerceAtLeast(match + 1)
            }
        }
        engine.apply(textView, rendered)
        textView.post {
            when {
                restoredViewport != null -> restoreViewport(restoredViewport)
                firstMatch != null -> scrollToCharacter(firstMatch!!)
                else -> scrollToCharacter(section.start)
            }
        }
    }

    fun captureViewportState(): ViewportState? {
        val current = document ?: return null
        val layout = textView.layout ?: return null
        val textY = scrollView.scrollY.coerceAtLeast(0)
        val line = layout.getLineForVertical(textY)
        val character = layout.getLineStart(line)
        val section = current.content.sections.lastOrNull { it.start <= character }
            ?: current.content.sections.first()
        val length = (section.end - section.start).coerceAtLeast(1)
        return ViewportState(section.index, (character - section.start).toFloat() / length)
    }

    fun clear() {
        document = null
        textView.text = ""
        scrollView.scrollTo(0, 0)
    }

    private fun restoreViewport(state: ViewportState) {
        val current = document ?: return
        val section = current.content.sections[state.sectionIndex.coerceIn(0, current.content.sections.lastIndex)]
        val offset = section.start + ((section.end - section.start) * state.sectionProgress.coerceIn(0f, 1f)).toInt()
        scrollToCharacter(offset)
    }

    private fun scrollToCharacter(offset: Int) {
        val layout = textView.layout ?: return
        val line = layout.getLineForOffset(offset.coerceIn(0, textView.text.length))
        scrollView.scrollTo(0, layout.getLineTop(line).coerceAtLeast(0))
    }

    private fun applyInsets() {
        scrollView.setPadding(contentInsets[0], contentInsets[1], contentInsets[2], contentInsets[3])
        textView.setPadding(0, 0, 0, 0)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
