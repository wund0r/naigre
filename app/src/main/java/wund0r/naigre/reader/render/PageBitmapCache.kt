// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

import wund0r.naigre.reader.pdf.RenderedPdfPage
import java.util.LinkedHashMap

data class PageCacheKey(
    val bookId: String,
    val sourceRevision: String,
    val pageIndex: Int,
    val targetWidthPx: Int,
)

data class CachedPageBitmapInfo(
    val width: Int,
    val height: Int,
    val bytes: Long,
)

class PageBitmapCache(
    private val maxBytes: Long,
) {
    private val entries = LinkedHashMap<PageCacheKey, RenderedPdfPage>(16, 0.75f, true)
    private val pinned = HashSet<PageCacheKey>()
    private var totalBytes = 0L

    @Synchronized
    fun get(key: PageCacheKey): RenderedPdfPage? = entries[key]

    @Synchronized
    fun put(key: PageCacheKey, page: RenderedPdfPage): RenderedPdfPage {
        // Foreground and speculative documents can race on the same key. Never replace an
        // existing entry: it may already be pinned and displayed by ReaderSurface.
        val existing = entries[key]
        if (existing != null) {
            if (existing.bitmap !== page.bitmap && !page.bitmap.isRecycled) page.bitmap.recycle()
            return existing
        }

        entries[key] = page
        totalBytes += page.bitmap.allocationByteCount.toLong()
        trimLocked()
        return entries[key] ?: page
    }

    @Synchronized
    fun pin(keys: Collection<PageCacheKey>) {
        pinned.clear()
        pinned.addAll(keys)
        trimLocked()
    }

    @Synchronized
    fun clear() {
        pinned.clear()
        for (page in entries.values) {
            if (!page.bitmap.isRecycled) page.bitmap.recycle()
        }
        entries.clear()
        totalBytes = 0
    }

    @Synchronized
    fun removeBook(bookId: String) {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key.bookId != bookId || entry.key in pinned) continue
            iterator.remove()
            totalBytes -= entry.value.bitmap.allocationByteCount.toLong()
            if (!entry.value.bitmap.isRecycled) entry.value.bitmap.recycle()
        }
    }

    /** Releases non-visible entries without recycling a Bitmap still owned by a surface. */
    @Synchronized
    fun trimUnpinned() {
        val iterator = entries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.key in pinned) continue
            iterator.remove()
            totalBytes -= entry.value.bitmap.allocationByteCount.toLong()
            if (!entry.value.bitmap.isRecycled) entry.value.bitmap.recycle()
        }
    }

    @Synchronized
    fun info(key: PageCacheKey): CachedPageBitmapInfo? = entries[key]?.bitmap?.let {
        CachedPageBitmapInfo(it.width, it.height, it.allocationByteCount.toLong())
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun bytes(): Long = totalBytes

    fun capacityBytes(): Long = maxBytes

    private fun trimLocked() {
        if (totalBytes <= maxBytes) return
        val iterator = entries.entries.iterator()
        while (iterator.hasNext() && totalBytes > maxBytes) {
            val entry = iterator.next()
            if (entry.key in pinned) continue
            iterator.remove()
            totalBytes -= entry.value.bitmap.allocationByteCount.toLong()
            if (!entry.value.bitmap.isRecycled) entry.value.bitmap.recycle()
        }
    }
}
