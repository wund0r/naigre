// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

import kotlin.math.max

object SpreadLayout {
    fun firstPage(
        pageIndex: Int,
        pageCount: Int,
        spreadMode: Boolean,
        skipCover: Boolean,
    ): Int {
        val clamped = pageIndex.coerceIn(0, max(0, pageCount - 1))
        if (!spreadMode) return clamped

        return if (skipCover) {
            when {
                clamped == 0 -> 0
                clamped % 2 == 1 -> clamped
                else -> clamped - 1
            }
        } else {
            clamped - (clamped % 2)
        }
    }

    fun secondPage(
        firstPage: Int,
        pageCount: Int,
        spreadMode: Boolean,
        skipCover: Boolean,
    ): Int? {
        if (!spreadMode) return null
        if (skipCover && firstPage == 0) return null
        return (firstPage + 1).takeIf { it < pageCount }
    }

    fun adjacentFirst(
        firstPage: Int,
        direction: Int,
        pageCount: Int,
        skipCover: Boolean,
    ): Int? {
        if (direction == 0 || pageCount <= 0) return null
        val candidate = if (skipCover) {
            if (direction > 0) {
                if (firstPage == 0) 1 else firstPage + 2
            } else {
                when {
                    firstPage <= 0 -> return null
                    firstPage == 1 -> 0
                    else -> firstPage - 2
                }
            }
        } else {
            firstPage + if (direction > 0) 2 else -2
        }
        return candidate.takeIf { it in 0 until pageCount }
    }
}
