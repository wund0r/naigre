// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.BackgroundColorSpan
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ReplacementSpan
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
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tables.TableTheme
import wund0r.naigre.reader.markdown.MarkdownContent
import wund0r.naigre.reader.markdown.MarkdownDocument
import wund0r.naigre.reader.markdown.MarkdownEngine
import wund0r.naigre.reader.markdown.MarkdownFoldMode
import wund0r.naigre.reader.markdown.MarkdownFoldState
import wund0r.naigre.reader.markdown.MarkdownVisibility
import wund0r.naigre.reader.markdown.WikiLinkSpan
import wund0r.naigre.reader.markdown.WikiLinkSpec
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
    private val foldMarkerSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 24f, resources.displayMetrics)
    private val foldTouchSize = dp(48)
    private val foldTouchPadding = dp(8)
    private val foldGutterWidth = maxOf(foldTouchSize, foldMarkerSize.roundToInt() + dp(8))
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
    private var contentInsets = intArrayOf(dp(20), dp(20), dp(20), 0, dp(32))
    var onBlankTap: (() -> Unit)? = null
    var onWikiLink: ((WikiLinkSpec, Boolean) -> Unit)? = null

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

    fun setUiColors(background: Int, text: Int, link: Int = text) {
        setBackgroundColor(background)
        scrollView.setBackgroundColor(background)
        textView.setTextColor(text)
        textView.setLinkTextColor(link)
    }

    /** Viewport insets are separate from end padding, which scrolls with the document. */
    fun setContentInsets(left: Int, top: Int, right: Int, bottom: Int, endPadding: Int = 0) {
        val insets = intArrayOf(left, top, right, bottom, endPadding)
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
        textView.cancelWikiGesture()
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

    private inner class HeadingFoldSpan(val sectionIndex: Int, val mode: MarkdownFoldMode) : ClickableSpan() {
        override fun onClick(widget: View) { toggleHeading(sectionIndex) }
        override fun updateDrawState(ds: TextPaint) { /* Keep the normal heading style. */ }
        val marker: String get() = when (mode) {
            MarkdownFoldMode.COLLAPSED -> "▸"
            MarkdownFoldMode.HEADINGS_ONLY -> "▿"
            MarkdownFoldMode.EXPANDED -> "▾"
        }
    }

    private data class HeadingTouchTarget(
        val span: HeadingFoldSpan,
        val bounds: RectF,
        val arrowLeft: Float,
        val arrowRight: Float,
    )

    /** A heading tap folds; scrolling, long-press selection, and inline links retain their gestures. */
    private inner class FoldingTextView(context: Context) : TextView(context), WikiLinkSpan.Host {
        private var downHeading: HeadingFoldSpan? = null
        private var clickedHeading: HeadingFoldSpan? = null
        private var downX = 0f
        private var downY = 0f
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var selectionScrollGeneration = -1
        private var restoringViewport = false
        private var downLink: WikiLinkSpan? = null
        private var linkGesture = false
        private var linkHeld = false
        private val tableCellPadding = TableTheme.create(context).tableCellPadding()
        private val linkLongPress = Runnable { if (downLink != null && linkGesture) performLongClick() }
        private val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val markerClip = Rect()

        override fun openWikiLink(spec: WikiLinkSpec) { onWikiLink?.invoke(spec, false) }

        override fun onDraw(canvas: Canvas) {
            val saved = canvas.save()
            super.onDraw(canvas)
            canvas.restoreToCount(saved)
            val spanned = text as? Spanned ?: return
            val textLayout = layout ?: return
            // The padding belongs to every paragraph. Decorations in that gutter
            // never add a leading margin to a heading or affect its line wrapping.
            canvas.getClipBounds(markerClip)
            val first = textLayout.getLineForVertical((markerClip.top - totalPaddingTop).coerceAtLeast(0))
            val last = textLayout.getLineForVertical(markerClip.bottom - totalPaddingTop)
            markerPaint.color = currentTextColor
            markerPaint.textSize = foldMarkerSize
            markerPaint.typeface = typeface
            val gutterSave = canvas.save()
            canvas.clipRect(scrollX, scrollY, scrollX + totalPaddingLeft, scrollY + height)
            for (span in spanned.getSpans(textLayout.getLineStart(first), textLayout.getLineEnd(last), HeadingFoldSpan::class.java)) {
                val line = textLayout.getLineForOffset(spanned.getSpanStart(span))
                val x = scrollX + (totalPaddingLeft - markerPaint.measureText(span.marker)) / 2f
                canvas.drawText(span.marker, x, (totalPaddingTop + textLayout.getLineBaseline(line)).toFloat(), markerPaint)
            }
            canvas.restoreToCount(gutterSave)
        }

        private fun linkAt(event: MotionEvent): WikiLinkSpan? {
            val spanned = text as? Spanned ?: return null
            val textLayout = layout ?: return null
            val y = event.y.toInt() - totalPaddingTop + scrollY
            if (y < 0 || y >= textLayout.height) return null
            val line = textLayout.getLineForVertical(y)
            val x = event.x - totalPaddingLeft + scrollX
            val row = spanned.getSpans(textLayout.getLineStart(line), textLayout.getLineEnd(line), TableRowSpan::class.java)
                .firstOrNull()
            if (row != null) {
                val rowX = x - textLayout.getPrimaryHorizontal(spanned.getSpanStart(row))
                if (rowX < 0) return null
                // Table cells have their own layouts, created lazily on the first draw.
                val cellLayout = runCatching { row.findLayoutForHorizontalOffset(rowX.toInt()) }.getOrNull() ?: return null
                val cellWidth = row.cellWidth().takeIf { it > 0 } ?: return null
                val cellText = cellLayout.text as? Spanned ?: return null
                var maxHeight = cellLayout.height
                for (columnX in 0 until textLayout.width step cellWidth) {
                    val columnLayout = row.findLayoutForHorizontalOffset(columnX) ?: break
                    maxHeight = maxOf(maxHeight, columnLayout.height)
                }
                val rowTop = textLayout.getLineTop(line)
                val heightDiff = (textLayout.getLineBottom(line) - rowTop - maxHeight) / 4
                return linkInLayout(cellText, cellLayout, rowX % cellWidth - tableCellPadding,
                    y - rowTop - tableCellPadding - heightDiff)
            }
            return linkInLayout(spanned, textLayout, x, y)
        }

        private fun linkInLayout(spanned: Spanned, textLayout: Layout, x: Float, y: Int): WikiLinkSpan? =
            clickableInLayout(spanned, textLayout, x, y) as? WikiLinkSpan

        private fun clickableInLayout(spanned: Spanned, textLayout: Layout, x: Float, y: Int): ClickableSpan? {
            if (y < 0 || y >= textLayout.height) return null
            val line = textLayout.getLineForVertical(y)
            if (x < textLayout.getLineLeft(line) || x > textLayout.getLineRight(line)) return null
            val offset = textLayout.getOffsetForHorizontal(line, x)
            return spanned.getSpans(offset, offset, ClickableSpan::class.java).firstOrNull {
                val start = maxOf(spanned.getSpanStart(it), textLayout.getLineStart(line))
                val end = minOf(spanned.getSpanEnd(it), textLayout.getLineEnd(line))
                val rtl = textLayout.getParagraphDirection(line) < 0
                // Paragraph margins (for example a blockquote) still apply to links
                // on continuation lines; the fold gutter is outside the text layout.
                val firstX = textLayout.getPrimaryHorizontal(start)
                val lastX = if (end == textLayout.getLineEnd(line) && line < textLayout.lineCount - 1) {
                    if (rtl) textLayout.getLineLeft(line) else textLayout.getLineRight(line)
                } else textLayout.getPrimaryHorizontal(end)
                it !is HeadingFoldSpan && start < end && x >= minOf(firstX, lastX) && x <= maxOf(firstX, lastX)
            }
        }

        fun cancelWikiGesture() {
            removeCallbacks(linkLongPress)
            downLink = null
            linkGesture = false
            linkHeld = false
        }

        override fun onDetachedFromWindow() {
            cancelWikiGesture()
            super.onDetachedFromWindow()
        }

        override fun performLongClick(): Boolean {
            val link = downLink ?: return super.performLongClick()
            if (!linkHeld) {
                linkHeld = true
                removeCallbacks(linkLongPress)
                onWikiLink?.invoke(link.spec, true)
            }
            return true
        }

        override fun onPreDraw(): Boolean {
            // TextView can register its cursor reveal after our restore listener during
            // measurement. Suppress it for this rebuild even if our listener ran first.
            restoringViewport = selectionScrollGeneration != layoutGeneration
            selectionScrollGeneration = layoutGeneration
            return try {
                super.onPreDraw()
            } finally {
                restoringViewport = false
            }
        }

        override fun bringPointIntoView(offset: Int): Boolean {
            if (pendingViewport != null || restoringViewport) return false
            return super.bringPointIntoView(offset)
        }

        private fun headingAt(event: MotionEvent): HeadingFoldSpan? {
            val spanned = text as? Spanned ?: return null
            val textLayout = layout ?: return null
            val y = event.y.toInt() - totalPaddingTop + scrollY
            if (y < 0 || y >= height - totalPaddingTop + scrollY) return null
            val x = event.x - totalPaddingLeft + scrollX
            val reach = maxOf(foldTouchPadding, foldTouchSize / 2)
            val fromLine = textLayout.getLineForVertical((y - reach).coerceAtLeast(0))
            val toLine = textLayout.getLineForVertical(y + reach)
            val targets = spanned.getSpans(textLayout.getLineStart(fromLine), textLayout.getLineEnd(toLine), HeadingFoldSpan::class.java)
                .map { headingTouchTarget(spanned, textLayout, it) }.filter { it.bounds.contains(x, y.toFloat()) }

            // A link's nearest text offset can be its first character even when the
            // finger is over the arrow. Resolve the gutter before testing link bounds.
            targets.firstOrNull { x >= it.arrowLeft && x < it.arrowRight }?.let { return it.span }
            if (clickableInLayout(spanned, textLayout, x, y) != null) return null
            return targets.firstOrNull()?.span
        }

        private fun headingTouchTarget(spanned: Spanned, textLayout: Layout, span: HeadingFoldSpan): HeadingTouchTarget {
            val start = spanned.getSpanStart(span)
            val end = spanned.getSpanEnd(span)
            val first = textLayout.getLineForOffset(start)
            val last = textLayout.getLineForOffset(maxOf(start, end - 1))
            val top = textLayout.getLineTop(first).toFloat()
            val bottom = textLayout.getLineBottom(last).toFloat()
            var before = first - 1
            while (before >= 0 && !lineHasContent(spanned, textLayout, before)) before--
            var after = last + 1
            while (after < textLayout.lineCount && !lineHasContent(spanned, textLayout, after)) after++
            // Share empty gaps with adjacent rows, never extending into their text or
            // into a replacement span such as a table or horizontal rule.
            val topLimit = if (before >= 0) (textLayout.getLineBottom(before) + top) / 2f else 0f
            val bottomLimit = if (after < textLayout.lineCount) (bottom + textLayout.getLineTop(after)) / 2f
                else (height - totalPaddingTop + scrollY).toFloat()
            val extra = maxOf(foldTouchPadding.toFloat(), (foldTouchSize - (bottom - top)) / 2f)
            val gutterLeft = (scrollX - totalPaddingLeft).toFloat()
            return HeadingTouchTarget(
                span, RectF(gutterLeft, maxOf(topLimit, top - extra), textLayout.width.toFloat(), minOf(bottomLimit, bottom + extra)),
                gutterLeft, scrollX.toFloat(),
            )
        }

        private fun lineHasContent(spanned: Spanned, textLayout: Layout, line: Int): Boolean {
            val start = textLayout.getLineStart(line)
            val end = textLayout.getLineEnd(line)
            return (start until end).any { !spanned[it].isWhitespace() } ||
                spanned.getSpans(start, end, ReplacementSpan::class.java).any {
                    spanned.getSpanStart(it) < end && spanned.getSpanEnd(it) > start
                }
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelWikiGesture()
                    downHeading = headingAt(event)
                    downLink = if (downHeading == null) linkAt(event) else null
                    linkGesture = downLink != null
                    downX = event.x
                    downY = event.y
                    if (linkGesture) {
                        downHeading = null
                        postDelayed(linkLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(event.x - downX) > slop || abs(event.y - downY) > slop) {
                        downHeading = null
                        downLink = null
                        removeCallbacks(linkLongPress)
                    }
                    if (linkGesture) return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    downHeading = null
                    if (linkGesture) {
                        cancelWikiGesture()
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (linkGesture) {
                        removeCallbacks(linkLongPress)
                        val link = downLink
                        if (link != null && link === linkAt(event) && !linkHeld) {
                            if (event.eventTime - event.downTime >= ViewConfiguration.getLongPressTimeout()) performLongClick()
                            else link.onClick(this)
                        }
                        cancelWikiGesture()
                        return true
                    }
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
        textView.setPadding(foldGutterWidth, 0, 0, contentInsets[4])
    }

    private fun dp(value: Int): Int = (value * density).toInt()
}
