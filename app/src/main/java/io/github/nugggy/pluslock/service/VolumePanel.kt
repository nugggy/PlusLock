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
import io.github.nugggy.pluslock.core.AppAudio
import io.github.nugggy.pluslock.core.Diag
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
        // Measure the panel ourselves and give the window that exact size and place. Some phones,
        // the OnePlus 15 included, keep a wrap-content overlay at its first size and cut off the rest.
        val margin = svc.dp(P.volPanelMargin.value.toFloat()).toInt()
        v.measure(
            View.MeasureSpec.makeMeasureSpec(b.width() - margin * 2, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(b.height(), View.MeasureSpec.AT_MOST),
        )
        val w = v.measuredWidth
        val h = v.measuredHeight
        val lp = svc.overlayParams(w, h, touchable = true)
        lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        val right = P.volPanelSide.value != "LEFT"
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = if (right) b.width() - w - margin else margin
        lp.y = ((b.height() - h) / 2 + b.height() * P.volPanelOffset.value / 100).coerceIn(0, (b.height() - h).coerceAtLeast(0))
        if (!svc.addOverlay(v, lp)) { Diag.log("volume panel", "couldn't add the panel window"); return null }
        Diag.log("volume panel", "window ${w}x$h px at ${lp.x},${lp.y}")
        view = v
        return v
    }

    /**
     * Rebuilds the panel at its new size rather than growing it in place,
     * because some phones don't resize an overlay window when its content grows.
     */
    private fun toggleExpanded() {
        val old = view ?: run { Diag.log("volume panel", "dots tapped but no panel showing"); return }
        expanded = !expanded
        Diag.log("volume panel", if (expanded) "expanding" else "collapsing")
        // Next frame: removing a window from inside its own touch handling can upset some phones.
        svc.handler.post {
            svc.removeOverlay(old)
            if (view === old) view = null
            val v = create(old.main)
            Diag.log("volume panel", if (v != null) "rebuilt, " + (if (expanded) "all volumes" else "compact") else "rebuild failed")
            v?.refresh()
            bumpHide()
        }
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
    private var dots: TextView? = null
    private var downOnDots = false
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

        if (expanded && P.volPanelApps.value) addView(appsSection())

        // The dots. Taps are caught in dispatchTouchEvent below, over a generous area,
        // so they work even if the phone is fussy about clicks in overlay windows.
        if (extra.childCount > 0 && !P.volPanelExpanded.value) {
            val dv = TextView(ctx).apply {
                text = "•  •  •"
                setTextColor(if (light) 0xAA000000.toInt() else 0xCCFFFFFF.toInt()); textSize = 18f; gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                minHeight = (44 * d).toInt()
                maxLines = 1
                contentDescription = if (expanded) "Show fewer volumes" else "Show every volume and app"
                if (expanded) background = GradientDrawable().apply { cornerRadius = 22 * d; setColor(if (light) 0x14000000 else 0x22FFFFFF) }
            }
            dots = dv
            addView(dv, LayoutParams(-1, -2).apply { topMargin = (6 * d).toInt() })
        }
        refresh()
    }

    private fun overDots(e: MotionEvent): Boolean {
        val v = dots ?: return false
        val slop = 12 * d
        return e.x >= v.left - slop && e.x <= v.right + slop && e.y >= v.top - slop && e.y <= v.bottom + slop
    }

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downOnDots = overDots(e)
                Diag.log("volume panel", "touch at " + e.x.roundToInt() + "," + e.y.roundToInt() + (if (downOnDots) " on the dots" else ""))
                if (downOnDots) { dots?.alpha = 0.5f; return true }
            }
            MotionEvent.ACTION_MOVE -> if (downOnDots) return true
            MotionEvent.ACTION_UP -> if (downOnDots) {
                downOnDots = false
                dots?.alpha = 1f
                if (overDots(e)) {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTouch(); onToggle()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (downOnDots) { downOnDots = false; dots?.alpha = 1f; return true }
            MotionEvent.ACTION_OUTSIDE -> Diag.log("volume panel", "touch outside the panel")
        }
        return super.dispatchTouchEvent(e)
    }

    /** One row per app playing sound, with its own volume where Android allows it. */
    private fun appsSection(): View {
        val dim = if (light) 0x99000000.toInt() else 0x99FFFFFF.toInt()
        val box = LinearLayout(context).apply { orientation = VERTICAL; setPadding(0, (12 * d).toInt(), 0, 0) }
        box.addView(TextView(context).apply {
            text = "Apps"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(dim)
            setPadding((4 * d).toInt(), 0, 0, (4 * d).toInt())
        })
        val apps = AppAudio.playing(context)
        if (apps.isEmpty()) box.addView(TextView(context).apply {
            text = "Nothing playing"; textSize = 12f; setTextColor(dim)
            setPadding((4 * d).toInt(), 0, 0, (4 * d).toInt())
        })
        apps.take(4).forEachIndexed { i, a ->
            val row = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt()) }
            val name = TextView(context).apply {
                text = a.label; textSize = 12f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
                setTextColor(textColour); setPadding((4 * d).toInt(), 0, (8 * d).toInt(), 0)
            }
            row.addView(name, LayoutParams((84 * d).toInt(), -2))
            if (a.adjustable) {
                val colour = if (P.volPanelRainbow.value) rainbow[(i + 2) % rainbow.size] else P.volPanelColour.value
                val bar = AppBar(context, a.pkg, colour, light) { onTouch() }
                name.setOnClickListener { AppAudio.toggleMute(a.pkg); bar.invalidate(); onTouch() }
                row.addView(bar, LayoutParams(0, (30 * d).toInt(), 1f))
            } else {
                // No way to change this app's volume, so offer play and pause instead.
                row.addView(TextView(context).apply {
                    text = if (a.playing) "Pause" else "Play"
                    textSize = 12f; gravity = Gravity.CENTER; setTextColor(textColour); typeface = Typeface.DEFAULT_BOLD
                    background = GradientDrawable().apply { cornerRadius = 14 * d; setColor(if (light) 0x14000000 else 0x22FFFFFF) }
                    setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
                    setOnClickListener {
                        val tc = a.controller?.transportControls
                        if (text == "Pause") { tc?.pause(); text = "Play" } else { tc?.play(); text = "Pause" }
                        onTouch()
                    }
                }, LayoutParams(0, -2, 1f))
            }
            box.addView(row, LayoutParams((260 * d).toInt(), -2))
        }
        return box
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

