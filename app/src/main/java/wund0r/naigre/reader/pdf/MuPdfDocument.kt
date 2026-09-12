// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.pdf

import android.os.ParcelFileDescriptor
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Matrix
import com.artifex.mupdf.fitz.PDFAnnotation
import com.artifex.mupdf.fitz.PDFPage
import com.artifex.mupdf.fitz.SeekableInputStream
import com.artifex.mupdf.fitz.StructuredText
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.Closeable
import java.io.File
import java.io.IOException
import kotlin.math.max

class MuPdfDocument private constructor(
    private val document: Document,
    private val backingStream: Closeable? = null,
) : PdfDocument {
    constructor(file: File) : this(Document.openDocument(file.absolutePath))

    constructor(descriptor: ParcelFileDescriptor) : this(openDescriptor(descriptor))

    private constructor(opened: OpenedDescriptor) : this(opened.document, opened.backing)

    private class ParcelDescriptorStream(
        descriptor: ParcelFileDescriptor,
    ) : SeekableInputStream, Closeable {
        private val input = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        private val channel = input.channel

        override fun read(buffer: ByteArray): Int = input.read(buffer)

        override fun seek(offset: Long, whence: Int): Long {
            val base = when (whence) {
                com.artifex.mupdf.fitz.SeekableStream.SEEK_SET -> 0L
                com.artifex.mupdf.fitz.SeekableStream.SEEK_CUR -> channel.position()
                com.artifex.mupdf.fitz.SeekableStream.SEEK_END -> channel.size()
                else -> throw IOException("Unknown seek origin: $whence")
            }
            val target = base + offset
            if (target < 0L) throw IOException("Cannot seek before the start of the PDF")
            channel.position(target)
            return channel.position()
        }

        override fun position(): Long = channel.position()

        override fun close() = input.close()
    }

    private data class OpenedDescriptor(
        val document: Document,
        val backing: Closeable,
    )

    companion object {
        private val SEARCH_WHITESPACE = Regex("\\s+")

        private fun openDescriptor(descriptor: ParcelFileDescriptor): OpenedDescriptor {
            // Preserve MuPDF's path-based document behavior while the retained descriptor
            // keeps the source alive. Some PDFs report different destination coordinates
            // through MuPDF's callback-stream opening API.
            try {
                return OpenedDescriptor(
                    document = Document.openDocument("/proc/self/fd/${descriptor.fd}"),
                    backing = descriptor,
                )
            } catch (_: Throwable) {
                // Some document providers expose descriptors that procfs cannot reopen.
                // Keep the seekable callback as a compatibility fallback.
            }

            val stream = try {
                ParcelDescriptorStream(descriptor)
            } catch (t: Throwable) {
                descriptor.close()
                throw t
            }
            return try {
                OpenedDescriptor(Document.openDocument(stream, "application/pdf"), stream)
            } catch (t: Throwable) {
                stream.close()
                throw t
            }
        }
    }

    private data class ResolvedDestination(
        val pageIndex: Int,
        val targetY: Float?,
    )

    private var closed = false
    private val pages: Int

    init {
        pages = try {
            require(document.isPDF()) { "Selected file is not a PDF" }
            require(!document.needsPassword()) { "Password-protected PDFs are not supported yet" }
            document.countPages().also { require(it > 0) { "PDF has no pages" } }
        } catch (t: Throwable) {
            try {
                document.destroy()
            } finally {
                backingStream?.close()
            }
            throw t
        }
    }

    override val pageCount: Int
        get() = pages

    override fun renderPage(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage {
        check(!closed) { "PDF document is closed" }
        require(pageIndex in 0 until pageCount)
        require(targetWidthPx > 0)

        val page = document.loadPage(pageIndex)
        try {
            val bounds = page.getBounds()
            val pageRect = PdfRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
            val pageWidthPoints = max(1f, pageRect.width)
            val scale = targetWidthPx.toFloat() / pageWidthPoints
            val matrix = Matrix.Scale(scale)

            // Render directly into an Android Bitmap. Page.run(), which backs this helper,
            // includes page contents and existing annotation/widget appearances without the
            // previous Pixmap -> PNG -> byte[] -> Bitmap round trip.
            val bitmap = AndroidDrawDevice.drawPage(page, matrix)

            val annotations = extractAnnotations(pageIndex, page as? PDFPage)
            val links = extractInternalLinks(pageIndex, page)
            return RenderedPdfPage(
                pageIndex = pageIndex,
                bitmap = bitmap,
                pageBounds = pageRect,
                annotations = annotations,
                links = links,
            )
        } finally {
            page.destroy()
        }
    }

    override fun outline(): List<PdfOutlineEntry> {
        check(!closed) { "PDF document is closed" }
        val roots = document.loadOutline() ?: return emptyList()
        val result = ArrayList<PdfOutlineEntry>()

        fun visit(nodes: Array<com.artifex.mupdf.fitz.Outline>, parents: List<String>) {
            for (node in nodes) {
                val title = node.title?.trim().orEmpty()
                val pathParts = if (title.isEmpty()) parents else parents + title

                if (title.isNotEmpty()) {
                    val uri = node.uri
                    if (!uri.isNullOrBlank()) {
                        try {
                            val destination = resolveInternalDestination(uri)
                            if (destination != null) {
                                result += PdfOutlineEntry(
                                    title = title,
                                    pageIndex = destination.pageIndex,
                                    targetY = destination.targetY,
                                    destinationKey = destinationKey(uri),
                                    path = pathParts.joinToString(" › "),
                                )
                            }
                        } catch (_: Throwable) {
                            // External/broken outline links are not useful for page navigation.
                        }
                    }
                }

                node.down?.let { children -> visit(children, pathParts) }
            }
        }

        visit(roots, emptyList())
        return result
    }

    override fun annotations(pageIndex: Int): List<PdfAnnotationInfo> {
        check(!closed) { "PDF document is closed" }
        require(pageIndex in 0 until pageCount)

        val page = document.loadPage(pageIndex)
        try {
            return extractAnnotations(pageIndex, page as? PDFPage)
        } finally {
            page.destroy()
        }
    }

    override fun textInRect(pageIndex: Int, bounds: PdfRect): String? {
        check(!closed) { "PDF document is closed" }
        require(pageIndex in 0 until pageCount)

        val page = document.loadPage(pageIndex)
        try {
            val structuredText = page.toStructuredText()
            try {
                val lines = ArrayList<String>()
                for (block in structuredText.getBlocks().orEmpty()) {
                    for (line in block.lines.orEmpty()) {
                        val text = StringBuilder()
                        for (character in line.chars.orEmpty()) {
                            val characterBounds = character.quad.toRect()
                            if (!intersects(bounds, characterBounds)) continue
                            val codePoint = character.c
                            if (Character.isValidCodePoint(codePoint) && !Character.isISOControl(codePoint)) {
                                text.appendCodePoint(codePoint)
                            }
                        }
                        text.toString().trim().takeIf { it.isNotEmpty() }?.let(lines::add)
                    }
                }
                return lines
                    .joinToString(" ")
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(160)
                    .takeIf { it.isNotEmpty() }
            } finally {
                structuredText.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    override fun textForSearch(pageIndex: Int): String {
        check(!closed) { "PDF document is closed" }
        require(pageIndex in 0 until pageCount)

        val page = document.loadPage(pageIndex)
        try {
            val structuredText = page.toStructuredText("dehyphenate")
            try {
                return structuredText.asText()
                    .replace('\u0000', ' ')
                    .replace(SEARCH_WHITESPACE, " ")
                    .trim()
            } finally {
                structuredText.destroy()
            }
        } finally {
            page.destroy()
        }
    }

    override fun searchText(pageIndex: Int, terms: List<String>, caseSensitive: Boolean): List<PdfRect> {
        check(!closed) { "PDF document is closed" }
        require(pageIndex in 0 until pageCount)
        if (terms.isEmpty()) return emptyList()

        val page = document.loadPage(pageIndex)
        try {
            val flags = StructuredText.SEARCH_IGNORE_DIACRITICS or
                if (caseSensitive) 0 else StructuredText.SEARCH_IGNORE_CASE
            val rectangles = LinkedHashSet<PdfRect>()
            for (term in terms) {
                val hits = runCatching { page.search(term, flags) }.getOrNull() ?: continue
                for (hit in hits) {
                    for (quad in hit) {
                        val bounds = quad.toRect()
                        rectangles += PdfRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1)
                        if (rectangles.size >= 160) return rectangles.toList()
                    }
                }
            }
            return rectangles.toList()
        } finally {
            page.destroy()
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            try {
                document.destroy()
            } finally {
                backingStream?.close()
            }
        }
    }

    private fun extractAnnotations(pageIndex: Int, pdfPage: PDFPage?): List<PdfAnnotationInfo> {
        val annotations = pdfPage?.annotations ?: return emptyList()
        return annotations.map { annotation ->
            try {
                val rect = try {
                    val b = annotation.getBounds()
                    PdfRect(b.x0, b.y0, b.x1, b.y1)
                } catch (_: Throwable) {
                    null
                }
                PdfAnnotationInfo(
                    pageNumber = pageIndex + 1,
                    type = annotationTypeName(annotation.getType()),
                    author = annotation.authorOrNull(),
                    subject = annotation.subjectOrNull(),
                    contents = annotation.getContents()?.takeIf { it.isNotBlank() },
                    bounds = rect,
                )
            } finally {
                annotation.destroy()
            }
        }
    }

    private fun extractInternalLinks(
        pageIndex: Int,
        page: com.artifex.mupdf.fitz.Page,
    ): List<PdfLinkInfo> {
        val links = try {
            page.getLinks() ?: return emptyList()
        } catch (_: Throwable) {
            return emptyList()
        }
        return links.mapNotNull { link ->
            try {
                val uri = link.getURI()
                if (uri.isNullOrBlank() || link.isExternal()) return@mapNotNull null

                val destination = resolveInternalDestination(uri) ?: return@mapNotNull null

                val bounds = link.getBounds()
                PdfLinkInfo(
                    sourcePageIndex = pageIndex,
                    targetPageIndex = destination.pageIndex,
                    targetY = destination.targetY,
                    destinationKey = destinationKey(uri),
                    bounds = PdfRect(bounds.x0, bounds.y0, bounds.x1, bounds.y1),
                )
            } catch (_: Throwable) {
                // Broken, unsupported, and external destinations are not reader navigation.
                null
            } finally {
                link.destroy()
            }
        }
    }

    private fun intersects(bounds: PdfRect, characterBounds: com.artifex.mupdf.fitz.Rect): Boolean {
        // Link rectangles and glyph quads are not always pixel-perfect. A small page-space
        // tolerance includes edge glyphs without pulling in adjacent lines in normal PDFs.
        val tolerance = 1.5f
        return characterBounds.x1 >= bounds.left - tolerance &&
            characterBounds.x0 <= bounds.right + tolerance &&
            characterBounds.y1 >= bounds.top - tolerance &&
            characterBounds.y0 <= bounds.bottom + tolerance
    }

    private fun resolveInternalDestination(uri: String): ResolvedDestination? {
        try {
            val destination = document.resolveLinkDestination(uri)
            val pageIndex = document.pageNumberFromLocation(destination)
            if (pageIndex in 0 until pageCount) {
                val targetY = if (destination.hasY() && destination.y.isFinite()) destination.y else null
                return ResolvedDestination(pageIndex, targetY)
            }
        } catch (_: Throwable) {
            // Fall back to page-only resolution below.
        }

        return try {
            val pageIndex = document.pageNumberFromLocation(document.resolveLink(uri))
            if (pageIndex in 0 until pageCount) ResolvedDestination(pageIndex, null) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun destinationKey(uri: String): String? = uri.trim().takeIf { it.isNotEmpty() }

    private fun PDFAnnotation.authorOrNull(): String? =
        if (hasAuthor()) getAuthor()?.takeIf { it.isNotBlank() } else null

    private fun PDFAnnotation.subjectOrNull(): String? =
        if (hasSubject()) getSubject()?.takeIf { it.isNotBlank() } else null

    private fun annotationTypeName(type: Int): String = when (type) {
        PDFAnnotation.TYPE_TEXT -> "Text note"
        PDFAnnotation.TYPE_LINK -> "Link"
        PDFAnnotation.TYPE_FREE_TEXT -> "Free text"
        PDFAnnotation.TYPE_LINE -> "Line"
        PDFAnnotation.TYPE_SQUARE -> "Square"
        PDFAnnotation.TYPE_CIRCLE -> "Circle"
        PDFAnnotation.TYPE_POLYGON -> "Polygon"
        PDFAnnotation.TYPE_POLY_LINE -> "Polyline"
        PDFAnnotation.TYPE_HIGHLIGHT -> "Highlight"
        PDFAnnotation.TYPE_UNDERLINE -> "Underline"
        PDFAnnotation.TYPE_SQUIGGLY -> "Squiggly"
        PDFAnnotation.TYPE_STRIKE_OUT -> "Strikeout"
        PDFAnnotation.TYPE_REDACT -> "Redaction"
        PDFAnnotation.TYPE_STAMP -> "Stamp"
        PDFAnnotation.TYPE_CARET -> "Caret"
        PDFAnnotation.TYPE_INK -> "Ink"
        PDFAnnotation.TYPE_POPUP -> "Popup"
        PDFAnnotation.TYPE_FILE_ATTACHMENT -> "File attachment"
        PDFAnnotation.TYPE_SOUND -> "Sound"
        PDFAnnotation.TYPE_MOVIE -> "Movie"
        PDFAnnotation.TYPE_RICH_MEDIA -> "Rich media"
        PDFAnnotation.TYPE_WIDGET -> "Widget"
        PDFAnnotation.TYPE_SCREEN -> "Screen"
        PDFAnnotation.TYPE_PRINTER_MARK -> "Printer mark"
        PDFAnnotation.TYPE_TRAP_NET -> "Trap net"
        PDFAnnotation.TYPE_WATERMARK -> "Watermark"
        PDFAnnotation.TYPE_3D -> "3D"
        PDFAnnotation.TYPE_PROJECTION -> "Projection"
        else -> "Unknown ($type)"
    }
}
