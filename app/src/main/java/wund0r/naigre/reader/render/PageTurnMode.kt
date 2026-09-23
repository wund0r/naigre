// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

import wund0r.naigre.reader.R

enum class PageTurnMode(val labelRes: Int) {
    EDGE_TAPS(R.string.page_turn_edges),
    SWIPE(R.string.page_turn_swipe),
    BOTH(R.string.page_turn_both);

    val edgeTapsEnabled: Boolean
        get() = this == EDGE_TAPS || this == BOTH

    val swipeEnabled: Boolean
        get() = this == SWIPE || this == BOTH
}
