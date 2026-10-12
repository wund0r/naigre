// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test
import wund0r.naigre.reader.table.BookRecord
import wund0r.naigre.reader.table.ImageFileRecord
import wund0r.naigre.reader.table.LibraryItemKind
import wund0r.naigre.reader.table.sourceRevisionKey

class WikiLinkTest {
    private fun parse(source: String): Node = Parser.builder()
        .extensions(listOf(TablesExtension.create()))
        .inlineParserFactory(wikiLinkInlineParser()).build().parse(source)

    private fun nodes(root: Node): List<Node> = buildList {
        fun visit(node: Node) {
            add(node)
            var child = node.firstChild
            while (child != null) { visit(child); child = child.next }
        }
        visit(root)
    }

    private fun book(id: String, name: String = "downtime.pdf", path: String? = null) = BookRecord(
        id = id, title = name, fileName = name, uri = "content://$id", color = 0,
        pageCount = 20, pdfBookmarks = emptyList(), sourceRelativePath = path,
    )

    private val firstImage = ImageFileRecord("content://image/3", "level 3.jpg", "level 3.jpg")
    private val secondImage = ImageFileRecord("content://image/4", "level 4.jpg", "level 4.jpg")
    private fun album(id: String = "maps") = book(id, "maps").copy(
        kind = LibraryItemKind.IMAGE_COLLECTION, pageCount = 2,
        imageFiles = listOf(firstImage, secondImage), sourceRelativePath = "maps",
    )

    @Test fun parserUsesAliasesAndBasenamesAndKeepsOrdinaryMarkdown() {
        val all = nodes(parse("**[[downtime.pdf#page=12|Pazuzu]]** and [[maps/level 3.jpg]] and [web](https://example.org)"))
        val links = all.filterIsInstance<WikiLinkNode>()
        assertEquals(listOf("Pazuzu", "level 3.jpg"), links.map { it.spec.label })
        assertEquals(12, links.first().spec.pageNumber)
        assertEquals(1, all.filterIsInstance<Link>().size)
        assertEquals("Pazuzu", (links.first().firstChild as Text).literal)
    }

    @Test fun codeEscapesAndIncompleteLinksRemainLiteral() {
        val all = nodes(parse("`[[downtime.pdf]]`\n\n```text\n[[maps/a.jpg]]\n```\n\n\\[[downtime.pdf]] and [[unfinished"))
        assertTrue(all.none { it is WikiLinkNode })
        assertEquals("[[downtime.pdf]]", all.filterIsInstance<Code>().single().literal)
        assertTrue(all.filterIsInstance<FencedCodeBlock>().single().literal.contains("[[maps/a.jpg]]"))
    }

    @Test fun escapedAliasSeparatorWorksInsideTables() {
        val all = nodes(parse("| Destination |\n| --- |\n| [[downtime.pdf#page=12\\|Pazuzu]] |"))
        assertEquals("Pazuzu", all.filterIsInstance<WikiLinkNode>().single().spec.label)
    }

    @Test fun invalidTargetsStillHaveVisibleLabels() {
        val link = nodes(parse("[[downtime.pdf#page=0|Bad page]]")).filterIsInstance<WikiLinkNode>().single()
        assertEquals("Bad page", link.spec.label)
        assertFalse(link.spec.valid)
        listOf("downtime.pdf#page=999999999999", "map.jpg#page=2", "../downtime.pdf", "https://example.org/a.pdf", "notes.exe")
            .forEach { assertFalse(it, WikiLinkSpec(it).valid) }
    }

    @Test fun tableMatchWinsOverSameNamedLibraryItems() {
        assertFalse(WikiLinkResolver.mayContain(WikiLinkSpec("downtime.pdf"), album()))
        val result = WikiLinkResolver.resolve(WikiLinkSpec("DOWNTIME.PDF"), listOf(book("library"), book("table")), setOf("table"))
        assertEquals(WikiLinkResolution.Matches(listOf(WikiLinkDestination("table")), true), result)
    }

    @Test fun libraryMatchRequiresTableAdditionAndMissingTargetsStayMissing() {
        assertEquals(WikiLinkResolution.Matches(listOf(WikiLinkDestination("library")), false),
            WikiLinkResolver.resolve(WikiLinkSpec("downtime.pdf"), listOf(book("library")), emptySet()))
        assertEquals(WikiLinkResolution.Missing, WikiLinkResolver.resolve(WikiLinkSpec("missing.pdf"), listOf(book("library")), emptySet()))
    }

