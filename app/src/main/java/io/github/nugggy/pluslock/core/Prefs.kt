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
    val lightingSpeed = IntPref("lighting_speed_pct", 100)
    val lightingSoftness = IntPref("lighting_softness_pct", 100)
    val lightingOpacity = IntPref("lighting_opacity_pct", 100)
    val lightingColour2 = IntPref("lighting_colour2", 0xFFFF6BE0.toInt())
    val lightingComets = IntPref("lighting_comets", 2)
    val lightingTail = IntPref("lighting_tail_pct", 18)
    val lightingWake = BoolPref("lighting_wake", false)
    val lightingWakePocket = BoolPref("lighting_wake_pocket", true)
    val lightingWakeVip = BoolPref("lighting_wake_vip_only", false)
    val lightingAutoPreview = BoolPref("lighting_auto_preview", true)

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
    val ringShowPct = BoolPref("ring_pct", false)
    val ringStyle = StringPref("ring_style", "SOLID") // SOLID, GRADIENT, RAINBOW, SEGMENTS, DOTS
    val ringColour2 = IntPref("ring_colour2", 0xFFFF6BE0.toInt())
    val ringGlow = IntPref("ring_glow_pct", 0)
    val ringOpacity = IntPref("ring_opacity_pct", 100)
    val ringChargeAnim = StringPref("ring_charge_anim", "DOT") // DOT, COMET, PULSE, SPIN, FILL, NONE
    val ringChargeColour = IntPref("ring_charge_colour", 0xFF4BE38A.toInt())
    val ringClockwise = BoolPref("ring_clockwise", true)
    val ringStart = IntPref("ring_start_deg", -90)
    val ringLowLevel = IntPref("ring_low_level", 15)
    val ringWarnLevel = IntPref("ring_warn_level", 30)
    val ringLowPulse = BoolPref("ring_low_pulse", true)
    val ringHideFull = BoolPref("ring_hide_full", false)
    val ringWhere = StringPref("ring_where", "ALL") // ALL, LOCK, UNLOCKED
    val ringNotifFlash = BoolPref("ring_notif_flash", false)

    // Charging effects
    val chargeGlowStay = BoolPref("chargefx_glow_stay", false)
    val chargeGlowWhere = StringPref("chargefx_glow_where", "ALL") // ALL, LOCK
    val chargeGlowEffect = StringPref("chargefx_glow_effect", "PULSE")
    val chargeGlowShape = StringPref("chargefx_glow_shape", "EDGE")
    val chargeGlowThickness = IntPref("chargefx_glow_thickness_dp", 6)
    val chargeGlowLevel = BoolPref("chargefx_glow_level", true)
    val chargeGlowSpeed = IntPref("chargefx_glow_speed_pct", 100)
    val chargeGlowOpacity = IntPref("chargefx_glow_opacity_pct", 100)
    val chargeColourMode = StringPref("chargefx_colour_mode", "LEVEL") // LEVEL, CUSTOM, DUO (several blended), CYCLE (several in turn), RAINBOW
    val chargeColour = IntPref("chargefx_colour", 0xFF4BE38A.toInt())
    val chargeAnimOn = BoolPref("chargefx_anim_on", false)
    val chargeAnimStyle = StringPref("chargefx_anim_style", "BUBBLE") // BUBBLE, RING, BATTERY, ORB
    val chargeAnimWhen = StringPref("chargefx_anim_when", "PLUG") // PLUG, WAKE
    val chargeAnimSeconds = IntPref("chargefx_anim_seconds", 6) // 0 until tapped
    val chargeAnimLockOnly = BoolPref("chargefx_anim_lock_only", true)
    val chargeAnimDim = IntPref("chargefx_anim_dim_pct", 85)
    val chargeAnimSize = IntPref("chargefx_anim_size_pct", 100)
    val chargeAnimEta = BoolPref("chargefx_anim_eta", true)
    val chargeAnimWakeOnPlug = BoolPref("chargefx_anim_wake", false)
    val chargeColour2 = IntPref("chargefx_colour2", 0xFF5EE4FF.toInt())
    /** Comma-separated colours for the "several colours" modes. Empty means the first and second colour. */
    val chargeColours = StringPref("chargefx_colours", "")
    val chargeColourLow = IntPref("chargefx_colour_low", 0xFFFF5252.toInt())
    val chargeColourMid = IntPref("chargefx_colour_mid", 0xFFFFD84D.toInt())
    val chargeColourHigh = IntPref("chargefx_colour_high", 0xFF4BE38A.toInt())
    val chargeTextColour = IntPref("chargefx_text_colour", 0xFFFFFFFF.toInt())
    val chargeSparkColour = IntPref("chargefx_spark_colour", 0xFFFFFFFF.toInt())
    val chargeBoltColour = IntPref("chargefx_bolt_colour", 0xFFFFD84D.toInt())
    val chargeBgColour = IntPref("chargefx_bg_colour", 0xFF000000.toInt())
    val chargeAnimPosition = StringPref("chargefx_anim_position", "CENTRE") // TOP, CENTRE, BOTTOM
    val chargeAnimPct = BoolPref("chargefx_anim_pct", true)
    val chargeAnimDecimals = BoolPref("chargefx_anim_decimals", false)
    val chargeAnimWatts = BoolPref("chargefx_anim_watts", true)
    val chargeAnimTemp = BoolPref("chargefx_anim_temp", false)
    val chargeAnimClock = BoolPref("chargefx_anim_clock", false)
    val chargeAnimBolt = BoolPref("chargefx_anim_bolt", true)
    val chargeAnimParticles = IntPref("chargefx_anim_particles_pct", 100)
    val chargeAnimSpeed = IntPref("chargefx_anim_speed_pct", 100)
    val chargeAnimGlow = IntPref("chargefx_anim_glow_pct", 100)
    val chargeAnimFont = StringPref("chargefx_anim_font", "SANS") // SANS, THIN, BOLD, MONO, SERIF, CONDENSED
    val chargePlugHaptic = StringPref("chargefx_plug_haptic", "NONE")
    val chargeUnplugGlow = BoolPref("chargefx_unplug_glow", false)
    val chargeFullAlert = BoolPref("chargefx_full_alert", false)

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
    val keywordsGlow = BoolPref("keywords_glow", true)
    val keywordsWake = BoolPref("keywords_wake", true)
    val keywordsColour = IntPref("keywords_colour", 0xFFFF5E5E.toInt())
    val keywordsEffect = StringPref("keywords_effect", "PULSE")
    val keywordsDuration = IntPref("keywords_duration_s", 15)
    val keywordsHaptic = StringPref("keywords_haptic", "SOS")
    val keywordsTorch = BoolPref("keywords_torch", false)
    val keywordsSound = BoolPref("keywords_sound", false)
    val keywordsPartial = BoolPref("keywords_partial", false)

    // Find my phone: a secret codeword in any message sets it ringing
    val findOn = BoolPref("find_on", false)
    val findCode = StringPref("find_code", "")
    val findSenders = StringPref("find_senders", "") // comma separated names, blank for anyone
    val findMinutes = IntPref("find_minutes", 2)
    val findSound = BoolPref("find_sound", true)
    val findLoud = BoolPref("find_loud", true)
    val findVibrate = BoolPref("find_vibrate", true)
    val findTorch = BoolPref("find_torch", true)
    val findScreen = BoolPref("find_screen", true)
    val findFlash = BoolPref("find_flash", true)
    val findMessage = StringPref("find_message", "")

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
    val panelToggles = StringPref("panel_toggles", "TORCH,CAFFEINE,SOUND,PAUSE,SCREENSHOT,LOCK")
    val panelSections = StringPref("panel_sections", "TOGGLES,APPS,PEOPLE,NOTE,CALC,RECENT")
    val panelNote = StringPref("panel_note", "")
    val panelStyle = StringPref("panel_style", "DARK") // DARK, BLACK, GLASS
    val panelWidth = IntPref("panel_width_pct", 78)
    val panelColour = IntPref("panel_colour", 0xFF7FB2FF.toInt())

    // Camera island
    val islandOn = BoolPref("island_on", false)
    val islandMedia = BoolPref("island_media", true)
    val islandCalls = BoolPref("island_calls", true)
    val islandTimers = BoolPref("island_timers", true)
    val islandCharging = BoolPref("island_charging", true)
    val islandPeek = BoolPref("island_peek", true)
    val islandPeekSeconds = IntPref("island_peek_seconds", 4)
    val islandProgress = BoolPref("island_progress", true)
    val islandNav = BoolPref("island_nav", true)
    val islandGlow = BoolPref("island_glow", true)
    val islandGestures = BoolPref("island_gestures", true)
    val islandSize = IntPref("island_size_pct", 100)
    val islandCollapseSeconds = IntPref("island_collapse_seconds", 6)

    // Volume panel
    val volPanelOn = BoolPref("volpanel_on", false)
    val volPanelSide = StringPref("volpanel_side", "RIGHT")
    val volPanelStreams = StringPref("volpanel_streams2", "MUSIC,RING,NOTIFICATION,ALARM,CALL,SYSTEM") // MUSIC, RING, NOTIFICATION, ALARM, CALL, SYSTEM
    val volPanelExpanded = BoolPref("volpanel_expanded", false)
    val volPanelButtons = StringPref("volpanel_buttons", "AUTO") // AUTO, MUSIC, RING
    val volPanelStyle = StringPref("volpanel_style", "DARK") // DARK, BLACK, GLASS, LIGHT, TINT
    val volPanelColour = IntPref("volpanel_colour", 0xFF7FB2FF.toInt())
    val volPanelRainbow = BoolPref("volpanel_rainbow", false)
    val volPanelOpacity = IntPref("volpanel_opacity_pct", 94)
    val volPanelWidth = IntPref("volpanel_width_dp", 44)
    val volPanelHeight = IntPref("volpanel_height_dp", 190)
    val volPanelCorner = IntPref("volpanel_corner_dp", 30)
    val volPanelOffset = IntPref("volpanel_offset_pct", -10)
    val volPanelMargin = IntPref("volpanel_margin_dp", 12)
    val volPanelTimeout = IntPref("volpanel_timeout_s", 3)
    val volPanelNumbers = StringPref("volpanel_numbers", "PERCENT") // PERCENT, STEPS, NONE
    val volPanelLabels = BoolPref("volpanel_labels", true)
    val volPanelModeChip = BoolPref("volpanel_mode_chip", true)
    val volPanelTapMute = BoolPref("volpanel_tap_mute", true)
    val volPanelHaptic = BoolPref("volpanel_haptic", true)
    val volPanelLayout = StringPref("volpanel_layout", "VERTICAL") // VERTICAL, HORIZONTAL

    // Volume boost
    val boostOn = BoolPref("boost_on", false)
    val boostDb = IntPref("boost_db", 6)
    val boostSpeakerOnly = BoolPref("boost_speaker_only", true)
    val appVolumesJson = StringPref("app_volumes_json", "{}")
    val volPanelApps = BoolPref("volpanel_apps", true)

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
    val lockClockColourMode = StringPref("lockclock_colour_mode", "CUSTOM") // CUSTOM, WALLPAPER, GRADIENT, RAINBOW
    val lockClockColour2 = IntPref("lockclock_colour2", 0xFFFF6BE0.toInt())
    val lockClockAlign = StringPref("lockclock_align", "CENTRE") // LEFT, CENTRE, RIGHT
    val lockClockSeconds = BoolPref("lockclock_seconds", false)
    val lockClockDateFormat = StringPref("lockclock_date_format", "FULL") // FULL, SHORT, NUMERIC
    val lockClockGlow = BoolPref("lockclock_glow", false)
    val lockClockOpacity = IntPref("lockclock_opacity_pct", 100)
    val lockClockBattery = BoolPref("lockclock_battery", false)
    val lockClockAlarm = BoolPref("lockclock_alarm", false)
    val lockClockWeather = BoolPref("lockclock_weather", false)
    val lockClockMusic = BoolPref("lockclock_music", false)
    val lockClockUnread = BoolPref("lockclock_unread", false)

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

    // Interactive wallpapers
    val playTheme = StringPref("play_theme", "SWARM")
    val playColourMode = StringPref("play_colour_mode", "THEME") // THEME, MINE, CUSTOM, RAINBOW
    /** Each wallpaper's own mix of colours, as {"FIREFLIES": "FFFFF35E,FFA6FF4D"}. */
    val playColoursJson = StringPref("play_colours_json", "{}")
    val playColour = IntPref("play_colour", 0xFF7FB2FF.toInt())
    val playDensity = IntPref("play_density_pct", 100)
    val playSpeed = IntPref("play_speed_pct", 100)
    val playTilt = BoolPref("play_tilt", true)
    val playBlack = BoolPref("play_black", true)
    val playSmooth = BoolPref("play_smooth", false)

    // Pause everything, to rule the app in or out when the phone misbehaves
    val allPaused = BoolPref("all_paused", false)

    // Updates
    val updateChannel = StringPref("update_channel", "stable")
    val autoUpdate = BoolPref("auto_update", true)
    val updateWifiOnly = BoolPref("update_wifi_only", true)
    val updateIntervalHours = IntPref("update_interval_h", 6)
    val updateLastCheck = LongPref("update_last_check", 0L)
    val updateStatus = StringPref("update_status", "")
    val lastSeenVersion = StringPref("last_seen_version", "")

    // The version of the terms and privacy policy you last agreed to
    val legalAccepted = IntPref("legal_accepted", 0)
}
