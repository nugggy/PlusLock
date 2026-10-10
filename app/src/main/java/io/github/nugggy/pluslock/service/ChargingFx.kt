package io.github.nugggy.pluslock.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Waker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

val chargeAnimStyles = linkedMapOf(
    "BUBBLE" to "Liquid bubble: fills up, with drips rising into it",
    "WAVE" to "Rising tide: the whole screen fills to your level",
    "RING" to "Halo: a big glowing ring",
    "BATTERY" to "Battery: a classic battery filling with bubbles",
    "ORB" to "Orb: a glowing core sending out ripples",
    "BOLT" to "Lightning: a bolt that fills and throws sparks",
)

/** The battery state the charging effects draw from. */
data class ChargeState(
    val level: Int = 0,
    val charging: Boolean = false,
    val etaMs: Long = -1,
    val watts: Float = 0f,
    val tempC: Float = 0f,
    /** Level with decimals, when the phone reports enough to estimate it. */
    val exact: Float? = null,
)

/** Colour for a charge level, following the chosen colour mode. */
fun chargeColour(level: Int, hueShift: Float = 0f): Int = when (P.chargeColourMode.value) {
    "CUSTOM", "DUO" -> P.chargeColour.value
    "RAINBOW" -> Color.HSVToColor(floatArrayOf((hueShift * 360f) % 360f, 0.75f, 1f))
    else -> when {
        level < 20 -> 0xFFFF5252.toInt()
        level < 50 -> 0xFFFFD84D.toInt()
        else -> 0xFF4BE38A.toInt()
    }
}

/**
 * Charging indicators: a glow that stays on while plugged in, a full-screen charging
 * animation over the lock screen, and the plug, unplug and fully charged moments.
 */
class ChargingFx(private val svc: PlusAccessibilityService) {
    var state = ChargeState()
        private set
    private var glow: LightingView? = null
    private var glowStyle: GlowStyle? = null
    private var screen: ChargeScreenView? = null
    private var ducked = false
    private val hideScreenRunnable = Runnable { hideScreen() }
    private val bm = svc.getSystemService(BatteryManager::class.java)
    private var volts = 0f

    private val pm get() = svc.getSystemService(PowerManager::class.java)
    private val locked get() = svc.getSystemService(KeyguardManager::class.java).isKeyguardLocked

    fun update(s: ChargeState, voltage: Float) {
        state = s
        volts = voltage
        screen?.state = s
        refreshGlow()
    }

    fun onPlugged() {
        val h = P.chargePlugHaptic.value
        if (h != "NONE") Haptics.play(svc, h, "")
        if (P.chargingGlow.value && !P.chargeGlowStay.value) {
            svc.lighting.show(LightAlert(chargeColour(state.level), null, "Charging", "${state.level}%",
                effect = if (P.chargeColourMode.value == "RAINBOW") "RAINBOW" else P.chargeGlowEffect.value))
        }
        if (P.chargeAnimOn.value) {
            if (pm.isInteractive) showScreen()
            else if (P.chargeAnimWakeOnPlug.value) Waker.wake(svc, 8_000, skipInPocket = true) { showScreen() }
            // Plugging in often wakes the screen a moment after the battery broadcast.
            else svc.handler.postDelayed({ if (pm.isInteractive && screen == null) showScreen() }, 900)
        }
    }

    fun onUnplugged() {
        hideScreen()
        if (P.chargeUnplugGlow.value) {
            svc.lighting.show(LightAlert(0xFFFF7A59.toInt(), null, "Unplugged", "${state.level}%", effect = "PULSE", durationMs = 2_500))
        }
    }

    fun onFull() {
        if (!P.chargeFullAlert.value) return
        Haptics.play(svc, "HEARTBEAT", "")
        svc.lighting.show(LightAlert(0xFF4BE38A.toInt(), null, "Fully charged", "100%. You can unplug now.", effect = "RAINBOW"))
    }

    fun onScreenOn() {
        if (state.charging && P.chargeAnimOn.value && P.chargeAnimWhen.value == "WAKE") showScreen()
        refreshGlow()
    }

