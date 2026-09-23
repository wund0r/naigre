// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.theme

import android.content.Context
import android.content.res.Configuration
import wund0r.naigre.reader.R

enum class ReaderThemeMode(val labelRes: Int) {
    DARK(R.string.theme_dark),
    LIGHT(R.string.theme_light),
    SYSTEM(R.string.theme_system);

    companion object {
        fun fromStored(value: String?): ReaderThemeMode =
            entries.firstOrNull { it.name == value } ?: DARK
    }
}

data class UiPalette(
    val isDark: Boolean,
    val background: Int,
    val surface: Int,
    val surfaceRaised: Int,
    val surfaceSelected: Int,
    val divider: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val textMuted: Int,
    val accent: Int,
    val onAccent: Int,
    val error: Int,
    val searchMatch: Int,
    val searchHighlight: Int,
    val pagePlaceholder: Int,
) {
    companion object {
        fun resolve(context: Context, mode: ReaderThemeMode): UiPalette {
            val systemDark = context.resources.configuration.uiMode and
                Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            return fromResources(context, mode == ReaderThemeMode.DARK || mode == ReaderThemeMode.SYSTEM && systemDark)
        }

        private fun fromResources(context: Context, dark: Boolean): UiPalette = UiPalette(
            isDark = dark,
            background = context.getColor(if (dark) R.color.naigre_dark_background else R.color.naigre_light_background),
            surface = context.getColor(if (dark) R.color.naigre_dark_surface else R.color.naigre_light_surface),
            surfaceRaised = context.getColor(
                if (dark) R.color.naigre_dark_surface_raised else R.color.naigre_light_surface_raised,
            ),
            surfaceSelected = context.getColor(
                if (dark) R.color.naigre_dark_surface_selected else R.color.naigre_light_surface_selected,
            ),
            divider = context.getColor(if (dark) R.color.naigre_dark_divider else R.color.naigre_light_divider),
            textPrimary = context.getColor(
                if (dark) R.color.naigre_dark_text_primary else R.color.naigre_light_text_primary,
            ),
            textSecondary = context.getColor(
                if (dark) R.color.naigre_dark_text_secondary else R.color.naigre_light_text_secondary,
            ),
            textMuted = context.getColor(
                if (dark) R.color.naigre_dark_text_muted else R.color.naigre_light_text_muted,
            ),
            accent = context.getColor(if (dark) R.color.naigre_dark_accent else R.color.naigre_light_accent),
            onAccent = context.getColor(
                if (dark) R.color.naigre_dark_on_accent else R.color.naigre_light_on_accent,
            ),
            error = context.getColor(if (dark) R.color.naigre_dark_error else R.color.naigre_light_error),
            searchMatch = context.getColor(
                if (dark) R.color.naigre_dark_search_match else R.color.naigre_light_search_match,
            ),
            searchHighlight = context.getColor(R.color.naigre_search_highlight),
            pagePlaceholder = context.getColor(
                if (dark) R.color.naigre_dark_page_placeholder else R.color.naigre_light_page_placeholder,
            ),
        )
    }
}
