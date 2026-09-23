// SPDX-License-Identifier: AGPL-3.0-or-later
package wund0r.naigre.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import wund0r.naigre.reader.R
import wund0r.naigre.reader.theme.UiPalette
import kotlin.math.roundToInt

/** Dialog-local draft. Nothing is persisted until its owner explicitly applies [color]. */
@SuppressLint("ViewConstructor") // Programmatic-only view with palette and preview styling supplied by owner.
class BookColorPicker(
    context: Context,
    initialColor: Int,
    bookName: String,
    private val palette: UiPalette,
    private val tabBackground: (Int) -> Drawable,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private val hsv = FloatArray(3).also { Color.colorToHSV(initialColor, it) }
    private var updating = false
    var onValidityChanged: (Boolean) -> Unit = {}
    val color: Int? get() = RgbColor.parse(hex.text.toString())

    private val swatch = View(context).apply { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    private val tab = TextView(context).apply {
        text = bookName
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.textPrimary)
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        contentDescription = context.getString(R.string.color_tab_preview)
    }
    private val hex = EditText(context).apply {
        setSingleLine(true)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_ACTION_DONE
        hint = "#RRGGBB"
        contentDescription = context.getString(R.string.color_hex_label)
        setTextColor(palette.textPrimary)
        setText(RgbColor.format(initialColor))
        minHeight = dp(48)
        // Opening the picker should show its controls, not immediately raise the keyboard.
        clearFocus()
    }
    private val error = TextView(context).apply {
        text = context.getString(R.string.color_hex_error)
        textSize = 12f
        setTextColor(palette.error)
        visibility = GONE
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val hue = slider(R.string.color_hue, 3600)
    private val saturation = slider(R.string.color_saturation, 1000)
    private val brightness = slider(R.string.color_brightness, 1000)
    private val saturationGradient = gradient(intArrayOf(Color.WHITE, Color.RED))
    private val brightnessGradient = gradient(intArrayOf(Color.BLACK, Color.RED))

    init {
        orientation = VERTICAL
        setPadding(dp(20), dp(12), dp(20), dp(12))
        isFocusableInTouchMode = true
        val preview = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        preview.addView(swatch, LayoutParams(dp(48), dp(48)).apply { marginEnd = dp(8) })
        preview.addView(tab, LayoutParams(0, dp(48), 1f))
        addView(preview, LayoutParams(-1, dp(48)).apply { bottomMargin = dp(12) })
        addView(hex, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        addView(error, LayoutParams(-1, LayoutParams.WRAP_CONTENT))
        for ((label, slider) in listOf(R.string.color_hue to hue, R.string.color_saturation to saturation, R.string.color_brightness to brightness)) {
            addView(TextView(context).apply {
                text = context.getString(label)
                textSize = 13f
                setTextColor(palette.textSecondary)
            }, LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
            addView(slider, LayoutParams(-1, dp(48)))
        }
        // Keep the same drawables during dragging so their native SeekBar bounds stay intact.
        hue.progressDrawable = gradient(intArrayOf(Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN, Color.BLUE, Color.MAGENTA, Color.RED))
        saturation.progressDrawable = saturationGradient
        brightness.progressDrawable = brightnessGradient
        syncSliders()
        updatePreview(initialColor or 0xff000000.toInt())
        hex.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (updating) return
                val parsed = color
                error.visibility = if (parsed == null) VISIBLE else GONE
                if (parsed != null) {
                    Color.colorToHSV(parsed, hsv)
                    syncSliders()
                    updatePreview(parsed)
                }
                onValidityChanged(parsed != null)
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })
        listOf(hue, saturation, brightness).forEach { slider ->
            slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser || updating) return
                    when (seekBar) {
                        hue -> hsv[0] = progress / 10f
                        saturation -> hsv[1] = progress / 1000f
                        brightness -> hsv[2] = progress / 1000f
                    }
                    val next = Color.HSVToColor(hsv)
                    updating = true
                    hex.setText(RgbColor.format(next))
                    hex.setSelection(hex.length())
                    updating = false
                    error.visibility = GONE
                    updatePreview(next)
                    onValidityChanged(true)
                }
            })
        }
        requestFocus()
    }

    private fun slider(label: Int, limit: Int) = SeekBar(context).apply {
        max = limit
        contentDescription = context.getString(label)
        splitTrack = false
        thumbTintList = ColorStateList.valueOf(palette.textPrimary)
        progressTintList = null
        progressBackgroundTintList = null
    }

    private fun syncSliders() {
        updating = true
        hue.progress = (hsv[0] * 10).roundToInt()
        saturation.progress = (hsv[1] * 1000).roundToInt()
        brightness.progress = (hsv[2] * 1000).roundToInt()
        updating = false
    }

    private fun gradient(colors: IntArray) = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, colors).apply {
        setSize(dp(1), dp(16))
    }

    private fun updatePreview(color: Int) {
        swatch.background = GradientDrawable().apply {
            setColor(color)
            setStroke(dp(1).coerceAtLeast(1), palette.divider)
        }
        tab.background = tabBackground(color)
        tab.setPadding(dp(12), 0, dp(12), 0)
        saturationGradient.colors = intArrayOf(
            Color.HSVToColor(floatArrayOf(hsv[0], 0f, hsv[2])), Color.HSVToColor(floatArrayOf(hsv[0], 1f, hsv[2])))
        brightnessGradient.colors = intArrayOf(Color.BLACK, Color.HSVToColor(floatArrayOf(hsv[0], hsv[1], 1f)))
    }
}
