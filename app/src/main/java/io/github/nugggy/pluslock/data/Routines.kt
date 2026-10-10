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
    SCREEN_ON("The screen turns on"),
    SCREEN_OFF("The screen turns off"),
    UNLOCK("You unlock the phone"),
    BATTERY_BELOW("Battery drops below a level"),
    BATTERY_ABOVE("Battery charges past a level"),
    NOTIFICATION("A notification arrives from an app"),
}

enum class ConditionType(val label: String) {
    TIME_BETWEEN("Between certain hours"),
    WEEKDAYS("On weekdays"),
    WEEKENDS("On weekends"),
    CHARGING("While charging"),
    NOT_CHARGING("While not charging"),
    HEADPHONES("While headphones are connected"),
    NO_HEADPHONES("While no headphones are connected"),
    WIFI("While on Wi-Fi"),
    NO_WIFI("While off Wi-Fi"),
    BATTERY_BELOW("While the battery is below a level"),
    LOCKED("While the phone is locked"),
    UNLOCKED("While the phone is unlocked"),
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
    RING_VOLUME("Set ringtone volume"),
    NOTIFICATION_VOLUME("Set notification volume"),
    ALARM_VOLUME("Set alarm volume"),
    VOLUME_BOOST("Volume boost"),
    EDGE_LIGHTING("Edge lighting"),
    EXTRA_DIM("Extra dim"),
    ROTATION("Auto-rotate"),
    VIBRATE("Vibrate a pattern"),
    BLINK_TORCH("Blink the torch"),
    MESSAGE("Show a message"),
    WAIT("Wait"),
}

/** arg holds the detail: a package, a device name, "HH:MM[,W]" for time, or a value for actions. */
data class Trigger(val type: TriggerType, val arg: String = "")
data class RoutineAction(val type: RoutineActionType, val arg: String = "")
data class RoutineCondition(val type: ConditionType, val arg: String = "")

data class Routine(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val on: Boolean = true,
    val trigger: Trigger = Trigger(TriggerType.CHARGER_IN),
    val actions: List<RoutineAction> = emptyList(),
    /** All must be true for the routine to run. */
    val conditions: List<RoutineCondition> = emptyList(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("on", on)
        .put("trigger", JSONObject().put("type", trigger.type.name).put("arg", trigger.arg))
        .put("actions", JSONArray().apply { actions.forEach { put(JSONObject().put("type", it.type.name).put("arg", it.arg)) } })
        .put("conditions", JSONArray().apply { conditions.forEach { put(JSONObject().put("type", it.type.name).put("arg", it.arg)) } })

    companion object {
        fun fromJson(o: JSONObject): Routine? = runCatching {
            val t = o.getJSONObject("trigger")
            val a = o.optJSONArray("actions") ?: JSONArray()
            val cs = o.optJSONArray("conditions") ?: JSONArray()
            Routine(
                id = o.optString("id").ifBlank { UUID.randomUUID().toString() },
                name = o.optString("name"),
                on = o.optBoolean("on", true),
                trigger = Trigger(TriggerType.valueOf(t.getString("type")), t.optString("arg")),
                actions = (0 until a.length()).mapNotNull { i ->
                    val x = a.getJSONObject(i)
                    runCatching { RoutineAction(RoutineActionType.valueOf(x.getString("type")), x.optString("arg")) }.getOrNull()
                },
                conditions = (0 until cs.length()).mapNotNull { i ->
                    val x = cs.getJSONObject(i)
                    runCatching { RoutineCondition(ConditionType.valueOf(x.getString("type")), x.optString("arg")) }.getOrNull()
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
        TriggerType.BATTERY_BELOW -> "When the battery drops below ${t.arg.ifBlank { "20" }}%"
        TriggerType.BATTERY_ABOVE -> "When the battery charges past ${t.arg.ifBlank { "80" }}%"
        TriggerType.NOTIFICATION -> if (t.arg.isBlank()) "When any notification arrives" else "When ${Apps.label(ctx, t.arg)} sends a notification"
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
        RoutineActionType.RING_VOLUME -> "Ringtone volume ${a.arg.ifBlank { "50" }}%"
        RoutineActionType.NOTIFICATION_VOLUME -> "Notification volume ${a.arg.ifBlank { "50" }}%"
        RoutineActionType.ALARM_VOLUME -> "Alarm volume ${a.arg.ifBlank { "50" }}%"
        RoutineActionType.VOLUME_BOOST -> "Volume boost " + if (a.arg == "OFF") "off" else "on"
        RoutineActionType.EDGE_LIGHTING -> "Edge lighting " + if (a.arg == "OFF") "off" else "on"
        RoutineActionType.EXTRA_DIM -> "Extra dim " + if (a.arg == "OFF") "off" else "on"
        RoutineActionType.ROTATION -> "Auto-rotate " + if (a.arg == "OFF") "off" else "on"
        RoutineActionType.VIBRATE -> "Vibrate: " + (io.github.nugggy.pluslock.core.Haptics.presets[a.arg] ?: "Heartbeat")
        RoutineActionType.BLINK_TORCH -> "Blink the torch"
        RoutineActionType.MESSAGE -> "Show \"" + a.arg.ifBlank { "Routine ran" } + "\""
        RoutineActionType.WAIT -> "Wait ${a.arg.ifBlank { "5" }} seconds"
    }

    fun describe(c: RoutineCondition): String = when (c.type) {
        ConditionType.TIME_BETWEEN -> {
            val (a, b) = c.arg.split('-').let { (it.getOrNull(0)?.toIntOrNull() ?: 22) to (it.getOrNull(1)?.toIntOrNull() ?: 7) }
            "Between %02d:00 and %02d:00".format(a, b)
        }
        ConditionType.BATTERY_BELOW -> "While the battery is below ${c.arg.ifBlank { "30" }}%"
        else -> c.type.label
    }

    /** "07:30,W" means 07:30 on weekdays. */
    fun timeArg(arg: String): Pair<String, Boolean> {
        val parts = arg.split(',')
        return parts[0].ifBlank { "07:00" } to (parts.getOrNull(1) == "W")
    }
}
