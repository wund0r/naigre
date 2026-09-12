// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.table

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BookLibraryRepositoryTest {
    private lateinit var directory: File
    private lateinit var catalog: File
    private lateinit var indexDirectory: File
    private lateinit var repository: BookLibraryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        directory = File(context.cacheDir, "library-test-${UUID.randomUUID()}").apply { mkdirs() }
        catalog = File(directory, "catalog.json")
        indexDirectory = File(directory, "indexes")
        repository = BookLibraryRepository(catalog, indexDirectory)
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun missingAndMalformedCatalogsRemainDistinctAndMalformedBytesSurvive() {
        assertTrue(repository.readCatalog() is BookCatalogState.Missing)

        val malformed = "{ definitely-not-json"
        catalog.writeText(malformed)
        val result = repository.readCatalog()

        assertTrue(result is BookCatalogState.Failed)
        assertEquals(CatalogFailureKind.INVALID, (result as BookCatalogState.Failed).failure.kind)
        assertEquals(malformed, catalog.readText())

        val truncated = """{"version":$CURRENT_LIBRARY_CATALOG_VERSION}"""
        catalog.writeText(truncated)
        val truncatedResult = repository.readCatalog()
        assertTrue(truncatedResult is BookCatalogState.Failed)
        assertEquals(truncated, catalog.readText())
    }

    @Test
    fun unsupportedCatalogVersionIsRecoverableInsteadOfLoadedAsEmpty() {
        catalog.writeText("""{"version":999,"books":[],"selected":[]}""")

        val result = repository.readCatalog()

        assertTrue(result is BookCatalogState.Failed)
        assertEquals(
            CatalogFailureKind.UNSUPPORTED_VERSION,
            (result as BookCatalogState.Failed).failure.kind,
        )
        assertTrue(catalog.isFile)
    }

    @Test
    fun corruptBookIndexDoesNotPreventOtherCatalogItemsFromLoading() {
        val first = book("first")
        val second = book("second")
        repository.saveIndex(first)
        repository.saveIndex(second)
        repository.saveCatalog(
            BookLibraryState(
                books = listOf(first, second),
                selectedBookIds = linkedSetOf(first.id, second.id),
                folders = emptyList(),
                tags = emptyList(),
            ),
        )
        File(indexDirectory, "${first.id}.json").writeText("not-json")

        val catalogResult = repository.readCatalog() as BookCatalogState.Loaded
        val summaries = catalogResult.library.books

        assertEquals(2, summaries.size)
        assertTrue(summaries.none { it.indexLoaded })
        assertTrue(repository.readIndex(summaries.first { it.id == first.id }) is BookIndexLoadResult.Failed)
        assertTrue(repository.readIndex(summaries.first { it.id == second.id }) is BookIndexLoadResult.Loaded)
    }

    private fun book(id: String) = BookRecord(
        id = id,
        title = id,
        uri = "content://$id",
        color = 0x112233,
        pageCount = 10,
        pdfBookmarks = emptyList(),
    )
}
