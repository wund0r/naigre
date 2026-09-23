// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

/** Persist semantics, not translated text. TEXT also preserves labels from older releases. */
enum class TabLabelKind { TEXT, START, PAGE, TEXT_WITH_PAGE }

data class TabTitle(
    val text: String = "",
    val kind: TabLabelKind = TabLabelKind.TEXT,
    val pageIndex: Int = 0,
)

data class ReaderTab(
    var bookId: String,
    var pageIndex: Int,
    var label: String,
    var anchorKey: String? = null,
    var originPageIndex: Int = pageIndex,
    var labelKind: TabLabelKind = TabLabelKind.TEXT,
    var labelPageIndex: Int = originPageIndex,
) {
    fun setTitle(title: TabTitle) {
        label = title.text
        labelKind = title.kind
        labelPageIndex = title.pageIndex
    }

    fun setTextTitle(text: String) = setTitle(TabTitle(text))

    companion object {
        fun start(bookId: String) = ReaderTab(bookId, 0, "", labelKind = TabLabelKind.START)
    }
}
