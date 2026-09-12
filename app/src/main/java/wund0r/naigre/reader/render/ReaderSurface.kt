// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import wund0r.naigre.reader.pdf.PdfAnnotationInfo
import wund0r.naigre.reader.pdf.PdfLinkInfo
import wund0r.naigre.reader.pdf.PdfRect
import wund0r.naigre.reader.pdf.RenderedPdfPage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ReaderSurface(context: Context) : View(context) {
    data class ViewportState(
        val zoom: Float,
        val focusX: Float,
        val focusY: Float,
    )

    var onAnnotationTap: ((PdfAnnotationInfo) -> Unit)? = null
    var onLinkTap: ((PdfLinkInfo) -> Unit)? = null
    var onBlankTap: (() -> Unit)? = null
    var onPageTurn: ((Int) -> Unit)? = null
    var pageTurnMode: PageTurnMode = PageTurnMode.BOTH

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val pagePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0
        style = Paint.Style.FILL
    }
    private val searchHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0
        style = Paint.Style.FILL
    }

    private var left: RenderedPdfPage? = null
    private var right: RenderedPdfPage? = null
    private var zoom = 1f
    private var panX = 0f
    private var panY = 0f
    private var searchHighlights: Map<Int, List<PdfRect>> = emptyMap()
    private val gapPx = 0f
    private val flingThresholdPx = 72f * resources.displayMetrics.density
    private val edgeFraction = 0.16f

    private val pageRects = ArrayList<Pair<RenderedPdfPage, RectF>>(2)

    fun setUiColors(pagePlaceholderColor: Int, searchHighlightColor: Int) {
        pagePaint.color = pagePlaceholderColor
        searchHighlightPaint.color = searchHighlightColor
        invalidate()
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldZoom = zoom
            val oldOrigin = currentOrigin()
            val nextZoom = (zoom * detector.scaleFactor).coerceIn(1f, 4f)
            if (nextZoom == oldZoom) return true

            val ratio = nextZoom / oldZoom
            zoom = nextZoom
            panX = detector.focusX - (detector.focusX - oldOrigin.first) * ratio
            panY = detector.focusY - (detector.focusY - oldOrigin.second) * ratio
            clampPan()
            invalidate()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            panX -= distanceX
            panY -= distanceY
            clampPan()
            invalidate()
            return true
        }

        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float,
        ): Boolean {
            val start = e1 ?: return false
            if (!pageTurnMode.swipeEnabled || zoom > 1.02f) return false

            val dx = e2.x - start.x
            val dy = e2.y - start.y
            val horizontal = abs(dx) >= flingThresholdPx && abs(dx) > abs(dy) * 1.25f
            val fastEnough = abs(velocityX) > abs(velocityY)
            if (!horizontal || !fastEnough) return false

            onPageTurn?.invoke(if (dx < 0f) +1 else -1)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            hitLink(e.x, e.y)?.let {
                onLinkTap?.invoke(it)
                return true
            }

            hitAnnotation(e.x, e.y)?.let {
                onAnnotationTap?.invoke(it)
                return true
            }

            if (pageTurnMode.edgeTapsEnabled && width > 0) {
                when {
                    e.x <= width * edgeFraction -> {
                        onPageTurn?.invoke(-1)
                        return true
                    }
                    e.x >= width * (1f - edgeFraction) -> {
                        onPageTurn?.invoke(+1)
                        return true
                    }
                }
            }

            onBlankTap?.invoke()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            resetTransform()
            return true
        }
    })

    fun setPages(
        leftPage: RenderedPdfPage?,
        rightPage: RenderedPdfPage?,
        resetTransform: Boolean,
        viewportState: ViewportState? = null,
    ) {
        left = leftPage
        right = rightPage
        if (viewportState != null) {
            restoreViewportState(viewportState)
        } else if (resetTransform) {
            zoom = 1f
            panX = 0f
            panY = 0f
        }
        clampPan()
        invalidate()
    }

    fun captureViewportState(): ViewportState? {
        val pages = listOfNotNull(left, right)
        if (pages.isEmpty() || width <= 0 || height <= 0) return null
        val dimensions = contentDimensions(pages, zoom)
        val origin = computeOrigin(dimensions.first, dimensions.second)
        return ViewportState(
            zoom = zoom,
            focusX = ((width / 2f - origin.first) / dimensions.first).coerceIn(0f, 1f),
            focusY = ((height / 2f - origin.second) / dimensions.second).coerceIn(0f, 1f),
        )
    }

    fun clearPages() {
        left = null
        right = null
        zoom = 1f
        panX = 0f
        panY = 0f
        searchHighlights = emptyMap()
        pageRects.clear()
        invalidate()
    }

    fun showSearchHighlights(pageIndex: Int, bounds: List<PdfRect>, focusFirst: Boolean) {
        searchHighlights = if (bounds.isEmpty()) emptyMap() else mapOf(pageIndex to bounds)
        if (focusFirst) bounds.firstOrNull()?.let { focusOn(pageIndex, it) }
        invalidate()
    }

    fun clearSearchHighlights() {
        if (searchHighlights.isEmpty()) return
        searchHighlights = emptyMap()
        invalidate()
    }

    fun focusOnY(pageIndex: Int, targetY: Float) {
        val page = listOfNotNull(left, right).firstOrNull { it.pageIndex == pageIndex } ?: return
        val y = targetY.coerceIn(page.pageBounds.top, page.pageBounds.bottom)
        focusOn(
            pageIndex,
            PdfRect(page.pageBounds.left, y, page.pageBounds.right, y),
        )
        invalidate()
    }

    fun resetTransform() {
        zoom = 1f
        panX = 0f
        panY = 0f
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        pageRects.clear()
        val pages = listOfNotNull(left, right)
        if (pages.isEmpty() || width <= 0 || height <= 0) return

        val rawWidth = pages.sumOf { it.bitmap.width.toDouble() }.toFloat() + if (pages.size > 1) gapPx else 0f
        val rawHeight = pages.maxOf { it.bitmap.height }.toFloat()
        if (rawWidth <= 0f || rawHeight <= 0f) return

        val fitScale = initialFitScale(pages, rawWidth, rawHeight)
        val scaledGap = gapPx * fitScale * zoom
        val totalWidth = pages.sumOf { (it.bitmap.width * fitScale * zoom).toDouble() }.toFloat() +
            if (pages.size > 1) scaledGap else 0f
        val totalHeight = pages.maxOf { it.bitmap.height * fitScale * zoom }
        val origin = computeOrigin(totalWidth, totalHeight)

        var x = origin.first
        for (page in pages) {
            val drawWidth = page.bitmap.width * fitScale * zoom
            val drawHeight = page.bitmap.height * fitScale * zoom
            val y = if (totalHeight < height) (height - drawHeight) / 2f else origin.second
            val rect = RectF(x, y, x + drawWidth, y + drawHeight)
            canvas.drawRect(rect, pagePaint)
            if (!page.bitmap.isRecycled) canvas.drawBitmap(page.bitmap, null, rect, paint)
            searchHighlights[page.pageIndex]?.forEach { bounds ->
                canvas.drawRect(mapPageRect(bounds, page, rect, 0f), searchHighlightPaint)
            }
            pageRects += page to rect
            x += drawWidth + scaledGap
        }
    }

    private fun hitLink(x: Float, y: Float): PdfLinkInfo? {
        for ((page, pageRect) in pageRects) {
            if (!pageRect.contains(x, y)) continue
            val pad = 6f * resources.displayMetrics.density
            val candidates = page.links.mapNotNull { link ->
                val hit = mapPageRect(link.bounds, page, pageRect, pad)
                if (hit.contains(x, y)) link to (hit.width() * hit.height()) else null
            }
            return candidates.minByOrNull { it.second }?.first
        }
        return null
    }

    private fun hitAnnotation(x: Float, y: Float): PdfAnnotationInfo? {
        for ((page, pageRect) in pageRects) {
            if (!pageRect.contains(x, y)) continue
            val pad = 10f * resources.displayMetrics.density

            val candidates = page.annotations.mapNotNull { annotation ->
                val bounds = annotation.bounds ?: return@mapNotNull null
                val hit = mapPageRect(bounds, page, pageRect, pad)
                if (hit.contains(x, y)) annotation to (hit.width() * hit.height()) else null
            }

            return candidates.minByOrNull { it.second }?.first
        }
        return null
    }

    private fun mapPageRect(
        bounds: PdfRect,
        page: RenderedPdfPage,
        pageRect: RectF,
        pad: Float,
    ): RectF {
        val pageBounds = page.pageBounds
        val pageWidth = max(0.001f, pageBounds.width)
        val pageHeight = max(0.001f, pageBounds.height)
        val left = pageRect.left + ((bounds.left - pageBounds.left) / pageWidth) * pageRect.width()
        val top = pageRect.top + ((bounds.top - pageBounds.top) / pageHeight) * pageRect.height()
        val right = pageRect.left + ((bounds.right - pageBounds.left) / pageWidth) * pageRect.width()
        val bottom = pageRect.top + ((bounds.bottom - pageBounds.top) / pageHeight) * pageRect.height()
        return RectF(
            min(left, right) - pad,
            min(top, bottom) - pad,
            max(left, right) + pad,
            max(top, bottom) + pad,
        )
    }

    private fun currentOrigin(): Pair<Float, Float> {
        val pages = listOfNotNull(left, right)
        if (pages.isEmpty() || width <= 0 || height <= 0) return 0f to 0f
        val dimensions = contentDimensions(pages, zoom)
        return computeOrigin(dimensions.first, dimensions.second)
    }

    private fun restoreViewportState(state: ViewportState) {
        val pages = listOfNotNull(left, right)
        zoom = state.zoom.coerceIn(1f, 4f)
        if (pages.isEmpty() || width <= 0 || height <= 0) {
            panX = 0f
            panY = 0f
            return
        }
        val dimensions = contentDimensions(pages, zoom)
        panX = width / 2f - state.focusX.coerceIn(0f, 1f) * dimensions.first
        panY = height / 2f - state.focusY.coerceIn(0f, 1f) * dimensions.second
    }

    private fun contentDimensions(pages: List<RenderedPdfPage>, atZoom: Float): Pair<Float, Float> {
        val rawWidth = pages.sumOf { it.bitmap.width.toDouble() }.toFloat() +
            if (pages.size > 1) gapPx else 0f
        val rawHeight = pages.maxOf { it.bitmap.height }.toFloat()
        val fitScale = initialFitScale(pages, rawWidth, rawHeight)
        val totalWidth = pages.sumOf { (it.bitmap.width * fitScale * atZoom).toDouble() }.toFloat() +
            if (pages.size > 1) gapPx * fitScale * atZoom else 0f
        val totalHeight = pages.maxOf { it.bitmap.height * fitScale * atZoom }
        return totalWidth to totalHeight
    }

    private fun focusOn(pageIndex: Int, bounds: PdfRect) {
        val pages = listOfNotNull(left, right)
        val page = pages.firstOrNull { it.pageIndex == pageIndex } ?: return
        if (width <= 0 || height <= 0) return

        zoom = 1f
        panX = 0f
        val rawWidth = pages.sumOf { it.bitmap.width.toDouble() }.toFloat() +
            if (pages.size > 1) gapPx else 0f
        val fitScale = initialFitScale(pages, rawWidth, pages.maxOf { it.bitmap.height }.toFloat())
        val drawHeight = page.bitmap.height * fitScale
        val pageHeight = max(0.001f, page.pageBounds.height)
        val targetY = ((bounds.top + bounds.bottom) / 2f - page.pageBounds.top) / pageHeight * drawHeight
        panY = if (drawHeight <= height) 0f else (height / 2f - targetY).coerceIn(height - drawHeight, 0f)
        clampPan()
    }

    private fun computeOrigin(totalWidth: Float, totalHeight: Float): Pair<Float, Float> {
        val x = if (totalWidth <= width) (width - totalWidth) / 2f else panX.coerceIn(width - totalWidth, 0f)
        val y = if (totalHeight <= height) (height - totalHeight) / 2f else panY.coerceIn(height - totalHeight, 0f)
        return x to y
    }

    private fun clampPan() {
        val pages = listOfNotNull(left, right)
        if (pages.isEmpty() || width <= 0 || height <= 0) return
        val rawWidth = pages.sumOf { it.bitmap.width.toDouble() }.toFloat() + if (pages.size > 1) gapPx else 0f
        val fitScale = initialFitScale(pages, rawWidth, pages.maxOf { it.bitmap.height }.toFloat())
        val totalWidth = pages.sumOf { (it.bitmap.width * fitScale * zoom).toDouble() }.toFloat() +
            if (pages.size > 1) gapPx * fitScale * zoom else 0f
        val totalHeight = pages.maxOf { it.bitmap.height * fitScale * zoom }

        panX = if (totalWidth <= width) 0f else panX.coerceIn(width - totalWidth, 0f)
        panY = if (totalHeight <= height) 0f else panY.coerceIn(height - totalHeight, 0f)
    }

    private fun initialFitScale(
        pages: List<RenderedPdfPage>,
        rawWidth: Float,
        rawHeight: Float,
    ): Float {
        val widthScale = width.toFloat() / max(1f, rawWidth)
        if (pages.size != 1 || !pages.single().fitToViewport) return widthScale
        return min(widthScale, height.toFloat() / max(1f, rawHeight))
    }
}
