package com.anchor.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import com.anchor.R
import kotlin.math.abs

/**
 * Pure touch arithmetic: was that a tap or a drag? Kept out of the view so it
 * can be unit-tested.
 */
object BubbleTouch {
    /** Movement under this is a tap, not a drag. */
    const val CLICK_SLOP_PX = 24f

    /** Beyond this a still finger is a long press, not a tap. */
    const val CLICK_MAX_MILLIS = 500L

    fun isClick(dxPx: Float, dyPx: Float, durationMillis: Long): Boolean =
        abs(dxPx) <= CLICK_SLOP_PX && abs(dyPx) <= CLICK_SLOP_PX && durationMillis <= CLICK_MAX_MILLIS
}

/**
 * A small floating button, shown only while an app with an active limit is in
 * the foreground, for ending that app's session early.
 *
 * Not Android's accessibility shortcut: the user assigns that shortcut and the
 * system draws its button, so a service has no dependable way to show it for
 * one app and hide it for another. An overlay we own appears exactly when it is
 * useful and goes away the moment it is not.
 *
 * **Staying put is the hard part.** Android fires a window-state change for
 * every transient window: the keyboard, a toast, the status bar, a dialog. Each
 * one looks like "some other package is in front", and naively hiding on each
 * would tear the view down and rebuild it a moment later, which reads as
 * flicker. So a hide is deferred and cancelled by any show that follows it, and
 * a show for the app already being displayed does nothing at all.
 */
class RelockBubble(
    private val context: Context,
    private val onTap: (packageName: String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var view: ImageView? = null
    private var params: WindowManager.LayoutParams? = null
    private var target: String? = null

    /** Remembered across shows, so a dragged position sticks. */
    private var savedX = Int.MIN_VALUE
    private var savedY = Int.MIN_VALUE

    private val dim = Runnable { view?.animate()?.alpha(DIM_ALPHA)?.setDuration(600)?.start() }
    private val remove = Runnable { removeNow() }

    fun show(packageName: String) {
        main.post {
            // A pending hide was a transient window, not a real departure.
            main.removeCallbacks(remove)

            if (view != null) {
                // Already up. Only stir it if it is now for a different app;
                // otherwise every stray window event would re-brighten it and
                // restart the fade, which looks like a pulse.
                if (target != packageName) {
                    target = packageName
                    wake()
                }
                return@post
            }

            val wm = windowManager ?: return@post
            val bubble = build()
            val lp = layoutParams()
            runCatching { wm.addView(bubble, lp) }
                .onSuccess {
                    view = bubble
                    params = lp
                    target = packageName
                    wake()
                    Log.d(TAG, "shown for $packageName")
                }
                // Silently swallowing this hid a real failure once already.
                .onFailure { Log.w(TAG, "could not add the button", it) }
        }
    }

    /**
     * Take it down shortly. Deferred so that a keyboard opening, or any other
     * momentary window, does not blink the button out and back.
     */
    fun hide() {
        main.post {
            if (view == null) return@post
            main.removeCallbacks(remove)
            main.postDelayed(remove, HIDE_DELAY_MILLIS)
        }
    }

    /** Take it down at once, for shutdown. */
    fun hideNow() {
        main.post {
            main.removeCallbacks(remove)
            removeNow()
        }
    }

    private fun removeNow() {
        main.removeCallbacks(dim)
        val current = view ?: return
        runCatching { windowManager?.removeView(current) }
        view = null
        params = null
        target = null
    }

    /** Full opacity now, fading back down if it is left alone. */
    private fun wake() {
        val bubble = view ?: return
        main.removeCallbacks(dim)
        bubble.animate().alpha(1f).setDuration(120).start()
        main.postDelayed(dim, DIM_AFTER_MILLIS)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun build(): ImageView = ImageView(context).apply {
        setImageResource(R.drawable.ic_relock_bubble)
        background = context.getDrawable(R.drawable.bg_relock_bubble)
        val pad = (context.resources.displayMetrics.density * 11).toInt()
        setPadding(pad, pad, pad, pad)
        alpha = 1f
        contentDescription = context.getString(R.string.relock_bubble_description)

        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var downAt = 0L

        setOnTouchListener { _, event ->
            val lp = params ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX
                    downY = event.rawY
                    startX = lp.x
                    startY = lp.y
                    downAt = System.currentTimeMillis()
                    wake()
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    if (!BubbleTouch.isClick(dx, dy, 0)) {
                        // Gravity is TOP|END, so x grows leftward.
                        lp.x = (startX - dx).toInt().coerceAtLeast(0)
                        lp.y = (startY + dy).toInt().coerceAtLeast(0)
                        runCatching { windowManager?.updateViewLayout(this, lp) }
                    }
                    true
                }

                MotionEvent.ACTION_UP -> {
                    val dx = event.rawX - downX
                    val dy = event.rawY - downY
                    savedX = lp.x
                    savedY = lp.y
                    if (BubbleTouch.isClick(dx, dy, System.currentTimeMillis() - downAt)) {
                        target?.let(onTap)
                    } else {
                        wake()
                    }
                    true
                }

                else -> false
            }
        }
    }

    private fun layoutParams(): WindowManager.LayoutParams {
        val density = context.resources.displayMetrics.density
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            // An accessibility service may use this type without the overlay
            // permission, and it draws in a layer above application windows.
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                // A window added from a service is not hardware accelerated
                // by default; a software layer over a video is what tears.
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = if (savedX != Int.MIN_VALUE) savedX else (density * 12).toInt()
            y = if (savedY != Int.MIN_VALUE) savedY else (density * 160).toInt()
        }
    }

    private companion object {
        const val TAG = "AnchorBubble"
        const val DIM_ALPHA = 0.28f
        const val DIM_AFTER_MILLIS = 4_000L

        /** Long enough to ride out a keyboard or a toast stealing focus. */
        const val HIDE_DELAY_MILLIS = 800L
    }
}
