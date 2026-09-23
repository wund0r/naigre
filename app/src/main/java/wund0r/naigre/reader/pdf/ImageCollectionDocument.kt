// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.pdf

import android.content.ContentResolver
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import wund0r.naigre.reader.table.ImageFileRecord
import kotlin.math.max
import kotlin.math.sqrt

/** Exposes a directory-backed image album through the reader's page abstraction. */
class ImageCollectionDocument(
    private val contentResolver: ContentResolver,
    private val images: List<ImageFileRecord>,
    private val maxDecodedPixels: Long,
) : PdfDocument {
    companion object {
        // Avoid exceptionally long panoramas exceeding common Canvas/texture dimensions even
        // when their total pixel count is modest.
        private const val MAX_DECODED_DIMENSION = 8_192
    }

    private var closed = false

    init {
        if (images.isEmpty()) throw DocumentReadException(DocumentReadProblem.EMPTY_ALBUM)
        require(maxDecodedPixels > 0L) { "Image decode budget must be positive" }
    }

    override val pageCount: Int
        get() = images.size

    override fun renderPage(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage {
        check(!closed) { "Image album is closed" }
        require(pageIndex in images.indices)
        require(targetWidthPx > 0)

        val bitmap = if (Build.VERSION.SDK_INT >= 28) {
            decodeModern(Uri.parse(images[pageIndex].uri))
        } else {
            decodeLegacy(Uri.parse(images[pageIndex].uri))
        }
        return RenderedPdfPage(
            pageIndex = pageIndex,
            bitmap = bitmap,
            pageBounds = PdfRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat()),
            annotations = emptyList(),
            links = emptyList(),
            fitToViewport = true,
        )
    }

    override fun annotations(pageIndex: Int): List<PdfAnnotationInfo> = emptyList()

    override fun textInRect(pageIndex: Int, bounds: PdfRect): String? = null

    override fun textForSearch(pageIndex: Int): String = ""

    override fun searchText(
        pageIndex: Int,
        terms: List<String>,
        caseSensitive: Boolean,
    ): List<PdfRect> = emptyList()

    override fun outline(): List<PdfOutlineEntry> = emptyList()

    override fun close() {
        closed = true
    }

    @TargetApi(Build.VERSION_CODES.P)
    private fun decodeModern(uri: Uri): Bitmap {
        val source = ImageDecoder.createSource(contentResolver, uri)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val size = boundedSize(info.size.width, info.size.height)
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
            decoder.setTargetSize(size.first, size.second)
        }
    }

    private fun decodeLegacy(uri: Uri): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw DocumentReadException(DocumentReadProblem.CANNOT_OPEN_IMAGE)
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw DocumentReadException(DocumentReadProblem.INVALID_IMAGE)
        val desired = boundedSize(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (
            bounds.outWidth / (sample * 2) >= desired.first &&
            bounds.outHeight / (sample * 2) >= desired.second
        ) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = contentResolver.openInputStream(uri).use { input ->
            if (input == null) throw DocumentReadException(DocumentReadProblem.CANNOT_OPEN_IMAGE)
            BitmapFactory.decodeStream(input, null, options)
                ?: throw DocumentReadException(DocumentReadProblem.INVALID_IMAGE)
        }
        if (decoded.width == desired.first && decoded.height == desired.second) return decoded
        val scaled = Bitmap.createScaledBitmap(decoded, desired.first, desired.second, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private fun boundedSize(sourceWidth: Int, sourceHeight: Int): Pair<Int, Int> {
        if (sourceWidth <= 0 || sourceHeight <= 0) throw DocumentReadException(DocumentReadProblem.INVALID_IMAGE)
        val sourcePixels = sourceWidth.toLong() * sourceHeight
        val scale = minOf(
            1.0,
            sqrt(maxDecodedPixels.toDouble() / sourcePixels),
            MAX_DECODED_DIMENSION.toDouble() / sourceWidth,
            MAX_DECODED_DIMENSION.toDouble() / sourceHeight,
        )
        val width = max(1, (sourceWidth * scale).toInt())
        val height = max(1, (sourceHeight * scale).toInt())
        return width to height
    }
}