    fun onScreenOff() {
        hideScreen(immediate = true)
        removeGlow()
    }

    fun onUnlocked() {
        if (P.chargeAnimLockOnly.value) hideScreen()
        refreshGlow()
    }

    /** Settings changed: rebuild what's showing. */
    fun refresh() {
        removeGlow()
        refreshGlow()
        if (screen != null) { hideScreen(immediate = true); showScreen(force = true) }
    }

    /** Edge lighting is showing a notification, so step aside for a moment. */
    fun duck(on: Boolean) {
        if (ducked == on) return
        ducked = on
        refreshGlow()
    }

    fun preview() = showScreen(force = true)

    fun release() {
        hideScreen(immediate = true)
        removeGlow()
    }

    // ---- the glow that stays on while charging ----

    private fun refreshGlow() {
        val want = state.charging && P.chargingGlow.value && P.chargeGlowStay.value && !ducked && pm.isInteractive &&
            (P.chargeGlowWhere.value == "ALL" || locked) && !(P.lightingLandscapeOff.value && svc.isLandscape)
        if (!want) { removeGlow(); return }
        val rainbow = P.chargeColourMode.value == "RAINBOW"
        val style = GlowStyle(
            colour = chargeColour(state.level),
            colour2 = P.chargeColour2.value,
            effect = if (rainbow) "RAINBOW" else if (P.chargeColourMode.value == "DUO") "DUO" else P.chargeGlowEffect.value,
            thickness = svc.dp(P.chargeGlowThickness.value.toFloat()),
            shape = P.chargeGlowShape.value,
            strength = P.chargeGlowOpacity.value / 100f,
            speed = P.chargeGlowSpeed.value / 100f,
        )
        if (glow != null && style == glowStyle) {
            glow?.setProgress(if (P.chargeGlowLevel.value) state.level / 100f else 1f)
            return
        }
        removeGlow()
        val v = glowView(svc, style)
        v.setProgress(if (P.chargeGlowLevel.value) state.level / 100f else 1f)
        val b = svc.screenBounds()
        if (svc.addOverlay(v, svc.overlayParams(b.width(), b.height(), touchable = false))) { glow = v; glowStyle = style }
    }

    private fun removeGlow() {
        glow?.stop()
        svc.removeOverlay(glow)
        glow = null
        glowStyle = null
    }

    // ---- the full-screen charging animation ----

    private fun showScreen(force: Boolean = false) {
        if (!force && (!state.charging || !P.chargeAnimOn.value)) return
        if (!force && P.chargeAnimLockOnly.value && !locked) return
        if (screen != null) { scheduleHide(); return }
        val b = svc.screenBounds()
        val v = ChargeScreenView(svc, onTap = { hideScreen() })
        v.state = if (force && !state.charging) state.copy(charging = true, etaMs = 75 * 60_000L, watts = 33f) else state
        if (!svc.addOverlay(v, svc.overlayParams(b.width(), b.height(), touchable = true))) return
        screen = v
        svc.handler.post(ticker)
        scheduleHide()
    }

    private fun scheduleHide() {
        svc.handler.removeCallbacks(hideScreenRunnable)
        val s = P.chargeAnimSeconds.value
        if (s > 0) svc.handler.postDelayed(hideScreenRunnable, s * 1000L)
    }

    private fun hideScreen(immediate: Boolean = false) {
        svc.handler.removeCallbacks(hideScreenRunnable)
        svc.handler.removeCallbacks(ticker)
        val v = screen ?: return
        screen = null
        if (immediate) { v.stop(); svc.removeOverlay(v) }
        else v.animate().alpha(0f).setDuration(350).withEndAction { v.stop(); svc.removeOverlay(v) }.start()
    }

    /** Keeps the charging speed live while the animation shows. */
    private val ticker = object : Runnable {
        override fun run() {
            val v = screen ?: return
            if (state.charging) {
                val ua = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
                if (ua != 0 && ua != Int.MIN_VALUE && volts > 0) {
                    v.state = v.state.copy(watts = abs(ua) / 1_000_000f * volts)
                }
            }
            svc.handler.postDelayed(this, 2_000)
        }
    }
}

