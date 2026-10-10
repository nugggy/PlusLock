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
 * Shake twice for the torch, and double-tap the back of the phone for a chosen action.
 * The sensor only runs while the screen is on, so it costs little battery.
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

    fun refresh() {
        val want = (P.shakeTorch.value || P.backTapOn.value) &&
            svc.getSystemService(PowerManager::class.java).isInteractive
        if (want && !listening) {
            val acc = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            // Back taps are sharp, so they need a fast sample rate (about 200 Hz).
            listening = sensors.registerListener(this, acc, 5_000)
            primed = 0
        } else if (!want && listening) {
            stop()
        }
    }

    fun stop() {
        sensors.unregisterListener(this)
        listening = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val a = 0.9f
        for (i in 0..2) gravity[i] = a * gravity[i] + (1 - a) * e.values[i]
        if (primed < 40) { primed++; return }
        val x = e.values[0] - gravity[0]
        val y = e.values[1] - gravity[1]
        val z = e.values[2] - gravity[2]
        val now = SystemClock.uptimeMillis()
        if (P.shakeTorch.value) checkShake(x, y, z, now)
        if (P.backTapOn.value) checkBackTap(x, y, z, now)
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
