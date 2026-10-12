// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.markdown

import android.text.TextPaint
import android.text.style.ClickableSpan
import android.view.View

/** Immutable spans can be shared by the primary/reference views and background parsers. */
class WikiLinkSpan(val spec: WikiLinkSpec) : ClickableSpan() {
    interface Host {
        fun openWikiLink(spec: WikiLinkSpec)
    }

    override fun onClick(widget: View) { (widget as? Host)?.openWikiLink(spec) }
    override fun updateDrawState(ds: TextPaint) {
        ds.color = ds.linkColor
        ds.isUnderlineText = true
    }
}
