// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.pdf

import android.graphics.Bitmap
import java.io.Closeable

data class PdfRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

data class PdfAnnotationInfo(
    val pageNumber: Int,
    val type: String,
    val author: String?,
    val subject: String?,
    val contents: String?,
    val bounds: PdfRect?,
)

data class PdfLinkInfo(
    val sourcePageIndex: Int,
    val targetPageIndex: Int,
    val targetY: Float?,
    val destinationKey: String?,
    val bounds: PdfRect,
)

data class PdfOutlineEntry(
    val title: String,
    val pageIndex: Int,
    val targetY: Float?,
    val destinationKey: String?,
    val path: String,
)

data class RenderedPdfPage(
    val pageIndex: Int,
    val bitmap: Bitmap,
    val pageBounds: PdfRect,
    val annotations: List<PdfAnnotationInfo>,
    val links: List<PdfLinkInfo>,
    val fitToViewport: Boolean = false,
)

interface PdfDocument : Closeable {
    val pageCount: Int

    /** pageIndex is zero-based; targetWidthPx is the desired rendered width. */
    fun renderPage(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage

    /** Returns existing PDF annotations without modifying the document. */
    fun annotations(pageIndex: Int): List<PdfAnnotationInfo>

    /** Extracts visible text intersecting a page-space rectangle. */
    fun textInRect(pageIndex: Int, bounds: PdfRect): String?

    /** Extracts normalized page text for the persistent full-text index. */
    fun textForSearch(pageIndex: Int): String

    /** Finds page-space rectangles for terms on one page. */
    fun searchText(pageIndex: Int, terms: List<String>, caseSensitive: Boolean): List<PdfRect>

    /** Returns flattened PDF outline/bookmark entries with their hierarchical path. */
    fun outline(): List<PdfOutlineEntry>
}
