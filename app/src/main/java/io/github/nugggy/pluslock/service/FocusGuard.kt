package io.github.nugggy.pluslock.service

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.data.AppProfile

/**
 * Doomscroll breaker. After the set time in an app, the screen dims and a
 * short breathing pause appears before you choose to leave or carry on.
 */
class FocusGuard(private val svc: PlusAccessibilityService) {
    private var pkg = ""
    private var sessionStart = 0L
    private var limitMs = 0L
    private val lastLeft = HashMap<String, Long>()
    private val sessionStarts = HashMap<String, Long>()
    private var overlay: View? = null
    private val trigger = Runnable { show() }

    fun onForeground(newPkg: String, profile: AppProfile?) {
        if (newPkg == pkg) return
        svc.handler.removeCallbacks(trigger)
        val now = System.currentTimeMillis()
        if (pkg.isNotEmpty()) lastLeft[pkg] = now
        dismiss()
        pkg = newPkg
        val minutes = profile?.focusMinutes ?: 0
        if (minutes <= 0) return
        limitMs = minutes * 60_000L
        // Popping out for under two minutes does not reset the clock.
        val left = lastLeft[newPkg]
        sessionStart = if (left != null && now - left < 120_000L) sessionStarts[newPkg] ?: now else now
        sessionStarts[newPkg] = sessionStart
        svc.handler.postDelayed(trigger, (sessionStart + limitMs - now).coerceAtLeast(1_000L))
    }

    private fun show() {
        if (overlay != null) return
        val minutes = ((System.currentTimeMillis() - sessionStart) / 60_000L).coerceAtLeast(1)
        val app = Apps.label(svc, pkg)
        val v = BreakView(svc, app, minutes,
            onLeave = {
                dismiss()
                svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            },
            onSnooze = {
                dismiss()
                svc.handler.postDelayed(trigger, 5 * 60_000L)
            })
        val b = svc.screenBounds()
        val lp = svc.overlayParams(b.width(), b.height(), touchable = true)
        if (svc.addOverlay(v, lp)) overlay = v
    }

    fun dismiss() {
        svc.removeOverlay(overlay)
        overlay = null
    }
}

@SuppressLint("ViewConstructor", "SetTextI18n")
private class BreakView(
    ctx: Context,
    app: String,
    minutes: Long,
    onLeave: () -> Unit,
    onSnooze: () -> Unit,
) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    private var breath = 0f
    private val circle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x557FB2FF }
    private val label: TextView
    private val buttons: LinearLayout
    private val breathing = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 4000
        repeatMode = ValueAnimator.REVERSE
        repeatCount = 3 // in, out, in, out: about 16 seconds
        interpolator = AccelerateDecelerateInterpolator()
        addUpdateListener {
            breath = it.animatedValue as Float
            if (!buttons.isEnabled) label.text = if (repeatCountDone() % 2 == 0) "Breathe in" else "Breathe out"
            invalidate()
        }
    }
    private var started = 0L
    private fun repeatCountDone(): Int = ((System.currentTimeMillis() - started) / 4000L).toInt()

    init {
        setWillNotDraw(false)
        setBackgroundColor(0xF20A0C12.toInt())
        alpha = 0f
        isClickable = true

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((28 * d).toInt(), 0, (28 * d).toInt(), (64 * d).toInt())
        }
        col.addView(TextView(ctx).apply {
            text = "$minutes minutes in $app"
            setTextColor(Color.WHITE)
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        })
        col.addView(TextView(ctx).apply {
            text = "You asked PlusLock to check in. Take a few breaths, then decide."
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 15f
            gravity = Gravity.CENTER
            setPadding(0, (10 * d).toInt(), 0, (28 * d).toInt())
        })
        label = TextView(ctx).apply {
            text = "Breathe in"
            setTextColor(0xFF7FB2FF.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, (24 * d).toInt())
        }
        col.addView(label)
        buttons = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            alpha = 0f
            isEnabled = false
        }
        fun pill(textValue: String, filled: Boolean, onTap: () -> Unit) = Button(ctx).apply {
            text = textValue
            isAllCaps = false
            textSize = 15f
            setTextColor(if (filled) 0xFF0A0C12.toInt() else Color.WHITE)
            background = GradientDrawable().apply {
                cornerRadius = 28 * d
                if (filled) setColor(0xFF7FB2FF.toInt()) else { setColor(Color.TRANSPARENT); setStroke((1.5f * d).toInt(), 0x88FFFFFF.toInt()) }
            }
            setPadding((22 * d).toInt(), 0, (22 * d).toInt(), 0)
            setOnClickListener { if (buttons.isEnabled) onTap() }
        }
        buttons.addView(pill("Close $app", true, onLeave), LinearLayout.LayoutParams(-2, (52 * d).toInt()).apply { marginEnd = (12 * d).toInt() })
        buttons.addView(pill("5 more minutes", false, onSnooze), LinearLayout.LayoutParams(-2, (52 * d).toInt()))
        col.addView(buttons)
        addView(col, LayoutParams(-1, -2, Gravity.BOTTOM))

        animate().alpha(1f).setDuration(1500).start()
        started = System.currentTimeMillis()
        breathing.start()
        postDelayed({
            buttons.isEnabled = true
            buttons.animate().alpha(1f).setDuration(600).start()
            label.text = "Ready when you are"
        }, 12_000L)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val r = (60 + 70 * breath) * d
        canvas.drawCircle(width / 2f, height * 0.33f, r, circle)
        canvas.drawCircle(width / 2f, height * 0.33f, r * 0.6f, circle)
    }

    override fun onDetachedFromWindow() {
        breathing.cancel()
        super.onDetachedFromWindow()
    }
}
