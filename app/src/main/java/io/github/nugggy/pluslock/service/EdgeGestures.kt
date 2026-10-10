package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import io.github.nugggy.pluslock.core.Actions
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Side
import io.github.nugggy.pluslock.core.Swipe
import kotlin.math.abs
import kotlin.math.hypot

/** One Hand Operation+ style handles on the screen edges. */
class EdgeGestures(private val svc: PlusAccessibilityService) {
    private val views = mutableListOf<HandleView>()
    var suppressed = false
        set(v) {
            if (field != v) { field = v; refresh() }
        }

    fun refresh() {
        remove()
        if (!P.gesturesOn.value || suppressed) return
        if (svc.isLandscape && P.gestureHideLandscape.value) return
        val b = svc.screenBounds()
        val w = svc.dp(P.handleWidth.value.toFloat()).toInt().coerceAtLeast(4)
        val h = svc.dp(P.handleHeight.value.toFloat()).toInt().coerceAtMost(b.height())
        val y = ((b.height() - h) * P.handlePosition.value / 100f).toInt()
        for (side in Side.entries) {
            val enabled = if (side == Side.LEFT) P.leftHandle.value else P.rightHandle.value
            if (!enabled) continue
            val v = HandleView(svc, side) { dir -> fire(side, dir) }
            val lp = svc.overlayParams(w, h, touchable = true)
            lp.x = if (side == Side.LEFT) 0 else b.width() - w
            lp.y = y
            if (svc.addOverlay(v, lp)) views += v
        }
    }

    private fun fire(side: Side, dir: Swipe) {
        if (P.gestureHaptics.value) Haptics.tick(svc)
        Actions.run(svc, P.gesture(side, dir).value)
    }

    fun remove() {
        views.forEach { svc.removeOverlay(it) }
        views.clear()
    }
}

@SuppressLint("ViewConstructor")
private class HandleView(
    ctx: Context,
    private val side: Side,
    private val onGesture: (Swipe) -> Unit,
) : View(ctx) {
    private val density = resources.displayMetrics.density
    private val trigger = 22 * density
    private val longDistance = 150 * density
    private val holdDelay = 450L

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    private var startX = 0f
    private var startY = 0f
    private var armed = false
    private var fired = false
    private var progress = 0f

    private val holdRunnable = Runnable {
        if (armed && !fired) {
            fired = true
            onGesture(Swipe.HOLD)
            reset()
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // Take priority over the system back gesture on this stretch of edge only.
        systemGestureExclusionRects = listOf(Rect(0, 0, width, height))
    }

    override fun onDraw(canvas: Canvas) {
        val alpha = P.handleVisibility.value / 100f
        val active = armed && !fired
        val a = if (active) (alpha + 0.5f).coerceAtMost(1f) else alpha
        if (a <= 0f) return
        paint.color = P.handleColor.value
        paint.alpha = (a * 255).toInt()
        val barW = (if (active) 6f else 3.5f) * density + progress * 4 * density
        val inset = 2 * density
        if (side == Side.LEFT) rect.set(inset, height * 0.08f, inset + barW, height * 0.92f)
        else rect.set(width - inset - barW, height * 0.08f, width - inset, height * 0.92f)
        canvas.drawRoundRect(rect, barW, barW, paint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = e.rawX; startY = e.rawY
                armed = false; fired = false; progress = 0f
            }
            MotionEvent.ACTION_MOVE -> {
                val inward = if (side == Side.LEFT) e.rawX - startX else startX - e.rawX
                val dist = hypot(inward, e.rawY - startY)
                progress = (inward / longDistance).coerceIn(0f, 1f)
                if (!armed && dist > trigger && inward > 0) {
                    armed = true
                    Haptics.tick(context)
                }
                if (armed && !fired) {
                    removeCallbacks(holdRunnable)
                    postDelayed(holdRunnable, holdDelay)
                }
                invalidate()
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(holdRunnable)
                if (armed && !fired) {
                    val inward = if (side == Side.LEFT) e.rawX - startX else startX - e.rawX
                    val dy = e.rawY - startY
                    val dir = when {
                        abs(dy) > inward * 0.9f && abs(dy) > trigger -> if (dy < 0) Swipe.UP else Swipe.DOWN
                        inward > longDistance -> Swipe.LONG
                        else -> Swipe.IN
                    }
                    fired = true
                    onGesture(dir)
                }
                reset()
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(holdRunnable)
                reset()
            }
        }
        return true
    }

    private fun reset() {
        armed = false
        progress = 0f
        invalidate()
    }
}
