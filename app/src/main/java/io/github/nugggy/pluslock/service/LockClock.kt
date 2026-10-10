package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.WallpaperManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.BatteryManager
import android.os.PowerManager
import android.text.format.DateFormat
import android.view.View
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.wallpaper.Weather
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * A custom clock drawn over the lock screen. Android doesn't let apps remove the
 * OxygenOS clock, so this one sits where you put it, and works best with the
 * system clock set to a small style.
 */
class LockClock(private val svc: PlusAccessibilityService) {
    companion object {
        val styles = linkedMapOf(
            "BOLD" to "Bold: one big line",
            "THIN" to "Thin: light and airy",
            "STACKED" to "Stacked: hours over minutes",
            "GIANT" to "Giant: hours over minutes, edge to edge",
            "SPLIT" to "Two-tone: hours and minutes in different colours",
            "OUTLINE" to "Outline: hollow numbers",
            "NEON" to "Neon: glowing tubes",
            "FLIP" to "Flip: split-flap cards",
            "DOTS" to "Dot matrix: like an old departure board",
            "ANALOGUE" to "Analogue: a watch face",
            "WORDS" to "Words: \"quarter past nine\"",
        )
        val fonts = linkedMapOf(
            "SANS" to "Sans",
            "SERIF" to "Serif",
            "MONO" to "Monospace",
            "CONDENSED" to "Condensed",
            "ROUNDED" to "Rounded",
        )

        fun typeface(font: String, weight: Int): Typeface {
            val family = when (font) {
                "SERIF" -> Typeface.SERIF
                "MONO" -> Typeface.MONOSPACE
                "CONDENSED" -> Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                // Falls back to plain sans where the phone has no rounded font.
                "ROUNDED" -> Typeface.create("sans-serif-rounded", Typeface.NORMAL)
                else -> Typeface.SANS_SERIF
            }
            return Typeface.create(family, weight, false)
        }
    }

    private var view: ClockView? = null
    private var registered = false
    private val ticker = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { view?.invalidate() }
    }
    private val secondTick = object : Runnable {
        override fun run() {
            val v = view ?: return
            v.invalidate()
            svc.handler.postDelayed(this, 1000 - System.currentTimeMillis() % 1000)
        }
    }

    /** Called on screen on, screen off, unlock and setting changes. */
    fun refresh() {
        val locked = svc.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val on = svc.getSystemService(PowerManager::class.java).isInteractive
        if (!P.lockClockOn.value || !locked || !on) { remove(); return }
        remove()
        val b = svc.screenBounds()
        val v = ClockView(svc)
        val h = v.measureHeight()
        val lp = svc.overlayParams(b.width(), h, touchable = false)
        lp.y = ((b.height() - h) * P.lockClockPosition.value / 100f).toInt()
        if (svc.addOverlay(v, lp)) {
            view = v
            if (!registered) {
                svc.registerReceiver(ticker, IntentFilter(Intent.ACTION_TIME_TICK).apply {
                    addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }, Context.RECEIVER_EXPORTED)
                registered = true
            }
            if (P.lockClockSeconds.value || P.lockClockColourMode.value == "RAINBOW" || P.lockClockStyle.value == "ANALOGUE") svc.handler.post(secondTick)
        }
    }

    /** An app window opened on top of the lock screen. */
    fun hideIfCovered() {
        if (view != null) remove()
    }

    fun remove() {
        svc.handler.removeCallbacks(secondTick)
        svc.removeOverlay(view)
        view = null
        if (registered) { runCatching { svc.unregisterReceiver(ticker) }; registered = false }
    }
}

