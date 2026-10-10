package io.github.nugggy.pluslock.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
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
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.PowerManager
import android.text.TextPaint
import android.text.TextUtils
import android.view.RoundedCorner
import android.view.View
import android.view.animation.LinearInterpolator
import io.github.nugggy.pluslock.core.P
import kotlin.math.PI
import kotlin.math.sin

/** Edge Lighting+ style glow around the screen when a notification arrives. */
class EdgeLighting(private val svc: PlusAccessibilityService) {
    private var view: LightingView? = null
    private val hideRunnable = Runnable { hide() }

    fun show(colour: Int, icon: Drawable?, title: String?, text: String?, effectOverride: String? = null) {
        svc.handler.post {
            if (!svc.getSystemService(PowerManager::class.java).isInteractive) return@post
            hide(immediate = true)
            val b = svc.screenBounds()
            val insets = svc.wm.currentWindowMetrics.windowInsets
            val radius = insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius?.toFloat()
                ?: svc.dp(32f)
            val cutoutBottom = insets.displayCutout?.boundingRects?.minByOrNull { it.top }?.bottom ?: 0
            val v = LightingView(
                svc, colour, effectOverride ?: P.lightingEffect.value,
                svc.dp(P.lightingThickness.value.toFloat()), radius,
                if (P.lightingPill.value) icon else null,
                if (P.lightingPill.value) title else null,
                if (P.lightingPill.value) text else null,
                cutoutBottom.toFloat(),
            )
            val lp = svc.overlayParams(b.width(), b.height(), touchable = false)
            if (svc.addOverlay(v, lp)) {
                view = v
                svc.handler.postDelayed(hideRunnable, P.lightingDuration.value * 1000L)
            }
        }
    }

    fun hide(immediate: Boolean = false) {
        svc.handler.removeCallbacks(hideRunnable)
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
    private val icon: Drawable?,
    private val title: String?,
    private val text: String?,
    private val topInset: Float,
) : View(ctx) {
    private val density = resources.displayMetrics.density
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
    private val pillBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6101318.toInt() }
    private val pillEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.5f * density
    }
    private val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 14 * density; typeface = Typeface.DEFAULT_BOLD
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); textSize = 13 * density
    }

    private val rect = RectF()
    private val path = Path()
    private val seg = Path()
    private var measure: PathMeasure? = null
    private val matrix = Matrix()
    private var sweep: SweepGradient? = null

    init {
        alpha = 0f
        animate().alpha(1f).setDuration(250).start()
        animator.start()
    }

    fun stop() = animator.cancel()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val inset = thickness / 2f + 1f
        rect.set(inset, inset, w - inset, h - inset)
        path.reset()
        val r = (radius - inset).coerceAtLeast(0f)
        path.addRoundRect(rect, r, r, Path.Direction.CW)
        measure = PathMeasure(path, true)
        val cx = w / 2f; val cy = h / 2f
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
                matrix.setRotate(t * 360f, width / 2f, height / 2f)
                sweep?.setLocalMatrix(matrix)
                stroke.shader = sweep; glow.shader = sweep
                stroke.alpha = 255; glow.alpha = 200
                canvas.drawPath(path, glow)
                canvas.drawPath(path, stroke)
                stroke.shader = null; glow.shader = null
            }
            else -> drawPath(canvas, path, colour, 1f)
        }
        drawPill(canvas)
    }

    private fun drawComets(canvas: Canvas) {
        val m = measure ?: return
        val len = m.length
        val segLen = len * 0.18f
        drawPath(canvas, path, colour, 0.18f)
        for (k in 0 until 2) {
            val start = ((t + k * 0.5f) % 1f) * len
            seg.reset()
            val end = start + segLen
            if (end <= len) m.getSegment(start, end, seg, true)
            else {
                m.getSegment(start, len, seg, true)
                m.getSegment(0f, end - len, seg, true)
            }
            drawPath(canvas, seg, colour, 1f)
        }
    }

    private fun drawPath(canvas: Canvas, p: Path, c: Int, a: Float) {
        glow.color = c; glow.alpha = (200 * a).toInt()
        stroke.color = c; stroke.alpha = (255 * a).toInt()
        canvas.drawPath(p, glow)
        canvas.drawPath(p, stroke)
    }

    private fun drawPill(canvas: Canvas) {
        if (title == null && icon == null) return
        val pad = 12 * density
        val iconSize = 28 * density
        val maxW = width * 0.78f
        val textMax = maxW - iconSize - pad * 3
        val tLine = TextUtils.ellipsize(title ?: "", titlePaint, textMax, TextUtils.TruncateAt.END).toString()
        val bLine = TextUtils.ellipsize(text ?: "", textPaint, textMax, TextUtils.TruncateAt.END).toString()
        val textW = maxOf(titlePaint.measureText(tLine), textPaint.measureText(bLine))
        val pillW = (iconSize + pad * 3 + textW).coerceAtMost(maxW)
        val pillH = 56 * density
        val left = (width - pillW) / 2f
        val top = topInset + 14 * density
        val r = RectF(left, top, left + pillW, top + pillH)
        canvas.drawRoundRect(r, pillH / 2, pillH / 2, pillBg)
        pillEdge.color = colour
        canvas.drawRoundRect(r, pillH / 2, pillH / 2, pillEdge)
        icon?.let {
            val ix = (left + pad).toInt(); val iy = (top + (pillH - iconSize) / 2).toInt()
            it.setBounds(ix, iy, ix + iconSize.toInt(), iy + iconSize.toInt())
            it.draw(canvas)
        }
        val tx = left + pad * 2 + iconSize
        if (bLine.isBlank()) {
            canvas.drawText(tLine, tx, top + pillH / 2 + titlePaint.textSize / 3, titlePaint)
        } else {
            canvas.drawText(tLine, tx, top + pillH / 2 - 3 * density, titlePaint)
            canvas.drawText(bLine, tx, top + pillH / 2 + textPaint.textSize + 1 * density, textPaint)
        }
    }
}
