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
    val lightingPillSize = IntPref("lighting_pill_size_pct", 130)
    val lightingRespectDnd = BoolPref("lighting_dnd", true)
    val lightingLandscapeOff = BoolPref("lighting_landscape_off", false)
    val lightingSilenceUntil = LongPref("lighting_silence_until", 0L) // 0 off, -1 until resumed, else epoch ms
    val lightingSilenceApp = StringPref("lighting_silence_app", "")
    val chargingGlow = BoolPref("charging_glow", true)
    val lightingShape = StringPref("lighting_shape", "EDGE") // EDGE, TOP, CAMERA
    val lightingQuietOn = BoolPref("lighting_quiet_on", false)
    val lightingQuietStart = IntPref("lighting_quiet_start", 22) // hour of day
    val lightingQuietEnd = IntPref("lighting_quiet_end", 7)
    val lightingReminder = IntPref("lighting_reminder_min", 0) // 0 off
    val lightingRulesJson = StringPref("lighting_rules_json", "[]")

    // Notification bubble
    val bubbleLines = IntPref("bubble_lines", 1)
    val bubblePosition = StringPref("bubble_position", "TOP") // TOP, BOTTOM
    val bubbleStyle = StringPref("bubble_style", "DARK") // DARK, LIGHT, GLASS, APP
    val bubbleTapToOpen = BoolPref("bubble_tap_open", true)
    val bubbleReply = BoolPref("bubble_reply", true)
    val bubbleGroup = BoolPref("bubble_group", true)

    // Hold notifications from chosen apps during set hours
    val holdOn = BoolPref("hold_on", false)
    val holdApps = StringPref("hold_apps", "") // comma separated packages
    val holdStart = IntPref("hold_start", 9)
    val holdEnd = IntPref("hold_end", 17)
    val holdWeekdaysOnly = BoolPref("hold_weekdays", true)

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
    val ringShowEta = BoolPref("ring_eta", true)

    // Charge guard
    val chargeAlertOn = BoolPref("charge_alert_on", false)
    val chargeAlertLevel = IntPref("charge_alert_level", 80)
    val bedtimeOn = BoolPref("bedtime_on", false)
    val bedtimeHour = IntPref("bedtime_hour", 22)
    val bedtimeMinute = IntPref("bedtime_minute", 0)
    val bedtimeBelow = IntPref("bedtime_below", 50)

    // Notification vault (NotiStar)
    val vaultOn = BoolPref("vault_on", true)
    val vaultRetentionDays = IntPref("vault_retention_days", 30)
    val unsentAlert = BoolPref("unsent_alert", true)
    val keywordsOn = BoolPref("keywords_on", false)
    val keywords = StringPref("keywords", "urgent, emergency")
    val keywordsBreakDnd = BoolPref("keywords_dnd", true)

    // Motion gestures
    val shakeTorch = BoolPref("motion_shake_torch", false)
    val shakeSensitivity = IntPref("motion_shake_sens", 50)
    val backTapOn = BoolPref("motion_backtap_on", false)
    val backTapAction = StringPref("motion_backtap_action", GestureAction.SCREENSHOT.name)
    val backTapSensitivity = IntPref("motion_backtap_sens", 50)
    val statusTapLock = BoolPref("statustap_on", false)
    val volumeSkip = BoolPref("volskip_on", false)

    val flipAction = StringPref("motion_flip", "OFF") // OFF, VIBRATE, SILENT, DND

    // Routines
    val routinesOn = BoolPref("routines_on", true)
    val routinesJson = StringPref("routines_json", "[]")

    // Side panel
    val panelApps = StringPref("panel_apps", "") // comma separated packages
    val panelContacts = StringPref("panel_contacts", "[]")
    val panelSide = StringPref("panel_side", "RIGHT")

    // Camera island
    val islandOn = BoolPref("island_on", false)
    val islandMedia = BoolPref("island_media", true)
    val islandCalls = BoolPref("island_calls", true)
    val islandTimers = BoolPref("island_timers", true)
    val islandCharging = BoolPref("island_charging", true)

    // Volume panel
    val volPanelOn = BoolPref("volpanel_on", false)
    val volPanelSide = StringPref("volpanel_side", "RIGHT")

    // Morning digest
    val digestOn = BoolPref("digest_on", false)
    val digestHour = IntPref("digest_hour", 7)
    val digestMinute = IntPref("digest_minute", 30)
    val digestLast = LongPref("digest_last", 0L)

    // Extra dim and colour filter
    val dimOn = BoolPref("dim_on", false)
    val dimLevel = IntPref("dim_level", 30)
    val dimWarmth = IntPref("dim_warmth", 0)
    val dimScheduled = BoolPref("dim_scheduled", false)
    val dimStart = IntPref("dim_start", 22)
    val dimEnd = IntPref("dim_end", 6)

    // Look and feel
    val netSpeedOn = BoolPref("netspeed_on", false)
    val netSpeedSide = StringPref("netspeed_side", "LEFT")
    val netSpeedHideLandscape = BoolPref("netspeed_hide_landscape", true)
    val lockClockOn = BoolPref("lockclock_on", false)
    val lockClockStyle = StringPref("lockclock_style", "BOLD")
    val lockClockFont = StringPref("lockclock_font", "SANS")
    val lockClockColour = IntPref("lockclock_colour", 0xFFFFFFFF.toInt())
    val lockClockPosition = IntPref("lockclock_position", 22)
    val lockClockSize = IntPref("lockclock_size", 100)
    val lockClockDate = BoolPref("lockclock_date", true)

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
    val skyWeather = BoolPref("sky_weather", false)
    val skyWeatherJson = StringPref("sky_weather_json", "")

    // Updates
    val updateChannel = StringPref("update_channel", "stable")
    val autoUpdate = BoolPref("auto_update", true)
    val updateWifiOnly = BoolPref("update_wifi_only", true)
    val updateIntervalHours = IntPref("update_interval_h", 6)
    val updateLastCheck = LongPref("update_last_check", 0L)
    val updateStatus = StringPref("update_status", "")
    val lastSeenVersion = StringPref("last_seen_version", "")
}