/** A sideways volume bar for one app: 0 to 150%, with a tick at its normal volume. */
@SuppressLint("ViewConstructor")
private class AppBar(ctx: Context, private val pkg: String, colour: Int, light: Boolean, private val onChange: () -> Unit) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) 0x1F000000 else 0x26FFFFFF }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = colour }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (light) 0x55000000 else 0x66FFFFFF }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 11 * d; typeface = Typeface.DEFAULT_BOLD; textAlign = Paint.Align.RIGHT }
    private var level = AppAudio.level(pkg)
    private var dragging = false
    private val rect = RectF()

    override fun onDraw(c: Canvas) {
        if (!dragging) level = AppAudio.level(pkg)
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        val r = height / 2f
        c.drawRoundRect(rect, r, r, track)
        val f = level / 150f
        c.save(); c.clipRect(0f, 0f, width * f, height.toFloat()); c.drawRoundRect(rect, r, r, fill); c.restore()
        val x100 = width * 100f / 150f
        c.drawRect(x100 - 1 * d, height * 0.2f, x100 + 1 * d, height * 0.8f, mark)
        label.color = if (f > 0.8f) 0xFF0A0C12.toInt() else Color.WHITE
        c.drawText(if (level == 0) "Muted" else "$level%", width - 8 * d, height / 2f + label.textSize / 3, label)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                dragging = true
                parent?.requestDisallowInterceptTouchEvent(true)
                var v = ((e.x / width).coerceIn(0f, 1f) * 150).roundToInt()
                if (v in 93..107) v = 100 // snaps to normal
                v = (v / 5) * 5
                if (v != level) { level = v; invalidate() }
                onChange()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                AppAudio.setLevel(pkg, level)
                performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }
        }
        return true
    }
}
