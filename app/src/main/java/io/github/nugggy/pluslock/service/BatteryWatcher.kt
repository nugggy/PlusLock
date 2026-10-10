package io.github.nugggy.pluslock.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.P

/** Feeds the camera ring, plays the charging glow and runs the charge guard. */
class BatteryWatcher(private val svc: PlusAccessibilityService) {
    private var alerted = false
    private var wasCharging: Boolean? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = handle(i)
    }

    fun start() {
        val f = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        svc.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)?.let { handle(it) }
    }

    fun stop() {
        runCatching { svc.unregisterReceiver(receiver) }
    }

    private fun handle(i: Intent) {
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0) return
        val pct = level * 100 / scale.coerceAtLeast(1)
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        svc.ring.update(pct, charging)

        val prev = wasCharging
        wasCharging = charging
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
