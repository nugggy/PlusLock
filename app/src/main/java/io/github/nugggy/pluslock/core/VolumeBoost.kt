package io.github.nugggy.pluslock.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.os.Handler
import android.os.Looper

/**
 * Volume boost: pushes sound past the phone's normal maximum with Android's loudness enhancer.
 * It's applied to the phone's whole output, and to each music or video app that announces its
 * audio session (as they do for equaliser apps), because some phones ignore the global one.
 */
object VolumeBoost {
    private val effects = HashMap<Int, LoudnessEnhancer>()
    private val sessions = HashSet<Int>()
    private var headphones = false
    private var registered = false
    private lateinit var app: Context

    /** True if the last attempt to switch it on worked for the whole phone. */
    var globalWorks: Boolean? = null
        private set

    private val headphoneTypes = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_HEARING_AID,
    )

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (!registered) {
            registered = true
            val am = app.getSystemService(AudioManager::class.java)
            am.registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = check(am)
                override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = check(am)
            }, Handler(Looper.getMainLooper()))
            check(am)
        }
        apply()
    }

    private fun check(am: AudioManager) {
        val now = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headphoneTypes }
        if (now != headphones) { headphones = now; apply() }
    }

    /** The gain to use right now, in millibels. */
    private fun gainMb(): Int {
        if (!P.boostOn.value) return 0
        if (P.boostSpeakerOnly.value && headphones) return 0
        return P.boostDb.value.coerceIn(0, 15) * 100
    }

    fun apply() {
        if (!::app.isInitialized) return
        val gain = gainMb()
        if (gain <= 0) { releaseAll(); return }
        globalWorks = attach(0, gain)
        sessions.forEach { attach(it, gain) }
    }

    private fun attach(session: Int, gain: Int): Boolean = runCatching {
        val e = effects.getOrPut(session) { LoudnessEnhancer(session) }
        e.setTargetGain(gain)
        e.enabled = true
        true
    }.getOrElse {
        effects.remove(session)?.let { e -> runCatching { e.release() } }
        false
    }

    fun openSession(id: Int) {
        if (id <= 0) return
        sessions += id
        val gain = gainMb()
        if (gain > 0) attach(id, gain)
    }

    fun closeSession(id: Int) {
        sessions -= id
        effects.remove(id)?.let { runCatching { it.release() } }
    }

    fun releaseAll() {
        effects.values.forEach { runCatching { it.release() } }
        effects.clear()
    }
}

/** Music and video apps announce their audio sessions so effects can attach to them. */
class BoostSessionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        Prefs.init(ctx)
        val id = i.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
        when (i.action) {
            AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> { VolumeBoost.init(ctx); VolumeBoost.openSession(id) }
            AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION -> VolumeBoost.closeSession(id)
        }
    }
}
