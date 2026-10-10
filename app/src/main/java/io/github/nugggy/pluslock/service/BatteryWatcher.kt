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
    private var lastFullPct = -1
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

    /** The level with decimals, from the charge counter and the battery's rated size. */
    private fun exactLevel(pct: Int): Float? {
        val design = BatteryLog.designMah(svc) ?: return null
        val counter = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(0)
        if (counter <= 0) return null
        return (counter / 1000f / design * 100f).coerceIn(pct.toFloat(), pct + 0.99f)
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
        svc.routines.onBattery(pct)

        val prev = wasCharging
        wasCharging = charging
        val volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f
        val microAmps = if (charging) runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrDefault(0) else 0
        val watts = if (microAmps == Int.MIN_VALUE) 0f else kotlin.math.abs(microAmps) / 1_000_000f * volts
        svc.chargeFx.update(
            ChargeState(pct, charging, eta, watts, i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f, exactLevel(pct)),
            volts,
        )
        if (prev == false && charging) {
            IslandState.onPlugged(pct, watts)
            svc.chargeFx.onPlugged()
        }
        if (prev == true && !charging) svc.chargeFx.onUnplugged()
        if (charging && pct >= 100 && lastFullPct in 0..99) svc.chargeFx.onFull()
        lastFullPct = if (charging) pct else -1
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
