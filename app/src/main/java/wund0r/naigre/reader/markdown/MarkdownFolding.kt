// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

data class MarkdownSection(
    val index: Int,
    val title: String,
    val path: String,
    val start: Int,
    val end: Int,
    val stableKey: String,
    val headingLevel: Int = 0,
    val headingEnd: Int = start,
    val bodyStart: Int = end,
    val subtreeEnd: Int = end,
) {
    val canFold: Boolean get() = headingLevel > 0 && bodyStart < subtreeEnd
    val hasSubheadings: Boolean get() = headingLevel > 0 && end < subtreeEnd
}

/** Heading levels may skip numbers. A subtree stops only at a peer or an ancestor. */
internal fun markdownSubtreeBounds(sections: List<MarkdownSection>, documentEnd: Int): List<MarkdownSection> {
    val following = ArrayDeque<MarkdownSection>()
    return sections.asReversed().map { section ->
        while (following.isNotEmpty() && following.last().headingLevel > section.headingLevel) {
            following.removeLast()
        }
        val subtreeEnd = following.lastOrNull()?.start ?: documentEnd
        following.addLast(section)
        section.copy(subtreeEnd = subtreeEnd)
    }.asReversed()
}

enum class MarkdownFoldMode {
    COLLAPSED,
    HEADINGS_ONLY,
    EXPANDED,
}

/** Per-view state: changing a source invalidates folds, but tab switches do not. */
class MarkdownFoldState {
    private var fingerprint: String? = null
    private val modes = mutableMapOf<String, MarkdownFoldMode>()
    val sectionModes: Map<String, MarkdownFoldMode> get() = modes

    fun bind(nextFingerprint: String): Boolean {
        if (fingerprint == nextFingerprint) return false
        fingerprint = nextFingerprint
        modes.clear()
        return true
    }

    /** HEADINGS_ONLY hides this heading's body; descendants have their own modes. */
    private fun mode(section: MarkdownSection): MarkdownFoldMode =
        modes[section.stableKey] ?: MarkdownFoldMode.EXPANDED

    fun displayModes(sections: List<MarkdownSection>): Map<String, MarkdownFoldMode> {
        val result = HashMap<String, MarkdownFoldMode>(sections.size)
        // A visible body or a collapsed branch makes an ancestor's outline mixed.
        // Scanning backwards finds such descendants in linear time, including skipped levels.
        var nextNonOutlineStart = Int.MAX_VALUE
        for (section in sections.asReversed()) {
            val ownMode = mode(section)
            result[section.stableKey] = when (ownMode) {
                MarkdownFoldMode.HEADINGS_ONLY -> when {
                    !section.hasSubheadings -> MarkdownFoldMode.COLLAPSED
                    nextNonOutlineStart < section.subtreeEnd -> MarkdownFoldMode.EXPANDED
                    else -> MarkdownFoldMode.HEADINGS_ONLY
                }
                else -> ownMode
            }
            if (ownMode == MarkdownFoldMode.COLLAPSED && section.hasSubheadings ||
                ownMode == MarkdownFoldMode.EXPANDED && section.bodyStart < section.end) {
                nextNonOutlineStart = section.start
            }
        }
        return result
    }

    fun cycle(section: MarkdownSection, sections: List<MarkdownSection>) {
        if (!section.canFold) return
        val next = when (displayModes(sections).getValue(section.stableKey)) {
            MarkdownFoldMode.COLLAPSED -> if (section.hasSubheadings) MarkdownFoldMode.HEADINGS_ONLY else MarkdownFoldMode.EXPANDED
            MarkdownFoldMode.HEADINGS_ONLY -> MarkdownFoldMode.EXPANDED
            MarkdownFoldMode.EXPANDED -> MarkdownFoldMode.COLLAPSED
        }
        setSubtree(section, sections, next)
    }

    private fun setSubtree(section: MarkdownSection, sections: List<MarkdownSection>, next: MarkdownFoldMode) {
        for (descendant in sections) {
            if (descendant.start < section.start || descendant.start >= section.subtreeEnd) continue
            modes.remove(descendant.stableKey)
            if (next == MarkdownFoldMode.HEADINGS_ONLY && descendant.canFold) {
                modes[descendant.stableKey] = MarkdownFoldMode.HEADINGS_ONLY
            }
        }
        if (next == MarkdownFoldMode.COLLAPSED) modes[section.stableKey] = next
    }

    fun reveal(section: MarkdownSection, sections: List<MarkdownSection>) {
        sections.forEach { ancestor ->
            if (ancestor.start <= section.start && section.start < ancestor.subtreeEnd) {
                if (mode(ancestor) == MarkdownFoldMode.COLLAPSED) {
                    setSubtree(ancestor, sections, MarkdownFoldMode.HEADINGS_ONLY)
                }
                // Reveal the destination body without expanding unrelated branches.
                if (ancestor.stableKey == section.stableKey) modes.remove(ancestor.stableKey)
            }
        }
    }
}

/** Maps immutable document offsets to a display with folded subtrees omitted. */
class MarkdownVisibility(sections: List<MarkdownSection>, sectionModes: Map<String, MarkdownFoldMode>, val sourceLength: Int) {
    data class HiddenRange(val start: Int, val end: Int, val headingStart: Int)

    val hiddenRanges: List<HiddenRange> = buildList {
        var hiddenUntil = -1
        sections.forEach { section ->
            if (section.start < hiddenUntil || !section.canFold) return@forEach
            val hiddenEnd = when (sectionModes[section.stableKey]) {
                MarkdownFoldMode.COLLAPSED -> section.subtreeEnd
                MarkdownFoldMode.HEADINGS_ONLY -> section.end
                else -> section.bodyStart
            }
            if (section.bodyStart < hiddenEnd) {
                add(HiddenRange(section.bodyStart, hiddenEnd, section.start))
                hiddenUntil = hiddenEnd
            }
        }
    }
    val visibleLength: Int = sourceLength - hiddenRanges.sumOf { it.end - it.start }

    fun isVisible(offset: Int): Boolean = hiddenRanges.none { offset >= it.start && offset < it.end }

    fun toVisible(sourceOffset: Int): Int {
        val offset = sourceOffset.coerceIn(0, sourceLength)
        var removed = 0
        for (range in hiddenRanges) {
            if (offset < range.start) break
            if (offset < range.end) return range.headingStart - removed
            removed += range.end - range.start
        }
        return offset - removed
    }

    fun toSource(visibleOffset: Int): Int {
        val offset = visibleOffset.coerceIn(0, visibleLength)
        var removed = 0
        for (range in hiddenRanges) {
            if (offset < range.start - removed) break
            removed += range.end - range.start
        }
        return (offset + removed).coerceAtMost(sourceLength)
    }
}
