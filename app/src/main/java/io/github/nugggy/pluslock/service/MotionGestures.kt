package io.github.nugggy.pluslock.service

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.PowerManager
import android.os.SystemClock
import io.github.nugggy.pluslock.core.Actions
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Torch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Shake twice for the torch, double-tap the back of the phone for a chosen action,
 * and flip the phone face down to silence it. The sensor runs while the screen is on,
 * and for two minutes after it goes off so a phone put face down still gets caught.
 */
class MotionGestures(private val svc: PlusAccessibilityService) : SensorEventListener {
    private val sensors = svc.getSystemService(SensorManager::class.java)
    private var listening = false

    // Gravity estimate for a simple high-pass filter.
    private val gravity = FloatArray(3)
    private var primed = 0

    private var lastShakePeak = 0L
    private var shakePeaks = 0
    private var lastShakeFire = 0L

    private var lastTap = 0L
    private var tapCount = 0
    private var lastTapFire = 0L
    private var quietSince = 0L

    private val flipOn get() = P.flipAction.value != "OFF"
    private var faceDownSince = 0L
    private var faceUpSince = 0L
    private var flipped = false
    // What flip changed, kept in prefs so a restart can still put it back.
    private val savedRinger = io.github.nugggy.pluslock.core.IntPref("restore_flip_ringer", -1)
    private val savedFilter = io.github.nugggy.pluslock.core.IntPref("restore_flip_filter", -1)
    private val setRinger = io.github.nugggy.pluslock.core.IntPref("restore_flip_set", -1)
    private var near = false
    private var lingerUntil = 0L
    /** Shake and back tap only count with the screen on, never in a pocket. */
    private var awake = true
    private val linger = Runnable { if (!svc.getSystemService(PowerManager::class.java).isInteractive) stop() }

    /** The screen went off: keep watching for a flip for a little while, then stop. */
    fun screenOff() {
        awake = false
        if (!flipOn) { stop(); return }
        lingerUntil = SystemClock.uptimeMillis() + 120_000
        svc.handler.removeCallbacks(linger)
        svc.handler.postDelayed(linger, 120_000)
    }

    init {
        // A flip from before a restart is still in force; picking the phone up will undo it.
        flipped = savedRinger.value >= 0 || savedFilter.value >= 0
    }

    fun refresh() {
        if (!flipOn && flipped) { flipped = false; restoreFlip() }
        awake = svc.getSystemService(PowerManager::class.java).isInteractive
        val want = (P.shakeTorch.value || P.backTapOn.value || flipOn) && awake ||
            flipOn && SystemClock.uptimeMillis() < lingerUntil
        if (want && !listening) {
            val acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            // Back taps are sharp, so they need a fast sample rate (about 200 Hz).
            listening = sensors.registerListener(this, acc, 5_000)
            primed = 0
            sensors.getDefaultSensor(Sensor.TYPE_PROXIMITY)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL) }
        } else if (!want && listening) {
            stop()
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_PROXIMITY) { near = e.values[0] < e.sensor.maximumRange.coerceAtMost(5f); return }
        val a = 0.9f
        for (i in 0..2) gravity[i] = a * gravity[i] + (1 - a) * e.values[i]
        if (primed < 40) { primed++; return }
        val x = e.values[0] - gravity[0]
        val y = e.values[1] - gravity[1]
        val z = e.values[2] - gravity[2]
        val now = SystemClock.uptimeMillis()
        if (P.shakeTorch.value && awake) checkShake(x, y, z, now)
        if (P.backTapOn.value && awake) checkBackTap(x, y, z, now)
        if (flipOn) checkFlip(gravity[2], now)
    }

    private fun checkFlip(gz: Float, now: Long) {
        // Face down and either lying on something (proximity covered) or not in use. Holding the
        // phone up over your face in bed is face down too, but the screen is on and nothing's near.
        if (gz < -8.5f && (near || !awake)) {
            faceUpSince = 0
            if (faceDownSince == 0L) faceDownSince = now
            if (!flipped && now - faceDownSince > 1_500) { flipped = true; silence() }
        } else if (gz > 3f) {
            faceDownSince = 0
            if (faceUpSince == 0L) faceUpSince = now
            if (flipped && now - faceUpSince > 800) { flipped = false; unsilence() }
        } else {
            faceDownSince = 0; faceUpSince = 0
        }
    }

    private fun silence() {
        val am = svc.getSystemService(android.media.AudioManager::class.java)
        val nm = svc.getSystemService(android.app.NotificationManager::class.java)
        when (P.flipAction.value) {
            "DND" -> if (nm.isNotificationPolicyAccessGranted && nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_ALL) {
                savedFilter.value = nm.currentInterruptionFilter
                runCatching { nm.setInterruptionFilter(android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) }
            }
            else -> {
                val mode = if (P.flipAction.value == "SILENT") android.media.AudioManager.RINGER_MODE_SILENT else android.media.AudioManager.RINGER_MODE_VIBRATE
                if (am.ringerMode != mode) {
                    savedRinger.value = am.ringerMode
                    setRinger.value = mode
                    io.github.nugggy.pluslock.core.SoundMode.set(svc, mode)
                }
            }
        }
        Haptics.tick(svc)
    }

    private fun unsilence() = restoreFlip()

    /** Puts back what flip changed, unless you've changed it yourself since. */
    fun restoreFlip() {
        val am = svc.getSystemService(android.media.AudioManager::class.java)
        val nm = svc.getSystemService(android.app.NotificationManager::class.java)
        if (savedFilter.value >= 0) {
            if (nm.currentInterruptionFilter == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
                runCatching { nm.setInterruptionFilter(savedFilter.value) }
            }
            savedFilter.value = -1
        }
        if (savedRinger.value >= 0) {
            if (am.ringerMode == setRinger.value) io.github.nugggy.pluslock.core.SoundMode.set(svc, savedRinger.value)
            savedRinger.value = -1
            setRinger.value = -1
        }
    }

    private fun checkShake(x: Float, y: Float, z: Float, now: Long) {
        val mag = sqrt(x * x + y * y + z * z)
        // Sensitivity 0..100 maps to a threshold of 30 down to 12 m/s².
        val threshold = 30f - 18f * P.shakeSensitivity.value / 100f
        if (mag < threshold) return
        if (now - lastShakePeak < 120) return
        shakePeaks = if (now - lastShakePeak < 600) shakePeaks + 1 else 1
        lastShakePeak = now
        if (shakePeaks >= 3 && now - lastShakeFire > 1_500) {
            lastShakeFire = now
            shakePeaks = 0
            Haptics.tick(svc)
            Torch.toggle(svc)
        }
    }

    private fun checkBackTap(x: Float, y: Float, z: Float, now: Long) {
        val side = sqrt(x * x + y * y)
        // Ignore taps while the phone is being moved about.
        if (side > 6f || abs(z) > 14f) { quietSince = now; tapCount = 0; return }
        val threshold = 5.5f - 3.5f * P.backTapSensitivity.value / 100f
        // A knock on the back pushes the phone forward (positive z, out of the screen) with
        // little sideways motion. A tap on the glass pushes it backwards, so it's ignored.
        if (z < threshold || side > z * 0.7f) return
        if (now - quietSince < 400) return
        if (now - lastTap < 90) return // the same knock ringing on
        tapCount = if (now - lastTap in 90..500) tapCount + 1 else 1
        lastTap = now
        if (tapCount >= 2 && now - lastTapFire > 1_000) {
            lastTapFire = now
            tapCount = 0
            Haptics.tick(svc)
            Actions.run(svc, P.backTapAction.value)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
