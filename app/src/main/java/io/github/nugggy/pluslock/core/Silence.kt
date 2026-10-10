package io.github.nugggy.pluslock.core

import android.content.Context
import java.text.DateFormat
import java.util.Date

/**
 * Pauses edge lighting and the preview bubble: for a while, until switched back on,
 * or until you leave a particular app (handy while watching something).
 * Notifications still arrive as normal; only Pimp my Phone's glow and bubble stay away.
 */
object Silence {
    private val until get() = P.lightingSilenceUntil
    private val app get() = P.lightingSilenceApp

    fun isActive(foregroundPkg: String): Boolean {
        val u = until.value
        if (u == -1L || u > System.currentTimeMillis()) return true
        val a = app.value
        return a.isNotEmpty() && a == foregroundPkg
    }

    fun forMinutes(minutes: Int) {
        app.value = ""
        until.value = System.currentTimeMillis() + minutes * 60_000L
    }

    fun untilResumed() {
        app.value = ""
        until.value = -1L
    }

    fun whileIn(pkg: String) {
        until.value = 0L
        app.value = pkg
    }

    fun resume() {
        until.value = 0L
        app.value = ""
    }

    /** The app-based pause ends as soon as another app comes to the front. */
    fun onForeground(pkg: String) {
        val a = app.value
        if (a.isNotEmpty() && a != pkg) app.value = ""
    }

    /** Off, 30 min, 1 h, 2 h, until resumed, then off again. Returns a label for the new state. */
    fun cycle(): String {
        val u = until.value
        val now = System.currentTimeMillis()
        val left = if (u > now) u - now else 0L
        return when {
            u == -1L -> { resume(); "Off" }
            left > 61 * 60_000L -> { untilResumed(); "Until you turn it off" }
            left > 31 * 60_000L -> { forMinutes(120); "2 hours" }
            left > 0L || app.value.isNotEmpty() -> { forMinutes(60); "1 hour" }
            else -> { forMinutes(30); "30 minutes" }
        }
    }

    /** "Paused until 21:30", "Paused while you're in Netflix", or null when not paused. */
    fun describe(ctx: Context): String? {
        val u = until.value
        val now = System.currentTimeMillis()
        return when {
            u == -1L -> "Paused until you turn it back on"
            u > now -> "Paused until " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(u))
            app.value.isNotEmpty() -> "Paused while you're in " + Apps.label(ctx, app.value)
            else -> null
        }
    }

    /** Short form for the Quick Settings tile. */
    fun tileLabel(): String? {
        val u = until.value
        val now = System.currentTimeMillis()
        return when {
            u == -1L -> "Until off"
            u > now -> {
                val mins = (u - now) / 60_000L + 1
                if (mins >= 60) "${mins / 60} h ${mins % 60} min" else "$mins min"
            }
            app.value.isNotEmpty() -> "This app"
            else -> null
        }
    }
}
