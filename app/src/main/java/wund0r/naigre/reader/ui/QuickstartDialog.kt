// SPDX-License-Identifier: AGPL-3.0-or-later
package wund0r.naigre.reader.ui

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import wund0r.naigre.reader.R
import wund0r.naigre.reader.markdown.MarkdownContent
import wund0r.naigre.reader.markdown.MarkdownEngine
import wund0r.naigre.reader.render.MarkdownSurface
import wund0r.naigre.reader.theme.UiPalette
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/** Bundled help has no book identity, persisted metadata or search-index membership. */
class QuickstartDialog(
    context: Context,
    private val palette: UiPalette,
    private val configureWindow: (Window) -> Unit,
) : Dialog(context, if (palette.isDark) R.style.FullScreenDialogThemeDark else R.style.FullScreenDialogThemeLight) {
    val contentView = LinearLayout(getContext()).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(palette.background)
    }
    private val surface = MarkdownSurface(getContext())
    private val readingState = MarkdownSurface.ReadingState()
    private val loader = Executors.newSingleThreadExecutor { task -> Thread(task, "naigre-help") }
    private var engine: MarkdownEngine? = null
    private var document: MarkdownContent? = null
    private var contentsDialog: Dialog? = null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).roundToInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val header = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(8))
        }
        header.addView(Button(context).apply {
            text = "←"
            textSize = 22f
            contentDescription = context.getString(R.string.help_back)
            setPadding(0, 0, 0, 0)
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(48), dp(48)))
        header.addView(TextView(context).apply {
            text = context.getString(R.string.quickstart_title)
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(palette.textPrimary)
        }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(8)
            marginEnd = dp(8)
        })
        val contents = Button(context).apply {
            text = context.getString(R.string.help_contents)
            minHeight = dp(48)
            isEnabled = false
            setOnClickListener { showContents() }
        }
        header.addView(contents, LinearLayout.LayoutParams(-2, -2))
        contentView.addView(header, LinearLayout.LayoutParams(-1, -2))
        val body = FrameLayout(context)
        surface.setUiColors(palette.background, palette.textPrimary)
        body.addView(surface, FrameLayout.LayoutParams(-1, -1))
        val status = TextView(context).apply {
            text = context.getString(R.string.help_loading)
            setTextColor(palette.textSecondary)
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        body.addView(status, FrameLayout.LayoutParams(-1, -1))
        contentView.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(contentView)
        // Load only when opened; use a separate parser so help never waits behind document work.
        loader.execute {
            val result = runCatching {
                val source = context.resources.openRawResource(R.raw.quickstart).bufferedReader(Charsets.UTF_8).use { it.readText() }
                val parser = MarkdownEngine(context)
                parser to parser.parse(source)
            }
            contentView.post {
                if (!isShowing) return@post
                result.fold(onSuccess = { (parser, parsed) ->
                    engine = parser
                    document = parsed
                    surface.showContent(parser, parsed, 0, readingState)
                    status.visibility = View.GONE
                    contents.isEnabled = true
                }, onFailure = {
                    status.text = context.getString(R.string.help_load_failed)
                })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        window?.let {
            it.setWindowAnimations(R.style.NaigreNoWindowAnimation)
            it.setLayout(-1, -1)
            configureWindow(it)
        }
        contentView.requestApplyInsets()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window?.let(configureWindow)
    }

    override fun onStop() {
        contentsDialog?.dismiss()
        contentsDialog = null
        loader.shutdownNow()
        super.onStop()
    }

    private fun showContents() {
        val parsed = document ?: return
        val parser = engine ?: return
        val headings = parsed.sections.filter { it.headingLevel > 0 }.ifEmpty { parsed.sections }
        contentsDialog = AlertDialog.Builder(context)
            .setTitle(R.string.help_contents)
            .setItems(headings.map { it.title }.toTypedArray()) { _, index ->
                readingState.requestNavigation()
                surface.showContent(parser, parsed, headings[index].index, readingState)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }
}
