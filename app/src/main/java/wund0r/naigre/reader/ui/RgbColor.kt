// SPDX-License-Identifier: AGPL-3.0-or-later
package wund0r.naigre.reader.ui

import java.util.Locale

/** Only opaque RGB: do not silently interpret/truncate short or alpha-bearing hex codes. */
internal object RgbColor {
    fun parse(text: String): Int? {
        val hex = text.trim().removePrefix("#")
        if (hex.length != 6 || hex.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return hex.toInt(16) or 0xff000000.toInt()
    }

    fun format(color: Int): String = "#" + (color and 0xffffff).toString(16).padStart(6, '0').uppercase(Locale.ROOT)
}
