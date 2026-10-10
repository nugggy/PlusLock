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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.SoundMode
import kotlin.math.roundToInt

/** Every volume the panel can show, in the order it shows them. */
val volumeStreams = linkedMapOf(
    "MUSIC" to "Media",
    "RING" to "Ringtone",
    "NOTIFICATION" to "Notifications",
    "ALARM" to "Alarms",
    "CALL" to "Calls",
    "SYSTEM" to "System sounds",
)

private fun streamOf(key: String) = when (key) {
    "MUSIC" -> AudioManager.STREAM_MUSIC
    "RING" -> AudioManager.STREAM_RING
    "NOTIFICATION" -> AudioManager.STREAM_NOTIFICATION
    "ALARM" -> AudioManager.STREAM_ALARM
    "CALL" -> AudioManager.STREAM_VOICE_CALL
    else -> AudioManager.STREAM_SYSTEM
}

private fun shortLabel(stream: Int) = when (stream) {
    AudioManager.STREAM_MUSIC -> "Media"
    AudioManager.STREAM_RING -> "Ring"
    AudioManager.STREAM_NOTIFICATION -> "Alerts"
    AudioManager.STREAM_ALARM -> "Alarm"
    AudioManager.STREAM_VOICE_CALL -> "Call"
    else -> "System"
}

private val rainbow = intArrayOf(0xFF7FB2FF.toInt(), 0xFF4BE38A.toInt(), 0xFFFFC75E.toInt(), 0xFFFF6BE0.toInt(), 0xFF5EE4FF.toInt(), 0xFFB388FF.toInt())

/**
 * Pimp my Phone's own volume panel, shown instead of the system one when it's switched on.
 * The buttons move one slider; More shows the rest of the volumes you have chosen.
 */
class VolumePanel(private val svc: PlusAccessibilityService) {
    private val am = svc.getSystemService(AudioManager::class.java)
    private var view: PanelRoot? = null
    private val hideRunnable = Runnable { hide() }
    /** Showing every chosen volume, after tapping More. Resets when the panel hides. */
    private var expanded = false

    /** Which volume the buttons move. */
    private fun activeStream() = when {
        am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION -> AudioManager.STREAM_VOICE_CALL
        P.volPanelButtons.value == "MUSIC" -> AudioManager.STREAM_MUSIC
        P.volPanelButtons.value == "RING" -> AudioManager.STREAM_RING
        // Media while something plays, otherwise the ringtone, like the system panel.
        am.isMusicActive -> AudioManager.STREAM_MUSIC
        else -> AudioManager.STREAM_RING
    }

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
        if (P.volPanelHaptic.value && am.getStreamVolume(stream) != before) {
            view?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
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
        svc.handler.postDelayed(hideRunnable, P.volPanelTimeout.value * 1000L)
    }

