// SPDX-License-Identifier: AGPL-3.0-or-later

package wund0r.naigre.reader.render

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import wund0r.naigre.reader.pdf.PdfRect
import wund0r.naigre.reader.pdf.RenderedPdfPage

class ReaderSurfaceGestureTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test
    fun blankTapRespondsOnReleaseWithoutWaitingForConfirmation() = onMain {
        val surface = surface()
        var taps = 0
        surface.onBlankTap = { taps++ }
        val downTime = SystemClock.uptimeMillis()
        surface.touch(downTime, downTime, MotionEvent.ACTION_DOWN)
        assertEquals(0, taps)
        surface.touch(downTime, downTime + 40, MotionEvent.ACTION_UP)
        // Still on the same main-thread turn: no delayed callback can have run.
        assertEquals(1, taps)
    }

    @Test
    fun rapidTapsBothToggleControlsWithoutResettingZoom() = onMain {
        val surface = surface()
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        try {
            val viewport = ReaderSurface.ViewportState(2f, 0.5f, 0.5f)
            surface.setPages(
                RenderedPdfPage(0, bitmap, PdfRect(0f, 0f, 100f, 100f), emptyList(), emptyList()),
                null,
                resetTransform = false,
                viewportState = viewport,
            )
            var taps = 0
            surface.onBlankTap = { taps++ }
            val now = SystemClock.uptimeMillis()
            surface.tap(now)
            assertEquals(1, taps)
            surface.tap(now + 120)
            assertEquals(2, taps)
            assertEquals(viewport, surface.captureViewportState())

            surface.resetTransform()
            assertEquals(1f, surface.captureViewportState()!!.zoom, 0f)
        } finally {
            surface.clearPages()
            bitmap.recycle()
        }
    }

    @Test
    fun edgeTapsTurnPagesOnReleaseWithoutTogglingControls() = onMain {
        val surface = surface()
        val turns = mutableListOf<Int>()
        var blankTaps = 0
        surface.onPageTurn = { turns += it }
        surface.onBlankTap = { blankTaps++ }
        val now = SystemClock.uptimeMillis()
        surface.tap(now, x = 1f)
        assertEquals(listOf(-1), turns)
        surface.tap(now + 120, x = surface.width - 1f)
        assertEquals(listOf(-1, 1), turns)
        assertEquals(0, blankTaps)
    }

    @Test
    fun draggingAndCancelledTouchesDoNotToggleControls() = onMain {
        val surface = surface()
        var taps = 0
        surface.onBlankTap = { taps++ }
        val now = SystemClock.uptimeMillis()
        surface.touch(now, now, MotionEvent.ACTION_DOWN)
        surface.touch(now, now + 40, MotionEvent.ACTION_MOVE, y = 50f)
        surface.touch(now, now + 80, MotionEvent.ACTION_UP, y = 50f)
        surface.touch(now + 120, now + 120, MotionEvent.ACTION_DOWN)
        surface.touch(now + 120, now + 160, MotionEvent.ACTION_CANCEL)
        assertEquals(0, taps)
    }

    private fun surface() = ReaderSurface(instrumentation.targetContext).apply {
        layout(0, 0, 600, 800)
    }

    private fun ReaderSurface.tap(downTime: Long, x: Float = 300f) {
        touch(downTime, downTime, MotionEvent.ACTION_DOWN, x)
        touch(downTime, downTime + 40, MotionEvent.ACTION_UP, x)
    }

    private fun ReaderSurface.touch(
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float = 300f,
        y: Float = 400f,
    ) {
        val event = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        try {
            dispatchTouchEvent(event)
        } finally {
            event.recycle()
        }
    }

    private fun onMain(test: () -> Unit) = instrumentation.runOnMainSync(test)
}
