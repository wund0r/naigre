// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.AbsListView
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import wund0r.naigre.reader.R
import wund0r.naigre.reader.table.BookRecord
import wund0r.naigre.reader.table.LibraryItemKind
import wund0r.naigre.reader.theme.UiPalette
import kotlin.math.roundToInt

/** One fixed geometry per font configuration, independent of content and thumbnail aspect ratio. */
@SuppressLint("ViewConstructor") // Programmatic recycled GridView child.
class LibraryCardView(
    context: Context,
    private val palette: UiPalette,
    private val previews: LibraryPreviewLoader,
    private val cardBackground: (Int) -> Drawable,
    private val toggle: (String) -> Unit,
    private val actions: (String) -> Unit,
) : LinearLayout(context) {
    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    private fun label(size: Float, lines: Int) = TextView(context).apply {
        textSize = size
        includeFontPadding = false
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        setTextColor(palette.textSecondary)
    }

    private val cover = FrameLayout(context).apply {
        setBackgroundColor(palette.background)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val snippet = label(11f, 6).apply { setPadding(dp(6), dp(6), dp(6), dp(6)) }
    private val headingList = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(6), dp(6), dp(6), dp(6))
    }
    private val headingLabels = List(4) { label(11f, 1) }
    private val title = label(17f, 2).apply {
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(palette.textPrimary)
    }
    private val tags = label(12f, 1)
    private val summary = label(12f, 2)
    private val dot = View(context)
    private val selected = CheckBox(context).apply {
        isClickable = false
        isFocusable = false
        textSize = 12f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        minWidth = 0
        minimumWidth = 0
        setTextColor(palette.textSecondary)
    }
    private val menu = Button(context).apply {
        text = "⋮"
        minWidth = 0
        minimumWidth = 0
        setPadding(0, 0, 0, 0)
    }
    private var book: BookRecord? = null
    private var previewKey: String? = null
    private var cancelPreview: (() -> Unit)? = null

    val cardHeight: Int = maxOf(dp(148), dp(20 + 48 + 6) + title.lineHeight * 2 + tags.lineHeight + summary.lineHeight * 2)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(10), dp(10), dp(10), dp(10))
        layoutParams = AbsListView.LayoutParams(LayoutParams.MATCH_PARENT, cardHeight)
        cover.addView(image, FrameLayout.LayoutParams(-1, -1))
        cover.addView(snippet, FrameLayout.LayoutParams(-1, -1))
        headingLabels.forEach { headingList.addView(it, LayoutParams(-1, LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(4) }) }
        cover.addView(headingList, FrameLayout.LayoutParams(-1, -1))
        addView(cover, LayoutParams(dp(72), dp(108)).apply { marginEnd = dp(12) })
        val text = LinearLayout(context).apply { orientation = VERTICAL }
        addView(text, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        top.addView(dot, LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(4) })
        top.addView(selected, LayoutParams(0, dp(48), 1f))
        top.addView(menu, LayoutParams(dp(48), dp(48)))
        text.addView(top, LayoutParams(-1, dp(48)))
        text.addView(title, LayoutParams(-1, title.lineHeight * 2))
        text.addView(tags, LayoutParams(-1, tags.lineHeight).apply { topMargin = dp(3) })
        text.addView(summary, LayoutParams(-1, summary.lineHeight * 2).apply { topMargin = dp(3) })
        setOnClickListener { book?.let { toggle(it.id) } }
        menu.setOnClickListener { book?.let { actions(it.id) } }
    }

    fun bind(item: BookRecord, onTable: Boolean, tagNames: String, metadata: String, problem: String?) {
        book = item
        background = cardBackground(if (onTable) palette.surfaceSelected else palette.surfaceRaised)
        dot.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(item.color) }
        selected.isChecked = onTable
        selected.text = context.getString(if (onTable) R.string.on_table else R.string.in_library)
        menu.contentDescription = context.getString(R.string.item_actions_description, item.title)
        title.text = item.fileName
        title.contentDescription = item.fileName
        tags.text = tagNames
        // Problems use the reserved metadata slot, never an extra row that changes card height.
        summary.text = problem ?: metadata
        summary.setTextColor(if (problem == null) palette.textSecondary else palette.error)
        summary.contentDescription = problem ?: metadata
        val key = LibraryPreviewLoader.key(item)
        if (key != previewKey) {
            cancelPreview?.invoke()
            cancelPreview = null
            previewKey = key
            showPreview(null)
            if (isAttachedToWindow) requestPreview()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestPreview()
    }

    override fun onDetachedFromWindow() {
        cancelPreview?.invoke()
        cancelPreview = null
        super.onDetachedFromWindow()
    }

    override fun onStartTemporaryDetach() {
        cancelPreview?.invoke()
        cancelPreview = null
        super.onStartTemporaryDetach()
    }

    override fun onFinishTemporaryDetach() {
        super.onFinishTemporaryDetach()
        post { requestPreview() }
    }

    private fun requestPreview() {
        if (!isAttachedToWindow || isTemporarilyDetached) return
        val current = book ?: return
        if (cancelPreview != null) return
        val key = previewKey
        cancelPreview = previews.load(current) { preview ->
            if (isAttachedToWindow && previewKey == key) showPreview(preview)
        }
    }

    internal fun showPreview(preview: LibraryPreview?) {
        image.setImageBitmap(preview?.bitmap)
        image.visibility = if (preview?.bitmap != null) VISIBLE else GONE
        val headings = preview?.headings.orEmpty()
        headingList.visibility = if (headings.isNotEmpty()) VISIBLE else GONE
        headingLabels.forEachIndexed { index, label ->
            label.text = headings.getOrNull(index).orEmpty()
            label.visibility = if (index < headings.size) VISIBLE else GONE
        }
        snippet.visibility = if (preview?.bitmap != null || headings.isNotEmpty()) GONE else VISIBLE
        snippet.gravity = Gravity.CENTER
        snippet.text = when (book?.kind) {
            LibraryItemKind.PDF -> context.getString(R.string.pdf_preview_placeholder)
            LibraryItemKind.IMAGE_COLLECTION -> context.getString(R.string.image)
            else -> context.getString(R.string.note)
        }
    }
}
