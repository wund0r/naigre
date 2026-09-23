// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import wund0r.naigre.reader.markdown.MarkdownContent
import wund0r.naigre.reader.markdown.MarkdownDocument
import wund0r.naigre.reader.markdown.MarkdownEngine
import wund0r.naigre.reader.markdown.MarkdownFoldMode
import wund0r.naigre.reader.markdown.MarkdownFoldState
import wund0r.naigre.reader.markdown.MarkdownVisibility
import kotlin.math.abs
import kotlin.math.roundToInt

class MarkdownSurface @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    data class ViewportState(val sectionIndex: Int, val sectionProgress: Float, val lineOffsetPx: Int = 0)

    /** Owned by a tab/reference, not the reusable surface. Never written to the source file. */
    class ReadingState {
        val folds = MarkdownFoldState()
        var viewport: ViewportState? = null
        var navigationPending = true
            private set

        fun requestNavigation() {
            navigationPending = true
            viewport = null
        }

        internal fun navigationApplied() { navigationPending = false }
    }

    private val density = resources.displayMetrics.density
    private val scrollView = ScrollView(context).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = true
        clipToPadding = true
    }
    private val textView = FoldingTextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        setLineSpacing(3f * density, 1.08f)
        gravity = Gravity.TOP or Gravity.START
        setTextIsSelectable(true)
        isClickable = true
    }
    private var content: MarkdownContent? = null
    private var engine: MarkdownEngine? = null
    private var readingState: ReadingState? = null
    private var visibilityMap: MarkdownVisibility? = null
    private var highlightedText: Spanned? = null
    private var hasSearchHighlights = false
    private var layoutGeneration = 0
    private var viewportRestoreListener: ViewTreeObserver.OnPreDrawListener? = null
    private var pendingViewport: ViewportState? = null
    private var contentInsets = intArrayOf(dp(20), dp(20), dp(20), dp(32))
    var onBlankTap: (() -> Unit)? = null

    init {
        scrollView.addView(
            textView,
            LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        addView(scrollView, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyInsets()
        textView.setOnClickListener { onBlankTap?.invoke() }
        scrollView.setOnScrollChangeListener { _, _, _, _, _ ->
            if (pendingViewport == null && readingState?.navigationPending == false) {
                readingState?.viewport = captureViewportState()
            }
        }
    }

    fun setUiColors(background: Int, text: Int) {
        setBackgroundColor(background)
        scrollView.setBackgroundColor(background)
        textView.setTextColor(text)
    }

    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int) {
        val insets = intArrayOf(left, top, right, bottom)
        if (contentInsets.contentEquals(insets)) return
        val viewport = captureViewportState()
        contentInsets = insets
        applyInsets()
        viewport?.let(::scheduleViewportRestore)
    }

    fun showDocument(
        engine: MarkdownEngine,
        nextDocument: MarkdownDocument,
        sectionIndex: Int,
        state: ReadingState,
        highlightTerms: List<String> = emptyList(),
        caseSensitive: Boolean = false,
        highlightColor: Int = Color.YELLOW,
        highlightTextColor: Int = Color.BLACK,
    ) = showContent(engine, nextDocument.content, sectionIndex, state, highlightTerms, caseSensitive,
        highlightColor, highlightTextColor)

    internal fun showContent(
        engine: MarkdownEngine,
        nextContent: MarkdownContent,
        sectionIndex: Int,
        state: ReadingState,
        highlightTerms: List<String> = emptyList(),
        caseSensitive: Boolean = false,
        highlightColor: Int = Color.YELLOW,
        highlightTextColor: Int = Color.BLACK,
    ) {
        if (readingState === state && !state.navigationPending) state.viewport = captureViewportState()
        if (state.folds.bind(nextContent.fingerprint)) state.requestNavigation()
        this.engine = engine
        content = nextContent
        readingState = state
        val section = nextContent.sections[sectionIndex.coerceIn(0, nextContent.sections.lastIndex)]
        val navigate = state.navigationPending
        if (navigate) state.folds.reveal(section, nextContent.sections)

        val rendered = SpannableString(nextContent.rendered)
        var firstMatch: Int? = null
        hasSearchHighlights = false
        highlightTerms.filter { it.isNotBlank() }.distinct().forEach { term ->
            var from = section.start
            while (from < section.end) {
                val match = nextContent.plainText.indexOf(term, from, ignoreCase = !caseSensitive)
                if (match < 0 || match + term.length > section.end) break
                val end = match + term.length
                rendered.setSpan(BackgroundColorSpan(highlightColor), match, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                rendered.setSpan(ForegroundColorSpan(highlightTextColor), match, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                firstMatch = minOf(firstMatch ?: match, match)
                hasSearchHighlights = true
                from = end
            }
        }
        highlightedText = rendered
        val viewport = if (navigate) viewportAt(firstMatch ?: section.start) else state.viewport ?: viewportAt(section.start)
        state.navigationApplied()
        rebuildVisibleText(viewport)
    }

    fun clearSearchHighlights() {
        if (!hasSearchHighlights) return
        val viewport = captureViewportState() ?: return
        highlightedText = content?.rendered
        hasSearchHighlights = false
        rebuildVisibleText(viewport)
    }

    private fun rebuildVisibleText(viewport: ViewportState) {
        val current = content ?: return
        val state = readingState ?: return
        val mapping = MarkdownVisibility(current.sections, state.folds.sectionModes, current.rendered.length)
        val displayModes = state.folds.displayModes(current.sections)
        visibilityMap = mapping
        val visible = SpannableStringBuilder(highlightedText ?: current.rendered)
        for (range in mapping.hiddenRanges.asReversed()) {
            // Paragraph/table spans entirely inside hidden content must not survive at
            // a deletion boundary and accidentally style the following visible heading.
            visible.getSpans(range.start, range.end, Any::class.java).forEach { span ->
                if (visible.getSpanStart(span) >= range.start && visible.getSpanEnd(span) <= range.end) {
                    visible.removeSpan(span)
                }
            }
            visible.delete(range.start, range.end)
        }
        for (section in current.sections) {
            if (!section.canFold || !mapping.isVisible(section.start)) continue
            visible.setSpan(
                HeadingFoldSpan(section.index, displayModes.getValue(section.stableKey)),
                mapping.toVisible(section.start), mapping.toVisible(section.headingEnd),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        // Mark the pending restore before setText/layout can generate scroll callbacks.
        pendingViewport = viewport
        engine?.apply(textView, visible)
        scheduleViewportRestore(viewport)
    }

    internal fun toggleHeading(sectionIndex: Int) {
        val current = content ?: return
        val state = readingState ?: return
        val mapping = visibilityMap ?: return
        val section = current.sections.getOrNull(sectionIndex) ?: return
        val layout = textView.layout ?: return
        if (!section.canFold || !mapping.isVisible(section.start)) return
        val line = layout.getLineForOffset(mapping.toVisible(section.start))
        val headingOffset = scrollView.scrollY - layout.getLineTop(line)
        state.folds.cycle(section, current.sections)
        rebuildVisibleText(viewportAt(section.start).copy(lineOffsetPx = headingOffset))
    }

    fun captureViewportState(): ViewportState? {
        pendingViewport?.let { return it }
        val mapping = visibilityMap ?: return null
        val layout = textView.layout ?: return null
        val textY = scrollView.scrollY.coerceAtLeast(0)
        val line = layout.getLineForVertical(textY)
        return viewportAt(mapping.toSource(layout.getLineStart(line)))
            .copy(lineOffsetPx = textY - layout.getLineTop(line))
    }

    fun isShowing(state: ReadingState?): Boolean = state != null && readingState === state

    private fun viewportAt(sourceOffset: Int): ViewportState {
        val sections = checkNotNull(content).sections
        val section = sections.lastOrNull { it.start <= sourceOffset } ?: sections.first()
        val length = (section.end - section.start).coerceAtLeast(1)
        return ViewportState(section.index, (sourceOffset - section.start).toFloat() / length)
    }

    private fun scheduleViewportRestore(viewport: ViewportState) {
        val generation = ++layoutGeneration
        cancelViewportRestore()
        pendingViewport = viewport
        readingState?.viewport = viewport
        val listener = ViewTreeObserver.OnPreDrawListener {
            val current = content
            val mapping = visibilityMap
            val layout = textView.layout
            if (generation != layoutGeneration) return@OnPreDrawListener true
            if (current == null || mapping == null || readingState?.navigationPending == true) {
                cancelViewportRestore()
                pendingViewport = null
                return@OnPreDrawListener true
            }
            if (layout == null) return@OnPreDrawListener true
            cancelViewportRestore()
            val section = current.sections[viewport.sectionIndex.coerceIn(0, current.sections.lastIndex)]
            val source = section.start + ((section.end - section.start) * viewport.sectionProgress.coerceIn(0f, 1f)).roundToInt()
            val line = layout.getLineForOffset(mapping.toVisible(source).coerceIn(0, textView.text.length))
            scrollView.scrollTo(0, (layout.getLineTop(line) + viewport.lineOffsetPx).coerceAtLeast(0))
            pendingViewport = null
            readingState?.viewport = captureViewportState()
            true
        }
        viewportRestoreListener = listener
        textView.viewTreeObserver.addOnPreDrawListener(listener)
        textView.invalidate()
    }

    private fun cancelViewportRestore() {
        viewportRestoreListener?.let { textView.viewTreeObserver.removeOnPreDrawListener(it) }
        viewportRestoreListener = null
    }

    fun clear() {
        ++layoutGeneration
        cancelViewportRestore()
        content = null
        engine = null
        readingState = null
        visibilityMap = null
        highlightedText = null
        hasSearchHighlights = false
        pendingViewport = null
        textView.text = ""
        scrollView.scrollTo(0, 0)
    }

    private inner class HeadingFoldSpan(val sectionIndex: Int, val mode: MarkdownFoldMode) : ClickableSpan(), LeadingMarginSpan {
        override fun onClick(widget: View) { toggleHeading(sectionIndex) }
        override fun updateDrawState(ds: TextPaint) { /* Keep the normal heading style. */ }
        override fun getLeadingMargin(first: Boolean): Int = dp(24)
        override fun drawLeadingMargin(
            canvas: Canvas, paint: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
            text: CharSequence, start: Int, end: Int, first: Boolean, layout: Layout,
        ) {
            if (!first) return
            val savedSize = paint.textSize
            val savedStyle = paint.style
            paint.textSize = 16f * density
            paint.style = Paint.Style.FILL
            val marker = when (mode) {
                MarkdownFoldMode.COLLAPSED -> "▸"
                MarkdownFoldMode.HEADINGS_ONLY -> "▿"
                MarkdownFoldMode.EXPANDED -> "▾"
            }
            canvas.drawText(marker, (x + if (dir < 0) -dp(20) else 0).toFloat(), baseline.toFloat(), paint)
            paint.textSize = savedSize
            paint.style = savedStyle
        }
    }

    /** A heading tap folds; scrolling, long-press selection, and inline links retain their gestures. */
    private inner class FoldingTextView(context: Context) : TextView(context) {
        private var downHeading: HeadingFoldSpan? = null
        private var clickedHeading: HeadingFoldSpan? = null
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop

        private fun headingAt(event: MotionEvent): HeadingFoldSpan? {
            val spanned = text as? Spanned ?: return null
            val textLayout = layout ?: return null
            val y = event.y.toInt() - totalPaddingTop + scrollY
            if (y < 0 || y >= textLayout.height) return null
            val line = textLayout.getLineForVertical(y)
            val x = event.x - totalPaddingLeft + scrollX
            val offset = textLayout.getOffsetForHorizontal(line, x)
            val withinText = x >= textLayout.getLineLeft(line) && x <= textLayout.getLineRight(line)
            if (withinText && spanned.getSpans(offset, offset, ClickableSpan::class.java).any { it !is HeadingFoldSpan }) return null
            return spanned.getSpans(textLayout.getLineStart(line), textLayout.getLineEnd(line), HeadingFoldSpan::class.java)
                .firstOrNull { spanned.getSpanStart(it) < textLayout.getLineEnd(line) && spanned.getSpanEnd(it) > textLayout.getLineStart(line) }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downHeading = headingAt(event)
                    downX = event.x
                    downY = event.y
                }
                MotionEvent.ACTION_MOVE -> if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) downHeading = null
                MotionEvent.ACTION_CANCEL -> downHeading = null
                MotionEvent.ACTION_UP -> {
                    val heading = downHeading
                    downHeading = null
                    if (heading != null && heading === headingAt(event) &&
                        event.eventTime - event.downTime < ViewConfiguration.getLongPressTimeout()) {
                        val cancel = MotionEvent.obtain(event)
                        cancel.action = MotionEvent.ACTION_CANCEL
                        super.onTouchEvent(cancel)
                        cancel.recycle()
                        clickedHeading = heading
                        performClick()
                        clickedHeading = null
                        return true
                    }
                }
            }
            return super.onTouchEvent(event)
        }

        override fun performClick(): Boolean {
            val heading = clickedHeading ?: return super.performClick()
            heading.onClick(this)
            return true
        }
    }

    private fun applyInsets() {
        scrollView.setPadding(contentInsets[0], contentInsets[1], contentInsets[2], contentInsets[3])
        textView.setPadding(0, 0, 0, 0)
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
