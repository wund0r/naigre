// SPDX-License-Identifier: AGPL-3.0-or-later
package wund0r.naigre.reader.ui

import org.junit.Assert.*
import org.junit.Test

class RgbColorTest {
    @Test
    fun parsesExactOpaqueRgbWithOptionalHash() {
        assertEquals(0xff79a7d3.toInt(), RgbColor.parse("#79a7d3"))
        assertEquals(0xffabcdef.toInt(), RgbColor.parse("  AbCdEf  "))
        assertEquals(0xff000000.toInt(), RgbColor.parse("000000"))
        assertEquals(0xffffffff.toInt(), RgbColor.parse("#FFFFFF"))
    }

    @Test
    fun rejectsIncompleteInvalidAndAlphaValues() {
        listOf("", "#", "#123", "#12345", "#1234567", "#ff123456", "0x123456", "#12G456", "##123456", "red", "12 456")
            .forEach { assertNull(it, RgbColor.parse(it)) }
    }

    @Test
    fun formatsPaddedUppercaseRgbWithoutAlpha() {
        assertEquals("#0010AB", RgbColor.format(0x010010ab))
        assertEquals("#FFFFFF", RgbColor.format(-1))
        assertEquals("#000000", RgbColor.format(0))
        listOf(0xff010203.toInt(), 0xff79a7d3.toInt(), -1).forEach {
            assertEquals(it, RgbColor.parse(RgbColor.format(it)))
        }
    }
}
