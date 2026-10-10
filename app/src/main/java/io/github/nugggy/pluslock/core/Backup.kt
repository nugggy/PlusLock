package io.github.nugggy.pluslock.core

import android.content.Context
import android.net.Uri
import org.json.JSONObject

/**
 * Saves every setting, app profile, routine and rule to a file you choose, and reads it back.
 * Notification history, screen time and battery logs stay on the phone and aren't included.
 */
object Backup {
    private const val FORMAT = "pimp-my-phone-settings"

    /** Changing state, history and logs, which don't belong in a backup. */
    private val skip = listOf(
        "usage_", "restore_", "update_", "battlog_", "digest_last", "caffeine_until",
        "lighting_silence_", "sky_weather_json", "last_seen_version",
    )

    private fun keep(key: String) = skip.none { key.startsWith(it) }

    fun export(ctx: Context, uri: Uri): Int {
        val prefs = JSONObject()
        var n = 0
        Prefs.sp.all.forEach { (k, v) ->
            if (!keep(k)) return@forEach
            val (type, value) = when (v) {
                is Boolean -> "b" to v
                is Int -> "i" to v
                is Long -> "l" to v
                is Float -> "f" to v.toDouble()
                is String -> "s" to v
                else -> return@forEach
            }
            prefs.put(k, JSONObject().put("t", type).put("v", value))
            n++
        }
        val out = JSONObject().put("format", FORMAT).put("version", 1).put("saved", System.currentTimeMillis()).put("prefs", prefs)
        ctx.contentResolver.openOutputStream(uri, "wt")?.use { it.write(out.toString(2).toByteArray()) }
            ?: error("Couldn't open the file")
        return n
    }

    /** Returns how many settings were restored. */
    fun import(ctx: Context, uri: Uri): Int {
        val text = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Couldn't open the file")
        val root = JSONObject(text)
        require(root.optString("format") == FORMAT) { "That file isn't a Pimp my Phone backup" }
        val prefs = root.getJSONObject("prefs")
        val e = Prefs.sp.edit()
        var n = 0
        prefs.keys().forEach { k ->
            if (!keep(k)) return@forEach
            val o = prefs.getJSONObject(k)
            when (o.optString("t")) {
                "b" -> e.putBoolean(k, o.getBoolean("v"))
                "i" -> e.putInt(k, o.getInt("v"))
                "l" -> e.putLong(k, o.getLong("v"))
                "f" -> e.putFloat(k, o.getDouble("v").toFloat())
                "s" -> e.putString(k, o.getString("v"))
                else -> return@forEach
            }
            n++
        }
        e.apply()
        return n
    }
}
