package io.github.nugggy.pluslock.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.os.PowerManager
import android.text.format.DateFormat
import android.view.View
import io.github.nugggy.pluslock.core.P
import java.util.Calendar

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
        }
    }

    /** An app window opened on top of the lock screen. */
    fun hideIfCovered() {
        if (view != null) remove()
    }

    fun remove() {
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
    private val colour = P.lockClockColour.value
    private val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        textAlign = Paint.Align.CENTER
        typeface = LockClock.typeface(P.lockClockFont.value, if (clockStyle == "THIN") 200 else if (clockStyle == "WORDS") 500 else 700)
        textSize = when (clockStyle) {
            "STACKED" -> 96 * d
            "WORDS" -> 40 * d
            "THIN" -> 92 * d
            else -> 84 * d
        } * scale
        setShadowLayer(8 * d, 0f, 0f, 0x66000000)
    }
    private val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = colour
        alpha = 220
        textAlign = Paint.Align.CENTER
        typeface = LockClock.typeface(P.lockClockFont.value, 500)
        textSize = 17 * d * scale
        setShadowLayer(6 * d, 0f, 0f, 0x66000000)
    }

    private fun lines(): List<String> {
        val c = Calendar.getInstance()
        val h24 = DateFormat.is24HourFormat(context)
        val hour = c.get(if (h24) Calendar.HOUR_OF_DAY else Calendar.HOUR).let { if (!h24 && it == 0) 12 else it }
        val min = c.get(Calendar.MINUTE)
        return when (clockStyle) {
            "STACKED" -> listOf("%02d".format(hour), "%02d".format(min))
            "WORDS" -> words(c.get(Calendar.HOUR_OF_DAY), min)
            else -> listOf(if (h24) "%02d:%02d".format(hour, min) else "%d:%02d".format(hour, min))
        }
    }

    fun measureHeight(): Int {
        val n = if (clockStyle == "STACKED" || clockStyle == "WORDS") 2 else 1
        val date = if (P.lockClockDate.value) small.fontSpacing * 1.6f else 0f
        return (big.fontSpacing * n + date + 24 * d).toInt()
    }

    override fun onDraw(canvas: Canvas) {
        var y = 12 * d - big.fontMetrics.ascent
        val step = if (clockStyle == "STACKED") big.textSize * 0.92f else big.fontSpacing
        for (line in lines()) {
            canvas.drawText(line, width / 2f, y, big)
            y += step
        }
        if (P.lockClockDate.value) {
            val date = DateFormat.format("EEEE d MMMM", Calendar.getInstance()).toString()
            canvas.drawText(date, width / 2f, y - step + big.fontMetrics.descent + small.fontSpacing * 1.3f, small)
        }
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
