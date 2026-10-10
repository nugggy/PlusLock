package io.github.nugggy.pluslock.data

import io.github.nugggy.pluslock.core.StringPref
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Time spent in each app per day, counted by the accessibility service while the
 * screen is on and unlocked. Keeps two weeks, on the phone only.
 * Keys starting with "+" are the extra minutes granted past a daily limit.
 */
object UsageStore {
    private val day = StringPref("usage_day", "")
    private val todayJson = StringPref("usage_json", "{}")
    private val historyJson = StringPref("usage_hist_json", "{}")
    private const val KEEP_DAYS = 14

    private fun dateKey(d: Date = Date()) = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(d)

    @Synchronized
    fun today(): JSONObject {
        val key = dateKey()
        if (day.value != key) {
            // A new day: file yesterday's numbers away before starting fresh.
            val prevKey = day.value
            if (prevKey.isNotEmpty()) {
                val hist = runCatching { JSONObject(historyJson.value) }.getOrDefault(JSONObject())
                val prev = runCatching { JSONObject(todayJson.value) }.getOrDefault(JSONObject())
                hist.put(prevKey, prev)
                val keep = (0 until KEEP_DAYS).map { dateKey(daysAgo(it)) }.toSet()
                hist.keys().asSequence().toList().filter { it !in keep }.forEach { hist.remove(it) }
                historyJson.value = hist.toString()
            }
            day.value = key
            todayJson.value = "{}"
        }
        return runCatching { JSONObject(todayJson.value) }.getOrDefault(JSONObject())
    }

    /** Adds time that ran from [startWall] to now, splitting it at midnight if it crossed one. */
    @Synchronized
    fun addSpan(pkg: String, startWall: Long, ms: Long) {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val before = (midnight - startWall).coerceIn(0, ms)
        if (before > 0) {
            today() // roll yesterday into history first
            val hist = runCatching { JSONObject(historyJson.value) }.getOrDefault(JSONObject())
            val key = dateKey(Date(midnight - 1))
            val day = hist.optJSONObject(key) ?: JSONObject()
            day.put(pkg, day.optLong(pkg) + before)
            hist.put(key, day)
            historyJson.value = hist.toString()
        }
        if (ms - before > 0) add(pkg, ms - before)
    }

    @Synchronized
    fun add(pkg: String, ms: Long) {
        val o = today()
        o.put(pkg, o.optLong(pkg) + ms)
        todayJson.value = o.toString()
    }

    fun used(pkg: String): Long = today().optLong(pkg)
    fun extra(pkg: String): Long = today().optLong("+$pkg")

    @Synchronized
    fun grantExtra(pkg: String, minutes: Int) {
        val o = today()
        o.put("+$pkg", o.optLong("+$pkg") + minutes * 60_000L)
        todayJson.value = o.toString()
    }

    private fun daysAgo(n: Int): Date = Calendar.getInstance().apply { add(Calendar.DAY_OF_YEAR, -n) }.time

    /** App -> milliseconds for the day n days ago (0 is today), without the "+" entries. */
    fun dayTotals(n: Int): Map<String, Long> {
        val o = if (n == 0) today() else {
            today() // makes sure history is rolled over first
            runCatching { JSONObject(historyJson.value).optJSONObject(dateKey(daysAgo(n))) }.getOrNull() ?: JSONObject()
        }
        return o.keys().asSequence().filter { !it.startsWith("+") }.associateWith { o.optLong(it) }
    }

    fun label(n: Int): String = if (n == 0) "Today" else SimpleDateFormat("EEE", Locale.getDefault()).format(daysAgo(n))
}
