// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.content.ContentResolver
import android.net.Uri
import android.text.Spanned
import android.widget.TextView
import io.noties.markwon.Markwon
import io.noties.markwon.core.spans.HeadingSpan
import io.noties.markwon.ext.tables.TablePlugin
import org.commonmark.node.Code
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.Text
import wund0r.naigre.reader.pdf.PdfAnnotationInfo
import wund0r.naigre.reader.pdf.PdfDocument
import wund0r.naigre.reader.pdf.PdfOutlineEntry
import wund0r.naigre.reader.pdf.PdfRect
import wund0r.naigre.reader.pdf.RenderedPdfPage
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.Locale

data class MarkdownSection(
    val index: Int,
    val title: String,
    val path: String,
    val start: Int,
    val end: Int,
    val stableKey: String,
)

data class MarkdownContent(
    val rendered: Spanned,
    val plainText: String,
    val sections: List<MarkdownSection>,
    val searchTextBySection: List<String>,
    val fingerprint: String,
)

/** Keeps the rendering library behind one small boundary. */
class MarkdownEngine(context: android.content.Context) {
    private val markwon = Markwon.builder(context.applicationContext)
        .usePlugin(TablePlugin.create(context.applicationContext))
        .build()

    @Synchronized
    fun parse(source: String): MarkdownContent {
        val root = markwon.parse(source)
        val rendered = markwon.render(root)
        val plainText = rendered.toString()
        val headingSpans = rendered.getSpans(0, rendered.length, HeadingSpan::class.java)
            .map { span ->
                Triple(
                    rendered.getSpanStart(span).coerceAtLeast(0),
                    rendered.getSpanEnd(span).coerceAtLeast(0),
                    span.level.coerceIn(1, 6),
                )
            }
            .filter { (start, end) -> end > start }
            .sortedBy { it.first }

        val provisional = mutableListOf<MarkdownSection>()
        val pathLevels = arrayOfNulls<String>(6)
        val keyCounts = HashMap<String, Int>()
        if (headingSpans.isEmpty()) {
            provisional += MarkdownSection(0, "Start", "Start", 0, plainText.length, "markdown:start")
        } else {
            val firstStart = headingSpans.first().first
            if (plainText.substring(0, firstStart).isNotBlank()) {
                provisional += MarkdownSection(0, "Start", "Start", 0, firstStart, "markdown:start")
            }
            headingSpans.forEachIndexed { headingIndex, (start, end, level) ->
                val title = plainText.substring(start, end).trim().ifEmpty { "Untitled heading" }
                pathLevels[level - 1] = title
                for (index in level until pathLevels.size) pathLevels[index] = null
                val path = pathLevels.take(level).filterNotNull().joinToString(" › ").ifEmpty { title }
                val baseKey = path.lowercase(Locale.ROOT)
                    .replace(Regex("[^\\p{L}\\p{N}]+"), "-")
                    .trim('-')
                    .ifEmpty { "heading" }
                val occurrence = (keyCounts[baseKey] ?: 0) + 1
                keyCounts[baseKey] = occurrence
                provisional += MarkdownSection(
                    index = provisional.size,
                    title = title,
                    path = path,
                    start = start,
                    end = headingSpans.getOrNull(headingIndex + 1)?.first ?: plainText.length,
                    stableKey = "markdown:$baseKey#$occurrence",
                )
            }
        }
        val sections = provisional.mapIndexed { index, section -> section.copy(index = index) }
        val searchTextBySection = extractSearchText(root, sections)
        return MarkdownContent(rendered, plainText, sections, searchTextBySection, sha256(source))
    }

    fun apply(textView: TextView, rendered: Spanned) {
        markwon.setParsedMarkdown(textView, rendered)
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private fun extractSearchText(root: Node, sections: List<MarkdownSection>): List<String> {
        val builders = List(sections.size) { StringBuilder() }
        val hasPreamble = sections.firstOrNull()?.stableKey == "markdown:start"
        var currentSection = if (hasPreamble) 0 else -1
        var headingIndex = 0

        fun append(value: String) {
            if (currentSection in builders.indices && value.isNotBlank()) {
                builders[currentSection].append(value).append(' ')
            }
        }

        fun visit(node: Node) {
            if (node is Heading) {
                currentSection = (if (hasPreamble) 1 else 0) + headingIndex
                headingIndex++
            }
            when (node) {
                is Text -> append(node.literal)
                is Code -> append(node.literal)
                is FencedCodeBlock -> append(node.literal)
                is IndentedCodeBlock -> append(node.literal)
                is SoftLineBreak, is HardLineBreak -> append("\n")
            }
            var child = node.firstChild
            while (child != null) {
                val next = child.next
                visit(child)
                child = next
            }
        }
        visit(root)
        return builders.map { it.toString().replace(Regex("\\s+"), " ").trim() }
    }
}

class MarkdownDocument(
    contentResolver: ContentResolver,
    uri: Uri,
    engine: MarkdownEngine,
) : PdfDocument {
    companion object {
        private const val MAX_NOTE_BYTES = 8 * 1024 * 1024
    }

    val content: MarkdownContent

    init {
        val source = contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Could not open Markdown note" }
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_NOTE_BYTES) { "Markdown note is larger than 8 MB" }
                output.write(buffer, 0, count)
            }
            output.toString(Charsets.UTF_8.name())
        }
        content = engine.parse(source)
    }

    override val pageCount: Int
        get() = content.sections.size

    override fun renderPage(pageIndex: Int, targetWidthPx: Int): RenderedPdfPage =
        error("Markdown is rendered as a continuous native document")

    override fun annotations(pageIndex: Int): List<PdfAnnotationInfo> = emptyList()

    override fun textInRect(pageIndex: Int, bounds: PdfRect): String? = null

    override fun textForSearch(pageIndex: Int): String {
        return content.searchTextBySection[pageIndex.coerceIn(0, content.searchTextBySection.lastIndex)]
    }

    override fun searchText(pageIndex: Int, terms: List<String>, caseSensitive: Boolean): List<PdfRect> =
        emptyList()

    override fun outline(): List<PdfOutlineEntry> = content.sections
        .filterNot { it.stableKey == "markdown:start" }
        .map { section ->
            PdfOutlineEntry(
                title = section.title,
                pageIndex = section.index,
                targetY = null,
                destinationKey = section.stableKey,
                path = section.path,
            )
        }

    override fun close() = Unit
}
