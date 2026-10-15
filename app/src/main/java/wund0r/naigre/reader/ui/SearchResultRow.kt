// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import wund0r.naigre.reader.theme.UiPalette
import kotlin.math.roundToInt

/** Shared search/history appearance. Compact rows reserve one title and one detail line. */
@SuppressLint("ViewConstructor") // Programmatic result row with an explicit reader palette.
class SearchResultRow(
    context: Context,
    private val palette: UiPalette,
    private val compact: Boolean = false,
    showColorDot: Boolean = true,
    private val showActions: Boolean = true,
) : LinearLayout(context) {
    val colorDot = View(context)
    val title = text(17f, true)
    val detail = text(12f, false)
    private val textColumn = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(8), dp(7), dp(8), dp(7))
        addView(title)
        addView(detail)
    }
    val plus = actionButton(context, palette).apply { text = "+"; textSize = 22f }
    val alongside = actionButton(context, palette)
    val compactHeight: Int = maxOf(dp(56), fontHeight(title) + fontHeight(detail) + dp(22))

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(4), dp(4), 0, dp(4))
        isClickable = true
        isFocusable = true
        if (showColorDot) addView(colorDot, LayoutParams(dp(9), dp(9)).apply {
            marginStart = dp(5); marginEnd = dp(3)
        })
        addView(textColumn, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (showActions) {
            addView(plus, LayoutParams(dp(48), dp(48)))
            addView(alongside, LayoutParams(dp(48), dp(48)))
        }
        if (compact) minimumHeight = compactHeight
    }

    fun stripe(position: Int) {
        setBackgroundColor(if (position % 2 == 1) palette.surfaceRaised else palette.surface)
    }

    fun setBookColor(color: Int) {
        colorDot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // Retain text room beside both 48 dp controls in a narrow history column.
        val tight = compact && showActions && MeasureSpec.getSize(widthMeasureSpec) - dp(96) < dp(120)
        val sidePadding = if (tight) 0 else dp(8)
        textColumn.setPadding(sidePadding, dp(7), sidePadding, dp(7))
        (colorDot.layoutParams as? LayoutParams)?.let {
            it.marginStart = if (tight) 0 else dp(5)
            it.marginEnd = if (tight) 0 else dp(3)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private fun text(size: Float, bold: Boolean) = TextView(context).apply {
        textSize = size
        maxLines = if (compact) 1 else 2
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(if (bold) palette.textPrimary else palette.textSecondary)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun fontHeight(view: TextView) = view.paint.fontMetricsInt.let { it.bottom - it.top }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    companion object {
        fun actionButton(context: Context, palette: UiPalette) = Button(context).apply {
            textSize = 20f
            isAllCaps = false
            gravity = Gravity.CENTER
            minWidth = 0; minimumWidth = 0
            minHeight = 0; minimumHeight = 0
            setTextColor(palette.accent)
            background = RippleDrawable(
                ColorStateList.valueOf((palette.textPrimary and 0x00ffffff) or 0x33000000),
                ColorDrawable(Color.TRANSPARENT), ColorDrawable(Color.WHITE),
            )
            setPadding(0, 0, 0, 0)
            elevation = 0f
            stateListAnimator = null
        }
    }
}
