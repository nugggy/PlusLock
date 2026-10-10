package io.github.nugggy.pluslock.wallpaper

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs

/** Reads the settings into a renderer. Shared by the wallpaper and the preview in settings. */
fun PlayRenderer.applyPrefs() {
    theme = P.playTheme.value
    colourMode = P.playColourMode.value
    colour = P.playColour.value
    density = P.playDensity.value / 100f
    speed = P.playSpeed.value / 100f
    blackBackground = P.playBlack.value
}

fun batteryFraction(ctx: Context): Float {
    val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return 0.6f
    val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 60)
    val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
    return level.toFloat() / scale
}

/** Interactive wallpapers: touch, tilt and battery make them move. */
class PlayWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        Prefs.init(this)
        return PlayEngine()
    }

    private inner class PlayEngine : Engine(), SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = PlayRenderer()
        private val sensors = getSystemService(SensorManager::class.java)
        private var visible = false
        private var gx = 0f
        private var gy = 0f
        private var lastBattery = 0L
        private val start = SystemClock.elapsedRealtime()
        private val frame = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                renderer.applyPrefs()
                renderer.battery = batteryFraction(this@PlayWallpaperService)
                if (P.playTilt.value) {
                    sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                        sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
                    }
                }
                drawFrame()
            } else {
                sensors.unregisterListener(this)
                handler.removeCallbacks(frame)
            }
        }

        override fun onTouchEvent(event: MotionEvent) = renderer.touch(event, now())

        override fun onSensorChanged(e: SensorEvent) {
            gx += (e.values[0] - gx) * 0.2f
            gy += (e.values[1] - gy) * 0.2f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun now() = (SystemClock.elapsedRealtime() - start) / 1000f

        private fun drawFrame() {
            handler.removeCallbacks(frame)
            if (!visible) return
            if (SystemClock.elapsedRealtime() - lastBattery > 30_000) {
                lastBattery = SystemClock.elapsedRealtime()
                renderer.battery = batteryFraction(this@PlayWallpaperService)
            }
            val holder = surfaceHolder
            val c = runCatching { holder.lockHardwareCanvas() }.getOrNull()
            if (c != null) {
                try {
                    renderer.draw(c, c.width, c.height, now(), gx, gy)
                } finally {
                    runCatching { holder.unlockCanvasAndPost(c) }
                }
            }
            handler.postDelayed(frame, if (P.playSmooth.value) 16 else 33)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            sensors.unregisterListener(this)
            super.onSurfaceDestroyed(holder)
        }
    }
}
