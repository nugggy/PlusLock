package io.github.nugggy.pluslock.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.os.SystemClock
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.nugggy.pluslock.core.P
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

val ringStyles = linkedMapOf(
    "SOLID" to "Solid",
    "GRADIENT" to "Gradient between two colours",
    "RAINBOW" to "Rainbow",
    "SEGMENTS" to "Segments, like a battery bar",
    "DOTS" to "Dots",
)

val ringChargeAnims = linkedMapOf(
    "DOT" to "A dot circling the ring",
    "COMET" to "A comet with a tail",
    "PULSE" to "The ring breathes",
    "SPIN" to "Rainbow spin",
    "FILL" to "Keeps filling up to full",
    "NONE" to "No animation",
)

/** A battery ring drawn around the punch-hole selfie camera. */
class CameraRing(private val svc: PlusAccessibilityService) {
    private var view: RingView? = null
    private var label: EtaView? = null
    var level = 100
        private set
    var charging = false
        private set
    private var etaMs = -1L
    private var hiddenForFull = false

    fun update(level: Int, charging: Boolean, etaMs: Long = -1L) {
        this.level = level
        this.charging = charging
        this.etaMs = etaMs
        val hideFull = P.ringHideFull.value && level >= 100 && !charging
        if (hideFull != hiddenForFull) { hiddenForFull = hideFull; refresh(); return }
        view?.setState(level, charging)
        label?.setText(labelText())
    }

    /** Briefly light the ring in a notification's colour. */
    fun flash(colour: Int, ms: Long) = view?.flash(colour, ms)

    /** "1 h 20 m to full" while charging, the level if asked for, or nothing. */
    private fun labelText(): String {
        val parts = mutableListOf<String>()
        if (P.ringShowPct.value) parts += "$level%"
        if (charging && P.ringShowEta.value && etaMs > 0) {
            val m = (etaMs / 60_000L).coerceAtLeast(1)
            parts += if (m >= 60) "${m / 60} h ${m % 60} m to full" else "$m m to full"
        }
        return parts.joinToString(" · ")
    }

    fun refresh() {
        remove()
        if (!P.ringOn.value) return
        if (svc.isLandscape && P.ringHideLandscape.value) return
        if (P.ringHideFull.value && level >= 100 && !charging) return
        val locked = svc.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        when (P.ringWhere.value) {
            "LOCK" -> if (!locked) return
            "UNLOCKED" -> if (locked) return
        }
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
        val glowRoom = thickness * 2.5f * P.ringGlow.value / 100f
        val size = ((r + thickness + glowRoom) * 2 + svc.dp(4f)).toInt()
        val cx = cx0 + svc.dp(P.ringOffsetX.value / 2f)
        val cy = cy0 + svc.dp(P.ringOffsetY.value / 2f)
        val v = RingView(svc, r, thickness)
        v.setState(level, charging)
        val lp = svc.overlayParams(size, size, touchable = false)
        lp.x = (cx - size / 2f).toInt()
        lp.y = (cy - size / 2f).toInt()
        if (svc.addOverlay(v, lp)) view = v

        if (P.ringShowEta.value || P.ringShowPct.value) {
            // Sits on the side the network speed readout isn't using.
            val left = P.netSpeedOn.value && P.netSpeedSide.value == "RIGHT"
            val l = EtaView(svc, alignRight = left)
            val lw = svc.dp(140f).toInt()
            val lh = svc.dp(20f).toInt()
            val llp = svc.overlayParams(lw, lh, touchable = false)
            llp.x = if (left) (cx - size / 2f - svc.dp(4f) - lw).toInt() else (cx + size / 2f + svc.dp(4f)).toInt()
            llp.y = (cy - lh / 2f).toInt()
            if (svc.addOverlay(l, llp)) { label = l; l.setText(labelText()) }
        }
    }

    fun remove() {
        view?.stop()
        svc.removeOverlay(view)
        view = null
        svc.removeOverlay(label)
        label = null
    }
}

