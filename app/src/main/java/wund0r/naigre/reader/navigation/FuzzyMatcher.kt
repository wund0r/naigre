// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.navigation

import java.util.Locale
import kotlin.math.max

object FuzzyMatcher {
    private val whitespace = Regex("\\s+")

    fun isSmartCase(query: String): Boolean = query.any(Char::isUpperCase)

    /**
     * Returns null when [query] does not fuzzy-match [candidate]. Higher scores are better.
     * The scoring intentionally favors exact substrings, word starts, consecutive matches,
     * and matches near the beginning of the candidate.
     */
    fun score(candidate: String, query: String): Int? {
        val caseSensitive = isSmartCase(query)
        return scorePrepared(
            candidate = if (caseSensitive) candidate else normalize(candidate),
            tokens = prepareQuery(query, caseSensitive),
        )
    }

    internal fun normalize(candidate: String): String = candidate.lowercase(Locale.ROOT)

    internal fun prepareQuery(query: String, caseSensitive: Boolean = isSmartCase(query)): List<String> =
        (if (caseSensitive) query.trim() else query.trim().lowercase(Locale.ROOT))
            .split(whitespace)
            .filter { it.isNotEmpty() }

    internal fun scorePrepared(candidate: String, tokens: List<String>): Int? {
        if (tokens.isEmpty()) return 0

        var total = 0
        for (token in tokens) {
            val tokenScore = scoreToken(candidate, token) ?: return null
            total += tokenScore
        }
        return total
    }

    private fun scoreToken(candidate: String, token: String): Int? {
        if (candidate == token) return 20_000

        val exactIndex = candidate.indexOf(token)
        if (exactIndex >= 0) {
            val boundaryBonus = if (isBoundary(candidate, exactIndex)) 1_500 else 0
            return 12_000 + boundaryBonus - exactIndex * 8 - candidate.length
        }

        var candidateIndex = 0
        var previousMatch = -2
        var streak = 0
        var score = 0
        var firstMatch = -1

        for (queryChar in token) {
            var match = -1
            while (candidateIndex < candidate.length) {
                if (candidate[candidateIndex] == queryChar) {
                    match = candidateIndex
                    break
                }
                candidateIndex++
            }
            if (match < 0) return null

            if (firstMatch < 0) firstMatch = match
            val consecutive = match == previousMatch + 1
            streak = if (consecutive) streak + 1 else 0

            score += 80
            if (isBoundary(candidate, match)) score += 120
            if (consecutive) score += 100 + streak * 20

            previousMatch = match
            candidateIndex = match + 1
        }

        score -= max(0, firstMatch) * 5
        score -= candidate.length / 2
        return score
    }

    private fun isBoundary(text: String, index: Int): Boolean {
        if (index <= 0) return true
        val previous = text[index - 1]
        return !previous.isLetterOrDigit()
    }
}
