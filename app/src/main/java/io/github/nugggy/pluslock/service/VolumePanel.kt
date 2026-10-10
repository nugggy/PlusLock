package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.SoundMode
import kotlin.math.roundToInt

/**
 * Pimp my Phone's own volume panel, shown instead of the system one when it's switched on.
 * One slider for whatever the buttons control; tap the dots for all four.
 */
class VolumePanel(private val svc: PlusAccessibilityService) {
    private val am = svc.getSystemService(AudioManager::class.java)
    private var view: PanelRoot? = null
    private val hideRunnable = Runnable { hide() }

    /** Media while something plays, otherwise the ringtone, like the system panel. */
    private fun activeStream() = if (am.isMusicActive) AudioManager.STREAM_MUSIC else AudioManager.STREAM_RING

    fun adjust(direction: Int) {
        val stream = activeStream()
        val flags = if (stream == AudioManager.STREAM_RING) AudioManager.FLAG_ALLOW_RINGER_MODES else 0
        val before = am.getStreamVolume(stream)
        runCatching { am.adjustStreamVolume(stream, direction, flags) }
        // Going up but nothing changed: usually the headphone safety limit. Let Android show its own prompt.
        if (direction == AudioManager.ADJUST_RAISE && am.getStreamVolume(stream) == before && before < am.getStreamMaxVolume(stream)) {
            runCatching { am.adjustStreamVolume(stream, direction, AudioManager.FLAG_SHOW_UI) }
            return
        }
        show(stream)
    }

    private fun show(stream: Int) {
        // Music started or stopped since the panel opened: rebuild it for the new stream.
        view?.let { if (it.main != stream) { svc.removeOverlay(it); view = null } }
        val v = view ?: create(stream) ?: return
        v.refresh()
        bumpHide()
    }

    private fun bumpHide() {
        svc.handler.removeCallbacks(hideRunnable)
        svc.handler.postDelayed(hideRunnable, 3_000)
    }

    private fun create(stream: Int): PanelRoot? {
        val b = svc.screenBounds()
        val v = PanelRoot(svc, am, stream, onTouch = { bumpHide() }, onResize = ::relayout, onOutside = { hide() })
        val lp = svc.overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val right = P.volPanelSide.value != "LEFT"
        lp.gravity = (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        lp.x = svc.dp(12f).toInt()
        lp.y = -b.height() / 10
        if (!svc.addOverlay(v, lp)) return null
        view = v
        return v
    }

    private fun relayout(v: View) {
        val lp = v.layoutParams as? WindowManager.LayoutParams ?: return
        runCatching { svc.wm.updateViewLayout(v, lp) }
    }

    fun hide() {
        svc.handler.removeCallbacks(hideRunnable)
        val v = view ?: return
        view = null
        v.animate().alpha(0f).setDuration(180).withEndAction { svc.removeOverlay(v) }.start()
        // Belt and braces: remove even if the fade is interrupted.
        svc.handler.postDelayed({ svc.removeOverlay(v) }, 250)
    }
}

@SuppressLint("ViewConstructor", "SetTextI18n")
private class PanelRoot(
    ctx: Context,
    private val am: AudioManager,
    val main: Int,
    private val onTouch: () -> Unit,
    private val onResize: (View) -> Unit,
    private val onOutside: () -> Unit,
) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val sliders = mutableListOf<VSlider>()
    private val extra = LinearLayout(ctx).apply { orientation = HORIZONTAL; visibility = GONE }
    private val modeChip: TextView

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding((10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        background = GradientDrawable().apply { cornerRadius = 30 * d; setColor(0xF0121418.toInt()) }
        alpha = 0f
        animate().alpha(1f).setDuration(150).start()

        modeChip = TextView(ctx).apply {
            textSize = 11f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(0x22FFFFFF) }
            setOnClickListener { SoundMode.cycle(ctx); refresh(); onTouch() }
        }
        addView(modeChip, LayoutParams(-2, -2).apply { bottomMargin = (10 * d).toInt() })

        val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
        row.addView(column(main, labelFor(main)))
        listOf(AudioManager.STREAM_MUSIC, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_ALARM)
            .filter { it != main }
            .forEach { extra.addView(column(it, labelFor(it))) }
        row.addView(extra)
        addView(row)

        addView(TextView(ctx).apply {
            text = "• • •"; setTextColor(0x99FFFFFF.toInt()); textSize = 12f; gravity = Gravity.CENTER
            setPadding(0, (8 * d).toInt(), 0, 0)
            setOnClickListener {
                extra.visibility = if (extra.visibility == VISIBLE) GONE else VISIBLE
                onTouch(); onResize(this@PanelRoot)
            }
        }, LayoutParams(-1, -2))
        refresh()
    }

    private fun labelFor(stream: Int) = when (stream) {
        AudioManager.STREAM_MUSIC -> "Media"
        AudioManager.STREAM_RING -> "Ring"
        AudioManager.STREAM_NOTIFICATION -> "Alerts"
        else -> "Alarm"
    }

    private fun column(stream: Int, label: String) = LinearLayout(context).apply {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        val s = VSlider(context, am, stream) { onTouch(); refresh() }
        sliders += s
        addView(s, LayoutParams((44 * d).toInt(), (190 * d).toInt()))
        addView(TextView(context).apply {
            text = label; textSize = 11f; setTextColor(0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, 0)
        })
        setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
    }

    fun refresh() {
        sliders.forEach { it.invalidate() }
        modeChip.text = SoundMode.label(am.ringerMode)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(); return false }
        return super.onTouchEvent(e)
    }
}

@SuppressLint("ViewConstructor")
private class VSlider(ctx: Context, private val am: AudioManager, private val stream: Int, private val onChange: () -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF7FB2FF.toInt() }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF0A0C12.toInt(); textSize = 11 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val min = am.getStreamMinVolume(stream)
    private val max = am.getStreamMaxVolume(stream).coerceAtLeast(min + 1)

    private fun fraction() = (am.getStreamVolume(stream) - min).toFloat() / (max - min)

    override fun onDraw(c: Canvas) {
        val r = RectF(0f, 0f, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, width / 2f, width / 2f, track)
        val f = fraction()
        val top = height * (1 - f)
        c.save()
        c.clipRect(0f, top, width.toFloat(), height.toFloat())
        c.drawRoundRect(r, width / 2f, width / 2f, fill)
        c.restore()
        label.color = if (f > 0.12f) 0xFF0A0C12.toInt() else Color.WHITE
        c.drawText("${(f * 100).roundToInt()}", width / 2f, height - 12 * d, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = (1 - e.y / height).coerceIn(0f, 1f)
                val idx = (min + f * (max - min)).roundToInt()
                if (idx != am.getStreamVolume(stream)) {
                    runCatching { am.setStreamVolume(stream, idx, 0) }
                    invalidate()
                }
                onChange()
            }
        }
        return true
    }
}
