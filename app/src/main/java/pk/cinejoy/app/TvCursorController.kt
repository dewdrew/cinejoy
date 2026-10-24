package pk.cinejoy.app

import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.webkit.WebView
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a Fire TV / Android TV D-pad into an on-screen pointer.
 *
 * Embedded players put their controls (play, pause, server selector, audio track,
 * subtitles, fullscreen) in positions that plain arrow-key focus traversal cannot
 * reach, because the page never marks them focusable. Driving a pointer and
 * dispatching a synthetic tap makes every control reachable with the stock remote.
 *
 * @param webView the page being driven; also defines the bounds of the pointer.
 * @param cursorView the pointer overlay.
 * @param activeTargetProvider resolves the view a tap should land on, so taps follow
 *   the video into fullscreen and reach the offline overlay when it is up.
 */
class TvCursorController(
    private val webView: WebView,
    private val cursorView: ImageView,
    private val activeTargetProvider: () -> View
) {

    /** True while the D-pad drives the pointer; false hands the keys back to the page. */
    var isPointerMode = true
        private set

    private var cursorX = 0f
    private var cursorY = 0f
    private var initialised = false

    private val handler = Handler(Looper.getMainLooper())
    private var scrollAnimator: ValueAnimator? = null

    private val hideCursorRunnable = Runnable {
        cursorView.animate()
            .alpha(0f)
            .setDuration(FADE_DURATION_MS)
            .withEndAction { cursorView.visibility = View.GONE }
            .start()
    }

    companion object {
        /** Pointer travel per key event before acceleration kicks in. */
        private const val BASE_STEP = 26f

        /** Extra travel added per repeated key event while a direction is held. */
        private const val STEP_PER_REPEAT = 7f

        /** Ceiling on pointer travel so a long hold stays controllable. */
        private const val MAX_STEP = 90f

        /** Distance from a WebView edge at which the page starts scrolling. */
        private const val EDGE_THRESHOLD = 48f

        /** Page distance covered by one edge-triggered scroll. */
        private const val SCROLL_STEP = 160

        private const val SCROLL_DURATION_MS = 180L
        private const val FADE_DURATION_MS = 220L
        private const val CLICK_DURATION_MS = 60L

        /** Idle time after which the pointer fades so it never sits over a movie. */
        private const val AUTO_HIDE_DELAY_MS = 5_000L

        private const val DEFAULT_CURSOR_SIZE_DP = 28f

        /** Pointer tip offset inside the 24x24 viewport of `cursor_pointer.xml`. */
        private const val TIP_FRACTION_X = 4f / 24f
        private const val TIP_FRACTION_Y = 2f / 24f
    }

    /** Centre the pointer and show it. Safe to call before the view is laid out. */
    fun initializeForTv() {
        cursorView.post {
            centreCursor()
            initialised = true
            updateCursorPosition()
            showCursor()
        }
    }

    /**
     * @return true when the event was consumed and must not reach the page.
     */
    fun handleKeyEvent(event: KeyEvent): Boolean {
        // Menu toggles pointer mode in either direction, so it is handled first.
        if (event.keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                togglePointerMode()
            }
            return true
        }

        if (!isPointerMode) return false

        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) onDirectional(0f, -1f, event.repeatCount)
                true
            }

            KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) onDirectional(0f, 1f, event.repeatCount)
                true
            }

            KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (event.action == KeyEvent.ACTION_DOWN) onDirectional(-1f, 0f, event.repeatCount)
                true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (event.action == KeyEvent.ACTION_DOWN) onDirectional(1f, 0f, event.repeatCount)
                true
            }

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BUTTON_A -> {
                if (event.action == KeyEvent.ACTION_UP) {
                    showCursor()
                    performSyntheticClick()
                }
                true
            }

            else -> false
        }
    }

    /** Re-clamp the pointer after a rotation or window-size change. */
    fun onViewportChanged() {
        if (!initialised) return
        cursorView.post { moveCursor(0f, 0f) }
    }

    /** Drop pending callbacks and animations. Call from `Activity.onDestroy`. */
    fun release() {
        handler.removeCallbacks(hideCursorRunnable)
        scrollAnimator?.cancel()
        scrollAnimator = null
        cursorView.animate().cancel()
    }

    private fun onDirectional(directionX: Float, directionY: Float, repeatCount: Int) {
        if (!initialised) {
            centreCursor()
            initialised = true
        }
        showCursor()

        // Holding a direction raises the step so long travels stay quick while
        // single presses remain precise.
        val step = min(MAX_STEP, BASE_STEP + repeatCount * STEP_PER_REPEAT)
        moveCursor(directionX * step, directionY * step)
        applyEdgeScroll(directionX, directionY)
    }

    private fun centreCursor() {
        val size = cursorSize()
        cursorX = max(0f, webView.width / 2f - size / 2f)
        cursorY = max(0f, webView.height / 2f - size / 2f)
    }

    private fun moveCursor(deltaX: Float, deltaY: Float) {
        val size = cursorSize()
        val maxX = max(0f, webView.width - size)
        val maxY = max(0f, webView.height - size)
        cursorX = max(0f, min(maxX, cursorX + deltaX))
        cursorY = max(0f, min(maxY, cursorY + deltaY))
        updateCursorPosition()
    }

    private fun updateCursorPosition() {
        cursorView.translationX = cursorX
        cursorView.translationY = cursorY
    }

    /**
     * Scroll the page once the pointer presses against the edge it is travelling
     * toward. Skipped while a video owns the screen, where there is nothing to scroll.
     */
    private fun applyEdgeScroll(directionX: Float, directionY: Float) {
        if (activeTargetProvider() !== webView) return

        val size = cursorSize()
        var scrollX = 0
        var scrollY = 0

        if (directionY < 0f && cursorY <= EDGE_THRESHOLD) {
            scrollY = -SCROLL_STEP
        }
        if (directionY > 0f && cursorY >= webView.height - size - EDGE_THRESHOLD) {
            scrollY = SCROLL_STEP
        }
        if (directionX < 0f && cursorX <= EDGE_THRESHOLD) {
            scrollX = -SCROLL_STEP
        }
        if (directionX > 0f && cursorX >= webView.width - size - EDGE_THRESHOLD) {
            scrollX = SCROLL_STEP
        }

        if (scrollX != 0 || scrollY != 0) smoothScroll(scrollX, scrollY)
    }

    private fun smoothScroll(distanceX: Int, distanceY: Int) {
        scrollAnimator?.cancel()

        var appliedX = 0
        var appliedY = 0
        scrollAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SCROLL_DURATION_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener { animator ->
                val fraction = animator.animatedValue as Float
                val targetX = (distanceX * fraction).toInt()
                val targetY = (distanceY * fraction).toInt()
                webView.scrollBy(targetX - appliedX, targetY - appliedY)
                appliedX = targetX
                appliedY = targetY
            }
            start()
        }
    }

    /**
     * Dispatch a paired DOWN/UP touch at the pointer tip so page and player controls
     * respond exactly as they would to a finger tap.
     */
    private fun performSyntheticClick() {
        val target = activeTargetProvider()
        val size = cursorSize()
        val x = cursorX + size * TIP_FRACTION_X
        val y = cursorY + size * TIP_FRACTION_Y

        val downTime = SystemClock.uptimeMillis()
        val downEvent = MotionEvent.obtain(
            downTime,
            downTime,
            MotionEvent.ACTION_DOWN,
            x,
            y,
            0
        )
        val upEvent = MotionEvent.obtain(
            downTime,
            downTime + CLICK_DURATION_MS,
            MotionEvent.ACTION_UP,
            x,
            y,
            0
        )

        target.dispatchTouchEvent(downEvent)
        target.dispatchTouchEvent(upEvent)

        downEvent.recycle()
        upEvent.recycle()
    }

    private fun togglePointerMode() {
        isPointerMode = !isPointerMode
        if (isPointerMode) {
            showCursor()
            toast(R.string.pointer_mode_on)
        } else {
            handler.removeCallbacks(hideCursorRunnable)
            cursorView.animate().cancel()
            cursorView.visibility = View.GONE
            // Hand focus back so arrow keys drive the page's own focus traversal.
            webView.requestFocus()
            toast(R.string.pointer_mode_off)
        }
    }

    private fun showCursor() {
        if (!isPointerMode) return
        handler.removeCallbacks(hideCursorRunnable)
        cursorView.animate().cancel()
        cursorView.alpha = 1f
        if (cursorView.visibility != View.VISIBLE) {
            cursorView.visibility = View.VISIBLE
        }
        handler.postDelayed(hideCursorRunnable, AUTO_HIDE_DELAY_MS)
    }

    /** Measured size, falling back to the declared 28dp before the first layout pass. */
    private fun cursorSize(): Float {
        val measured = cursorView.width
        if (measured > 0) return measured.toFloat()
        val density = cursorView.resources.displayMetrics.density
        return DEFAULT_CURSOR_SIZE_DP * density
    }

    private fun toast(messageRes: Int) {
        Toast.makeText(cursorView.context, messageRes, Toast.LENGTH_SHORT).show()
    }
}
