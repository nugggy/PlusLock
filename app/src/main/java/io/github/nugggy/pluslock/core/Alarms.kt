package io.github.nugggy.pluslock.core

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.nugggy.pluslock.service.PlusAccessibilityService

/**
 * On-time alarms for routines, the morning digest and the bedtime reminder.
 * Exact and allowed while idle, so Doze can't push a 22:00 routine to midnight.
 * Each alarm has a key; firing hands the key to the accessibility service.
 */
object Alarms {
    private const val EXTRA_KEY = "key"

    private fun intent(ctx: Context, key: String) = PendingIntent.getBroadcast(
        ctx, key.hashCode(),
        Intent(ctx, AlarmReceiver::class.java).setPackage(ctx.packageName).putExtra(EXTRA_KEY, key),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun set(ctx: Context, key: String, atMillis: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = intent(ctx, key)
        runCatching {
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pi)
        }
    }

    fun cancel(ctx: Context, key: String) {
        runCatching { ctx.getSystemService(AlarmManager::class.java).cancel(intent(ctx, key)) }
    }

    internal fun keyOf(i: Intent) = i.getStringExtra(EXTRA_KEY)
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Prefs.init(ctx)
        val key = Alarms.keyOf(i) ?: return
        PlusAccessibilityService.instance?.onAlarm(key)
    }
}
