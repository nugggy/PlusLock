package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.net.TrafficStats
import android.os.PowerManager
import android.os.SystemClock
import android.view.View
import io.github.nugggy.pluslock.core.P
import kotlin.math.max

/** A small live download and upload speed readout beside the selfie camera. */
class NetSpeed(private val svc: PlusAccessibilityService) {
    private var view: SpeedView? = null
    private var lastBytes = -1L
    private var lastTime = 0L
    private val tick = object : Runnable {
        override fun run() {
            sample()
            svc.handler.postDelayed(this, 1_000)
        }
    }

    fun refresh() {
        remove()
        if (!P.netSpeedOn.value) return
        if (svc.isLandscape && P.netSpeedHideLandscape.value) return
        if (!svc.getSystemService(PowerManager::class.java).isInteractive) return
        val b = svc.screenBounds()
        val cut = svc.wm.currentWindowMetrics.windowInsets.displayCutout?.boundingRects
            ?.filter { it.width() < b.width() / 3 }?.minByOrNull { it.top }
        val w = svc.dp(70f).toInt()
        val h = svc.dp(24f).toInt()
        val cx = cut?.exactCenterX() ?: (b.width() / 2f)
        val cy = cut?.exactCenterY() ?: svc.dp(20f)
        val holeHalf = (cut?.width() ?: svc.dp(28f).toInt()) / 2f + svc.dp(10f)
        val right = P.netSpeedSide.value == "RIGHT"
        val v = SpeedView(svc, alignRight = !right)
        val lp = svc.overlayParams(w, h, touchable = false)
        lp.x = if (right) (cx + holeHalf).toInt() else (cx - holeHalf - w).toInt()
        lp.y = (cy - h / 2f).toInt()
        if (svc.addOverlay(v, lp)) {
            view = v
            lastBytes = -1L
            svc.handler.post(tick)
        }
    }

    private fun sample() {
        val bytes = TrafficStats.getTotalRxBytes() + TrafficStats.getTotalTxBytes()
        val now = SystemClock.elapsedRealtime()
        if (lastBytes >= 0 && bytes >= lastBytes) {
            val perSec = (bytes - lastBytes) * 1000 / max(1L, now - lastTime)
            view?.setText(format(perSec))
        }
        lastBytes = bytes
        lastTime = now
    }

    private fun format(bps: Long): String = when {
        bps >= 1_000_000 -> "%.1f MB/s".format(bps / 1_000_000f)
        bps >= 1_000 -> "${bps / 1_000} KB/s"
        else -> "0 KB/s"
    }

    fun remove() {
        svc.handler.removeCallbacks(tick)
        svc.removeOverlay(view)
        view = null
    }
}

@SuppressLint("ViewConstructor")
private class SpeedView(ctx: Context, private val alignRight: Boolean) : View(ctx) {
    private var text = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 11 * resources.displayMetrics.scaledDensityCompat()
        typeface = Typeface.DEFAULT_BOLD
        // A soft shadow keeps it readable on light status bars too.
        setShadowLayer(3f * resources.displayMetrics.density, 0f, 0f, 0xCC000000.toInt())
    }

    fun setText(t: String) {
        if (t == text) return
        text = t
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        paint.textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
        val x = if (alignRight) width.toFloat() - 2 else 2f
        canvas.drawText(text, x, height / 2f + paint.textSize / 3, paint)
    }
}

private fun android.util.DisplayMetrics.scaledDensityCompat(): Float =
    android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 1f, this)