    private fun create(stream: Int): PanelRoot? {
        val b = svc.screenBounds()
        val v = PanelRoot(svc, am, stream, expanded || P.volPanelExpanded.value,
            onTouch = { bumpHide() }, onToggle = ::toggleExpanded, onOutside = { hide() })
        val lp = svc.overlayParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, touchable = true)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val right = P.volPanelSide.value != "LEFT"
        lp.gravity = (if (right) Gravity.END else Gravity.START) or Gravity.CENTER_VERTICAL
        lp.x = svc.dp(P.volPanelMargin.value.toFloat()).toInt()
        lp.y = b.height() * P.volPanelOffset.value / 100
        if (!svc.addOverlay(v, lp)) return null
        view = v
        return v
    }

    /**
     * Rebuilds the panel at its new size rather than growing it in place,
     * because some phones don't resize an overlay window when its content grows.
     */
    private fun toggleExpanded() {
        val old = view ?: return
        expanded = !expanded
        svc.removeOverlay(old)
        view = null
        create(old.main)?.refresh()
        bumpHide()
    }

    /** Shows the panel from settings so changes can be seen straight away. */
    fun preview() {
        view?.let { svc.removeOverlay(it); view = null }
        show(activeStream())
    }

    fun hide() {
        svc.handler.removeCallbacks(hideRunnable)
        expanded = false
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
    private val expanded: Boolean,
    private val onTouch: () -> Unit,
    private val onToggle: () -> Unit,
    private val onOutside: () -> Unit,
) : LinearLayout(ctx) {
    private val d = resources.displayMetrics.density
    private val sliders = mutableListOf<VSlider>()
    private val horizontal = P.volPanelLayout.value == "HORIZONTAL"
    private val extra = LinearLayout(ctx).apply {
        orientation = if (horizontal) VERTICAL else HORIZONTAL
        visibility = if (expanded) VISIBLE else GONE
    }
    private val modeChip: TextView?
    private val light = P.volPanelStyle.value == "LIGHT"
    private val textColour = if (light) 0xFF15171C.toInt() else Color.WHITE

    init {
        orientation = VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding((10 * d).toInt(), (12 * d).toInt(), (10 * d).toInt(), (10 * d).toInt())
        val accent = P.volPanelColour.value
        val bg = when (P.volPanelStyle.value) {
            "BLACK" -> 0xFF000000.toInt()
            "GLASS" -> 0x99202228.toInt()
            "LIGHT" -> 0xFFF2F3F7.toInt()
            "TINT" -> Color.rgb(Color.red(accent) / 6, Color.green(accent) / 6, Color.blue(accent) / 6)
            else -> 0xFF121418.toInt()
        }
        background = GradientDrawable().apply {
            cornerRadius = P.volPanelCorner.value * d
            setColor(bg)
            if (P.volPanelStyle.value == "BLACK" || P.volPanelStyle.value == "GLASS") setStroke((1 * d).toInt(), 0x33FFFFFF)
        }
        val targetAlpha = P.volPanelOpacity.value / 100f
        alpha = 0f
        animate().alpha(targetAlpha).setDuration(150).start()

        modeChip = if (!P.volPanelModeChip.value) null else TextView(ctx).apply {
            textSize = 11f; setTextColor(textColour); gravity = Gravity.CENTER
            setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 12 * d; setColor(if (light) 0x14000000 else 0x22FFFFFF) }
            setOnClickListener { SoundMode.cycle(ctx); refresh(); onTouch() }
        }
        modeChip?.let { addView(it, LayoutParams(-2, -2).apply { bottomMargin = (10 * d).toInt() }) }

        val chosen = P.volPanelStreams.value.split(',').filter { it in volumeStreams }.map(::streamOf)
        val row = LinearLayout(ctx).apply { orientation = if (horizontal) VERTICAL else HORIZONTAL }
        row.addView(column(main, 0))
        chosen.filter { it != main }.forEachIndexed { i, s -> extra.addView(column(s, i + 1)) }
        row.addView(extra)
        addView(row)

        // A proper button with a big target, rather than three small dots.
        if (extra.childCount > 0 && !P.volPanelExpanded.value) addView(TextView(ctx).apply {
            text = if (expanded) "Less" else "More"
            setTextColor(textColour); textSize = 12f; gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            minHeight = (40 * d).toInt()
            setPadding((10 * d).toInt(), (8 * d).toInt(), (10 * d).toInt(), (8 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 20 * d; setColor(if (light) 0x14000000 else 0x22FFFFFF) }
            contentDescription = if (expanded) "Show fewer volumes" else "Show all volumes"
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onTouch(); onToggle()
            }
        }, LayoutParams(-1, -2).apply { topMargin = (10 * d).toInt() })
        refresh()
    }

    private fun column(stream: Int, index: Int) = LinearLayout(context).apply {
        orientation = if (horizontal) HORIZONTAL else VERTICAL
        gravity = if (horizontal) Gravity.CENTER_VERTICAL else Gravity.CENTER_HORIZONTAL
        val colour = if (P.volPanelRainbow.value) rainbow[index % rainbow.size] else P.volPanelColour.value
        val s = VSlider(context, am, stream, colour, horizontal, light) { onTouch(); refresh() }
        sliders += s
        val label = if (!P.volPanelLabels.value) null else TextView(context).apply {
            text = shortLabel(stream); textSize = 11f; setTextColor(if (light) 0xCC000000.toInt() else 0xCCFFFFFF.toInt()); gravity = Gravity.CENTER
            if (horizontal) setPadding(0, 0, (8 * d).toInt(), 0) else setPadding(0, (6 * d).toInt(), 0, 0)
            // Tap the name to mute, tap again to bring it back.
            if (P.volPanelTapMute.value) setOnClickListener { s.toggleMute(); onTouch(); refresh() }
        }
        val thick = (P.volPanelWidth.value * d).toInt()
        val long = (P.volPanelHeight.value * d).toInt()
        if (horizontal) {
            label?.let { addView(it, LayoutParams((52 * d).toInt(), -2)) }
            addView(s, LayoutParams(long, thick))
            setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        } else {
            addView(s, LayoutParams(thick, long))
            label?.let { addView(it) }
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        }
    }

    fun refresh() {
        sliders.forEach { it.invalidate() }
        modeChip?.text = SoundMode.label(am.ringerMode)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) { onOutside(); return false }
        return super.onTouchEvent(e)
    }
}

@SuppressLint("ViewConstructor")
private class VSlider(
    ctx: Context,
    private val am: AudioManager,
    private val stream: Int,
    colour: Int,
    private val horizontal: Boolean,
    light: Boolean,
    private val onChange: () -> Unit,
) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) 0x1F000000 else 0x26FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 11 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER
    }
    private val numbers = P.volPanelNumbers.value
    private val haptic = P.volPanelHaptic.value
    private val min = am.getStreamMinVolume(stream)
    private val max = am.getStreamMaxVolume(stream).coerceAtLeast(min + 1)
    private var beforeMute = -1
    private val rect = RectF()

    private fun fraction() = (am.getStreamVolume(stream) - min).toFloat() / (max - min)

    fun toggleMute() {
        val now = am.getStreamVolume(stream)
        if (now > min) { beforeMute = now; set(min) } else set(if (beforeMute > min) beforeMute else (min + max) / 2)
    }

    private fun set(idx: Int) {
        if (idx == am.getStreamVolume(stream)) return
        runCatching { am.setStreamVolume(stream, idx, 0) }
        if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val corner = minOf(width, height) / 2f
        c.drawRoundRect(rect, corner, corner, track)
        val f = fraction()
        c.save()
        if (horizontal) c.clipRect(0f, 0f, width * f, height.toFloat())
        else c.clipRect(0f, height * (1 - f), width.toFloat(), height.toFloat())
        c.drawRoundRect(rect, corner, corner, fill)
        c.restore()
        val text = when (numbers) {
            "NONE" -> return
            "STEPS" -> "${am.getStreamVolume(stream)}"
            else -> "${(f * 100).roundToInt()}"
        }
        val onFill = if (horizontal) f > 0.25f else f > 0.12f
        label.color = if (onFill) 0xFF0A0C12.toInt() else Color.WHITE
        if (horizontal) {
            label.textAlign = Paint.Align.LEFT
            c.drawText(text, 10 * d, height / 2f + label.textSize / 3, label)
        } else {
            c.drawText(text, width / 2f, height - 12 * d, label)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val f = if (horizontal) (e.x / width).coerceIn(0f, 1f) else (1 - e.y / height).coerceIn(0f, 1f)
                set((min + f * (max - min)).roundToInt())
                onChange()
            }
        }
        return true
    }
}
