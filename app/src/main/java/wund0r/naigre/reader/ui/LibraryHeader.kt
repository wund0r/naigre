// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.view.View
import android.view.ViewGroup
import kotlin.math.max
import kotlin.math.roundToInt

/** Wraps actions without squeezing the title or shrinking the menu's touch target. */
@SuppressLint("ViewConstructor") // Programmatic-only container; controls are supplied by the caller.
class LibraryHeader(
    context: Context,
    private val menu: View,
    private val title: View,
    private val addFile: View,
    private val addFolder: View,
    private val read: View,
) : ViewGroup(context) {
    private val gap = (6 * resources.displayMetrics.density).roundToInt()
    private val minRowHeight = (48 * resources.displayMetrics.density).roundToInt()
    private var stacked = false
    private var readOnImportRow = false
    private var firstRowHeight = 0

    init {
        listOf(menu, title, addFile, addFolder, read).forEach { addView(it, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val children = listOf(menu, title, addFile, addFolder, read)
        val unspecified = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        children.forEach { it.measure(unspecified, unspecified) }
        val menuSize = MeasureSpec.makeMeasureSpec(minRowHeight, MeasureSpec.EXACTLY)
        menu.measure(menuSize, menuSize)
        val naturalWidth = children.sumOf { it.measuredWidth } + 4 * gap
        val width = resolveSize(naturalWidth, widthMeasureSpec)
        stacked = naturalWidth > width
        readOnImportRow = stacked && menu.measuredWidth + title.measuredWidth + read.measuredWidth + 2 * gap > width
        val titleStart = menu.measuredWidth + gap
        fun measureWidth(view: View, size: Int) = view.measure(
            MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY), unspecified,
        )
        if (stacked) {
            read.measure(MeasureSpec.makeMeasureSpec(width / 3, MeasureSpec.AT_MOST), unspecified)
            measureWidth(title, width - titleStart - if (readOnImportRow) 0 else read.measuredWidth + gap)
            val importsEnd = width - if (readOnImportRow) read.measuredWidth + gap else 0
            val importWidth = (importsEnd - gap) / 2
            measureWidth(addFile, importWidth)
            measureWidth(addFolder, importsEnd - gap - importWidth)
            firstRowHeight = max(minRowHeight, max(title.measuredHeight, if (readOnImportRow) 0 else read.measuredHeight))
        } else {
            measureWidth(title, width - titleStart - addFile.measuredWidth - addFolder.measuredWidth - read.measuredWidth - 3 * gap)
            firstRowHeight = max(minRowHeight, children.maxOf { it.measuredHeight })
        }
        val importHeight = maxOf(minRowHeight, addFile.measuredHeight, addFolder.measuredHeight, if (readOnImportRow) read.measuredHeight else 0)
        listOf(addFile, addFolder, read).forEach { view ->
            val height = if (stacked && (view !== read || readOnImportRow)) importHeight else firstRowHeight
            view.measure(MeasureSpec.makeMeasureSpec(view.measuredWidth, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(width, resolveSize(firstRowHeight + if (stacked) gap + importHeight else 0, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        fun place(view: View, x: Int, y: Int, rowHeight: Int) {
            val centeredTop = y + (rowHeight - view.measuredHeight) / 2
            val start = if (layoutDirection == LAYOUT_DIRECTION_RTL) width - x - view.measuredWidth else x
            view.layout(start, centeredTop, start + view.measuredWidth, centeredTop + view.measuredHeight)
        }
        place(menu, 0, 0, firstRowHeight)
        val titleStart = menu.measuredWidth + gap
        place(title, titleStart, 0, firstRowHeight)
        place(read, width - read.measuredWidth, if (readOnImportRow) firstRowHeight + gap else 0,
            if (readOnImportRow) read.measuredHeight else firstRowHeight)
        if (stacked) {
            place(addFile, 0, firstRowHeight + gap, addFile.measuredHeight)
            place(addFolder, addFile.measuredWidth + gap, firstRowHeight + gap, addFolder.measuredHeight)
        } else {
            place(addFile, titleStart + title.measuredWidth + gap, 0, firstRowHeight)
            place(addFolder, titleStart + title.measuredWidth + addFile.measuredWidth + 2 * gap, 0, firstRowHeight)
        }
    }
}