@SuppressLint("ViewConstructor")
private class ChargeScreenView(ctx: Context, private val onTap: () -> Unit) : View(ctx) {
    var state = ChargeState()
        set(v) { field = v; invalidate() }

    private val style = P.chargeAnimStyle.value
    private val size = P.chargeAnimSize.value / 100f
    private val speed = P.chargeAnimSpeed.value / 100f
    private val particles = P.chargeAnimParticles.value / 100f
    private val glowAmt = P.chargeAnimGlow.value / 100f
    private val dim = P.chargeAnimDim.value / 100f
    private val position = P.chargeAnimPosition.value
    private val mode = P.chargeColourMode.value
    private val colour2 = P.chargeColour2.value
    private val showPct = P.chargeAnimPct.value
    private val decimals = P.chargeAnimDecimals.value
    private val showEta = P.chargeAnimEta.value
    private val showWatts = P.chargeAnimWatts.value
    private val showTemp = P.chargeAnimTemp.value
    private val showClock = P.chargeAnimClock.value
    private val showBolt = P.chargeAnimBolt.value
    private val typeface = when (P.chargeAnimFont.value) {
        "THIN" -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
        "BOLD" -> Typeface.create("sans-serif-black", Typeface.NORMAL)
        "MONO" -> Typeface.MONOSPACE
        "SERIF" -> Typeface.SERIF
        "CONDENSED" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
        else -> Typeface.create("sans-serif", Typeface.NORMAL)
    }

