// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyMatcherTest {
    @Test
    fun lowercaseCyrillicFindsUppercaseHeadingsAndFilenames() {
        assertFalse(FuzzyMatcher.isSmartCase("тайная пещера"))
        assertNotNull(FuzzyMatcher.score("ТАЙНАЯ ПЕЩЕРА", "тайная пещера"))
        assertNotNull(FuzzyMatcher.score("Подземелье/КАРТА.png", "карта"))
    }

    @Test
    fun cyrillicCapitalsEnableSmartcase() {
        assertTrue(FuzzyMatcher.isSmartCase("Пещера"))
        assertNotNull(FuzzyMatcher.score("Тайная Пещера", "Пещера"))
        assertNull(FuzzyMatcher.score("тайная пещера", "Пещера"))
    }

    @Test
    fun yoUsesCaseFoldingWithoutAddingTransliteration() {
        assertNotNull(FuzzyMatcher.score("ЁЖ", "ёж"))
        assertTrue(FuzzyMatcher.isSmartCase("Ёж"))
        assertNull(FuzzyMatcher.score("ёж", "Ёж"))
        // Navigation has always treated е and ё as distinct letters. Localization must not change it.
        assertNull(FuzzyMatcher.score("ёж", "еж"))
    }
}
