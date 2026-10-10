package io.github.nugggy.pluslock.data

import android.content.Context
import io.github.nugggy.pluslock.core.Actions
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.P
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class TriggerType(val label: String) {
    APP_OPEN("An app opens"),
    BT_ON("Bluetooth device connects"),
    BT_OFF("Bluetooth device disconnects"),
    HEADPHONES_IN("Headphones plugged in or connected"),
    HEADPHONES_OUT("Headphones removed"),
    CHARGER_IN("Charger plugged in"),
    CHARGER_OUT("Charger unplugged"),
    WIFI_ON("Wi-Fi connects"),
    WIFI_OFF("Wi-Fi disconnects"),
    TIME("At a time of day"),
}

enum class RoutineActionType(val label: String) {
    OPEN_APP("Open an app"),
    MEDIA_VOLUME("Set media volume"),
    SOUND_MODE("Set sound mode"),
    DND("Do Not Disturb"),
    TORCH("Torch"),
    CAFFEINE("Keep the screen on"),
    PAUSE_LIGHTING("Pause edge lighting"),
    BRIGHTNESS("Set brightness"),
    GESTURE("Run a gesture action"),
}

/** arg holds the detail: a package, a device name, "HH:MM[,W]" for time, or a value for actions. */
data class Trigger(val type: TriggerType, val arg: String = "")
data class RoutineAction(val type: RoutineActionType, val arg: String = "")

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val on: Boolean = true,
    val trigger: Trigger = Trigger(TriggerType.CHARGER_IN),
    val actions: List<RoutineAction> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("on", on)
        .put("trigger", JSONObject().put("type", trigger.type.name).put("arg", trigger.arg))
        .put("actions", JSONArray().apply { actions.forEach { put(JSONObject().put("type", it.type.name).put("arg", it.arg)) } })

    companion object {
        fun fromJson(o: JSONObject): Routine? = runCatching {
            val t = o.getJSONObject("trigger")
            val a = o.optJSONArray("actions") ?: JSONArray()
            Routine(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name"),
                on = o.optBoolean("on", true),
                trigger = Trigger(TriggerType.valueOf(t.getString("type")), t.optString("arg")),
                actions = (0 until a.length()).mapNotNull { i ->
                    val x = a.getJSONObject(i)
                    runCatching { RoutineAction(RoutineActionType.valueOf(x.getString("type")), x.optString("arg")) }.getOrNull()
                },
            )
        }.getOrNull()
    }
}

object Routines {
    fun all(): List<Routine> {
        val a = runCatching { JSONArray(P.routinesJson.value) }.getOrDefault(JSONArray())
        return (0 until a.length()).mapNotNull { Routine.fromJson(a.getJSONObject(it)) }
    }

    fun get(id: String) = all().firstOrNull { it.id == id }

    @Synchronized
    fun save(r: Routine) {
        val list = all().toMutableList()
        val i = list.indexOfFirst { it.id == r.id }
        if (i >= 0) list[i] = r else list += r
        write(list)
    }

    @Synchronized
    fun remove(id: String) = write(all().filter { it.id != id })

    private fun write(list: List<Routine>) {
        P.routinesJson.value = JSONArray().apply { list.forEach { put(it.toJson()) } }.toString()
    }

    // ---- wording for the UI ----

    fun describe(ctx: Context, t: Trigger): String = when (t.type) {
        TriggerType.APP_OPEN -> if (t.arg.isBlank()) "When an app opens" else "When ${Apps.label(ctx, t.arg)} opens"
        TriggerType.BT_ON -> "When ${t.arg.ifBlank { "any Bluetooth device" }} connects"
        TriggerType.BT_OFF -> "When ${t.arg.ifBlank { "any Bluetooth device" }} disconnects"
        TriggerType.TIME -> {
            val (time, weekdays) = timeArg(t.arg)
            "At $time" + if (weekdays) " on weekdays" else " every day"
        }
        else -> "When " + t.type.label.replaceFirstChar { it.lowercase() }
    }

    fun describe(ctx: Context, a: RoutineAction): String = when (a.type) {
        RoutineActionType.OPEN_APP -> "Open " + if (a.arg.isBlank()) "an app" else Apps.label(ctx, a.arg)
        RoutineActionType.MEDIA_VOLUME -> "Media volume ${a.arg.ifBlank { "50" }}%"
        RoutineActionType.SOUND_MODE -> "Sound mode: " + a.arg.ifBlank { "VIBRATE" }.lowercase().replaceFirstChar { it.uppercase() }
        RoutineActionType.DND -> "Do Not Disturb " + if (a.arg == "OFF") "off" else "on"
        RoutineActionType.TORCH -> "Torch " + if (a.arg == "OFF") "off" else "on"
        RoutineActionType.CAFFEINE -> if (a.arg == "OFF") "Let the screen sleep" else "Keep the screen on"
        RoutineActionType.PAUSE_LIGHTING -> if (a.arg == "0") "Resume edge lighting" else "Pause edge lighting for ${a.arg.ifBlank { "60" }} min"
        RoutineActionType.BRIGHTNESS -> "Brightness ${a.arg.ifBlank { "50" }}%"
        RoutineActionType.GESTURE -> Actions.label(ctx, a.arg.ifBlank { "PLAY_PAUSE" })
    }

    /** "07:30,W" means 07:30 on weekdays. */
    fun timeArg(arg: String): Pair<String, Boolean> {
        val parts = arg.split(',')
        return parts[0].ifBlank { "07:00" } to (parts.getOrNull(1) == "W")
    }
}
