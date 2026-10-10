package io.github.nugggy.pluslock.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
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
import io.github.nugggy.pluslock.core.P
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.sin

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
            if (!svc.getSystemService(PowerManager::class.java).isInteractive) return@post
            val cur = current
            // Another message in the same chat: refresh the bubble instead of restarting the glow.
            if (P.bubbleGroup.value && cur != null && alert.key != null && cur.key == alert.key && view != null && !alert.reminder) {
                current = alert
                bubble?.update(alert)
                scheduleHide()
                return@post
            }
            hide(immediate = true)
            current = alert
            val b = svc.screenBounds()
            val insets = svc.wm.currentWindowMetrics.windowInsets
            val radius = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
                ?: svc.dp(32f)
            val cutouts = insets.displayCutout?.boundingRects.orEmpty()
            val hole = cutouts.filter { it.width() < b.width() / 3 }.minByOrNull { it.top }
            val cutoutBottom = cutouts.minByOrNull { it.top }?.bottom ?: insets.getInsets(WindowInsets.Type.statusBars()).top
            val shape = P.lightingShape.value
            val camera = if (shape == "CAMERA") {
                val cx = hole?.exactCenterX() ?: (b.width() / 2f)
                val cy = hole?.exactCenterY() ?: svc.dp(22f)
                val r = (if (hole != null) max(hole.width(), hole.height()) / 2f else svc.dp(14f)) + svc.dp(7f)
                floatArrayOf(cx, cy, r)
            } else null
            val thickness = svc.dp(P.lightingThickness.value.toFloat()) * (if (shape == "CAMERA") 0.7f else 1f)
            val v = LightingView(svc, alert.colour, alert.effect ?: P.lightingEffect.value, thickness, radius, shape, camera,
                if (alert.reminder) 0.55f else 1f)
            val lp = svc.overlayParams(b.width(), b.height(), touchable = false)
            if (!svc.addOverlay(v, lp)) { current = null; return@post }
            view = v
            if (P.lightingPill.value && !alert.reminder && (alert.title != null || alert.icon != null)) {
                val navBottom = insets.getInsets(WindowInsets.Type.navigationBars()).bottom
                bubble = NotificationBubble(svc, alert, cutoutBottom.toFloat(), navBottom.toFloat(),
                    onTouching = { touching -> if (touching) svc.handler.removeCallbacks(hideRunnable) else scheduleHide() },
                    onGone = { hide() },
                ).also { it.attach() }
            }
            scheduleHide(if (alert.reminder) 2_500L else null)
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
        if (immediate) {
            v.stop(); svc.removeOverlay(v)
        } else {
            v.animate().alpha(0f).setDuration(400).withEndAction {
                v.stop(); svc.removeOverlay(v)
            }.start()
        }
    }
}

@SuppressLint("ViewConstructor")
private class LightingView(
    ctx: Context,
    private val colour: Int,
    private val effect: String,
    private val thickness: Float,
    private val radius: Float,
    private val shape: String,
    /** cx, cy, r of the camera ring when shape is CAMERA. */
    private val camera: FloatArray?,
    private val strength: Float,
) : View(ctx) {
    private var t = 0f
    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = if (effect == "PULSE") 1400 else 2200
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener { t = it.animatedValue as Float; invalidate() }
    }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thickness
        strokeCap = Paint.Cap.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = thickness * 2.6f
        strokeCap = Paint.Cap.ROUND
        maskFilter = BlurMaskFilter(thickness * 2.2f, BlurMaskFilter.Blur.NORMAL)
    }

    private val rect = RectF()
    private val path = Path()
    private val seg = Path()
    private var measure: PathMeasure? = null
    private var closed = true
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null
    private var cx = 0f
    private var cy = 0f

    init {
        alpha = 0f
        animate().alpha(1f).setDuration(250).start()
        animator.start()
    }

    fun stop() = animator.cancel()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = thickness / 2f + 1f
        path.reset()
        val r = (radius - inset).coerceAtLeast(0f)
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
            else -> {
                rect.set(inset, inset, w - inset, h - inset)
                path.addRoundRect(rect, r, r, Path.Direction.CW)
                cx = w / 2f; cy = h / 2f; closed = true
            }
        }
        measure = PathMeasure(path, closed)
        sweep = when (effect) {
            "RAINBOW" -> SweepGradient(cx, cy,
                intArrayOf(0xFFFF5E5E.toInt(), 0xFFFFC75E.toInt(), 0xFF7CFF6B.toInt(), 0xFF5EE4FF.toInt(),
                    0xFF7F7CFF.toInt(), 0xFFFF6BE0.toInt(), 0xFFFF5E5E.toInt()), null)
            "NEON" -> SweepGradient(cx, cy,
                intArrayOf(colour, Color.TRANSPARENT, colour, Color.TRANSPARENT, colour), null)
            else -> null
        }
    }

    override fun onDraw(canvas: Canvas) {
        when (effect) {
            "COMET" -> drawComets(canvas)
            "PULSE" -> {
                val a = (0.35f + 0.65f * (0.5f + 0.5f * sin(t * 2 * PI).toFloat()))
                drawPath(canvas, path, colour, a)
            }
            "RAINBOW", "NEON" -> {
                matrix.setRotate(t * 360f, cx, cy)
                sweep?.setLocalMatrix(matrix)
                stroke.shader = sweep; glow.shader = sweep
                stroke.alpha = (255 * strength).toInt(); glow.alpha = (200 * strength).toInt()
                canvas.drawPath(path, glow)
                canvas.drawPath(path, stroke)
                stroke.shader = null; glow.shader = null
            }
            else -> drawPath(canvas, path, colour, 1f)
        }
    }

    private fun drawComets(canvas: Canvas) {
        val m = measure ?: return
        val len = m.length
        val segLen = len * (if (closed) 0.18f else 0.3f)
        drawPath(canvas, path, colour, 0.18f)
        for (k in 0 until 2) {
            seg.reset()
            if (closed) {
                val start = ((t + k * 0.5f) % 1f) * len
                val end = start + segLen
                if (end <= len) m.getSegment(start, end, seg, true)
                else {
                    m.getSegment(start, len, seg, true)
                    m.getSegment(0f, end - len, seg, true)
                }
            } else {
                // An open path: the two comets sweep outwards from the middle and back.
                val phase = (t + k * 0.5f) % 1f
                val centre = len / 2f + (if (k == 0) -1 else 1) * (len / 2f) * sin(phase * PI).toFloat()
                m.getSegment(centre - segLen / 2f, centre + segLen / 2f, seg, true)
            }
            drawPath(canvas, seg, colour, 1f)
        }
    }

    private fun drawPath(canvas: Canvas, p: Path, c: Int, a: Float) {
        glow.color = c; glow.alpha = (200 * a * strength).toInt()
        stroke.color = c; stroke.alpha = (255 * a * strength).toInt()
        canvas.drawPath(p, glow)
        canvas.drawPath(p, stroke)
    }
}
