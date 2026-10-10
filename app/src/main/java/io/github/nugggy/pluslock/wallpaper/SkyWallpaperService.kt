package io.github.nugggy.pluslock.wallpaper

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.MotionEvent
import android.view.SurfaceHolder
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs

class SkyWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine {
        Prefs.init(this)
        return SkyEngine()
    }

    private inner class SkyEngine : Engine(), SensorEventListener {
        private val handler = Handler(Looper.getMainLooper())
        private val renderer = SkyRenderer()
        private val sensors = getSystemService(SensorManager::class.java)
        private var visible = false
        private var tiltX = 0f
        private var tiltY = 0f
        private var scroll = 0f
        private val start = SystemClock.elapsedRealtime()
        private val frame = Runnable { drawFrame() }

        override fun onCreate(surfaceHolder: SurfaceHolder) {
            super.onCreate(surfaceHolder)
            setTouchEventsEnabled(true)
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                renderer.theme = P.skyTheme.value
                renderer.speed = P.skySpeed.value / 100f
                renderer.showStars = P.skyStars.value
                renderer.weather = if (P.skyWeather.value) Weather.current() else null
                Weather.refreshIfStale(this@SkyWallpaperService)
                if (P.skyParallax.value) {
                    sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                        sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                    }
                }
                drawFrame()
            } else {
                sensors.unregisterListener(this)
                handler.removeCallbacks(frame)
            }
        }

        override fun onOffsetsChanged(xOffset: Float, yOffset: Float, xStep: Float, yStep: Float, xPx: Int, yPx: Int) {
            scroll = xOffset
        }

        override fun onTouchEvent(event: MotionEvent) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) renderer.touch(event.x, event.y, now())
        }

        override fun onSensorChanged(e: SensorEvent) {
            // Low-pass filter so the parallax drifts rather than jitters.
            tiltX += (e.values[0] - tiltX) * 0.08f
            tiltY += (e.values[1] - 5f - tiltY) * 0.08f
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

        private fun now() = (SystemClock.elapsedRealtime() - start) / 1000f

        private fun drawFrame() {
            handler.removeCallbacks(frame)
            if (!visible) return
            val holder = surfaceHolder
            val c = runCatching { holder.lockHardwareCanvas() }.getOrNull()
            if (c != null) {
                try {
                    renderer.draw(c, c.width, c.height, now(), tiltX, tiltY, scroll)
                } finally {
                    runCatching { holder.unlockCanvasAndPost(c) }
                }
            }
            // 30 fps keeps the motion smooth without costing much battery.
            handler.postDelayed(frame, 33)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(frame)
            sensors.unregisterListener(this)
            super.onSurfaceDestroyed(holder)
        }
    }
}
