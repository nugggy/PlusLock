package io.github.nugggy.pluslock.service

import android.media.AudioManager
import android.provider.Settings
import android.view.Surface
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.data.AppProfile
import io.github.nugggy.pluslock.data.Profiles
import kotlin.math.roundToInt

/**
 * Applies per-app profiles (Sound Assistant and Display Assistant style):
 * media volume, stay awake, rotation, and switching off gestures.
 * Whatever it changes is put back when you leave the app.
 */
class ProfileEngine(private val svc: PlusAccessibilityService) {
    private val am = svc.getSystemService(AudioManager::class.java)
    private var current: AppProfile? = null
    private var currentPkg = ""
    private var restoreVolume = -1
    private var restoreRotation: Pair<Int, Int>? = null

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
    }

    /** Settings changed while an app is open. */
    fun reapply() {
        val next = if (P.profilesOn.value) Profiles.get(currentPkg) else null
        val volumeChanged = next?.volume != current?.volume
        current = next
        apply(next, setVolume = volumeChanged)
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