    @Test fun bareDuplicateNamesAreAmbiguousButQualifiedPathsDisambiguate() {
        val books = listOf(book("a", path = "rules/downtime.pdf"), book("b", path = "other/downtime.pdf"))
        val bare = WikiLinkResolver.resolve(WikiLinkSpec("downtime.pdf"), books, setOf("a", "b")) as WikiLinkResolution.Matches
        assertEquals(2, bare.destinations.size)
        assertEquals(WikiLinkResolution.Matches(listOf(WikiLinkDestination("a")), true),
            WikiLinkResolver.resolve(WikiLinkSpec("./Rules/Downtime.pdf"), books, setOf("a", "b")))
        assertEquals(WikiLinkResolution.Missing,
            WikiLinkResolver.resolve(WikiLinkSpec("wrong/downtime.pdf"), books, setOf("a", "b")))
    }

    @Test fun imagesResolveWithinAlbumsWithSpacesAndUnicode() {
        val maps = album()
        val result = WikiLinkResolver.resolve(WikiLinkSpec("maps/LEVEL 3.JPG"), listOf(maps), setOf(maps.id))
        assertEquals(WikiLinkResolution.Matches(listOf(WikiLinkDestination(maps.id, firstImage.uri)), true), result)
        val cyrillic = firstImage.copy(fileName = "Уровень 3.jpg", relativePath = "Уровень 3.jpg")
        val russian = maps.copy(imageFiles = listOf(cyrillic), pageCount = 1)
        assertTrue(WikiLinkResolver.resolve(WikiLinkSpec("maps/уровень 3.jpg"), listOf(russian), setOf(russian.id)) is WikiLinkResolution.Matches)
    }

    @Test fun imageIdentitySurvivesReorderingAndAliases() {
        val maps = album()
        val destination = WikiLinkDestination(maps.id, firstImage.uri)
        val first = checkNotNull(destination.entry(maps, WikiLinkSpec("maps/level 3.jpg")))
        val moved = checkNotNull(destination.entry(maps.copy(imageFiles = listOf(secondImage, firstImage)), WikiLinkSpec("maps/level 3.jpg", "Dungeon")))
        assertEquals(0, first.pageIndex)
        assertEquals(1, moved.pageIndex)
        assertEquals(first.identityKey, moved.identityKey)
        assertEquals("Dungeon", moved.title)
        assertNull(destination.entry(maps.copy(imageFiles = listOf(secondImage)), WikiLinkSpec("maps/level 3.jpg")))
    }

    @Test fun pageNumbersAreOneBasedValidatedAndIndependentOfAliases() {
        val pdf = book("pdf")
        val destination = WikiLinkDestination(pdf.id)
        val first = checkNotNull(destination.entry(pdf, WikiLinkSpec("downtime.pdf#page=12", "Pazuzu")))
        val other = checkNotNull(destination.entry(pdf, WikiLinkSpec("downtime.pdf#page=12", "Another label")))
        assertEquals(11, first.pageIndex)
        assertEquals(first.identityKey, other.identityKey)
        assertNull(destination.entry(pdf, WikiLinkSpec("downtime.pdf#page=21")))
        assertEquals(0, destination.entry(pdf, WikiLinkSpec("downtime.pdf"))?.pageIndex)
        assertEquals(destination.entry(pdf, WikiLinkSpec("downtime.pdf"))?.identityKey,
            destination.entry(pdf, WikiLinkSpec("downtime.pdf#page=1"))?.identityKey)
    }

    @Test fun contentVersionInvalidatesMarkdownCachesWithoutReindexingPdfs() {
        val legacy = book("note", "notes.md").copy(kind = LibraryItemKind.MARKDOWN, markdownContentVersion = 0)
        assertNotEquals(legacy.sourceRevisionKey(), legacy.copy(markdownContentVersion = MARKDOWN_CONTENT_VERSION).sourceRevisionKey())
        val pdf = book("pdf").copy(markdownContentVersion = 0)
        assertEquals(pdf.sourceRevisionKey(), pdf.copy(markdownContentVersion = MARKDOWN_CONTENT_VERSION).sourceRevisionKey())
    }
}
