package io.github.nugggy.pluslock.data

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.nugggy.pluslock.core.StringPref
import org.json.JSONArray

/** Battery level and temperature every ten minutes for the last two days, plus health readings. */
object BatteryLog {
    data class Sample(val time: Long, val pct: Int, val tempC: Float, val charging: Boolean)

    private val json = StringPref("battlog_json", "[]")
    private const val EVERY = 10 * 60_000L
    private const val KEEP = 48 * 3_600_000L

    @Synchronized
    fun record(pct: Int, tempTenths: Int, charging: Boolean) {
        val a = runCatching { JSONArray(json.value) }.getOrDefault(JSONArray())
        val now = System.currentTimeMillis()
        val last = if (a.length() > 0) a.getJSONArray(a.length() - 1).getLong(0) else 0L
        // A clock set backwards would otherwise stop the log for good.
        if (now - last in 0 until EVERY) return
        val out = JSONArray()
        for (i in 0 until a.length()) {
            val s = a.getJSONArray(i)
            if (now - s.getLong(0) < KEEP) out.put(s)
        }
        out.put(JSONArray().put(now).put(pct).put(tempTenths).put(if (charging) 1 else 0))
        json.value = out.toString()
    }

    fun samples(): List<Sample> {
        val a = runCatching { JSONArray(json.value) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { a.getJSONArray(it) }
            .map { Sample(it.getLong(0), it.getInt(1), it.getInt(2) / 10f, it.getInt(3) == 1) }
    }

    data class Health(
        val pct: Int,
        val tempC: Float,
        val volts: Float,
        val health: String,
        val cycles: Int?,
        val fullMah: Int?,
        val designMah: Int?,
        val currentMa: Int?,
        val charging: Boolean,
    )

    private var design: Int? = null
    private var designRead = false

    /** The battery's rated capacity. Android only exposes it through an internal class, so read it once. */
    @SuppressLint("PrivateApi")
    private fun designMah(ctx: Context): Int? {
        if (!designRead) {
            designRead = true
            design = runCatching {
                val c = Class.forName("com.android.internal.os.PowerProfile")
                val p = c.getConstructor(Context::class.java).newInstance(ctx)
                (c.getMethod("getBatteryCapacity").invoke(p) as Double).toInt()
            }.getOrNull()?.takeIf { it > 500 }
        }
        return design
    }

    fun health(ctx: Context): Health? {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return null
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val pct = level * 100 / scale
        val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val counter = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) }.getOrDefault(0)
        // Charge counter is the charge left (µAh); scaled to 100% it estimates the battery's real capacity today.
        val full = if (counter > 0 && pct >= 20) (counter / 1000f * 100f / pct).toInt() else null
        val design = designMah(ctx)
        val current = runCatching { bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) }.getOrNull()
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }?.let { it / 1000 }
        return Health(
            pct = pct,
            tempC = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f,
            volts = i.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) / 1000f,
            health = when (i.getIntExtra(BatteryManager.EXTRA_HEALTH, 0)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheating"
                BatteryManager.BATTERY_HEALTH_DEAD -> "Worn out"
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over voltage"
                BatteryManager.BATTERY_HEALTH_COLD -> "Too cold"
                else -> "Not reported"
            },
            cycles = i.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1).takeIf { it >= 0 },
            fullMah = full,
            designMah = design,
            currentMa = current,
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL,
        )
    }
}
