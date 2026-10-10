package io.github.nugggy.pluslock.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import io.github.nugggy.pluslock.core.P

/**
 * Double-tap the status bar to lock the phone. The strip sits over the status bar,
 * so it passes a swipe down on to the notification shade itself.
 */
class StatusBarTap(private val svc: PlusAccessibilityService) {
    private var view: View? = null
    /** True while the shade is probably open. Overlays sit above the shade, so the strip steps aside. */
    private var suspended = false

    fun refresh() {
        remove()
        if (!P.statusTapLock.value || svc.isLandscape || suspended || svc.statusTapSuppressed) return
        val b = svc.screenBounds()
        val h = svc.wm.currentWindowMetrics.windowInsets.getInsets(WindowInsets.Type.statusBars()).top
            .takeIf { it > 0 } ?: svc.dp(28f).toInt()
        val v = StripView(svc,
            onDoubleTap = { svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) },
            onPullDown = { fromRight ->
                suspended = true
                svc.handler.post { remove() }
                svc.performGlobalAction(
                    if (fromRight) AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
                    else AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
                )
            })
        if (svc.addOverlay(v, svc.overlayParams(b.width(), h, touchable = true))) view = v
    }

    /** Another app came to the front or the screen went off, so the shade is closed again. */
    fun resume() {
        if (!suspended) return
        suspended = false
        refresh()
    }

    fun remove() {
        svc.removeOverlay(view)
        view = null
    }
}

@SuppressLint("ViewConstructor")
private class StripView(
    ctx: Context,
    onDoubleTap: () -> Unit,
    private val onPullDown: (fromRight: Boolean) -> Unit,
) : View(ctx) {
    private var startX = 0f
    private var startY = 0f
    private var pulled = false
    private val pullDistance = 16 * resources.displayMetrics.density

    private val detector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { onDoubleTap(); return true }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { startX = e.x; startY = e.rawY; pulled = false }
            MotionEvent.ACTION_MOVE -> if (!pulled && e.rawY - startY > pullDistance) {
                pulled = true
                // Same split as OxygenOS: the right side of the bar opens Quick Settings.
                onPullDown(startX > width * 0.6f)
            }
        }
        if (!pulled) detector.onTouchEvent(e)
        return true
    }
}
