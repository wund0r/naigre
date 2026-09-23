// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.LruCache
import org.json.JSONArray
import wund0r.naigre.reader.navigation.BookmarkSource
import wund0r.naigre.reader.pdf.ImageCollectionDocument
import wund0r.naigre.reader.pdf.MuPdfDocument
import wund0r.naigre.reader.table.BookIndexLoadResult
import wund0r.naigre.reader.table.BookLibraryStorage
import wund0r.naigre.reader.table.BookRecord
import wund0r.naigre.reader.table.LibraryItemKind
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlin.math.roundToInt

data class LibraryPreview(val bitmap: Bitmap? = null, val headings: List<String> = emptyList())

/**
 * Main-thread request ownership, one background thumbnail at a time. Only attached cards request
 * work; detaching/rebinding cancels their subscription and removes obsolete queued work. This
 * process-owned worker never borrows reader handles or queues work on interactive render lanes.
 */
class LibraryPreviewLoader private constructor(context: Context) {
    companion object {
        internal const val WIDTH = 192
        internal const val HEIGHT = 288
        @Volatile private var instance: LibraryPreviewLoader? = null

        fun shared(context: Context): LibraryPreviewLoader = instance ?: synchronized(this) {
            instance ?: LibraryPreviewLoader(context.applicationContext).also { instance = it }
        }

        /** Catalog-only stamps: index hydration and tag/color edits must not invalidate covers. */
        internal fun key(book: BookRecord): String {
            val digest = MessageDigest.getInstance("SHA-256")
            listOf(book.id, book.kind.name, book.uri, book.sourceSize, book.sourceLastModified,
                book.sourceFingerprint, book.sourceRevisionToken, book.indexVersion).forEach {
                digest.update((it?.toString() ?: "?").toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
            }
            return bookPrefix(book.id) + digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private fun bookPrefix(bookId: String): String = MessageDigest.getInstance("SHA-256")
            .digest(bookId.toByteArray(Charsets.UTF_8)).take(16)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) } + "-"
    }

