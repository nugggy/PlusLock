package io.github.nugggy.pluslock.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.nugggy.pluslock.core.P
import kotlin.math.max

/** A battery ring drawn around the punch-hole selfie camera. */
class CameraRing(private val svc: PlusAccessibilityService) {
    private var view: RingView? = null
    var level = 100
        private set
    var charging = false
        private set

    fun update(level: Int, charging: Boolean) {
        this.level = level
        this.charging = charging
        view?.setState(level, charging)
    }

    fun refresh() {
        remove()
        if (!P.ringOn.value) return
        if (svc.isLandscape && P.ringHideLandscape.value) return
        val b = svc.screenBounds()
        val cut = svc.wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects
            ?.filter { it.width() < b.width() / 3 }
            ?.minByOrNull { it.top }
        // No cutout reported: assume a centred hole near the top and let the user nudge it.
        val cx0 = cut?.exactCenterX() ?: (b.width() / 2f)
        val cy0 = cut?.exactCenterY() ?: svc.dp(22f)
        val holeR = if (cut != null) max(cut.width(), cut.height()) / 2f else svc.dp(14f)
        val thickness = svc.dp(P.ringThickness.value / 10f)
        val gap = svc.dp(P.ringGap.value / 10f)
        val r = holeR * P.ringScale.value / 100f + gap + thickness / 2f
        val size = ((r + thickness) * 2 + svc.dp(4f)).toInt()
        val cx = cx0 + svc.dp(P.ringOffsetX.value / 2f)
        val cy = cy0 + svc.dp(P.ringOffsetY.value / 2f)
        val v = RingView(svc, r, thickness)
        v.setState(level, charging)
        val lp = svc.overlayParams(size, size, touchable = false)
        lp.x = (cx - size / 2f).toInt()
        lp.y = (cy - size / 2f).toInt()
        if (svc.addOverlay(v, lp)) view = v
    }

    fun remove() {
        view?.stop()
        svc.removeOverlay(view)
        view = null
    }
}

@SuppressLint("ViewConstructor")
private class RingView(ctx: Context, private val radius: Float, thickness: Float) : View(ctx) {
    private var level = 100
    private var charging = false
    private var spin = 0f
    private val oval = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = thickness; color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = thickness; strokeCap = Paint.Cap.ROUND
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }

    fun setState(level: Int, charging: Boolean) {
        this.level = level
        this.charging = charging
        if (charging && !animator.isStarted) animator.start()
        if (!charging && animator.isStarted) animator.cancel()
        invalidate()
    }

    fun stop() = animator.cancel()

    private fun colour(): Int {
        if (P.ringColourMode.value == "CUSTOM") return P.ringColour.value
        return when {
            charging -> 0xFF4BE38A.toInt()
            level <= 15 -> 0xFFFF5252.toInt()
            level <= 30 -> 0xFFFFA94D.toInt()
            else -> if (P.ringColourMode.value == "LEVEL") 0xFFFFFFFF.toInt() else P.ringColour.value
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        if (P.ringShowTrack.value) canvas.drawOval(oval, track)
        arc.color = colour()
        canvas.drawArc(oval, -90f, 360f * level / 100f, false, arc)
        if (charging) {
            val a = Math.toRadians((spin - 90).toDouble())
            dot.color = arc.color
            canvas.drawCircle(cx + radius * kotlin.math.cos(a).toFloat(), cy + radius * kotlin.math.sin(a).toFloat(), arc.strokeWidth * 0.9f, dot)
        }
    }
}
