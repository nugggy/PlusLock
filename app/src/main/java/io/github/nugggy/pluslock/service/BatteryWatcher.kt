package io.github.nugggy.pluslock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.nugggy.pluslock.core.Alarms
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.Hours
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.data.BatteryLog

/** Feeds the camera ring, plays the charging glow, and runs the charge guard and bedtime reminder. */
class BatteryWatcher(private val svc: PlusAccessibilityService) {
    private var alerted = false
    private var wasCharging: Boolean? = null
    private var lastPct = 100
    private val bm = svc.getSystemService(BatteryManager::class.java)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handle(i)
    }

    fun start() {
        val f = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        svc.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)?.let { handle(it) }
        scheduleBedtime()
    }

    fun stop() {
        runCatching { svc.unregisterReceiver(receiver) }
        Alarms.cancel(svc, ALARM)
    }

    fun scheduleBedtime() {
        Alarms.cancel(svc, ALARM)
        if (!P.bedtimeOn.value) return
        Alarms.set(svc, ALARM, System.currentTimeMillis() + Hours.millisUntilHour(P.bedtimeHour.value, P.bedtimeMinute.value))
    }

    companion object { const val ALARM = "bedtime" }

    fun checkBedtime() {
        if (P.bedtimeOn.value && wasCharging == false && lastPct < P.bedtimeBelow.value) {
            Haptics.play(svc, "KNOCK", "")
            Notifs.post(
                svc, Notifs.ID_BEDTIME, Notifs.CH_ALERTS,
                "Plug in before bed",
                "Your battery is at $lastPct%. Charging overnight means a full day tomorrow.",
                Notifs.openApp(svc, "battery"),
            )
        }
        scheduleBedtime()
    }

    private fun handle(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val pct = level * 100 / scale.coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        lastPct = pct
        BatteryLog.record(pct, i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0), charging)
        val eta = if (charging) runCatching { bm.computeChargeTimeRemaining() }.getOrDefault(-1L) else -1L
        svc.ring.update(pct, charging, eta)

        val prev = wasCharging
        wasCharging = charging
        if (prev == false && charging) {
            val microAmps = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0)
            val volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
            IslandState.onPlugged(pct, kotlin.math.abs(microAmps) / 1_000_000f * volts)
        }
        if (prev == false && charging && P.chargingGlow.value) {
            svc.lighting.show(0xFF4BE38A.toInt(), null, "Charging", "$pct%", effectOverride = "PULSE")
        }
        if (!charging) alerted = false
        if (charging && !alerted && P.chargeAlertOn.value && pct >= P.chargeAlertLevel.value) {
            alerted = true
            Haptics.play(svc, "KNOCK", "")
            Notifs.post(
                svc, Notifs.ID_CHARGE, Notifs.CH_ALERTS,
                "Battery at $pct%",
                "Unplug now to go easy on the battery. You set the charge guard to ${P.chargeAlertLevel.value}%.",
                Notifs.openApp(svc, "battery"),
            )
        }
    }
}
