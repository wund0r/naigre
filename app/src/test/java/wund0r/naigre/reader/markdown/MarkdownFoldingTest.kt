// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wund0r.naigre.reader.markdown.MarkdownFoldMode.COLLAPSED
import wund0r.naigre.reader.markdown.MarkdownFoldMode.EXPANDED
import wund0r.naigre.reader.markdown.MarkdownFoldMode.HEADINGS_ONLY

class MarkdownFoldingTest {
    private val parent = section(0, 0, 20, 8, 80, 1)
    private val child = section(1, 20, 34, 28, 45, 2)
    private val grandchild = section(2, 34, 45, 40, 45, 3)
    private val sibling = section(3, 45, 80, 53, 80, 2)
    private val next = section(4, 80, 100, 88, 100, 1)
    private val sections = listOf(parent, child, grandchild, sibling, next)

    @Test
    fun subtreeBoundariesHandleSkippedLevelsAndPreambleWithoutChangingSearchSections() {
        val input = listOf(0, 1, 3, 4, 2, 1, 6).mapIndexed { index, level ->
            section(index, index * 10, (index + 1) * 10, index * 10 + 5, (index + 1) * 10, level)
        }
        val output = markdownSubtreeBounds(input, 70)
        assertEquals(listOf(70, 50, 40, 40, 50, 70, 70), output.map { it.subtreeEnd })
        assertEquals(input.map { it.end }, output.map { it.end })
        assertEquals(input.map { it.stableKey }, output.map { it.stableKey })
    }

    @Test
    fun foldingParentHidesDescendantsButKeepsNextPeerAndMapsItsOffsets() {
        val map = MarkdownVisibility(sections, mapOf(parent.stableKey to COLLAPSED, child.stableKey to COLLAPSED), 100)
        assertEquals(listOf(MarkdownVisibility.HiddenRange(8, 80, 0)), map.hiddenRanges)
        assertEquals(28, map.visibleLength)
        assertTrue(map.isVisible(parent.start))
        assertFalse(map.isVisible(child.start))
        assertTrue(map.isVisible(next.start))
        assertEquals(8, map.toVisible(next.start))
        assertEquals(next.start, map.toSource(8))
        assertEquals(0, map.toVisible(40)) // A hidden viewport falls back to its visible parent.
    }

    @Test
    fun visibleOffsetsRoundTripAcrossMultipleFoldedSectionsAndDocumentEnd() {
        val map = MarkdownVisibility(sections, mapOf(child.stableKey to COLLAPSED, next.stableKey to COLLAPSED), 100)
        for (source in 0..100) {
            if (map.isVisible(source)) assertEquals("source $source", source, map.toSource(map.toVisible(source)))
        }
        assertEquals(100, map.toSource(map.visibleLength))
    }

    @Test
    fun parentCyclesThroughClosedAllDescendantHeadingsAndFullSubtree() {
        val folds = MarkdownFoldState()
        folds.cycle(parent, sections)
        assertEquals(COLLAPSED, folds.displayModes(sections)[parent.stableKey])
        folds.cycle(parent, sections)
        assertEquals(HEADINGS_ONLY, folds.displayModes(sections)[parent.stableKey])
        val outline = MarkdownVisibility(sections, folds.sectionModes, 100)
        sections.forEach { assertTrue("Heading ${it.title}", outline.isVisible(it.start)) }
        sections.dropLast(1).forEach { assertFalse("Body ${it.title}", outline.isVisible(it.bodyStart)) }
        assertTrue(outline.isVisible(next.bodyStart))
        for (source in 0..100) {
            if (outline.isVisible(source)) assertEquals(source, outline.toSource(outline.toVisible(source)))
        }
        assertEquals(outline.toVisible(child.start), outline.toVisible(child.bodyStart))
        folds.cycle(parent, sections)
        assertEquals(EXPANDED, folds.displayModes(sections)[parent.stableKey])
        assertTrue(MarkdownVisibility(sections, folds.sectionModes, 100).hiddenRanges.isEmpty())
        folds.cycle(parent, sections)
        assertEquals(COLLAPSED, folds.displayModes(sections)[parent.stableKey])
        assertFalse(MarkdownVisibility(sections, folds.sectionModes, 100).isVisible(grandchild.start))
    }

