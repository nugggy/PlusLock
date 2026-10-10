package io.github.nugggy.pluslock.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.view.RoundedCorner
import android.view.View
import android.view.WindowInsets
import android.view.animation.LinearInterpolator
import io.github.nugggy.pluslock.core.Diag
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Waker
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

/** Every glow effect, in the order the pickers show them. */
val lightingEffects = linkedMapOf(
    "COMET" to "Comet: lights race round the edge",
    "PULSE" to "Pulse: the whole edge breathes",
    "HEARTBEAT" to "Heartbeat: a double thump",
    "NEON" to "Neon: a spinning sweep",
    "RAINBOW" to "Rainbow: every colour at once",
    "DUO" to "Duo: two colours chasing each other",
    "HUE" to "Disco: the colour drifts through the rainbow",
    "DASH" to "Marching dashes",
    "SPARKLE" to "Sparkle: twinkling lights",
    "SOLID" to "Solid: a steady glow",
)

val lightingShapes = linkedMapOf(
    "EDGE" to "The whole screen edge",
    "TOP" to "Just the top edge",
    "SIDES" to "The two long sides",
    "CAMERA" to "Around the selfie camera",
)

/**
 * One alert for the edge glow and its preview bubble.
 * key groups alerts from the same conversation so a burst of messages updates one bubble.
 */
data class LightAlert(
    val colour: Int,
    val icon: Drawable? = null,
    val title: String? = null,
    val text: String? = null,
    val effect: String? = null,
    val key: String? = null,
    val count: Int = 1,
    val open: PendingIntent? = null,
    val canReply: Boolean = false,
    /** Reminder glows are softer and skip the bubble. */
    val reminder: Boolean = false,
    /** Shown over the lock screen, so no quick reply. */
    val locked: Boolean = false,
    /** Light up the screen if it's off, whatever the edge lighting setting says. */
    val wake: Boolean = false,
    /** How long to glow, instead of the edge lighting setting. */
    val durationMs: Long? = null,
)

/** How a glow looks. Shared by notifications and the charging glow. */
data class GlowStyle(
    val colour: Int,
    val colour2: Int,
    val effect: String,
    val thickness: Float,
    val shape: String,
    val strength: Float = 1f,
    val speed: Float = 1f,
    val softness: Float = 1f,
    val comets: Int = 2,
    val tail: Float = 0.18f,
)

/** Edge Lighting+ style glow around the screen when a notification arrives. */
class EdgeLighting(private val svc: PlusAccessibilityService) {
    private var view: LightingView? = null
    private var bubble: NotificationBubble? = null
    private var current: LightAlert? = null
    private val hideRunnable = Runnable { hide() }

    fun show(colour: Int, icon: Drawable?, title: String?, text: String?, effectOverride: String? = null) =
        show(LightAlert(colour, icon, title, text, effectOverride))

    fun show(alert: LightAlert) {
        svc.handler.post {
            if (!svc.getSystemService(PowerManager::class.java).isInteractive) {
                // Screen off: light it up first if asked to, then glow over the lock screen.
                if (alert.reminder || !(alert.wake || P.lightingWake.value)) {
                    Diag.log("lighting", "screen off, not waking for ${alert.title ?: "a glow"}"); return@post
                }
                Waker.wake(svc, holdMs = (alert.durationMs ?: (P.lightingDuration.value * 1000L)) + 1500,
                    skipInPocket = P.lightingWakePocket.value) { show(alert) }
                return@post
            }
            val cur = current
            // Another message in the same chat: refresh the bubble instead of restarting the glow.
            if (P.bubbleGroup.value && cur != null && alert.key != null && cur.key == alert.key && view != null && !alert.reminder) {
                current = alert
                bubble?.update(alert)
                scheduleHide(alert.durationMs)
                return@post
            }
            hide(immediate = true)
            current = alert
            val style = GlowStyle(
                colour = alert.colour,
                colour2 = P.lightingColour2.value,
                effect = alert.effect ?: P.lightingEffect.value,
                thickness = svc.dp(P.lightingThickness.value.toFloat()),
                shape = P.lightingShape.value,
                strength = (if (alert.reminder) 0.55f else 1f) * P.lightingOpacity.value / 100f,
                speed = P.lightingSpeed.value / 100f,
                softness = P.lightingSoftness.value / 100f,
                comets = P.lightingComets.value,
                tail = P.lightingTail.value / 100f,
            )
            val v = glowView(svc, style)
            val b = svc.screenBounds()
            val lp = svc.overlayParams(b.width(), b.height(), touchable = false)
            if (!svc.addOverlay(v, lp)) { current = null; Diag.log("lighting", "couldn't draw the glow"); return@post }
            Diag.log("lighting", "glow for ${alert.title ?: "an alert"}, ${style.effect}")
            view = v
            svc.chargeFx.duck(true)
            if (P.ringNotifFlash.value && !alert.reminder) svc.ring.flash(alert.colour, alert.durationMs ?: (P.lightingDuration.value * 1000L))
            if (P.lightingPill.value && !alert.reminder && (alert.title != null || alert.icon != null)) {
                val insets = svc.wm.currentWindowMetrics.windowInsets
                val cutoutBottom = insets.displayCutout?.boundingRects.orEmpty().minByOrNull { it.top }?.bottom
                    ?: insets.getInsets(WindowInsets.Type.statusBars()).top
                val navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                bubble = NotificationBubble(svc, alert, cutoutBottom.toFloat(), navBottom.toFloat(),
                    onTouching = { touching -> if (touching) svc.handler.removeCallbacks(hideRunnable) else scheduleHide() },
                    onGone = { hide() },
                ).also { it.attach() }
            }
            scheduleHide(if (alert.reminder) 2_500L else alert.durationMs)
        }
    }

