package io.github.nugggy.pluslock.service

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.P

/**
 * Handles the volume buttons for two features:
 * hold to skip tracks while music plays, and Pimp my Phone's own volume panel.
 * Android stops sending keys to accessibility services when the screen is off,
 * so both work with the screen on only. Calls are always left to the system.
 */
class VolumeSkip(private val svc: PlusAccessibilityService, private val panel: VolumePanel) {
    private val am = svc.getSystemService(AudioManager::class.java)
    private var held = 0
    private var fired = false
    private var waitingForLong = false
    private var repeats = 0

    /**
     * Holding a button keeps moving the volume. Android doesn't send repeats for a key an
     * accessibility service has taken, so this times them itself.
     */
    private val repeat = object : Runnable {
        override fun run() {
            // Stops by itself in case the button's release never arrives.
            if (held == 0 || waitingForLong || ++repeats > 60) return
            step(held)
            svc.handler.postDelayed(this, 80)
        }
    }

    private val longPress = Runnable {
        fired = true
        Haptics.tick(svc)
        media(if (held == KeyEvent.KEYCODE_VOLUME_UP) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    val wanted get() = P.volumeSkip.value || P.volPanelOn.value

    fun onKey(e: KeyEvent): Boolean {
        if (!wanted) return false
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        // Only ever handle a press from its own DOWN, so the system keeps everything else.
        if (held != 0 && code != held) return false
        val inCall = am.mode != AudioManager.MODE_NORMAL
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (held == 0) {
                    if (e.repeatCount != 0 || inCall || systemShouldHandle()) {
                        io.github.nugggy.pluslock.core.Diag.log("volume keys", "left to the system" + if (inCall) " (call)" else "")
                        return false
                    }
                    val skip = P.volumeSkip.value && am.isMusicActive
                    if (!skip && !P.volPanelOn.value) return false
                    held = code
                    fired = false
                    waitingForLong = skip
                    if (skip) svc.handler.postDelayed(longPress, 550)
                    else {
                        step(code)
                        repeats = 0
                        svc.handler.postDelayed(repeat, 350)
                    }
                }
                // Any repeats Android does send are ignored; the timer above does the holding.
            }
            KeyEvent.ACTION_UP -> {
                if (held == 0) return false
                svc.handler.removeCallbacks(longPress)
                svc.handler.removeCallbacks(repeat)
                if (waitingForLong && !fired) step(code)
                held = 0
                waitingForLong = false
            }
        }
        return true
    }

    /**
     * Times the buttons mean something else: the camera shutter, snoozing an alarm (shown over
     * the lock screen), or casting, where the buttons control the TV or speaker.
     */
    private fun systemShouldHandle(): Boolean {
        val fg = svc.foregroundPkg.lowercase()
        if ("camera" in fg || "clock" in fg || "alarm" in fg) return true
        if (svc.getSystemService(android.app.KeyguardManager::class.java).isKeyguardLocked) return true
        val remote = IslandState.media?.controller?.playbackInfo?.playbackType == android.media.session.MediaController.PlaybackInfo.PLAYBACK_TYPE_REMOTE
        return remote
    }

    private fun step(code: Int) {
        val dir = if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
        if (P.volPanelOn.value) {
            panel.adjust(dir)
        } else {
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        }
    }

    private fun media(code: Int) {
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
