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
    "VORTEX" to "Vortex: sparks spiralling into the middle",
    "HEART" to "Heart: a heart filling up",
    "HONEYCOMB" to "Honeycomb: cells lighting up one by one",
    "BARS" to "Equaliser: bars dancing to your level",
    "DNA" to "Helix: a turning double helix",
    "WARP" to "Warp speed: stars rushing past",
    "FIREWORKS" to "Fireworks: bursts of colour",
    "MATRIX" to "Code rain: falling characters",
    "ECG" to "Heartbeat: a pulse line across the screen",
    "REACTOR" to "Reactor: spinning rings round a glowing core",
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
            "VORTEX" -> drawVortex(c, cx, cy, r, lvl, col, blur)
            "HEART" -> drawHeart(c, cx, cy, r, lvl, col, blur)
            "HONEYCOMB" -> drawHoneycomb(c, cx, cy, r, lvl, col, blur)
            "BARS" -> drawBars(c, cx, cy, r, lvl, col, blur)
            "DNA" -> drawHelix(c, cx, cy, r, lvl, col)
            "WARP" -> drawWarp(c, w, h, cx, cy, r, lvl, col)
            "FIREWORKS" -> drawFireworks(c, cx, cy, r, col)
            "MATRIX" -> drawCodeRain(c, w, h, r, col)
            "ECG" -> drawEcg(c, w, cy, r, col, blur)
            "REACTOR" -> drawReactor(c, cx, cy, r, lvl, col, blur)
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
            "BARS", "DNA", "ECG" -> cy - r * 0.95f
            "HEART" -> cy + r * 0.05f
            else -> cy + r * 0.16f
        }
        if (showPct && style == "MATRIX") {
            // A dark plate so the number reads over the falling code.
            fill.shader = null; fill.maskFilter = null; fill.color = 0xCC000000.toInt()
            c.drawRoundRect(cx - r * 0.75f, pctY - r * 0.42f, cx + r * 0.75f, pctY + r * 0.16f, r * 0.12f, r * 0.12f, fill)
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

    private fun drawVortex(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        val reach = r * 1.7f
        fill.shader = null; fill.maskFilter = null
        val count = (70 * particles).toInt()
        for (i in 0 until count) {
            val p = (t * (0.18f + 0.12f * hash(i, 4)) + hash(i, 1)) % 1f
            val d = reach * (1f - p)
            val a = hash(i, 2) * 2 * PI.toFloat() + p * 5f
            fill.color = if (i % 4 == 0 && mode == "DUO") colour2 else col
            fill.alpha = (255 * (0.2f + 0.8f * p)).toInt()
            c.drawCircle(cx + cos(a) * d, cy + sin(a) * d, r * (0.012f + 0.03f * (1f - p)), fill)
        }
        fill.alpha = 255
        rect.set(cx - r * 0.62f, cy - r * 0.62f, cx + r * 0.62f, cy + r * 0.62f)
        line.shader = null; line.color = col; line.strokeWidth = r * 0.05f
        if (blur != null) { halo.color = col; halo.strokeWidth = r * 0.08f; halo.maskFilter = blur; c.drawArc(rect, -90f, 360f * lvl, false, halo) }
        c.drawArc(rect, -90f, 360f * lvl, false, line)
    }

    private fun heartPath(cx: Float, cy: Float, s: Float) {
        path.reset()
        path.moveTo(cx, cy + s * 0.42f)
        path.cubicTo(cx - s * 0.62f, cy - s * 0.02f, cx - s * 0.42f, cy - s * 0.62f, cx, cy - s * 0.3f)
        path.cubicTo(cx + s * 0.42f, cy - s * 0.62f, cx + s * 0.62f, cy - s * 0.02f, cx, cy + s * 0.42f)
        path.close()
    }

    private fun drawHeart(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        val beat = sin(t * 6f)
        val s = r * 2.2f * (1f + 0.03f * beat * beat * beat)
        heartPath(cx, cy, s)
        val outline = Path(path)
        if (blur != null) { halo.color = col; halo.strokeWidth = r * 0.08f; halo.maskFilter = blur; c.drawPath(outline, halo) }
        line.shader = null; line.color = col; line.strokeWidth = r * 0.05f; line.strokeJoin = Paint.Join.ROUND
        c.drawPath(outline, line)
        c.save()
        c.clipPath(outline)
        val top = cy - s * 0.42f; val bottom = cy + s * 0.42f
        fill.maskFilter = null; fill.shader = shaderFor(top, bottom, col); fill.color = col
        val surface = bottom - (bottom - top) * lvl
        wave(cx - s, cx + s, surface, bottom, r * 0.04f, r * 0.8f, t * 3f)
        c.drawPath(path, fill)
        fill.shader = null; fill.color = Color.WHITE
        for (i in 0 until (10 * particles).toInt()) {
            val p = (t * 0.35f + hash(i, 1)) % 1f
            fill.alpha = (140 * (1 - p)).toInt()
            c.drawCircle(cx + (hash(i, 2) - 0.5f) * s * 0.6f, bottom - p * (bottom - surface), r * 0.03f, fill)
        }
        fill.alpha = 255
        c.restore()
    }

    private fun drawHoneycomb(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        val cell = r * 0.22f
        val dx = cell * 1.75f; val dy = cell * 1.52f
        val cells = ArrayList<FloatArray>()
        for (row in -5..5) for (k in -5..5) {
            val x = cx + k * dx + (if (row % 2 != 0) dx / 2f else 0f)
            val y = cy + row * dy
            if (kotlin.math.hypot(x - cx, y - cy) <= r * 1.25f) cells += floatArrayOf(x, y)
        }
        // Fill from the bottom up, like a battery.
        cells.sortByDescending { it[1] * 1000 + it[0] }
        val lit = (cells.size * lvl).toInt()
        cells.forEachIndexed { i, p ->
            path.reset()
            for (k in 0 until 6) {
                val a = (PI / 3 * k + PI / 6).toFloat()
                val x = p[0] + cos(a) * cell * 0.92f; val y = p[1] + sin(a) * cell * 0.92f
                if (k == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            fill.shader = null
            when {
                i < lit -> { fill.maskFilter = blur; fill.color = if (mode == "DUO" && i % 2 == 0) colour2 else col }
                i == lit -> { fill.maskFilter = null; fill.color = col; fill.alpha = (60 + 160 * (0.5f + 0.5f * sin(t * 5f))).toInt() }
                else -> { fill.maskFilter = null; fill.color = 0x22FFFFFF }
            }
            c.drawPath(path, fill)
        }
        fill.maskFilter = null; fill.alpha = 255
    }

    private fun drawBars(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        val n = 17
        val span = r * 2.8f
        val bw = span / n * 0.62f
        val base = cy + r * 0.9f
        for (i in 0 until n) {
            val x = cx - span / 2 + span * (i + 0.5f) / n
            val bounce = 0.55f + 0.45f * kotlin.math.abs(sin(t * (2.2f + hash(i, 3)) + i * 0.7f))
            val bh = r * 1.5f * (0.25f + 0.75f * lvl) * bounce
            fill.maskFilter = blur
            fill.shader = shaderFor(base - r * 1.5f, base, col); fill.color = col
            c.drawRoundRect(x - bw / 2, base - bh, x + bw / 2, base, bw / 2, bw / 2, fill)
            // A faint reflection below.
            fill.shader = null; fill.maskFilter = null; fill.alpha = 50
            c.drawRoundRect(x - bw / 2, base + r * 0.04f, x + bw / 2, base + r * 0.04f + bh * 0.3f, bw / 2, bw / 2, fill)
            fill.alpha = 255
        }
        fill.maskFilter = null
    }

    private fun drawHelix(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int) {
        val span = r * 3f
        val n = 26
        line.shader = null; line.strokeWidth = r * 0.025f
        fill.shader = null; fill.maskFilter = null
        for (i in 0 until n) {
            val x = cx - span / 2 + span * i / (n - 1)
            val a = i * 0.48f + t * 2f
            val y1 = cy + sin(a) * r * 0.55f
            val y2 = cy - sin(a) * r * 0.55f
            val on = i.toFloat() / n < lvl
            line.color = if (on) col else 0x44FFFFFF; line.alpha = if (on) 140 else 40
            c.drawLine(x, y1, x, y2, line)
            val depth = cos(a)
            val base = if (on) 255 else 90
            fill.color = if (on) col else 0x66FFFFFF
            fill.alpha = (base * (0.55f + 0.45f * depth).coerceIn(0.2f, 1f)).toInt()
            c.drawCircle(x, y1, r * (0.05f + 0.025f * depth), fill)
            fill.color = if (on) (if (mode == "DUO") colour2 else col) else 0x66FFFFFF
            fill.alpha = (base * (0.55f - 0.45f * depth).coerceIn(0.2f, 1f)).toInt()
            c.drawCircle(x, y2, r * (0.05f - 0.025f * depth), fill)
        }
        fill.alpha = 255; line.alpha = 255
    }

    private fun drawWarp(c: Canvas, w: Float, h: Float, cx: Float, cy: Float, r: Float, lvl: Float, col: Int) {
        val maxD = kotlin.math.hypot(w, h) / 2f
        line.shader = null
        val count = (90 * particles).toInt()
        for (i in 0 until count) {
            val p = (t * (0.35f + 0.3f * hash(i, 4)) + hash(i, 1)) % 1f
            val a = hash(i, 2) * 2 * PI.toFloat()
            val d = maxD * p * p
            val d0 = d * 0.82f
            line.color = if (i % 5 == 0) col else Color.WHITE
            line.alpha = (255 * p).toInt()
            line.strokeWidth = r * 0.01f + r * 0.025f * p
            c.drawLine(cx + cos(a) * d0, cy + sin(a) * d0, cx + cos(a) * d, cy + sin(a) * d, line)
        }
        line.alpha = 255
        rect.set(cx - r * 0.7f, cy - r * 0.7f, cx + r * 0.7f, cy + r * 0.7f)
        line.color = col; line.strokeWidth = r * 0.05f
        c.drawArc(rect, -90f, 360f * lvl, false, line)
    }

    private fun drawFireworks(c: Canvas, cx: Float, cy: Float, r: Float, col: Int) {
        fill.shader = null; fill.maskFilter = null
        val bursts = (5 * particles.coerceAtLeast(0.2f)).toInt().coerceAtLeast(1)
        val sparks = 26
        for (b in 0 until bursts) {
            val cycle = t * 0.45f + hash(b, 7)
            val p = cycle % 1f
            val gen = cycle.toInt()
            val ox = cx + (hash(b + gen * 13, 1) - 0.5f) * r * 2.6f
            val oy = cy + (hash(b + gen * 13, 2) - 0.6f) * r * 2.2f
            val hue = hash(b + gen * 13, 3) * 360f
            val bc = if (mode == "LEVEL" || mode == "CUSTOM") col else Color.HSVToColor(floatArrayOf(hue, 0.7f, 1f))
            for (k in 0 until sparks) {
                val a = k * 2 * PI.toFloat() / sparks
                val d = r * 0.9f * (1 - (1 - p) * (1 - p))
                fill.color = bc; fill.alpha = (255 * (1 - p)).toInt()
                c.drawCircle(ox + cos(a) * d, oy + sin(a) * d + r * 0.5f * p * p, r * 0.025f * (1.2f - p), fill)
            }
        }
        fill.alpha = 255
    }

    private val glyphs = "01アイウエオカキクケコ23456789ABCDEF<>*+="
    private val code = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }

    private fun drawCodeRain(c: Canvas, w: Float, h: Float, r: Float, col: Int) {
        val size = r * 0.13f
        code.textSize = size
        val cols = (w / (size * 1.1f)).toInt()
        val trail = 16
        for (k in 0 until cols) {
            if (hash(k, 9) > 0.35f + 0.65f * particles.coerceAtMost(1f)) continue
            val speed = 0.18f + 0.25f * hash(k, 4)
            val head = ((t * speed + hash(k, 1)) % 1.3f) * h
            val x = (k + 0.5f) * size * 1.1f
            for (j in 0 until trail) {
                val y = head - j * size * 1.05f
                if (y < -size || y > h + size) continue
                val seed = k * 31 + (y / size).toInt() * 17 + (if (j % 3 == 0) (t * 3).toInt() else 0)
                val ch = glyphs[((seed % glyphs.length) + glyphs.length) % glyphs.length]
                code.color = if (j == 0) Color.WHITE else col
                code.alpha = (255 * (1f - j.toFloat() / trail)).toInt()
                c.drawText(ch.toString(), x, y, code)
            }
        }
    }

    private fun drawEcg(c: Canvas, w: Float, cy: Float, r: Float, col: Int, blur: BlurMaskFilter?) {
        val beat = r * 1.6f
        fun ecg(x: Float): Float {
            val p = (((x / beat) % 1f) + 1f) % 1f
            return when {
                p < 0.08f -> -0.08f * sin(p / 0.08f * PI.toFloat())
                p in 0.16f..0.2f -> 0.18f * (p - 0.16f) / 0.04f
                p in 0.2f..0.24f -> 0.18f - 1.3f * (p - 0.2f) / 0.04f
                p in 0.24f..0.28f -> -1.12f + 1.5f * (p - 0.24f) / 0.04f
                p in 0.28f..0.31f -> 0.38f - 0.38f * (p - 0.28f) / 0.03f
                p in 0.42f..0.56f -> -0.22f * sin((p - 0.42f) / 0.14f * PI.toFloat())
                else -> 0f
            } * r * 0.6f
        }
        val base = cy + r * 0.25f
        val head = (t * r * 1.1f) % w
        path.reset()
        var x = 0f
        path.moveTo(0f, base + ecg(0f))
        while (x <= w) { x += 4f; path.lineTo(x, base + ecg(x)) }
        // Brightest just behind the moving head, fading further back.
        val fade = LinearGradient(head - w * 0.8f, 0f, head, 0f, intArrayOf(Color.TRANSPARENT, col), null, Shader.TileMode.CLAMP)
        line.shader = fade; line.strokeWidth = r * 0.035f; line.strokeJoin = Paint.Join.ROUND
        c.save()
        c.clipRect(0f, 0f, head, height.toFloat())
        if (blur != null) { halo.shader = fade; halo.strokeWidth = r * 0.07f; halo.maskFilter = blur; c.drawPath(path, halo); halo.shader = null }
        c.drawPath(path, line)
        c.restore()
        line.shader = null
        fill.shader = null; fill.maskFilter = null; fill.color = Color.WHITE
        c.drawCircle(head, base + ecg(head), r * 0.045f, fill)
    }

    private fun drawReactor(c: Canvas, cx: Float, cy: Float, r: Float, lvl: Float, col: Int, blur: BlurMaskFilter?) {
        line.shader = null
        val rings = listOf(Triple(1.15f, 18, 1f), Triple(0.95f, 10, -1.6f), Triple(0.76f, 6, 2.3f))
        rings.forEachIndexed { k, (scale, segs, spin) ->
            val rr = r * scale
            rect.set(cx - rr, cy - rr, cx + rr, cy + rr)
            line.color = if (k == 1 && mode == "DUO") colour2 else col
            line.alpha = 200 - k * 40
            line.strokeWidth = r * (0.05f - k * 0.01f)
            val step = 360f / segs
            for (i in 0 until segs) c.drawArc(rect, t * 40f * spin + i * step, step * 0.62f, false, line)
        }
        line.alpha = 255
        rect.set(cx - r * 0.58f, cy - r * 0.58f, cx + r * 0.58f, cy + r * 0.58f)
        line.color = col; line.strokeWidth = r * 0.06f
        if (blur != null) { halo.color = col; halo.strokeWidth = r * 0.1f; halo.maskFilter = blur; c.drawArc(rect, -90f, 360f * lvl, false, halo) }
        c.drawArc(rect, -90f, 360f * lvl, false, line)
        val core = r * 0.48f * (1f + 0.05f * sin(t * 4f))
        fill.maskFilter = null
        fill.shader = RadialGradient(cx, cy, core,
            intArrayOf(0x66FFFFFF, Color.argb(90, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, core, fill)
        fill.shader = null
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
