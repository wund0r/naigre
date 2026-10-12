// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import io.noties.markwon.inlineparser.InlineProcessor
import io.noties.markwon.inlineparser.MarkwonInlineParser
import org.commonmark.node.CustomNode
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.InlineParserFactory

class WikiLinkNode(val spec: WikiLinkSpec) : CustomNode()

/** Registered before the ordinary bracket processor; backticks and escapes retain their meaning. */
fun wikiLinkInlineParser(): InlineParserFactory = MarkwonInlineParser.factoryBuilderNoDefaults()
    .apply { addInlineProcessor(object : InlineProcessor() {
        override fun specialCharacter(): Char = '['

        override fun parse(): Node? {
            if (!input.startsWith("[[", index) || lastBracket() != null) return null
            var end = index + 2
            // Bound an unsuccessful scan and stop at another opener or a line break.
            val limit = minOf(input.length - 1, index + 4096)
            while (end < limit && input[end] != ']') {
                if (input[end] in "[\r\n") return null
                end++
            }
            if (end >= limit || !input.startsWith("]]", end)) return null
            val spec = WikiLinkSpec.parse(input.substring(index + 2, end).replace("\\|", "|")) ?: return null
            index = end + 2
            return WikiLinkNode(spec).apply { appendChild(Text(spec.label)) }
        }
    }) }
    .includeDefaults()
    .build()