    private fun scheduleHide(ms: Long? = null) {
        svc.handler.removeCallbacks(hideRunnable)
        svc.handler.postDelayed(hideRunnable, ms ?: (P.lightingDuration.value * 1000L))
    }

    fun hide(immediate: Boolean = false) {
        svc.handler.removeCallbacks(hideRunnable)
        current = null
        bubble?.detach(immediate)
        bubble = null
        val v = view ?: return
        view = null
        svc.chargeFx.duck(false)
        if (immediate) {
            v.stop(); svc.removeOverlay(v)
        } else {
            v.animate().alpha(0f).setDuration(400).withEndAction {
                v.stop(); svc.removeOverlay(v)
            }.start()
        }
    }
}

/** A full-screen glow view in the given style, fitted to this phone's corners and camera. */
internal fun glowView(svc: PlusAccessibilityService, style: GlowStyle): LightingView {
    val b = svc.screenBounds()
    val insets = svc.wm.currentWindowMetrics.windowInsets
    val radius = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat() ?: svc.dp(32f)
    val hole = insets.displayCutout?.boundingRects.orEmpty().filter { it.width() < b.width() / 3 }.minByOrNull { it.top }
    val camera = if (style.shape == "CAMERA") {
        val cx = hole?.exactCenterX() ?: (b.width() / 2f)
        val cy = hole?.exactCenterY() ?: svc.dp(22f)
        val r = (if (hole != null) max(hole.width(), hole.height()) / 2f else svc.dp(14f)) + svc.dp(7f)
        floatArrayOf(cx, cy, r)
    } else null
    val s = if (style.shape == "CAMERA") style.copy(thickness = style.thickness * 0.7f) else style
    return LightingView(svc, s, radius, camera)
}

