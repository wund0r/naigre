// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import wund0r.naigre.reader.R
import wund0r.naigre.reader.navigation.ClosedReaderTab
import wund0r.naigre.reader.navigation.RecentSearch
import wund0r.naigre.reader.theme.UiPalette
import kotlin.math.roundToInt

enum class HistoryTabAction { CURRENT, NEW_TAB, BACKGROUND, REFERENCE }
data class SearchHistoryItem(val search: RecentSearch, val detail: String)
data class ClosedTabHistoryItem(val entry: ClosedReaderTab, val title: String, val detail: String, val color: Int)

/** A fixed 40/60 search/tab split, including on phones and with larger fonts. */
@SuppressLint("ViewConstructor") // Programmatic ListView header with an explicit reader palette.
class SearchHistoryView(context: Context, private val palette: UiPalette) : LinearLayout(context) {
    private val searchesColumn = LinearLayout(context).apply { orientation = VERTICAL; tag = "recent-searches-column" }
    private val tabsColumn = LinearLayout(context).apply { orientation = VERTICAL; tag = "recent-tabs-column" }
    private val split = LinearLayout(context).apply { orientation = HORIZONTAL; tag = "search-history-split" }
    private val moreButton = SearchResultRow.actionButton(context, palette).apply {
        tag = "more-history"
        textSize = 13f
        minHeight = dp(48)
        minimumHeight = dp(48)
        setPadding(dp(16), 0, dp(16), 0)
        visibility = View.GONE
        setOnClickListener { expanded = !expanded; render() }
    }
    private var searches: List<SearchHistoryItem> = emptyList()
    private var tabs: List<ClosedTabHistoryItem> = emptyList()
    private var expanded = false
    private var referenceIcon = "◨"
    var onSearch: (RecentSearch) -> Unit = {}
    var onTab: (ClosedReaderTab, HistoryTabAction) -> Unit = { _, _ -> }
    var onClearSearches: () -> Unit = {}
    var onClearTabs: () -> Unit = {}

    init {
        orientation = VERTICAL
        gravity = Gravity.TOP
        tag = "search-history"
        split.addView(searchesColumn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        split.addView(View(context).apply { setBackgroundColor(palette.divider) }, LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply {
            marginStart = dp(8); marginEnd = dp(8)
        })
        split.addView(tabsColumn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 3f))
        addView(split, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(moreButton, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
    }

    fun showEntries(searches: List<SearchHistoryItem>, tabs: List<ClosedTabHistoryItem>, referenceIcon: String) {
        this.searches = searches
        this.tabs = tabs
        this.referenceIcon = referenceIcon
        if (!hasMore()) expanded = false
        render()
    }

    private fun render() {
        searchesColumn.removeAllViews()
        tabsColumn.removeAllViews()
        heading(searchesColumn, R.string.recent_searches, R.string.clear_recent_searches, onClearSearches)
        heading(tabsColumn, R.string.recently_closed_tabs, R.string.clear_closed_tabs, onClearTabs)
        val limit = if (expanded) EXPANDED_LIMIT else COLLAPSED_LIMIT
        val shownSearches = searches.take(limit)
        val shownTabs = tabs.take(limit)
        shownSearches.forEachIndexed { index, item ->
            val row = SearchResultRow(context, palette, compact = true, showColorDot = false, showActions = false).apply {
                title.text = item.search.query
                detail.text = item.detail
                stripe(index)
                contentDescription = context.getString(R.string.repeat_search_description, item.search.query, item.detail)
                setOnClickListener { onSearch(item.search) }
            }
            addRow(searchesColumn, row, index, shownSearches.size)
        }
        shownTabs.forEachIndexed { index, item ->
            val row = SearchResultRow(context, palette, compact = true).apply {
                title.text = item.title
                detail.text = item.detail
                setBookColor(item.color)
                stripe(index)
                contentDescription = context.getString(R.string.restore_recent_tab_description, item.title)
                setOnClickListener { onTab(item.entry, HistoryTabAction.CURRENT) }
                plus.contentDescription = context.getString(R.string.open_new_tab_description, item.title)
                plus.setOnClickListener { onTab(item.entry, HistoryTabAction.NEW_TAB) }
                plus.setOnLongClickListener { onTab(item.entry, HistoryTabAction.BACKGROUND); true }
                alongside.text = referenceIcon
                alongside.contentDescription = context.getString(R.string.open_reference_description, item.title)
                alongside.setOnClickListener { onTab(item.entry, HistoryTabAction.REFERENCE) }
            }
            addRow(tabsColumn, row, index, shownTabs.size)
        }
        if (searches.isEmpty()) searchesColumn.addView(label(context.getString(R.string.no_recent_searches), 12f))
        if (tabs.isEmpty()) tabsColumn.addView(label(context.getString(R.string.no_closed_tabs), 12f))
        moreButton.visibility = if (hasMore()) View.VISIBLE else View.GONE
        moreButton.text = context.getString(if (expanded) R.string.less_history else R.string.more_history)
        moreButton.contentDescription = moreButton.text
    }

    private fun addRow(column: LinearLayout, row: SearchResultRow, index: Int, count: Int) {
        column.addView(row, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, row.compactHeight))
        if (index < count - 1) {
            column.addView(View(context).apply { background = ColorDrawable(palette.divider) },
                LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
        }
    }

    private fun heading(column: LinearLayout, title: Int, clear: Int, onClear: () -> Unit) {
        val heading = label(context.getString(R.string.history_heading, context.getString(title)), 14f, true).apply {
            gravity = Gravity.CENTER_VERTICAL
            setTextColor(palette.accent)
            isFocusable = true
            setOnClickListener {
                PopupMenu(context, this).apply {
                    menu.add(clear)
                    setOnMenuItemClickListener { onClear(); true }
                    show()
                }
            }
        }
        val height = maxOf(dp(48), (heading.paint.fontMetricsInt.bottom - heading.paint.fontMetricsInt.top) * 2 + dp(6))
        column.addView(heading, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height))
    }

    private fun label(text: String, size: Float, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        maxLines = 2
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (bold) palette.textPrimary else palette.textSecondary)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(2), dp(3), dp(2), dp(3))
    }

    private fun hasMore() = searches.size > COLLAPSED_LIMIT || tabs.size > COLLAPSED_LIMIT
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private companion object {
        const val COLLAPSED_LIMIT = 3
        const val EXPANDED_LIMIT = 10
    }
}
