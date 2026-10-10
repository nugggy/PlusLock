package io.github.nugggy.pluslock.wallpaper

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import io.github.nugggy.pluslock.core.P
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Local weather and sunrise times for the living sky, from Open-Meteo (free, no account).
 * Only a rounded, coarse location is sent, about once an hour while the wallpaper is visible.
 */
object Weather {
    data class Now(val code: Int, val sunrise: Long, val sunset: Long, val fetched: Long) {
        val rain get() = code in 51..67 || code in 80..82 || code >= 95
        val snow get() = code in 71..77 || code in 85..86
        val cloudy get() = code in 2..3 || code in 45..48 || rain || snow
        val storm get() = code >= 95
    }

    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var busy = false

    fun current(): Now? {
        val o = runCatching { JSONObject(P.skyWeatherJson.value) }.getOrNull() ?: return null
        val n = Now(o.optInt("code"), o.optLong("sunrise") * 1000, o.optLong("sunset") * 1000, o.optLong("t"))
        // Older than a day is too stale to trust.
        return if (System.currentTimeMillis() - n.fetched < 86_400_000L) n else null
    }

    fun hasPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun refreshIfStale(ctx: Context) {
        if (!P.skyWeather.value || !hasPermission(ctx) || busy) return
        val cur = current()
        if (cur != null && System.currentTimeMillis() - cur.fetched < 3_600_000L) return
        busy = true
        val app = ctx.applicationContext
        io.execute {
            try { fetch(app) } catch (_: Exception) { } finally { busy = false }
        }
    }

    @Suppress("MissingPermission")
    private fun fetch(ctx: Context) {
        val lm = ctx.getSystemService(LocationManager::class.java)
        val loc: Location = listOf(LocationManager.FUSED_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time } ?: return
        // Two decimal places is roughly a 1 km grid: enough for weather, not a street address.
        val lat = "%.2f".format(Locale.ROOT, loc.latitude)
        val lon = "%.2f".format(Locale.ROOT, loc.longitude)
        val url = URL("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
            "&current=weather_code&daily=sunrise,sunset&forecast_days=1&timezone=auto&timeformat=unixtime")
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        val body = try {
            if (conn.responseCode != 200) return
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally { conn.disconnect() }
        val o = JSONObject(body)
        val code = o.getJSONObject("current").getInt("weather_code")
        val daily = o.getJSONObject("daily")
        val out = JSONObject()
            .put("code", code)
            .put("sunrise", daily.getJSONArray("sunrise").getLong(0))
            .put("sunset", daily.getJSONArray("sunset").getLong(0))
            .put("t", System.currentTimeMillis())
        P.skyWeatherJson.value = out.toString()
    }
}