@SuppressLint("ViewConstructor")
internal class LightingView(
    ctx: Context,
    private val style: GlowStyle,
    private val radius: Float,
    /** cx, cy, r of the camera ring when shape is CAMERA. */
    private val camera: FloatArray?,
) : View(ctx) {
    private val colour = style.colour
    private val effect = style.effect
    private val thickness = style.thickness
    private val strength = style.strength
    private var t = 0f
    /** 0 to 1: how much of the edge is lit, starting from the bottom middle. Used for battery level. */
    private var progress = 1f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        val base = when (effect) {
            "PULSE" -> 1400L
            "HEARTBEAT" -> 1200L
            "SPARKLE" -> 2600L
            "HUE" -> 5000L
            "DASH" -> 1600L
            else -> 2200L
        }
        duration = (base / style.speed.coerceIn(0.2f, 4f)).toLong()
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thickness
        strokeCap = Paint.Cap.ROUND
    }
    private val softness = style.softness
    private val glow: Paint? = if (softness <= 0.05f) null else Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.style = Paint.Style.STROKE
        strokeWidth = thickness * (1.6f + softness)
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(thickness * 2.2f * softness, BlurMaskFilter.Blur.NORMAL)
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)

    private val rect = RectF()
    private val path = Path()
    private val fill = Path()
    private val seg = Path()
    private var measure: PathMeasure? = null
    private var closed = true
    /** Where along the path the bottom middle sits, for level fills. */
    private var bottomAt = 0f
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null
    private var cx = 0f
    private var cy = 0f
    private val pos = FloatArray(2)
    private val hsv = FloatArray(3)

    init {
        alpha = 0f
        animate().alpha(1f).setDuration(250).start()
        animator.start()
    }

    fun stop() = animator.cancel()

    fun setProgress(p: Float) {
        val v = p.coerceIn(0f, 1f)
        if (v == progress) return
        progress = v
        buildFill()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = thickness / 2f + 1f
        path.reset()
        val r = (radius - inset).coerceAtLeast(0f)
        val shape = style.shape
        when {
            shape == "CAMERA" && camera != null -> {
                path.addCircle(camera[0], camera[1], camera[2], Path.Direction.CW)
                cx = camera[0]; cy = camera[1]; closed = true
            }
            shape == "TOP" -> {
                // Down the top-left corner, across the top, down the top-right corner.
                path.moveTo(inset, r + inset + r * 0.6f)
                path.lineTo(inset, r + inset)
                path.arcTo(RectF(inset, inset, inset + 2 * r, inset + 2 * r), 180f, 90f)
                path.lineTo(w - inset - r, inset)
                path.arcTo(RectF(w - inset - 2 * r, inset, w - inset, inset + 2 * r), 270f, 90f)
                path.lineTo(w - inset, r + inset + r * 0.6f)
                cx = w / 2f; cy = 0f; closed = false
            }
            shape == "SIDES" -> {
                // Just the left side; onDraw mirrors it onto the right.
                path.moveTo(inset, h - r - inset); path.lineTo(inset, r + inset)
                cx = w / 2f; cy = h / 2f; closed = false
            }
            else -> {
                rect.set(inset, inset, w - inset, h - inset)
                path.addRoundRect(rect, r, r, Path.Direction.CW)
                cx = w / 2f; cy = h / 2f; closed = true
            }
        }
        val m = PathMeasure(path, closed)
        measure = m
        // A rounded rectangle starts top left and runs clockwise; a circle starts at 3 o'clock.
        bottomAt = when {
            shape == "CAMERA" -> m.length * 0.25f
            closed -> {
                val sw = w - 2 * inset - 2 * r; val sh = h - 2 * inset - 2 * r
                sw + sh + sw / 2f + (PI * r).toFloat()
            }
            else -> 0f
        }
        sweep = when (effect) {
            "RAINBOW" -> SweepGradient(cx, cy,
                intArrayOf(0xFFFF5E5E.toInt(), 0xFFFFC75E.toInt(), 0xFF7CFF6B.toInt(), 0xFF5EE4FF.toInt(),
                    0xFF7F7CFF.toInt(), 0xFFFF6BE0.toInt(), 0xFFFF5E5E.toInt()), null)
            "NEON" -> SweepGradient(cx, cy,
                intArrayOf(colour, Color.TRANSPARENT, colour, Color.TRANSPARENT, colour), null)
            "DUO" -> SweepGradient(cx, cy,
                intArrayOf(colour, style.colour2, colour, style.colour2, colour), null)
            else -> null
        }
        if (effect == "DASH") {
            val d = thickness * 4f
            stroke.pathEffect = DashPathEffect(floatArrayOf(d, d * 0.8f), 0f)
        }
        buildFill()
    }

    /** The lit part of the edge for a level fill: rising up both sides from the bottom. */
    private fun buildFill() {
        val m = measure ?: return
        fill.reset()
        if (progress >= 0.999f || !closed) return
        val len = m.length
        val half = len * progress / 2f
        fun add(from: Float, to: Float) {
            var a = from; var b = to
            while (a < 0) { a += len; b += len }
            if (b <= len) m.getSegment(a, b, fill, true)
            else { m.getSegment(a, len, fill, true); m.getSegment(0f, b - len, fill, true) }
        }
        add(bottomAt - half, bottomAt + half)
    }

    private val lit: Path get() = if (progress < 0.999f && closed) fill else path

    override fun onDraw(canvas: Canvas) {
        drawEffect(canvas)
        if (style.shape == "SIDES") {
            canvas.save()
            canvas.scale(-1f, 1f, width / 2f, 0f)
            drawEffect(canvas)
            canvas.restore()
        }
    }

    private fun drawEffect(canvas: Canvas) {
        if (lit !== path) drawPath(canvas, path, colour, 0.12f)
        when (effect) {
            "COMET" -> drawComets(canvas)
            "PULSE" -> drawPath(canvas, lit, colour, 0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2 * PI).toFloat()))
            "HEARTBEAT" -> {
                fun beat(c: Float) = (1f - kotlin.math.abs(t - c) / 0.07f).coerceAtLeast(0f)
                drawPath(canvas, lit, colour, 0.25f + 0.75f * max(beat(0.08f), beat(0.26f) * 0.8f))
            }
            "HUE" -> {
                Color.colorToHSV(colour, hsv)
                hsv[0] = (hsv[0] + t * 360f) % 360f; hsv[1] = max(hsv[1], 0.6f); hsv[2] = 1f
                drawPath(canvas, lit, Color.HSVToColor(hsv), 1f)
            }
            "DASH" -> {
                val d = thickness * 4f
                stroke.pathEffect = DashPathEffect(floatArrayOf(d, d * 0.8f), -t * d * 1.8f * 4f)
                drawPath(canvas, lit, colour, 1f)
            }
            "SPARKLE" -> drawSparkles(canvas)
            "RAINBOW", "NEON", "DUO" -> {
                matrix.setRotate(t * 360f, cx, cy)
                sweep?.setLocalMatrix(matrix)
                stroke.shader = sweep; glow?.shader = sweep
                stroke.alpha = (255 * strength).toInt(); glow?.alpha = (200 * strength).toInt()
                glow?.let { canvas.drawPath(lit, it) }
                canvas.drawPath(lit, stroke)
                stroke.shader = null; glow?.shader = null
            }
            else -> drawPath(canvas, lit, colour, 1f)
        }
    }

    private fun drawComets(canvas: Canvas) {
        val m = measure ?: return
        val len = m.length
        val segLen = len * (if (closed) style.tail else style.tail * 1.6f).coerceIn(0.03f, 0.6f)
        if (lit === path) drawPath(canvas, path, colour, 0.18f) else drawPath(canvas, lit, colour, 0.45f)
        val n = style.comets.coerceIn(1, 4)
        for (k in 0 until n) {
            // Draw each comet as a few pieces that fade towards the tail.
            for (piece in 0 until 4) {
                seg.reset()
                val pieceLen = segLen / 4f
                if (closed) {
                    val head = ((t + k.toFloat() / n) % 1f) * len
                    val end = head - piece * pieceLen
                    val start = end - pieceLen
                    var a = start; var b = end
                    while (a < 0) { a += len; b += len }
                    if (b <= len) m.getSegment(a, b, seg, true)
                    else { m.getSegment(a, len, seg, true); m.getSegment(0f, b - len, seg, true) }
                } else {
                    if (piece > 0) continue
                    // An open path: the comets sweep outwards from the middle and back.
                    val phase = (t + k.toFloat() / n) % 1f
                    val centre = len / 2f + (if (k % 2 == 0) -1 else 1) * (len / 2f) * sin(phase * PI).toFloat()
                    m.getSegment(centre - segLen / 2f, centre + segLen / 2f, seg, true)
                }
                drawPath(canvas, seg, colour, 1f - piece * 0.24f)
            }
        }
    }

    private fun drawSparkles(canvas: Canvas) {
        val m = measure ?: return
        drawPath(canvas, lit, colour, 0.2f)
        val count = 28
        val len = m.length
        for (i in 0 until count) {
            val h1 = ((i * 7919) % 1000) / 1000f
            val h2 = ((i * 104729) % 997) / 997f
            val life = (t * 2f + h2) % 1f
            val a = sin(life * PI).toFloat()
            if (a <= 0.05f) continue
            if (!m.getPosTan(h1 * len, pos, null)) continue
            dot.color = if (i % 3 == 0) style.colour2 else colour
            dot.alpha = (255 * a * strength).toInt()
            dot.maskFilter = null
            canvas.drawCircle(pos[0], pos[1], thickness * (0.5f + 0.7f * a), dot)
        }
    }

    private fun drawPath(canvas: Canvas, p: Path, c: Int, a: Float) {
        glow?.let {
            it.color = c; it.alpha = (200 * a * strength).toInt()
            canvas.drawPath(p, it)
        }
        stroke.color = c; stroke.alpha = (255 * a * strength).toInt()
        canvas.drawPath(p, stroke)
    }
}
