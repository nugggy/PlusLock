package io.github.nugggy.pluslock.data

import io.github.nugggy.pluslock.core.P
import org.json.JSONArray
import org.json.JSONObject

/**
 * A per-contact edge lighting rule. When a notification's sender or title contains
 * [match], the glow uses [effect] and [colour] instead of the app's own.
 * colour 0 keeps the app's colour, effect "" keeps the global effect.
 */
data class LightingRule(
    val match: String,
    val effect: String = "",
    val colour: Int = 0,
    /** VIPs get through work-hours holds, pauses, quiet hours and Do Not Disturb. */
    val vip: Boolean = false,
    /** Their own vibration pattern (a [io.github.nugggy.pluslock.core.Haptics] preset), "" for none. */
    val haptic: String = "",
) {
    fun toJson(): JSONObject = JSONObject().put("match", match).put("effect", effect).put("colour", colour)
        .put("vip", vip).put("haptic", haptic)

    companion object {
        fun fromJson(o: JSONObject) = LightingRule(
            o.optString("match"), o.optString("effect"), o.optInt("colour"), o.optBoolean("vip"), o.optString("haptic"),
        )
    }
}

object LightingRules {
    fun all(): List<LightingRule> {
        val a = runCatching { JSONArray(P.lightingRulesJson.value) }.getOrDefault(JSONArray())
        return (0 until a.length()).map { LightingRule.fromJson(a.getJSONObject(it)) }.filter { it.match.isNotBlank() }
    }

    fun save(rules: List<LightingRule>) {
        P.lightingRulesJson.value = JSONArray().apply { rules.forEach { put(it.toJson()) } }.toString()
    }

    /**
     * The first rule whose name appears in the notification's title, or in the sender
     * of a group message ("Sam: see you soon"). The message itself is never matched.
     */
    fun find(title: String, body: String): LightingRule? {
        val sender = body.substringBefore(": ", "").takeIf { it.length in 1..40 }.orEmpty()
        val hay = "$title\n$sender"
        return all().firstOrNull { hay.contains(it.match.trim(), ignoreCase = true) }
    }
}
