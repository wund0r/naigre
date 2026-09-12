// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

enum class PageTurnMode(val label: String) {
    EDGE_TAPS("Edge taps"),
    SWIPE("Swipe"),
    BOTH("Edge taps + swipe");

    val edgeTapsEnabled: Boolean
        get() = this == EDGE_TAPS || this == BOTH

    val swipeEnabled: Boolean
        get() = this == SWIPE || this == BOTH
}
