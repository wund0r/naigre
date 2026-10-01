// SPDX-License-Identifier: AGPL-3.0-or-later
package wund0r.naigre.reader.ui

import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Heading
import org.commonmark.node.Node
import org.commonmark.parser.Parser
import org.junit.Assert.*
import org.junit.Test
import wund0r.naigre.reader.navigation.ExternalTocParser
import java.io.File

class QuickstartContentTest {
    private val resources = listOf(File("src/main/res"), File("app/src/main/res"))
        .first { File(it, "raw/quickstart.md").isFile }
    private val guides = listOf("raw", "raw-ru").map { File(resources, "$it/quickstart.md") }

    private fun nodes(node: Node): Sequence<Node> = sequence {
        yield(node)
        var child = node.firstChild
        while (child != null) {
            yieldAll(nodes(child))
            child = child.next
        }
    }

    @Test
    fun bothBundledGuidesHaveNavigableHeadings() {
        guides.forEach { guide ->
            val source = guide.readText()
            assertTrue(guide.path, source.isNotBlank())
            assertTrue(guide.path, nodes(Parser.builder().build().parse(source)).any { it is Heading })
        }
    }

    @Test
    fun tocExamplesMatchTheActualImporter() {
        guides.forEach { guide ->
            val examples = nodes(Parser.builder().build().parse(guide.readText()))
                .filterIsInstance<FencedCodeBlock>().filter { it.info == "text" }.toList()
            assertTrue(guide.path, examples.isNotEmpty())
            examples.forEach { example ->
                val parsed = ExternalTocParser.parse(example.literal, 1000, "help-example")
                assertEquals(guide.path, 0, parsed.rejectedLines)
                assertTrue(guide.path, parsed.entries.isNotEmpty())
            }
        }
    }
}
