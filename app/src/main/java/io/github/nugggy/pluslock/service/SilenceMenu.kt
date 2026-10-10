package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nugggy.pluslock.core.Actions
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.Silence

/** The pause choices that appear when you long-press a notification bubble. */
object SilenceMenu {
    private var view: View? = null

    fun show(svc: PlusAccessibilityService, y: Int) {
        hide(svc)
        val pkg = svc.foregroundPkg
        val app = if (pkg.isNotEmpty()) Apps.label(svc, pkg) else null
        val v = MenuView(svc, app,
            onPick = { choice ->
                when (choice) {
                    30, 60, 120 -> Silence.forMinutes(choice)
                    -1 -> if (pkg.isNotEmpty()) Silence.whileIn(pkg)
                }
                if (choice != 0) Actions.toast(svc, Silence.describe(svc) ?: "Edge lighting paused")
                hide(svc)
            },
            onOutside = { hide(svc) },
        )
        val b = svc.screenBounds()
        val lp = svc.overlayParams(b.width() - svc.dp(24f).toInt(), WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = y.coerceIn(0, b.height() - svc.dp(140f).toInt())
        if (svc.addOverlay(v, lp)) {
            view = v
            svc.handler.postDelayed({ if (view === v) hide(svc) }, 8_000)
        }
    }

    fun hide(svc: PlusAccessibilityService) {
        svc.removeOverlay(view)
        view = null
    }
}

@SuppressLint("ViewConstructor", "SetTextI18n")
private class MenuView(ctx: Context, app: String?, onPick: (Int) -> Unit, private val onOutside: () -> Unit) : LinearLayout(ctx) {
    init {
        val d = resources.displayMetrics.density
        orientation = VERTICAL
        setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (14 * d).toInt())
        background = GradientDrawable().apply { cornerRadius = 26 * d; setColor(0xF2101318.toInt()); setStroke((1.5f * d).toInt(), 0x557FB2FF) }
        alpha = 0f
        animate().alpha(1f).setDuration(180).start()

        addView(TextView(ctx).apply {
            text = "Pause bubbles and edge lighting"
            setTextColor(Color.WHITE); textSize = 15f; typeface = Typeface.DEFAULT_BOLD
            setPadding((4 * d).toInt(), 0, 0, (10 * d).toInt())
        })
        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        fun chip(label: String, value: Int, filled: Boolean = false) = Button(ctx).apply {
            text = label; isAllCaps = false; textSize = 14f
            setTextColor(if (filled) 0xFF0A0C12.toInt() else Color.WHITE)
            minWidth = 0; minimumWidth = 0
            background = GradientDrawable().apply {
                cornerRadius = 20 * d
                if (filled) setColor(0xFF7FB2FF.toInt()) else { setColor(0x22FFFFFF); setStroke((1 * d).toInt(), 0x55FFFFFF) }
            }
            setPadding((14 * d).toInt(), 0, (14 * d).toInt(), 0)
            setOnClickListener { onPick(value) }
        }
        fun add(b: Button) = row.addView(b, LayoutParams(-2, (40 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
        if (app != null) add(chip("While in $app", -1, filled = true))
        add(chip("30 min", 30))
        add(chip("1 hour", 60))
        add(chip("2 hours", 120))
        add(chip("Cancel", 0))
        addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(row) })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(); return true }
        return super.onTouchEvent(e)
    }
}
