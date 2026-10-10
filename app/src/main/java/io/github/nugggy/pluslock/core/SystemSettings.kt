package io.github.nugggy.pluslock.core

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import java.util.Calendar

/**
 * Settings that Android keeps behind WRITE_SECURE_SETTINGS. There is no on-phone
 * screen to grant it, so it takes one ADB command from a computer, once.
 */
object SystemSettings {
    fun hasSecure(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun adbCommand(ctx: Context) =
        "adb shell pm grant ${ctx.packageName} android.permission.WRITE_SECURE_SETTINGS"

    // ---- font size ----

    fun fontScale(ctx: Context): Float =
        Settings.System.getFloat(ctx.contentResolver, Settings.System.FONT_SCALE, 1f)

    /** Font scale needs Modify system settings on most builds, and the ADB grant on stricter ones. */
    fun setFontScale(ctx: Context, scale: Float): Boolean =
        runCatching { Settings.System.putFloat(ctx.contentResolver, Settings.System.FONT_SCALE, scale) }.getOrDefault(false)

    // ---- keyboard ----

    data class Keyboard(val id: String, val label: String)

    fun keyboards(ctx: Context): List<Keyboard> {
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        return imm.enabledInputMethodList.map { Keyboard(it.id, it.loadLabel(ctx.packageManager).toString()) }
    }

    fun currentKeyboard(ctx: Context): String =
        Settings.Secure.getString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()

    fun setKeyboard(ctx: Context, id: String): Boolean {
        if (!hasSecure(ctx) || id.isBlank()) return false
        return runCatching { Settings.Secure.putString(ctx.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD, id) }
            .getOrDefault(false)
    }

    // ---- status bar ----

    /** AOSP status bar icon slots. OxygenOS honours most of them, but not always all. */
    val iconSlots = linkedMapOf(
        "alarm_clock" to "Alarm",
        "bluetooth" to "Bluetooth",
        "headset" to "Headphones",
        "volume" to "Vibrate and silent",
        "zen" to "Do Not Disturb",
        "nfc" to "NFC",
        "location" to "Location",
        "hotspot" to "Hotspot",
        "vpn" to "VPN",
        "cast" to "Cast",
        "rotate" to "Rotation",
        "airplane" to "Aeroplane mode",
        "wifi" to "Wi-Fi",
        "mobile" to "Mobile signal",
    )

    private const val ICON_BLACKLIST = "icon_blacklist"
    private const val CLOCK_SECONDS = "clock_seconds"

    fun hiddenIcons(ctx: Context): Set<String> =
        Settings.Secure.getString(ctx.contentResolver, ICON_BLACKLIST).orEmpty()
            .split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun setIconHidden(ctx: Context, slot: String, hidden: Boolean): Boolean {
        if (!hasSecure(ctx)) return false
        val now = hiddenIcons(ctx).toMutableSet()
        if (hidden) now += slot else now -= slot
        return runCatching { Settings.Secure.putString(ctx.contentResolver, ICON_BLACKLIST, now.joinToString(",")) }
            .getOrDefault(false)
    }

    fun clockSeconds(ctx: Context): Boolean =
        Settings.Secure.getInt(ctx.contentResolver, CLOCK_SECONDS, 0) == 1

    fun setClockSeconds(ctx: Context, on: Boolean): Boolean {
        if (!hasSecure(ctx)) return false
        return runCatching { Settings.Secure.putInt(ctx.contentResolver, CLOCK_SECONDS, if (on) 1 else 0) }.getOrDefault(false)
    }

    // ---- one-handed mode ----

    /** Asks SystemUI to drop the screen down. Works where OxygenOS uses the AOSP one-handed mode. */
    fun oneHanded(ctx: Context): Boolean {
        if (!hasSecure(ctx)) return false
        val cr = ctx.contentResolver
        return runCatching {
            Settings.Secure.putInt(cr, "one_handed_mode_enabled", 1)
            Settings.Secure.putInt(cr, "one_handed_mode_activated", 1)
        }.getOrDefault(false)
    }
}

object Hours {
    /** True when the current time is inside [start, end), wrapping past midnight. */
    fun inWindow(start: Int, end: Int, cal: Calendar = Calendar.getInstance()): Boolean {
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return if (start == end) false else if (start < end) h in start until end else h >= start || h < end
    }

    fun isWeekday(cal: Calendar = Calendar.getInstance()): Boolean =
        cal.get(Calendar.DAY_OF_WEEK) !in setOf(Calendar.SATURDAY, Calendar.SUNDAY)

    /** Milliseconds until the clock next reads this hour, on the hour. */
    fun millisUntilHour(hour: Int, minute: Int = 0): Long {
        val now = Calendar.getInstance()
        val t = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        if (!t.after(now)) t.add(Calendar.DAY_OF_YEAR, 1)
        return t.timeInMillis - now.timeInMillis
    }

    fun label(h: Int): String = "%02d:00".format(h)
}