    /** Seconds since the animation started, scaled by the speed setting. */
    private var t = 0f
    private val start = SystemClock.elapsedRealtime()
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1000
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = (SystemClock.elapsedRealtime() - start) / 1000f * speed; invalidate() }
    }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; typeface = this@ChargeScreenView.typeface
    }
    private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDDFFFFFF.toInt(); textAlign = Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val path = Path()
    private val clip = Path()
    private val rect = RectF()

    init {
        alpha = 0f
        animate().alpha(1f).setDuration(300).start()
        animator.start()
    }

    fun stop() = animator.cancel()

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP) onTap()
        return true
    }

    private fun colour(): Int = chargeColour(state.level, t * 0.08f)

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        c.drawColor(Color.argb((255 * dim).toInt(), 0, 0, 0))
        val cx = w / 2f
        val cy = when (position) { "TOP" -> h * 0.32f; "BOTTOM" -> h * 0.6f; else -> h * 0.44f }
        val r = min(w, h) * 0.3f * size
        val col = colour()
        val blur = if (glowAmt > 0.05f) BlurMaskFilter(r * 0.12f * glowAmt, BlurMaskFilter.Blur.NORMAL) else null
        val lvl = (state.exact ?: state.level.toFloat()) / 100f

        when (style) {
            "WAVE" -> drawTide(c, w, h, lvl, col)
            "RING" -> drawHalo(c, cx, cy, r, lvl, col, blur)
            "BATTERY" -> drawBattery(c, cx, cy, r, lvl, col, blur)
            "ORB" -> drawOrb(c, cx, cy, r, lvl, col, blur)
            "BOLT" -> drawBolt(c, cx, cy, r, lvl, col, blur)
            else -> drawBubble(c, w, h, cx, cy, r, lvl, col, blur)
        }

        // Clock along the top.
        if (showClock) {
            text.textSize = min(w, h) * 0.18f
            text.setShadowLayer(12f, 0f, 0f, 0x99000000.toInt())
            c.drawText(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), cx, h * 0.14f, text)
        }

        // Percentage and details.
        val pctY = when (style) {
            "WAVE" -> (h * (1 - lvl) - r * 0.25f).coerceIn(h * 0.25f, h * 0.8f)
            "BOLT" -> cy + r * 1.45f
            else -> cy + r * 0.16f
        }
        if (showPct) {
            text.textSize = r * (if (style == "WAVE" || style == "BOLT") 0.5f else 0.42f)
            text.setShadowLayer(10f, 0f, 0f, 0xAA000000.toInt())
            val label = if (decimals && state.exact != null) "%.2f%%".format(state.exact) else "${state.level}%"
            val tw = text.measureText(label)
            val boltInline = showBolt && style != "BOLT"
            val shift = if (boltInline) text.textSize * 0.28f else 0f
            c.drawText(label, cx + shift, pctY, text)
            if (boltInline) {
                fill.shader = null; fill.maskFilter = null; fill.color = 0xFFFFD84D.toInt()
                boltPath(cx - tw / 2f - text.textSize * 0.08f, pctY - text.textSize * 0.36f, text.textSize * 0.62f)
                c.drawPath(path, fill)
            }
        }
        val details = buildList {
            if (showEta) add(if (state.etaMs > 0) etaText(state.etaMs) else if (state.level >= 100) "Fully charged" else "Charging")
            if (showWatts && state.watts >= 1f) add("%.0f W".format(state.watts))
            if (showTemp && state.tempC > 0) add("%.1f °C".format(state.tempC))
        }
        if (details.isNotEmpty()) {
            sub.textSize = min(w, h) * 0.042f
            sub.setShadowLayer(8f, 0f, 0f, 0xAA000000.toInt())
            val y = when (style) {
                "WAVE" -> pctY + sub.textSize * 1.8f
                "BOLT" -> pctY + sub.textSize * 1.8f
                "BATTERY" -> cy + r * 1.35f
                else -> cy + r * 1.35f
            }
            c.drawText(details.joinToString("  ·  "), cx, y, sub)
        }
    }

    private fun etaText(ms: Long): String {
        val m = (ms / 60_000L).coerceAtLeast(1)
        return if (m >= 60) "${m / 60} h ${m % 60} min to full" else "$m min to full"
    }

    private fun shaderFor(top: Float, bottom: Float, col: Int): Shader? =
        if (mode == "DUO") LinearGradient(0f, top, 0f, bottom, colour2, col, Shader.TileMode.CLAMP) else null

    /** A filled shape whose top is a moving wave. */
    private fun wave(left: Float, right: Float, surface: Float, bottom: Float, amp: Float, length: Float, phase: Float) {
        path.reset()
        path.moveTo(left, bottom)
        path.lineTo(left, surface)
        var x = left
        val step = (right - left) / 40f
        while (x <= right + step) {
            path.lineTo(x, surface + amp * sin((x / length) * 2 * PI.toFloat() + phase))
            x += step
        }
        path.lineTo(right, bottom)
        path.close()
    }

    private fun hash(i: Int, salt: Int): Float = (((i + 1) * (7919 + salt * 104729)) % 1009) / 1009f

    private fun drawBubble(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        // Drips rising from a puddle at the bottom of the screen into the bubble.
        fill.shader = null; fill.maskFilter = null; fill.color = col
        val bottom = cy + r
        val count = (16 * particles).toInt()
        for (i in 0 until count) {
            val p = (t * 0.32f + hash(i, 1)) % 1f
            val y = h - p * (h - bottom + r * 0.1f)
            val x = cx + (hash(i, 2) - 0.5f) * r * 0.55f * (1f - p * 0.7f) + sin(t * 2f + i) * r * 0.03f
            val rad = r * (0.035f + 0.07f * hash(i, 3)) * (1f - 0.35f * p)
            c.drawCircle(x, y, rad, fill)
        }
        if (particles > 0f) {
            val pr = r * 0.5f
            val wob = sin(t * 2.4f) * r * 0.04f
            rect.set(cx - pr - wob, h - pr * 0.55f, cx + pr + wob, h + pr * 0.55f)
            c.drawOval(rect, fill)
        }
        // The ring.
        halo.color = col; halo.strokeWidth = r * 0.08f; halo.maskFilter = blur; halo.alpha = 220
        if (blur != null) c.drawCircle(cx, cy, r, halo)
        line.color = col; line.strokeWidth = r * 0.06f; line.shader = null
        c.drawCircle(cx, cy, r, line)
        // The liquid inside.
        c.save()
        clip.reset(); clip.addCircle(cx, cy, r * 0.95f, Path.Direction.CW)
        c.clipPath(clip)
        val surface = cy + r - 2 * r * lvl
        fill.shader = shaderFor(cy - r, cy + r, col)
        fill.alpha = 110
        wave(cx - r, cx + r, surface, cy + r, r * 0.07f, r * 1.1f, -t * 2.6f + 1.4f)
        c.drawPath(path, fill)
        fill.alpha = 230
        wave(cx - r, cx + r, surface, cy + r, r * 0.06f, r * 0.9f, t * 3.1f)
        c.drawPath(path, fill)
        c.restore()
        fill.shader = null; fill.alpha = 255
    }

    private fun drawTide(c: Canvas, w: Float, h: Float, lvl: Float, col: Int) {
        val surface = h * (1 - lvl.coerceIn(0.02f, 1f))
        fill.maskFilter = null
        fill.shader = shaderFor(surface, h, col)
        fill.color = col; fill.alpha = 90
        wave(0f, w, surface, h, h * 0.012f, w * 0.7f, -t * 2.2f)
        c.drawPath(path, fill)
        fill.alpha = 200
        wave(0f, w, surface + h * 0.008f, h, h * 0.01f, w * 0.5f, t * 2.8f + 1f)
        c.drawPath(path, fill)
        fill.shader = null
        // Bubbles rising through the water.
        fill.color = Color.WHITE
        val count = (24 * particles).toInt()
        for (i in 0 until count) {
            val p = (t * (0.12f + 0.1f * hash(i, 4)) + hash(i, 1)) % 1f
            val y = h - p * (h - surface)
            val x = hash(i, 2) * w + sin(t * 1.5f + i) * w * 0.01f
            fill.alpha = (110 * (1 - p)).toInt()
            c.drawCircle(x, y, w * (0.006f + 0.012f * hash(i, 3)), fill)
        }
        fill.alpha = 255
    }

    private fun drawHalo(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        rect.set(cx - r, cy - r, cx + r, cy + r)
        line.shader = null; line.color = col; line.alpha = 40; line.strokeWidth = r * 0.09f
        c.drawOval(rect, line)
        if (mode == "DUO") line.shader = android.graphics.SweepGradient(cx, cy, intArrayOf(col, colour2, col), null)
        line.alpha = 255
        if (blur != null) { halo.color = col; halo.strokeWidth = r * 0.13f; halo.maskFilter = blur; c.drawArc(rect, -90f, 360f * lvl, false, halo) }
        c.drawArc(rect, -90f, 360f * lvl, false, line)
        line.shader = null
        // Two orbiting sparks and a slow outer dashed ring.
        fill.shader = null; fill.maskFilter = null; fill.color = Color.WHITE
        for (k in 0..1) {
            val a = t * 1.6f + k * PI.toFloat()
            c.drawCircle(cx + cos(a) * r, cy + sin(a) * r, r * 0.045f, fill)
        }
        val ticks = (36 * particles.coerceAtLeast(0.3f)).toInt()
        line.strokeWidth = r * 0.02f; line.color = col; line.alpha = 120
        for (i in 0 until ticks) {
            val a = (i.toFloat() / ticks) * 2 * PI.toFloat() + t * 0.3f
            val r1 = r * 1.18f; val r2 = r * 1.24f
            c.drawLine(cx + cos(a) * r1, cy + sin(a) * r1, cx + cos(a) * r2, cy + sin(a) * r2, line)
        }
        line.alpha = 255
    }

    private fun drawBattery(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        val bw = r * 1.1f; val bh = r * 2f
        rect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        val corner = r * 0.18f
        if (blur != null) { halo.color = col; halo.strokeWidth = r * 0.08f; halo.maskFilter = blur; c.drawRoundRect(rect, corner, corner, halo) }
        line.shader = null; line.color = col; line.strokeWidth = r * 0.06f
        c.drawRoundRect(rect, corner, corner, line)
        fill.shader = null; fill.maskFilter = null; fill.color = col
        c.drawRoundRect(cx - bw * 0.2f, cy - bh / 2 - r * 0.16f, cx + bw * 0.2f, cy - bh / 2 + r * 0.02f, r * 0.05f, r * 0.05f, fill)
        c.save()
        val inner = r * 0.09f
        clip.reset(); clip.addRoundRect(rect.left + inner, rect.top + inner, rect.right - inner, rect.bottom - inner, corner * 0.6f, corner * 0.6f, Path.Direction.CW)
        c.clipPath(clip)
        val top = rect.top + inner; val bottom = rect.bottom - inner
        val surface = bottom - (bottom - top) * lvl
        fill.shader = shaderFor(top, bottom, col)
        wave(rect.left, rect.right, surface, bottom, r * 0.035f, bw * 0.8f, t * 3f)
        c.drawPath(path, fill)
        fill.shader = null; fill.color = Color.WHITE
        val count = (12 * particles).toInt()
        for (i in 0 until count) {
            val p = (t * 0.4f + hash(i, 1)) % 1f
            val y = bottom - p * (bottom - surface)
            fill.alpha = (150 * (1 - p)).toInt()
            c.drawCircle(rect.left + inner + hash(i, 2) * (bw - 2 * inner), y, r * (0.025f + 0.03f * hash(i, 3)), fill)
        }
        fill.alpha = 255
        c.restore()
    }

    private fun drawOrb(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        // Ripples spreading outwards.
        val rings = (4 * particles.coerceAtLeast(0.25f)).toInt().coerceAtLeast(1)
        line.shader = null; line.strokeWidth = r * 0.025f
        for (k in 0 until rings) {
            val p = (t * 0.35f + k.toFloat() / rings) % 1f
            line.color = col; line.alpha = (200 * (1 - p)).toInt()
            c.drawCircle(cx, cy, r * (0.6f + p * 1.1f), line)
        }
        line.alpha = 255
        // The core breathes, and grows with the charge.
        val core = r * (0.45f + 0.35f * lvl) * (1f + 0.04f * sin(t * 3f))
        fill.maskFilter = blur
        fill.shader = RadialGradient(cx, cy, core, intArrayOf(Color.WHITE, col, if (mode == "DUO") colour2 else Color.argb(0, Color.red(col), Color.green(col), Color.blue(col))),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, core, fill)
        fill.shader = null; fill.maskFilter = null
        text.color = Color.WHITE
    }

    private fun drawBolt(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        val s = r * 2.1f
        boltPath(cx, cy, s)
        val outline = Path(path)
        if (blur != null) { halo.color = col; halo.strokeWidth = r * 0.07f; halo.maskFilter = blur; c.drawPath(outline, halo) }
        line.shader = null; line.color = col; line.strokeWidth = r * 0.04f; line.strokeJoin = Paint.Join.ROUND
        c.drawPath(outline, line)
        c.save()
        c.clipPath(outline)
        val top = cy - s / 2; val bottom = cy + s / 2
        fill.maskFilter = null
        fill.shader = shaderFor(top, bottom, col); fill.color = col
        wave(cx - s, cx + s, bottom - (bottom - top) * lvl, bottom, r * 0.03f, r * 0.6f, t * 3f)
        c.drawPath(path, fill)
        fill.shader = null
        c.restore()
        // Sparks flying off.
        fill.color = col
        val count = (14 * particles).toInt()
        for (i in 0 until count) {
            val p = (t * 0.7f + hash(i, 1)) % 1f
            val a = hash(i, 2) * 2 * PI.toFloat()
            val d = r * (0.5f + p * 0.9f)
            fill.alpha = (255 * (1 - p)).toInt()
            c.drawCircle(cx + cos(a) * d, cy + sin(a) * d, r * 0.03f * (1 - p * 0.5f), fill)
        }
        fill.alpha = 255
    }

    /** A lightning bolt centred on (cx, cy), size tall. Leaves it in [path]. */
    private fun boltPath(cx: Float, cy: Float, size: Float) {
        val pts = floatArrayOf(0.12f, -0.5f, -0.28f, 0.06f, -0.02f, 0.06f, -0.14f, 0.5f, 0.28f, -0.08f, 0.02f, -0.08f)
        path.reset()
        for (i in pts.indices step 2) {
            val x = cx + pts[i] * size * 0.9f; val y = cy + pts[i + 1] * size
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }
}