@SuppressLint("ViewConstructor")
private class RingView(ctx: Context, private val radius: Float, private val thickness: Float) : View(ctx) {
    private var level = 100
    private var charging = false
    private var spin = 0f
    private var flashColour = 0
    private var flashUntil = 0L
    private val style = P.ringStyle.value
    private val anim = P.ringChargeAnim.value
    private val clockwise = P.ringClockwise.value
    private val startDeg = P.ringStart.value.toFloat()
    private val opacity = P.ringOpacity.value / 100f
    private val glowAmt = P.ringGlow.value / 100f
    private val oval = RectF()
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = thickness; color = 0x33FFFFFF
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE; strokeWidth = thickness
        strokeCap = if (this@RingView.style == "SEGMENTS") Paint.Cap.BUTT else Paint.Cap.ROUND
    }
    private val glow: Paint? = if (glowAmt <= 0.02f) null else Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE; strokeWidth = thickness * (1.5f + glowAmt); strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(thickness * 2f * glowAmt, BlurMaskFilter.Blur.NORMAL)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val animator = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { spin = it.animatedValue as Float; invalidate() }
    }

    init {
        alpha = opacity
        when (style) {
            "SEGMENTS" -> {
                val circ = (2 * PI * radius).toFloat()
                val seg = circ / 10f
                arc.pathEffect = DashPathEffect(floatArrayOf(seg * 0.78f, seg * 0.22f), 0f)
                track.pathEffect = arc.pathEffect
            }
            "DOTS" -> {
                val circ = (2 * PI * radius).toFloat()
                val seg = circ / 24f
                arc.strokeCap = Paint.Cap.ROUND
                arc.pathEffect = DashPathEffect(floatArrayOf(0.1f, seg), 0f)
                track.pathEffect = arc.pathEffect; track.strokeCap = Paint.Cap.ROUND
            }
        }
    }

    private fun low() = level <= P.ringLowLevel.value

    private fun needsAnimating() =
        (charging && anim != "NONE") || (P.ringLowPulse.value && low() && !charging) ||
            SystemClock.uptimeMillis() < flashUntil || style == "RAINBOW" && charging

    fun setState(level: Int, charging: Boolean) {
        this.level = level
        this.charging = charging
        updateAnimator()
        invalidate()
    }

    private fun updateAnimator() {
        val want = needsAnimating()
        if (want && !animator.isStarted) animator.start()
        if (!want && animator.isStarted) animator.cancel()
    }

    fun flash(colour: Int, ms: Long) {
        flashColour = colour
        flashUntil = SystemClock.uptimeMillis() + ms
        updateAnimator()
        postDelayed({ updateAnimator(); invalidate() }, ms + 50)
    }

    fun stop() = animator.cancel()

    private fun colour(): Int {
        if (SystemClock.uptimeMillis() < flashUntil) return flashColour
        if (charging) return P.ringChargeColour.value
        if (P.ringColourMode.value == "CUSTOM") return P.ringColour.value
        return when {
            low() -> 0xFFFF5252.toInt()
            level <= P.ringWarnLevel.value -> 0xFFFFA94D.toInt()
            else -> if (P.ringColourMode.value == "LEVEL") 0xFFFFFFFF.toInt() else P.ringColour.value
        }
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f; val cy = height / 2f
        oval.set(cx - radius, cy - radius, cx + radius, cy + radius)
        val flashing = SystemClock.uptimeMillis() < flashUntil
        if (P.ringShowTrack.value) canvas.drawOval(oval, track)
        val c = colour()
        var a = 1f
        if (flashing) a = 0.5f + 0.5f * sin(spin / 360f * 6 * PI).toFloat().let { it * it }
        else if (charging && anim == "PULSE") a = 0.45f + 0.55f * (0.5f + 0.5f * sin(spin / 360f * 2 * PI).toFloat())
        else if (!charging && P.ringLowPulse.value && low()) a = 0.35f + 0.65f * (0.5f + 0.5f * sin(spin / 360f * 4 * PI).toFloat())

        var sweepLevel = level / 100f
        if (charging && anim == "FILL") sweepLevel = level / 100f + (1 - level / 100f) * (spin / 360f)
        if (flashing) sweepLevel = 1f
        val sweepDeg = 360f * sweepLevel * (if (clockwise) 1 else -1)

        val shader = when {
            flashing -> null
            style == "RAINBOW" || (charging && anim == "SPIN") -> SweepGradient(cx, cy,
                intArrayOf(0xFFFF5E5E.toInt(), 0xFFFFC75E.toInt(), 0xFF7CFF6B.toInt(), 0xFF5EE4FF.toInt(),
                    0xFF7F7CFF.toInt(), 0xFFFF6BE0.toInt(), 0xFFFF5E5E.toInt()), null).also {
                    val m = android.graphics.Matrix()
                    m.setRotate(startDeg + if (charging) spin else 0f, cx, cy)
                    it.setLocalMatrix(m)
                }
            style == "GRADIENT" -> SweepGradient(cx, cy, intArrayOf(c, P.ringColour2.value, c), null).also {
                val m = android.graphics.Matrix(); m.setRotate(startDeg, cx, cy); it.setLocalMatrix(m)
            }
            else -> null
        }
        arc.shader = shader; arc.color = c; arc.alpha = (255 * a).toInt()
        glow?.let {
            it.shader = shader; it.color = c; it.alpha = (170 * a).toInt()
            canvas.drawArc(oval, startDeg, sweepDeg, false, it)
        }
        canvas.drawArc(oval, startDeg, sweepDeg, false, arc)

        if (charging && !flashing && (anim == "DOT" || anim == "COMET")) {
            val dir = if (clockwise) 1 else -1
            val head = startDeg + dir * spin
            if (anim == "COMET") {
                arc.shader = null; arc.color = Color.WHITE
                for (i in 0 until 6) {
                    arc.alpha = (220 * (1 - i / 6f)).toInt()
                    canvas.drawArc(oval, head - dir * (i + 1) * 9f, dir * 9f, false, arc)
                }
            } else {
                val rad = Math.toRadians(head.toDouble())
                dot.color = c
                canvas.drawCircle(cx + radius * cos(rad).toFloat(), cy + radius * sin(rad).toFloat(), thickness * 0.9f, dot)
            }
        }
        arc.shader = null
    }
}

@SuppressLint("ViewConstructor")
private class EtaView(ctx: Context, private val alignRight: Boolean) : View(ctx) {
    private var text = ""
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = P.ringChargeColour.value
        textSize = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 11f, resources.displayMetrics)
        typeface = android.graphics.Typeface.DEFAULT_BOLD
        setShadowLayer(3f * resources.displayMetrics.density, 0f, 0f, 0xCC000000.toInt())
    }

    fun setText(t: String) {
        if (t == text) return
        text = t
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (text.isEmpty()) return
        paint.textAlign = if (alignRight) Paint.Align.RIGHT else Paint.Align.LEFT
        canvas.drawText(text, if (alignRight) width.toFloat() else 0f, height / 2f + paint.textSize / 3, paint)
    }
}
