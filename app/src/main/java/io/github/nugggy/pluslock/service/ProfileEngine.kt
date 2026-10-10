package io.github.nugggy.pluslock.service

import android.app.NotificationManager
import android.media.AudioManager
import android.provider.Settings
import android.view.Surface
import io.github.nugggy.pluslock.core.IntPref
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.StringPref
import io.github.nugggy.pluslock.core.SystemSettings
import io.github.nugggy.pluslock.data.AppProfile
import io.github.nugggy.pluslock.data.Profiles
import kotlin.math.roundToInt

/**
 * Applies per-app profiles (Sound Assistant and Display Assistant style):
 * media volume, stay awake, rotation, Do Not Disturb, text size, keyboard,
 * and switching off gestures. Whatever it changes is put back when you leave the app.
 */
class ProfileEngine(private val svc: PlusAccessibilityService) {
    private val am = svc.getSystemService(AudioManager::class.java)
    private var current: AppProfile? = null
    private var currentPkg = ""
    // What to put back, kept in prefs so a crash or update can't strand a setting.
    private val rv = IntPref("restore_volume", -1)
    private val rr1 = IntPref("restore_rot_auto", -1)
    private val rr2 = IntPref("restore_rot_user", -1)
    private val rd = IntPref("restore_dnd", -1)
    private val rf = IntPref("restore_font_milli", -1)
    private val rk = StringPref("restore_keyboard", "\u0000")
    private var restoreVolume: Int
        get() = rv.value
        set(v) { rv.value = v }
    private var restoreRotation: Pair<Int, Int>?
        get() = if (rr1.value < 0) null else rr1.value to rr2.value
        set(v) { rr1.value = v?.first ?: -1; rr2.value = v?.second ?: -1 }
    private val nm = svc.getSystemService(NotificationManager::class.java)
    private var restoreFilter: Int
        get() = rd.value
        set(v) { rd.value = v }
    private var restoreFont: Float
        get() = if (rf.value < 0) -1f else rf.value / 1000f
        set(v) { rf.value = if (v < 0) -1 else (v * 1000).toInt() }
    private var restoreKeyboard: String?
        get() = rk.value.takeIf { it != "\u0000" }
        set(v) { rk.value = v ?: "\u0000" }

    fun onForeground(pkg: String) {
        val prev = current
        val next = if (P.profilesOn.value) Profiles.get(pkg) else null
        if (prev != null && prev.volume >= 0 && P.rememberVolume.value) {
            // Volume memory: keep whatever level the user settled on in that app.
            val pct = currentVolumePct()
            if (pct != prev.volume) Profiles.get(prev.pkg)?.let { Profiles.save(it.copy(volume = pct)) }
        }
        currentPkg = pkg
        current = next
        apply(next)
        svc.focus.onForeground(pkg, next)
        svc.guard.onForeground(pkg, next)
    }

    /** Settings changed while an app is open. */
    fun reapply() {
        val next = if (P.profilesOn.value) Profiles.get(currentPkg) else null
        val volumeChanged = next?.volume != current?.volume
        current = next
        apply(next, setVolume = volumeChanged)
        svc.guard.reapply(next)
    }

    private fun apply(p: AppProfile?, setVolume: Boolean = true) {
        // Volume
        if (p != null && p.volume >= 0) {
            if (restoreVolume < 0) restoreVolume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (setVolume) setVolumePct(p.volume)
        } else if (restoreVolume >= 0) {
            runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0) }
            restoreVolume = -1
        }

        svc.keepAwake.perApp = p?.keepAwake == true
        svc.gestures.suppressed = p?.noGestures == true
        svc.statusTapSuppressed = p?.noGestures == true

        // Rotation needs "Modify system settings".
        if (Settings.System.canWrite(svc)) {
            val cr = svc.contentResolver
            if (p != null && p.rotation > 0) {
                if (restoreRotation == null) {
                    restoreRotation = Settings.System.getInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1) to
                        Settings.System.getInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0)
                }
                runCatching {
                    when (p.rotation) {
                        1 -> Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 1)
                        2 -> {
                            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                            Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_0)
                        }
                        3 -> {
                            Settings.System.putInt(cr, Settings.System.ACCELEROMETER_ROTATION, 0)
                            Settings.System.putInt(cr, Settings.System.USER_ROTATION, Surface.ROTATION_90)
                        }
                    }
                }
            } else restoreRotationNow()
        }

        // Do Not Disturb (priority only) while the app is open.
        if (p?.dnd == true && nm.isNotificationPolicyAccessGranted) {
            if (restoreFilter < 0) restoreFilter = nm.currentInterruptionFilter
            runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
        } else restoreDndNow()

        // Text size.
        if (p != null && p.fontScale > 0) {
            if (restoreFont < 0) restoreFont = SystemSettings.fontScale(svc)
            val want = p.fontScale / 100f
            if (SystemSettings.fontScale(svc) != want) SystemSettings.setFontScale(svc, want)
        } else restoreFontNow()

        // Keyboard.
        if (p != null && p.keyboard.isNotEmpty() && SystemSettings.hasSecure(svc)) {
            if (restoreKeyboard == null) restoreKeyboard = SystemSettings.currentKeyboard(svc)
            if (SystemSettings.currentKeyboard(svc) != p.keyboard) SystemSettings.setKeyboard(svc, p.keyboard)
        } else restoreKeyboardNow()
    }

    private fun restoreDndNow() {
        if (restoreFilter < 0) return
        // Leave it alone if the user changed Do Not Disturb themselves meanwhile.
        if (nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            runCatching { nm.setInterruptionFilter(restoreFilter) }
        }
        restoreFilter = -1
    }

    private fun restoreFontNow() {
        if (restoreFont < 0) return
        SystemSettings.setFontScale(svc, restoreFont)
        restoreFont = -1f
    }

    private fun restoreKeyboardNow() {
        val k = restoreKeyboard ?: return
        SystemSettings.setKeyboard(svc, k)
        restoreKeyboard = null
    }

    private fun restoreRotationNow() {
        val r = restoreRotation ?: return
        runCatching {
            Settings.System.putInt(svc.contentResolver, Settings.System.ACCELEROMETER_ROTATION, r.first)
            Settings.System.putInt(svc.contentResolver, Settings.System.USER_ROTATION, r.second)
        }
        restoreRotation = null
    }

    fun restoreAll() {
        if (restoreVolume >= 0) runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVolume, 0) }
        restoreVolume = -1
        restoreRotationNow()
        restoreDndNow()
        restoreFontNow()
        restoreKeyboardNow()
    }

    private fun currentVolumePct(): Int {
        val min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val v = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        return if (max <= min) 0 else ((v - min) * 100f / (max - min)).roundToInt()
    }

    private fun setVolumePct(pct: Int) {
        val min = am.getStreamMinVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val idx = (min + (max - min) * pct / 100f).roundToInt()
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0) }
    }
}