    @Test
    fun revealingOneGrandchildMakesParentCollapseAndNextOutlineHidesItsBodyAgain() {
        val folds = outline()
        folds.cycle(grandchild, sections)
        assertTrue(MarkdownVisibility(sections, folds.sectionModes, 100).isVisible(grandchild.bodyStart))
        assertEquals(EXPANDED, folds.displayModes(sections)[parent.stableKey])
        assertEquals(EXPANDED, folds.displayModes(sections)[child.stableKey])
        folds.cycle(parent, sections)
        assertEquals(COLLAPSED, folds.displayModes(sections)[parent.stableKey])
        folds.cycle(parent, sections)
        assertEquals(HEADINGS_ONLY, folds.displayModes(sections)[parent.stableKey])
        assertFalse(MarkdownVisibility(sections, folds.sectionModes, 100).isVisible(grandchild.bodyStart))
        folds.cycle(parent, sections)
        assertTrue(MarkdownVisibility(sections, folds.sectionModes, 100).hiddenRanges.isEmpty())
    }

    @Test
    fun leafSkipsOutlineAndClosingItRestoresParentsHeadingsOnlyAppearance() {
        val folds = outline()
        assertEquals(COLLAPSED, folds.displayModes(sections)[grandchild.stableKey])
        folds.cycle(grandchild, sections)
        assertEquals(EXPANDED, folds.displayModes(sections)[grandchild.stableKey])
        folds.cycle(grandchild, sections)
        assertEquals(COLLAPSED, folds.displayModes(sections)[grandchild.stableKey])
        assertEquals(HEADINGS_ONLY, folds.displayModes(sections)[parent.stableKey])
        folds.cycle(parent, sections)
        assertEquals(EXPANDED, folds.displayModes(sections)[grandchild.stableKey])
        assertTrue(MarkdownVisibility(sections, folds.sectionModes, 100).hiddenRanges.isEmpty())
    }

    @Test
    fun collapsingBranchWithinOutlineMakesNextParentTapCollapse() {
        val folds = outline()
        folds.cycle(child, sections) // Show its body and all descendants.
        folds.cycle(child, sections) // Hide its descendants as well as its body.
        assertEquals(EXPANDED, folds.displayModes(sections)[parent.stableKey])
        folds.cycle(parent, sections)
        assertEquals(COLLAPSED, folds.displayModes(sections)[parent.stableKey])
    }

    @Test
    fun navigationRevealsTargetAndAncestorsButLeavesUnrelatedFolds() {
        val folds = MarkdownFoldState()
        folds.bind("v1")
        folds.cycle(parent, sections)
        folds.cycle(next, sections)
        folds.reveal(grandchild, sections)
        val map = MarkdownVisibility(sections, folds.sectionModes, 100)
        assertTrue(map.isVisible(grandchild.start))
        assertTrue(map.isVisible(grandchild.bodyStart))
        assertFalse(map.isVisible(parent.bodyStart))
        assertFalse(map.isVisible(sibling.bodyStart))
        assertFalse(map.isVisible(next.bodyStart))
        assertEquals(EXPANDED, folds.displayModes(sections)[parent.stableKey])
        folds.cycle(parent, sections)
        assertFalse(MarkdownVisibility(sections, folds.sectionModes, 100).isVisible(grandchild.start))
    }

    @Test
    fun sourceChangeResetsFoldsButRebindingSameSourceAndOtherViewsDoNot() {
        val folds = MarkdownFoldState()
        val otherView = MarkdownFoldState()
        assertTrue(folds.bind("v1"))
        folds.cycle(parent, sections)
        otherView.bind("v1")
        assertFalse(folds.bind("v1"))
        assertEquals(mapOf(parent.stableKey to COLLAPSED), folds.sectionModes)
        assertTrue(otherView.sectionModes.isEmpty())
        assertTrue(folds.bind("v2"))
        assertTrue(folds.sectionModes.isEmpty())
    }

    @Test
    fun emptySectionsAndPreamblesCannotFold() {
        val preamble = section(0, 0, 10, 0, 100, 0)
        val empty = section(1, 10, 20, 20, 20, 1)
        val folds = MarkdownFoldState()
        val sections = listOf(preamble, empty)
        folds.cycle(preamble, sections)
        folds.cycle(empty, sections)
        assertTrue(folds.sectionModes.isEmpty())
        val map = MarkdownVisibility(sections, mapOf(preamble.stableKey to COLLAPSED, empty.stableKey to COLLAPSED), 100)
        assertTrue(map.hiddenRanges.isEmpty())
        assertEquals(20, map.toSource(map.toVisible(20)))
    }

    private fun outline() = MarkdownFoldState().apply {
        cycle(parent, sections)
        cycle(parent, sections)
    }

    private fun section(index: Int, start: Int, end: Int, body: Int, subtree: Int, level: Int) = MarkdownSection(
        index, "Heading $index", "Heading $index", start, end, "heading:$index",
        headingLevel = level, headingEnd = body - 2, bodyStart = body, subtreeEnd = subtree,
    )
}