    private val resolver = context.contentResolver
    private val storage = BookLibraryStorage.shared(context)
    private val directory = File(context.cacheDir, "library-previews-v1")
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { work ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            work.run()
        }, "naigre-library-previews")
    }
    private data class Cached(val preview: LibraryPreview?, val time: Long = SystemClock.uptimeMillis())
    private val memory = object : LruCache<String, Cached>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Cached): Int =
            value.preview?.bitmap?.allocationByteCount ?: 1024
    }
    private class Pending(val key: String, val book: BookRecord) {
        val callbacks = linkedMapOf<Any, (LibraryPreview?) -> Unit>()
        @Volatile var discarded = false
    }
    private val pending = linkedMapOf<String, Pending>()
    private var active: Pending? = null

    /** Returns cancellation for this card only. Call on the main thread. */
    fun load(book: BookRecord, onResult: (LibraryPreview?) -> Unit): () -> Unit {
        check(Looper.myLooper() == Looper.getMainLooper())
        val key = key(book)
        memory.get(key)?.let { cached ->
            // Failures are temporary: retry when revisiting after a provider becomes available.
            if (cached.preview != null || SystemClock.uptimeMillis() - cached.time < 10_000) {
                onResult(cached.preview)
                return {}
            }
            memory.remove(key)
        }
        val request = pending.getOrPut(key) { Pending(key, book) }
        val token = Any()
        request.callbacks[token] = onResult
        pump()
        return {
            request.callbacks.remove(token)
            if (request !== active && request.callbacks.isEmpty()) pending.remove(key, request)
        }
    }

    fun trimMemory() = memory.evictAll() // Never recycle a bitmap still displayed by a card.

    /** Purge all revisions, including an in-flight preview, when its library item is forgotten. */
    fun forget(bookId: String) {
        val prefix = bookPrefix(bookId)
        memory.snapshot().keys.filter { it.startsWith(prefix) }.forEach(memory::remove)
        pending.values.filter { it.book.id == bookId }.toList().forEach {
            it.discarded = true
            it.callbacks.clear()
            if (it !== active) pending.remove(it.key)
        }
        worker.execute {
            directory.listFiles()?.filter { it.name.startsWith(prefix) }?.forEach { it.delete() }
        }
    }

    private fun pump() {
        if (active != null) return
        val request = pending.values.firstOrNull() ?: return
        active = request
        worker.execute {
            val cached = runCatching { readDisk(request.key, request.book.kind) }.getOrNull()
            main.post {
                if (cached != null) finish(request, cached)
                else if (request.callbacks.isEmpty()) finish(request, null)
                else prepare(request)
            }
        }
    }

    private fun prepare(request: Pending) {
        val book = request.book
        if (book.kind == LibraryItemKind.PDF || book.indexLoaded) render(request, book)
        else storage.loadIndex(book) { result ->
            // Loading a preview must not select a book, schedule text indexing or mutate the table.
            if (request.callbacks.isEmpty() || result !is BookIndexLoadResult.Loaded) finish(request, null)
            else render(request, result.book)
        }
    }

    private fun render(request: Pending, book: BookRecord) {
        worker.execute {
            val preview = runCatching { generate(book) }.getOrNull()
            if (preview != null && !request.discarded) runCatching { writeDisk(request.key, preview) }
            main.post { finish(request, preview) }
        }
    }

    private fun finish(request: Pending, preview: LibraryPreview?) {
        if (!request.discarded && (preview != null || request.callbacks.isNotEmpty())) memory.put(request.key, Cached(preview))
        pending.remove(request.key)
        active = null
        val callbacks = request.callbacks.values.toList()
        request.callbacks.clear()
        callbacks.forEach { it(preview) }
        pump()
    }

    private fun generate(book: BookRecord): LibraryPreview = when (book.kind) {
        LibraryItemKind.PDF -> {
            val descriptor = requireNotNull(resolver.openFileDescriptor(Uri.parse(book.uri), "r"))
            MuPdfDocument(descriptor).use { LibraryPreview(bitmap = fit(it.renderThumbnail(WIDTH, HEIGHT))) }
        }
        LibraryItemKind.IMAGE_COLLECTION -> ImageCollectionDocument(
            resolver, book.imageFiles.take(1), WIDTH.toLong() * HEIGHT,
        ).use { LibraryPreview(bitmap = fit(it.renderPage(0, WIDTH).bitmap)) }
        LibraryItemKind.MARKDOWN -> LibraryPreview(headings = book.pdfBookmarks.asSequence()
            .filter { it.source == BookmarkSource.MARKDOWN_HEADING && it.stableKey != "markdown:start" }
            .take(4).map { it.title.take(160) }.toList())
    }

    private fun fit(bitmap: Bitmap): Bitmap {
        val scale = minOf(1f, WIDTH.toFloat() / bitmap.width, HEIGHT.toFloat() / bitmap.height)
        if (scale == 1f) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).roundToInt().coerceAtLeast(1),
            (bitmap.height * scale).roundToInt().coerceAtLeast(1), true).also {
            if (it !== bitmap) bitmap.recycle()
        }
    }

    private fun readDisk(key: String, kind: LibraryItemKind): LibraryPreview? {
        val file = File(directory, "$key.${if (kind == LibraryItemKind.MARKDOWN) "json" else "png"}")
        if (!file.isFile) return null
        val preview = if (kind == LibraryItemKind.MARKDOWN) {
            require(file.length() <= 8192)
            val headings = JSONArray(file.readText())
            require(headings.length() <= 4)
            LibraryPreview(headings = (0 until headings.length()).map { headings.getString(it).take(160) })
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            require(bounds.outWidth in 1..WIDTH && bounds.outHeight in 1..HEIGHT)
            LibraryPreview(bitmap = requireNotNull(BitmapFactory.decodeFile(file.path)))
        }
        file.setLastModified(System.currentTimeMillis())
        return preview
    }

    private fun writeDisk(key: String, preview: LibraryPreview) {
        if (!directory.isDirectory && !directory.mkdirs()) return
        val extension = if (preview.bitmap != null) "png" else "json"
        val target = File(directory, "$key.$extension")
        val temp = File.createTempFile("preview-", ".tmp", directory)
        try {
            if (preview.bitmap != null) temp.outputStream().use {
                check(preview.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            } else temp.writeText(JSONArray(preview.headings).toString())
            check(temp.renameTo(target))
        } finally {
            temp.delete()
        }
        // Disposable cache only; bounded even after many refresh/relink operations.
        val files = directory.listFiles()?.sortedBy { it.lastModified() }.orEmpty()
        var bytes = files.sumOf { it.length() }
        var count = files.size
        for (file in files) {
            if (bytes <= 32L * 1024 * 1024 && count <= 256) break
            val length = file.length()
            if (file.delete()) { bytes -= length; count-- }
        }
    }
}