@SuppressLint("ViewConstructor")
private class ClockView(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val scale = P.lockClockSize.value / 100f
    private val clockStyle = P.lockClockStyle.value
    private val font = P.lockClockFont.value
    private val mode = P.lockClockColourMode.value
    private val colour2 = P.lockClockColour2.value
    private val align = P.lockClockAlign.value
    private val seconds = P.lockClockSeconds.value
    private val glow = P.lockClockGlow.value || clockStyle == "NEON"
    private val colour: Int = when (mode) {
        // The lock screen wallpaper's main colour, brightened so it reads on the wallpaper itself.
        "WALLPAPER" -> runCatching {
            WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_LOCK)
                ?: WallpaperManager.getInstance(ctx).getWallpaperColors(WallpaperManager.FLAG_SYSTEM)
        }.getOrNull()?.let { wc ->
            val hsv = FloatArray(3); Color.colorToHSV((wc.secondaryColor ?: wc.primaryColor).toArgb(), hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.35f); hsv[2] = 1f; Color.HSVToColor(hsv)
        } ?: P.lockClockColour.value
        else -> P.lockClockColour.value
    }

    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        typeface = LockClock.typeface(font, when (clockStyle) {
            "THIN", "NEON" -> 200; "WORDS" -> 500; "GIANT" -> 900; "OUTLINE" -> 800; else -> 700
        })
        textSize = when (clockStyle) {
            "STACKED" -> 96 * d
            "GIANT" -> 150 * d
            "WORDS" -> 40 * d
            "THIN", "NEON" -> 92 * d
            "FLIP" -> 64 * d
            else -> 84 * d
        } * scale
        if (clockStyle == "OUTLINE") { style = Paint.Style.STROKE; strokeWidth = 2.5f * d * scale }
        if (clockStyle == "NEON") { style = Paint.Style.STROKE; strokeWidth = 3f * d * scale }
        if (!glow) setShadowLayer(8 * d, 0f, 0f, 0x66000000)
    }
    private val halo = Paint(big).apply {
        maskFilter = BlurMaskFilter(14 * d * scale, BlurMaskFilter.Blur.NORMAL)
        clearShadowLayer()
        if (clockStyle == "NEON") strokeWidth = 7 * d * scale
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        alpha = 225
        typeface = LockClock.typeface(font, 500)
        textSize = 16 * d * scale
        setShadowLayer(6 * d, 0f, 0f, 0x66000000)
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }

    init {
        alpha = P.lockClockOpacity.value / 100f
    }

    private fun anchorX(w: Float): Float = when (align) {
        "LEFT" -> 24 * d
        "RIGHT" -> w - 24 * d
        else -> w / 2f
    }

    private val textAlign get() = when (align) { "LEFT" -> Paint.Align.LEFT; "RIGHT" -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }

    private fun time(): Triple<Int, Int, Int> {
        val c = Calendar.getInstance()
        val h24 = DateFormat.is24HourFormat(context)
        val hour = c.get(if (h24) Calendar.HOUR_OF_DAY else Calendar.HOUR).let { if (!h24 && it == 0) 12 else it }
        return Triple(hour, c.get(Calendar.MINUTE), c.get(Calendar.SECOND))
    }

    private fun lines(): List<String> {
        val (hour, min, sec) = time()
        val h24 = DateFormat.is24HourFormat(context)
        val s = if (seconds) ":%02d".format(sec) else ""
        return when (clockStyle) {
            "STACKED", "GIANT" -> listOf("%02d".format(hour), "%02d".format(min))
            "WORDS" -> words(Calendar.getInstance().get(Calendar.HOUR_OF_DAY), min)
            else -> listOf((if (h24) "%02d:%02d".format(hour, min) else "%d:%02d".format(hour, min)) + s)
        }
    }

    /** The small lines under the clock: date, battery, alarm, weather, music, unread. */
    private fun extras(): List<String> {
        val out = mutableListOf<String>()
        if (P.lockClockDate.value) {
            val pattern = when (P.lockClockDateFormat.value) { "SHORT" -> "EEE d MMM"; "NUMERIC" -> "dd/MM/yyyy"; else -> "EEEE d MMMM" }
            out += DateFormat.format(pattern, Calendar.getInstance()).toString()
        }
        val bits = mutableListOf<String>()
        if (P.lockClockBattery.value) {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { i ->
                val pct = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
                val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                bits += (if (charging) "Charging " else "Battery ") + "$pct%"
            }
        }
        if (P.lockClockAlarm.value) {
            context.getSystemService(AlarmManager::class.java).nextAlarmClock?.let {
                bits += "Alarm " + DateFormat.format(if (DateFormat.is24HourFormat(context)) "HH:mm" else "h:mm a", it.triggerTime)
            }
        }
        if (P.lockClockWeather.value) Weather.current()?.let { w ->
            bits += when { w.storm -> "Storms"; w.snow -> "Snow"; w.rain -> "Rain"; w.cloudy -> "Cloudy"; else -> "Clear" }
        }
        if (P.lockClockUnread.value) PlusNotificationListener.unreadCount.takeIf { it > 0 }?.let { bits += "$it unread" }
        if (bits.isNotEmpty()) out += bits.joinToString("  ·  ")
        if (P.lockClockMusic.value) IslandState.media?.takeIf { it.playing }?.let { m ->
            out += "♪ " + m.title + if (m.subtitle.isNotBlank()) " · " + m.subtitle else ""
        }
        return out
    }

    fun measureHeight(): Int {
        val body = when (clockStyle) {
            "STACKED", "GIANT", "WORDS" -> big.fontSpacing * 2 * (if (clockStyle == "STACKED" || clockStyle == "GIANT") 0.92f else 1f)
            "ANALOGUE" -> 220 * d * scale
            "DOTS" -> 120 * d * scale
            "FLIP" -> big.textSize * 1.5f
            else -> big.fontSpacing
        }
        val extraLines = (if (P.lockClockDate.value) 1 else 0) + (if (P.lockClockBattery.value || P.lockClockAlarm.value || P.lockClockWeather.value || P.lockClockUnread.value) 1 else 0) +
            (if (P.lockClockMusic.value) 1 else 0)
        return (body + extraLines * small.fontSpacing * 1.35f + 48 * d).toInt()
    }

    /** Sets the paint's colour or shader for the current colour mode. */
    private fun tint(p: Paint, top: Float, bottom: Float, alt: Boolean = false) {
        p.shader = null
        when (mode) {
            "GRADIENT" -> p.shader = LinearGradient(0f, top, 0f, bottom, colour, colour2, Shader.TileMode.CLAMP)
            "RAINBOW" -> p.color = Color.HSVToColor(floatArrayOf(((System.currentTimeMillis() / 60L) % 360).toFloat() + (if (alt) 120 else 0), 0.65f, 1f))
            else -> p.color = if (alt) colour2 else colour
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val x = anchorX(w)
        var y = 16 * d
        when (clockStyle) {
            "ANALOGUE" -> { y = drawAnalogue(canvas, x, y) }
            "DOTS" -> { y = drawDots(canvas, x, y) }
            "FLIP" -> { y = drawFlip(canvas, x, y) }
            else -> {
                big.textAlign = textAlign; halo.textAlign = textAlign
                y -= big.fontMetrics.ascent
                val step = if (clockStyle == "STACKED" || clockStyle == "GIANT") big.textSize * 0.92f else big.fontSpacing
                lines().forEach { text ->
                    val split = clockStyle == "SPLIT"
                    if (split) {
                        // Hours in one colour, minutes in the other.
                        val parts = text.split(":", limit = 2)
                        val hText = parts[0]; val rest = ":" + parts.getOrElse(1) { "" }
                        val total = big.measureText(text)
                        val left = when (align) { "LEFT" -> x; "RIGHT" -> x - total; else -> x - total / 2 }
                        big.textAlign = Paint.Align.LEFT
                        tint(big, y - big.textSize, y); canvas.drawText(hText, left, y, big)
                        tint(big, y - big.textSize, y, alt = true); canvas.drawText(rest, left + big.measureText(hText), y, big)
                        big.textAlign = textAlign
                    } else {
                        tint(big, y - big.textSize, y)
                        if (glow) { tint(halo, y - big.textSize, y); canvas.drawText(text, x, y, halo) }
                        canvas.drawText(text, x, y, big)
                    }
                    y += step
                }
                y -= step
                y += big.fontMetrics.descent
            }
        }
        small.textAlign = textAlign
        extras().forEach { t ->
            y += small.fontSpacing * 1.3f
            canvas.drawText(t, x, y, small)
        }
    }

    private fun drawAnalogue(c: Canvas, x: Float, top: Float): Float {
        val r = 100 * d * scale
        val cx = when (align) { "LEFT" -> x + r; "RIGHT" -> x - r; else -> x }
        val cy = top + r + 4 * d
        val (h, m, s) = Calendar.getInstance().let { Triple(it.get(Calendar.HOUR), it.get(Calendar.MINUTE), it.get(Calendar.SECOND)) }
        line.shader = null
        tint(line, cy - r, cy + r)
        // Hour marks.
        for (i in 0 until 60) {
            val a = (i / 60f * 2 * PI).toFloat()
            val major = i % 5 == 0
            line.strokeWidth = (if (major) 3.5f else 1.2f) * d * scale
            line.alpha = if (major) 255 else 120
            val r1 = r * (if (major) 0.84f else 0.9f)
            c.drawLine(cx + sin(a) * r1, cy - cos(a) * r1, cx + sin(a) * r * 0.97f, cy - cos(a) * r * 0.97f, line)
        }
        line.alpha = 255
        fun hand(angle: Float, len: Float, width: Float) {
            line.strokeWidth = width
            if (glow) { halo.style = Paint.Style.STROKE; halo.strokeWidth = width * 2.5f; tint(halo, cy - r, cy + r); c.drawLine(cx, cy, cx + sin(angle) * len, cy - cos(angle) * len, halo) }
            c.drawLine(cx, cy, cx + sin(angle) * len, cy - cos(angle) * len, line)
        }
        hand(((h + m / 60f) / 12f * 2 * PI).toFloat(), r * 0.5f, 6 * d * scale)
        hand(((m + s / 60f) / 60f * 2 * PI).toFloat(), r * 0.75f, 4 * d * scale)
        if (seconds) {
            line.shader = null; line.color = colour2
            hand((s / 60f * 2 * PI).toFloat(), r * 0.85f, 1.5f * d * scale)
        }
        fill.shader = null; fill.color = colour
        c.drawCircle(cx, cy, 5 * d * scale, fill)
        return cy + r
    }

    // 5 by 7 dot patterns for the digits and the colon.
    private val glyphs = mapOf(
        '0' to "01110100011001110101110011000101110", '1' to "00100011000010000100001000010001110",
        '2' to "01110100010000100010001000100011111", '3' to "11111000100010000010000011000101110",
        '4' to "00010001100101010010111110001000010", '5' to "11111100001111000001000011000101110",
        '6' to "00110010001000011110100011000101110", '7' to "11111000010001000100010000100001000",
        '8' to "01110100011000101110100011000101110", '9' to "01110100011000101111000010001001100",
        ':' to "00000001000010000000001000010000000",
    )

    private fun drawDots(c: Canvas, x: Float, top: Float): Float {
        val text = lines().first()
        val cell = 14 * d * scale * 0.9f
        val charW = cell * 6
        val total = charW * text.length - cell
        val left = when (align) { "LEFT" -> x; "RIGHT" -> x - total; else -> x - total / 2 }
        fill.shader = null
        text.forEachIndexed { i, ch ->
            val g = glyphs[ch] ?: return@forEachIndexed
            for (row in 0 until 7) for (col in 0 until 5) {
                val on = g[row * 5 + col] == '1'
                tint(fill, top, top + cell * 7)
                fill.alpha = if (on) 255 else 28
                val cx = left + i * charW + col * cell + cell / 2
                val cy = top + row * cell + cell / 2
                if (on && glow) { halo.style = Paint.Style.FILL; tint(halo, top, top + cell * 7); c.drawCircle(cx, cy, cell * 0.6f, halo) }
                c.drawCircle(cx, cy, cell * 0.38f, fill)
            }
        }
        fill.alpha = 255
        return top + cell * 7
    }

    private fun drawFlip(c: Canvas, x: Float, top: Float): Float {
        val (hour, min, sec) = time()
        val groups = mutableListOf("%02d".format(hour), "%02d".format(min))
        if (seconds) groups += "%02d".format(sec)
        val cardW = big.textSize * 1.35f
        val cardH = big.textSize * 1.4f
        val gap = 10 * d * scale
        val total = groups.size * cardW + (groups.size - 1) * gap
        var left = when (align) { "LEFT" -> x; "RIGHT" -> x - total; else -> x - total / 2 }
        big.textAlign = Paint.Align.CENTER
        groups.forEach { g ->
            fill.shader = null; fill.color = 0xE6181A1F.toInt()
            c.drawRoundRect(left, top, left + cardW, top + cardH, 12 * d * scale, 12 * d * scale, fill)
            tint(big, top, top + cardH)
            c.drawText(g, left + cardW / 2, top + cardH / 2 - (big.fontMetrics.ascent + big.fontMetrics.descent) / 2, big)
            // The split line across the middle of the card.
            fill.color = 0xFF000000.toInt()
            c.drawRect(left, top + cardH / 2 - 1.5f * d, left + cardW, top + cardH / 2 + 1.5f * d, fill)
            left += cardW + gap
        }
        return top + cardH
    }

    private val numbers = listOf("twelve", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
        "ten", "eleven", "twelve")

    private fun words(hour24: Int, min: Int): List<String> {
        val rounded = ((min + 2) / 5) * 5
        val h = hour24 % 12
        val next = numbers[(h + 1) % 12]
        val cur = numbers[h]
        return when (rounded) {
            0 -> listOf(cur, "o'clock")
            60 -> listOf(next, "o'clock")
            15 -> listOf("quarter", "past $cur")
            30 -> listOf("half", "past $cur")
            45 -> listOf("quarter", "to $next")
            in 5..25 -> listOf(spoken(rounded), "past $cur")
            else -> listOf(spoken(60 - rounded), "to $next")
        }
    }

    private fun spoken(m: Int) = when (m) {
        5 -> "five"; 10 -> "ten"; 20 -> "twenty"; 25 -> "twenty-five"; else -> m.toString()
    }
}
