package io.github.nugggy.pluslock.service

import android.view.View
import android.view.WindowManager
import io.github.nugggy.pluslock.core.P

/** Holds the screen on for the Caffeine tile and for apps set to stay awake. */
class KeepAwake(private val svc: PlusAccessibilityService) {
    private var view: View? = null
    private val tick = Runnable { update() }

    var perApp = false
        set(v) { field = v; update() }

    fun update() {
        svc.handler.removeCallbacks(tick)
        val until = P.caffeineUntil.value
        val now = System.currentTimeMillis()
        if (until > 0 && until <= now) P.caffeineUntil.value = 0L
        val caffeine = until == -1L || until > now
        if (perApp || caffeine) acquire() else release()
        if (until > now) svc.handler.postDelayed(tick, until - now + 50)
    }

    private fun acquire() {
        if (view != null) return
        val v = View(svc)
        val lp = svc.overlayParams(1, 1, touchable = false)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        if (svc.addOverlay(v, lp)) view = v
    }

    fun release() {
        svc.removeOverlay(view)
        view = null
    }
}
