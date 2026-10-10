package io.github.nugggy.pluslock.data

import io.github.nugggy.pluslock.core.P
import org.json.JSONObject

/**
 * Per-app behaviour, applied when that app comes to the front.
 * volume: media volume percent, or -1 to leave it alone.
 * rotation: 0 system default, 1 auto-rotate, 2 portrait, 3 landscape.
 * focusMinutes: doomscroll breaker limit, 0 for off.
 * edgeColour: 0 means use the app's own colour.
 * dailyMinutes: daily time limit, 0 for none.
 * fontScale: percent, 0 to leave the system size alone.
 * keyboard: input method id, empty to leave it alone.
 */
data class AppProfile(
    val pkg: String,
    val volume: Int = -1,
    val keepAwake: Boolean = false,
    val rotation: Int = 0,
    val noGestures: Boolean = false,
    val noLighting: Boolean = false,
    val edgeColour: Int = 0,
    val haptic: String = "NONE",
    val whisper: Boolean = false,
    val focusMinutes: Int = 0,
    val locked: Boolean = false,
    val dailyMinutes: Int = 0,
    val dnd: Boolean = false,
    val fontScale: Int = 0,
    val keyboard: String = "",
) {
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (volume >= 0) parts += "Volume $volume%"
        if (keepAwake) parts += "Stay awake"
        when (rotation) {
            1 -> parts += "Auto-rotate"
            2 -> parts += "Portrait"
            3 -> parts += "Landscape"
        }
        if (noGestures) parts += "No edge gestures"
        if (noLighting) parts += "No edge lighting"
        if (haptic != "NONE") parts += "Haptic signature"
        if (whisper) parts += "Read aloud"
        if (focusMinutes > 0) parts += "Break after $focusMinutes min"
        if (locked) parts += "Fingerprint lock"
        if (dailyMinutes > 0) parts += "$dailyMinutes min a day"
        if (dnd) parts += "Do Not Disturb"
        if (fontScale > 0) parts += "Text $fontScale%"
        if (keyboard.isNotEmpty()) parts += "Own keyboard"
        return parts.joinToString(" · ").ifEmpty { "No changes yet" }
    }

    fun toJson(): JSONObject = JSONObject()
        .put("volume", volume).put("keepAwake", keepAwake).put("rotation", rotation)
        .put("noGestures", noGestures).put("noLighting", noLighting).put("edgeColour", edgeColour)
        .put("haptic", haptic).put("whisper", whisper).put("focusMinutes", focusMinutes)
        .put("locked", locked).put("dailyMinutes", dailyMinutes).put("dnd", dnd)
        .put("fontScale", fontScale).put("keyboard", keyboard)

    companion object {
        fun fromJson(pkg: String, o: JSONObject) = AppProfile(
            pkg = pkg,
            volume = o.optInt("volume", -1),
            keepAwake = o.optBoolean("keepAwake"),
            rotation = o.optInt("rotation"),
            noGestures = o.optBoolean("noGestures"),
            noLighting = o.optBoolean("noLighting"),
            edgeColour = o.optInt("edgeColour"),
            haptic = o.optString("haptic", "NONE"),
            whisper = o.optBoolean("whisper"),
            focusMinutes = o.optInt("focusMinutes"),
            locked = o.optBoolean("locked"),
            dailyMinutes = o.optInt("dailyMinutes"),
            dnd = o.optBoolean("dnd"),
            fontScale = o.optInt("fontScale"),
            keyboard = o.optString("keyboard"),
        )
    }
}

object Profiles {
    @Volatile private var cacheJson: String? = null
    @Volatile private var cache: Map<String, AppProfile> = emptyMap()

    fun all(): Map<String, AppProfile> {
        val json = P.profilesJson.value
        if (json != cacheJson) {
            val o = runCatching { JSONObject(json) }.getOrDefault(JSONObject())
            cache = o.keys().asSequence().associateWith { AppProfile.fromJson(it, o.getJSONObject(it)) }
            cacheJson = json
        }
        return cache
    }

    fun get(pkg: String): AppProfile? = all()[pkg]

    @Synchronized
    fun save(p: AppProfile) {
        val o = runCatching { JSONObject(P.profilesJson.value) }.getOrDefault(JSONObject())
        o.put(p.pkg, p.toJson())
        P.profilesJson.value = o.toString()
    }

    @Synchronized
    fun remove(pkg: String) {
        val o = runCatching { JSONObject(P.profilesJson.value) }.getOrDefault(JSONObject())
        o.remove(pkg)
        P.profilesJson.value = o.toString()
    }
}
