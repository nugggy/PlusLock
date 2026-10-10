package io.github.nugggy.pluslock.core

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    lateinit var sp: SharedPreferences
        private set

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences("pluslock", Context.MODE_PRIVATE)
        }
    }
}

abstract class Pref<T>(val key: String, val default: T) {
    abstract var value: T
}

class BoolPref(key: String, default: Boolean) : Pref<Boolean>(key, default) {
    override var value: Boolean
        get() = Prefs.sp.getBoolean(key, default)
        set(v) = Prefs.sp.edit().putBoolean(key, v).apply()
}

class IntPref(key: String, default: Int) : Pref<Int>(key, default) {
    override var value: Int
        get() = Prefs.sp.getInt(key, default)
        set(v) = Prefs.sp.edit().putInt(key, v).apply()
}

class LongPref(key: String, default: Long) : Pref<Long>(key, default) {
    override var value: Long
        get() = Prefs.sp.getLong(key, default)
        set(v) = Prefs.sp.edit().putLong(key, v).apply()
}

class StringPref(key: String, default: String) : Pref<String>(key, default) {
    override var value: String
        get() = Prefs.sp.getString(key, default) ?: default
        set(v) = Prefs.sp.edit().putString(key, v).apply()
}

enum class Side(val label: String) { LEFT("Left handle"), RIGHT("Right handle") }

enum class Swipe(val label: String) {
    IN("Swipe in"),
    UP("Swipe in and up"),
    DOWN("Swipe in and down"),
    LONG("Long swipe in"),
    HOLD("Swipe in and hold"),
}

/** Every setting in the app, with its default. */
object P {
    // Edge gestures (One Hand Operation+)
    val gesturesOn = BoolPref("gestures_on", false)
    val leftHandle = BoolPref("gesture_left", true)
    val rightHandle = BoolPref("gesture_right", true)
    val handleWidth = IntPref("handle_width_dp", 16)
    val handleHeight = IntPref("handle_height_dp", 170)
    val handlePosition = IntPref("handle_position", 60)
    val handleVisibility = IntPref("handle_alpha", 30)
    val handleColor = IntPref("handle_color", 0xFF7FB2FF.toInt())
    val gestureHaptics = BoolPref("gesture_haptics", true)
    val gestureHideLandscape = BoolPref("gesture_hide_landscape", true)

    fun gesture(side: Side, dir: Swipe) = StringPref("gesture_${side.name}_${dir.name}", defaultGesture(side, dir))

    private fun defaultGesture(side: Side, dir: Swipe): String = when (side) {
        Side.LEFT -> when (dir) {
            Swipe.IN -> GestureAction.BACK
            Swipe.UP -> GestureAction.HOME
            Swipe.DOWN -> GestureAction.NOTIFICATIONS
            Swipe.LONG -> GestureAction.LAST_APP
            Swipe.HOLD -> GestureAction.SCREENSHOT
        }
        Side.RIGHT -> when (dir) {
            Swipe.IN -> GestureAction.BACK
            Swipe.UP -> GestureAction.RECENTS
            Swipe.DOWN -> GestureAction.QUICK_SETTINGS
            Swipe.LONG -> GestureAction.CONTROL_DECK
            Swipe.HOLD -> GestureAction.TORCH
        }
    }.name

    // Edge lighting (Edge Lighting+)
    val lightingOn = BoolPref("lighting_on", false)
    val lightingEffect = StringPref("lighting_effect", "COMET")
    val lightingThickness = IntPref("lighting_thickness_dp", 5)
    val lightingDuration = IntPref("lighting_duration_s", 5)
    val lightingAppColour = BoolPref("lighting_app_colour", true)
    val lightingColour = IntPref("lighting_colour", 0xFF7FB2FF.toInt())
    val lightingPill = BoolPref("lighting_pill", true)
    val lightingRespectDnd = BoolPref("lighting_dnd", true)
    val chargingGlow = BoolPref("charging_glow", true)

    // Camera ring
    val ringOn = BoolPref("ring_on", false)
    val ringThickness = IntPref("ring_thickness_dp10", 30) // tenths of a dp
    val ringGap = IntPref("ring_gap_dp10", 20)
    val ringScale = IntPref("ring_scale", 100)
    val ringOffsetX = IntPref("ring_offset_x", 0)
    val ringOffsetY = IntPref("ring_offset_y", 0)
    val ringColourMode = StringPref("ring_colour_mode", "LEVEL")
    val ringColour = IntPref("ring_colour", 0xFF7FB2FF.toInt())
    val ringHideLandscape = BoolPref("ring_hide_landscape", true)
    val ringShowTrack = BoolPref("ring_track", true)

    // Charge guard
    val chargeAlertOn = BoolPref("charge_alert_on", false)
    val chargeAlertLevel = IntPref("charge_alert_level", 80)

    // Notification vault (NotiStar)
    val vaultOn = BoolPref("vault_on", true)
    val vaultRetentionDays = IntPref("vault_retention_days", 30)
    val unsentAlert = BoolPref("unsent_alert", true)

    // Notification tricks
    val hapticsOn = BoolPref("haptics_on", true)
    val whisperOn = BoolPref("whisper_on", false)
    val whisperHeadphonesOnly = BoolPref("whisper_headphones_only", true)

    // App profiles (Sound Assistant, Display Assistant)
    val profilesOn = BoolPref("profiles_on", true)
    val rememberVolume = BoolPref("remember_volume", true)
    val profilesJson = StringPref("profiles_json", "{}")

    // Controls
    val caffeineUntil = LongPref("caffeine_until", 0L) // 0 off, -1 indefinite, else epoch ms
    val torchLevel = IntPref("torch_level", 0)

    // Wallpaper
    val skyTheme = StringPref("sky_theme", "LIVING")
    val skyParallax = BoolPref("sky_parallax", true)
    val skySpeed = IntPref("sky_speed", 100)
    val skyStars = BoolPref("sky_stars", true)

    // Updates
    val updateChannel = StringPref("update_channel", "stable")
    val autoUpdate = BoolPref("auto_update", true)
    val updateWifiOnly = BoolPref("update_wifi_only", true)
    val updateIntervalHours = IntPref("update_interval_h", 6)
    val updateLastCheck = LongPref("update_last_check", 0L)
    val updateStatus = StringPref("update_status", "")
    val lastSeenVersion = StringPref("last_seen_version", "")
}
