package io.github.nugggy.pluslock.core

import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.nugggy.pluslock.ui.WakeActivity
import kotlin.math.min

/**
 * Lights up a sleeping screen so a glow can show over the lock screen.
 * Tries a wake lock first, then falls back to a see-through activity that turns the screen on.
 */
object Waker {
    private val handler = Handler(Looper.getMainLooper())

    fun wake(ctx: Context, holdMs: Long, skipInPocket: Boolean, then: () -> Unit) {
        if (skipInPocket) Pocket.check(ctx) { near ->
            if (near) Diag.log("wake", "skipped: phone looks like it's in a pocket") else doWake(ctx, holdMs, then)
        }
        else doWake(ctx, holdMs, then)
    }

    @Suppress("DEPRECATION")
    private fun doWake(ctx: Context, holdMs: Long, then: () -> Unit) {
        val pm = ctx.getSystemService(PowerManager::class.java)
        Diag.log("wake", "waking the screen")
        runCatching {
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "pimpmyphone:wake",
            ).acquire(holdMs.coerceIn(2_000L, 120_000L))
        }
        handler.postDelayed({
            if (!pm.isInteractive) Diag.log("wake", "wake lock didn't turn the screen on, trying the activity")
            if (!pm.isInteractive) runCatching {
                ctx.startActivity(Intent(ctx, WakeActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }, 250)
        // Wait for the screen, then carry on. Give up after a couple of seconds.
        var tries = 0
        val poll = object : Runnable {
            override fun run() {
                if (pm.isInteractive) { Diag.log("wake", "screen on after ${tries * 100 + 100} ms"); handler.postDelayed({ then() }, 150); return }
                if (++tries < 25) handler.postDelayed(this, 100)
                else Diag.log("wake", "gave up: the screen stayed off")
            }
        }
        handler.postDelayed(poll, 100)
    }
}

/** A quick look at the proximity sensor, so a phone in a pocket or bag stays dark. */
object Pocket {
    fun check(ctx: Context, cb: (near: Boolean) -> Unit) {
        val handler = Handler(Looper.getMainLooper())
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY, true) ?: sm?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (sm == null || sensor == null) { cb(false); return }
        var done = false
        val listener = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                if (done) return
                done = true
                sm.unregisterListener(this)
                cb(e.values[0] < min(5f, sensor.maximumRange))
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
        }
        sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_FASTEST, handler)
        handler.postDelayed({
            if (!done) { done = true; sm.unregisterListener(listener); cb(false) }
        }, 500)
    }
}
