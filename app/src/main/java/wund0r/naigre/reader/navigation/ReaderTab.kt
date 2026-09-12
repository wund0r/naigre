// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

data class ReaderTab(
    var bookId: String,
    var pageIndex: Int,
    var label: String,
    var anchorKey: String? = null,
    var originPageIndex: Int = pageIndex,
)
