package io.github.nugggy.pluslock.service

import android.media.AudioManager
import android.os.SystemClock
import android.view.KeyEvent
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.P

/**
 * Hold volume up for the next track and volume down for the previous one, while music plays.
 * A short press still changes the volume. Android stops sending keys to accessibility
 * services when the screen is off, so this works with the screen on only.
 */
class VolumeSkip(private val svc: PlusAccessibilityService) {
    private val am = svc.getSystemService(AudioManager::class.java)
    private var held = 0
    private var fired = false
    private val longPress = Runnable {
        fired = true
        Haptics.tick(svc)
        media(if (held == KeyEvent.KEYCODE_VOLUME_UP) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS)
    }

    fun onKey(e: KeyEvent): Boolean {
        if (!P.volumeSkip.value) return false
        val code = e.keyCode
        if (code != KeyEvent.KEYCODE_VOLUME_UP && code != KeyEvent.KEYCODE_VOLUME_DOWN) return false
        // Only ever handle a press from its own DOWN, so the system keeps everything else.
        if (held != 0 && code != held) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (held == 0) {
                    if (e.repeatCount != 0 || !am.isMusicActive) return false
                    held = code
                    fired = false
                    svc.handler.postDelayed(longPress, 550)
                }
            }
            KeyEvent.ACTION_UP -> {
                if (held == 0) return false
                svc.handler.removeCallbacks(longPress)
                if (!fired) {
                    am.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC,
                        if (code == KeyEvent.KEYCODE_VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                        AudioManager.FLAG_SHOW_UI,
                    )
                }
                held = 0
            }
        }
        return true
    }

    private fun media(code: Int) {
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }
}
