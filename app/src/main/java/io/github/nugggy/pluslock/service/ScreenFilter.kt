package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.view.View
import io.github.nugggy.pluslock.core.Hours
import io.github.nugggy.pluslock.core.P

/**
 * Extra dim and a warm colour filter: a see-through layer over the whole screen.
 * It can't lower the backlight itself, but darkens past the lowest brightness setting.
 * Screenshots include the layer.
 */
class ScreenFilter(private val svc: PlusAccessibilityService) {
    private var view: FilterView? = null
    private val tick = Runnable { refresh() }

    fun isActive(): Boolean =
        P.dimOn.value && (!P.dimScheduled.value || Hours.inWindow(P.dimStart.value, P.dimEnd.value))

    fun refresh() {
        svc.handler.removeCallbacks(tick)
        if (P.dimOn.value && P.dimScheduled.value) svc.handler.postDelayed(tick, 60_000)
        if (!isActive() || (P.dimLevel.value == 0 && P.dimWarmth.value == 0)) { remove(); return }
        val v = view
        if (v != null) { v.update(); return }
        val b = svc.screenBounds()
        // Bigger than the screen so it still covers everything after a rotation.
        val size = maxOf(b.width(), b.height())
        val nv = FilterView(svc)
        if (svc.addOverlay(nv, svc.overlayParams(size, size, touchable = false))) view = nv
    }

    fun remove() {
        svc.removeOverlay(view)
        view = null
    }
}

@SuppressLint("ViewConstructor")
private class FilterView(ctx: Context) : View(ctx) {
    private var dim = 0
    private var warm = 0

    init { update() }

    fun update() {
        // Up to 85% black, and up to a strong amber tint.
        dim = (P.dimLevel.value * 255 * 0.85f / 100).toInt()
        warm = (P.dimWarmth.value * 255 * 0.32f / 100).toInt()
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (warm > 0) c.drawColor(Color.argb(warm, 255, 138, 0))
        if (dim > 0) c.drawColor(Color.argb(dim, 0, 0, 0))
    }
}
