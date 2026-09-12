// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.search

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
import kotlin.math.ln

data class ExtractedTextPage(
    val pageIndex: Int,
    val text: String,
)

data class TextIndexProgress(
    val indexedPages: Int,
    val pageCount: Int,
    val sourceRevision: String,
    val complete: Boolean,
)

data class TextSearchHit(
    val bookId: String,
    val pageIndex: Int,
    val snippet: String,
    val score: Double,
)

data class TextSearchSnapshot(
    val hits: List<TextSearchHit>,
    val totalMatches: Int,
    val indexedPages: Int,
    val totalPages: Int,
    val readyBooks: Int,
)

class TextSearchIndexRepository private constructor(context: Context) : SQLiteOpenHelper(
    context,
    DATABASE_NAME,
    null,
    DATABASE_VERSION,
) {
    companion object {
        @Volatile private var sharedInstance: TextSearchIndexRepository? = null

        private const val DATABASE_NAME = "text-search.db"
        // v3 prevents a same-page-count replacement PDF from reusing stale extracted text.
        private const val DATABASE_VERSION = 3
        const val RESULT_PAGE_SIZE = 160
        private const val SNIPPET_QUERY_CHUNK_SIZE = 400
        const val MATCH_START = '\u27e6'
        const val MATCH_END = '\u27e7'

        private val QUERY_PART = Regex("\"([^\"]+)\"|([\\p{L}\\p{N}_]+)")
        private val HIGHLIGHT_TERM = Regex("[\\p{L}\\p{N}_]+")

        /**
         * The text index belongs to the app process, not an Activity instance. Sharing one helper
         * prevents configuration-change recreation from abandoning an open SQLite connection or
         * closing it while the replacement Activity has already started querying.
         */
        fun shared(context: Context): TextSearchIndexRepository =
            sharedInstance ?: synchronized(this) {
                sharedInstance ?: TextSearchIndexRepository(context.applicationContext).also {
                    sharedInstance = it
                }
            }

        /** Plain words are ANDed; a final word of 3+ characters is a prefix query. */
        fun matchExpression(query: String): String? {
            val matches = QUERY_PART.findAll(query).toList()
            if (matches.isEmpty()) return null
            return matches.mapIndexed { index, match ->
                val quotedPhrase = match.groups[1]?.value
                val value = quotedPhrase ?: match.groups[2]?.value.orEmpty()
                val quoted = value.replace("\"", "\"\"")
                val prefix = quotedPhrase == null && index == matches.lastIndex && value.length >= 3
                if (prefix) "\"$quoted*\"" else "\"$quoted\""
            }.joinToString(" AND ")
        }

        fun highlightTerms(query: String): List<String> =
            HIGHLIGHT_TERM.findAll(query)
                .map { it.value }
                .filter { it.length >= 2 }
                .distinctBy { it.lowercase() }
                .toList()
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE VIRTUAL TABLE page_search USING fts4(
                book_id,
                page_index,
                body,
                tokenize=unicode61,
                notindexed=book_id,
                notindexed=page_index,
                prefix="2,3,4"
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE index_state(
                book_id TEXT PRIMARY KEY NOT NULL,
                page_count INTEGER NOT NULL,
                source_revision TEXT NOT NULL,
                indexed_pages INTEGER NOT NULL,
                complete INTEGER NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS page_search")
        db.execSQL("DROP TABLE IF EXISTS index_state")
        onCreate(db)
    }

    /** Returns the next page to extract, resetting an incompatible or forced index. */
    fun prepareBook(
        bookId: String,
        pageCount: Int,
        sourceRevision: String,
        force: Boolean = false,
    ): TextIndexProgress {
        val db = writableDatabase
        if (!force) {
            readProgress(db, bookId)
                ?.takeIf { it.pageCount == pageCount && it.sourceRevision == sourceRevision }
                ?.let { return it }
        }
        db.beginTransaction()
        try {
            val current = readProgress(db, bookId)
            val incompatible = current != null && (
                current.pageCount != pageCount || current.sourceRevision != sourceRevision
                )
            if (force || incompatible) {
                db.delete("page_search", "book_id = ?", arrayOf(bookId))
                db.delete("index_state", "book_id = ?", arrayOf(bookId))
            }
            val retained = if (force || incompatible) null else current
            if (retained != null) {
                db.setTransactionSuccessful()
                return retained
            }

            val values = ContentValues().apply {
                put("book_id", bookId)
                put("page_count", pageCount)
                put("source_revision", sourceRevision)
                put("indexed_pages", 0)
                put("complete", if (pageCount == 0) 1 else 0)
            }
            db.insertOrThrow("index_state", null, values)
            db.setTransactionSuccessful()
            return TextIndexProgress(0, pageCount, sourceRevision, pageCount == 0)
        } finally {
            db.endTransaction()
        }
    }

    fun storePages(
        bookId: String,
        pageCount: Int,
        sourceRevision: String,
        pages: List<ExtractedTextPage>,
    ): TextIndexProgress {
        if (pages.isEmpty()) {
            return progress(bookId)
                ?.takeIf { it.pageCount == pageCount && it.sourceRevision == sourceRevision }
                ?: TextIndexProgress(0, pageCount, sourceRevision, false)
        }
        val db = writableDatabase
        db.beginTransaction()
        try {
            val current = readProgress(db, bookId)
            require(current?.pageCount == pageCount && current.sourceRevision == sourceRevision) {
                "Text index revision changed during extraction"
            }
            for (page in pages) {
                db.delete(
                    "page_search",
                    "book_id = ? AND page_index = ?",
                    arrayOf(bookId, page.pageIndex.toString()),
                )
                val values = ContentValues().apply {
                    put("book_id", bookId)
                    put("page_index", page.pageIndex)
                    put("body", page.text)
                }
                db.insertOrThrow("page_search", null, values)
            }
            val indexedPages = (pages.maxOf { it.pageIndex } + 1).coerceAtMost(pageCount)
            val complete = indexedPages >= pageCount
            val state = ContentValues().apply {
                put("indexed_pages", indexedPages)
                put("complete", if (complete) 1 else 0)
            }
            db.update("index_state", state, "book_id = ?", arrayOf(bookId))
            db.setTransactionSuccessful()
            return TextIndexProgress(indexedPages, pageCount, sourceRevision, complete)
        } finally {
            db.endTransaction()
        }
    }

    fun progress(bookId: String): TextIndexProgress? = readProgress(readableDatabase, bookId)

    fun deleteBook(bookId: String) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("page_search", "book_id = ?", arrayOf(bookId))
            db.delete("index_state", "book_id = ?", arrayOf(bookId))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun search(
        query: String,
        bookIds: List<String>,
        pageCounts: Map<String, Int>,
        sourceRevisions: Map<String, String>,
        resultLimit: Int = RESULT_PAGE_SIZE,
    ): TextSearchSnapshot {
        val expression = matchExpression(query)
        if (expression == null || bookIds.isEmpty()) {
            return TextSearchSnapshot(emptyList(), 0, 0, pageCounts.values.sum(), 0)
        }

        val db = readableDatabase
        var indexedPages = 0
        var readyBooks = 0
        val compatibleBookIds = ArrayList<String>(bookIds.size)
        for (bookId in bookIds) {
            readProgress(db, bookId)?.let { state ->
                val compatible = state.pageCount == pageCounts[bookId] &&
                    state.sourceRevision == sourceRevisions[bookId]
                if (compatible) {
                    compatibleBookIds += bookId
                    indexedPages += state.indexedPages.coerceAtMost(state.pageCount)
                    if (state.complete) readyBooks++
                }
            }
        }

        if (compatibleBookIds.isEmpty()) {
            return TextSearchSnapshot(emptyList(), 0, 0, pageCounts.values.sum(), 0)
        }

        val placeholders = compatibleBookIds.joinToString(",") { "?" }
        val sql = """
            SELECT rowid, book_id, page_index, matchinfo(page_search, 'nx')
            FROM page_search
            WHERE page_search MATCH ? AND book_id IN ($placeholders)
        """.trimIndent()
        val args = arrayOf(expression, *compatibleBookIds.toTypedArray())
        val order = bookIds.withIndex().associate { it.value to it.index }
        data class Candidate(val rowId: Long, val hit: TextSearchHit)
        val bestFirst = compareByDescending<Candidate> { it.hit.score }
            .thenBy { order[it.hit.bookId] ?: Int.MAX_VALUE }
            .thenBy { it.hit.pageIndex }
        val boundedLimit = resultLimit.coerceAtLeast(0)
        val best = PriorityQueue<Candidate>(boundedLimit.coerceAtLeast(1), bestFirst.reversed())
        var totalMatches = 0
        db.rawQuery(sql, args).use { cursor ->
            while (cursor.moveToNext()) {
                totalMatches++
                if (boundedLimit == 0) continue
                val candidate = Candidate(
                    rowId = cursor.getLong(0),
                    hit = TextSearchHit(
                        bookId = cursor.getString(1),
                        pageIndex = cursor.getInt(2),
                        snippet = "",
                        score = relevance(cursor.getBlob(3)),
                    ),
                )
                if (best.size < boundedLimit) {
                    best += candidate
                } else if (bestFirst.compare(candidate, best.peek()) < 0) {
                    best.poll()
                    best += candidate
                }
            }
        }
        val ranked = best.sortedWith(bestFirst)
        val snippets = snippets(db, expression, ranked.map { it.rowId })
        val sorted = ranked.map { candidate ->
            candidate.hit.copy(snippet = snippets[candidate.rowId].orEmpty())
        }
        return TextSearchSnapshot(
            hits = sorted,
            totalMatches = totalMatches,
            indexedPages = indexedPages,
            totalPages = pageCounts.values.sum(),
            readyBooks = readyBooks,
        )
    }

    fun databaseBytes(): Long = runCatching {
        readableDatabase.path?.let { java.io.File(it).length() } ?: 0L
    }.getOrDefault(0L)

    private fun readProgress(db: SQLiteDatabase, bookId: String): TextIndexProgress? =
        db.query(
            "index_state",
            arrayOf("indexed_pages", "page_count", "source_revision", "complete"),
            "book_id = ?",
            arrayOf(bookId),
            null,
            null,
            null,
        ).use { cursor ->
            if (!cursor.moveToFirst()) null else TextIndexProgress(
                indexedPages = cursor.getInt(0),
                pageCount = cursor.getInt(1),
                sourceRevision = cursor.getString(2),
                complete = cursor.getInt(3) != 0,
            )
        }

    private fun relevance(blob: ByteArray): Double {
        if (blob.size < Int.SIZE_BYTES * 2) return 0.0
        val values = ByteBuffer.wrap(blob).order(ByteOrder.nativeOrder()).asIntBuffer()
        val documentCount = values.get(0).coerceAtLeast(1)
        val remaining = values.limit() - 1
        if (remaining < 3) return 0.0

        var score = 0.0
        var index = 1
        while (index + 2 < values.limit()) {
            val hitsHere = values.get(index).coerceAtLeast(0)
            val documentsWithHits = values.get(index + 2).coerceAtLeast(0)
            if (hitsHere > 0) {
                val rarity = ln((documentCount + 1.0) / (documentsWithHits + 1.0)) + 1.0
                score += hitsHere * rarity
            }
            index += 3
        }
        return score
    }

    private fun snippets(db: SQLiteDatabase, expression: String, rowIds: List<Long>): Map<Long, String> {
        if (rowIds.isEmpty()) return emptyMap()
        val result = HashMap<Long, String>(rowIds.size)
        for (chunk in rowIds.chunked(SNIPPET_QUERY_CHUNK_SIZE)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val sql = """
                SELECT rowid, snippet(page_search, '$MATCH_START', '$MATCH_END', ' … ', 2, -30)
                FROM page_search
                WHERE page_search MATCH ? AND rowid IN ($placeholders)
            """.trimIndent()
            val args = arrayOf(expression, *chunk.map(Long::toString).toTypedArray())
            db.rawQuery(sql, args).use { cursor ->
                while (cursor.moveToNext()) {
                    result[cursor.getLong(0)] = cursor.getString(1)
                        .orEmpty()
                        .replace(Regex("\\s+"), " ")
                        .trim()
                }
            }
        }
        return result
    }
}
