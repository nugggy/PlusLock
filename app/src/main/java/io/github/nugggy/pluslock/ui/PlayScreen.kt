package io.github.nugggy.pluslock.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.wallpaper.PlayRenderer
import io.github.nugggy.pluslock.wallpaper.PlayWallpaperService
import io.github.nugggy.pluslock.wallpaper.applyPrefs
import io.github.nugggy.pluslock.wallpaper.batteryFraction

@Composable
fun PlayWallpaperScreen(nav: Nav) {
    val ctx = LocalContext.current
    val theme by P.playTheme.observe()
    val mode by P.playColourMode.observe()
    val colour by P.playColour.observe()
    val density by P.playDensity.observe()
    val speed by P.playSpeed.observe()
    val black by P.playBlack.observe()
    ScreenScaffold("Interactive wallpapers", nav.back) {
        item {
            AndroidView(
                factory = { PlayPreview(it) },
                update = { v ->
                    // Read every setting so the preview follows along.
                    listOf(theme, mode, colour, density, speed, black)
                    v.renderer.applyPrefs()
                },
                modifier = Modifier.fillMaxWidth().height(420.dp).clip(RoundedCornerShape(28.dp)),
            )
        }
        item { Note("Try it: touch, drag and tilt the preview.") }
        item {
            Section("Wallpaper") {
                PrefChoice(P.playTheme, "Wallpaper", PlayRenderer.themes)
                PrefChoice(P.playColourMode, "Colours", linkedMapOf(
                    "THEME" to "The wallpaper's own colours", "MINE" to "Shades of my colour", "RAINBOW" to "Rainbow, always shifting",
                ))
                if (mode == "MINE") ColourRow("My colour", colour) { P.playColour.value = it }
                PrefSwitch(P.playBlack, "Pure black background", "Easiest on the battery with the OnePlus screen")
            }
        }
        item {
            Section("Feel") {
                PrefSlider(P.playDensity, "How many", 30..250) { "$it%" }
                PrefSlider(P.playSpeed, "Speed", 30..250) { "$it%" }
                PrefSwitch(P.playTilt, "React to tilting the phone", "Marbles roll, the tide sloshes and the swarm leans")
                PrefSwitch(P.playSmooth, "Extra smooth", "60 frames a second instead of 30. Uses a little more battery.")
            }
        }
        item {
            Button(
                onClick = {
                    val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(ctx, PlayWallpaperService::class.java),
                    )
                    runCatching { ctx.startActivity(i) }.onFailure {
                        Toast.makeText(ctx, "Open Wallpaper settings and pick Pimp my Phone Play", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set as wallpaper") }
        }
        item {
            Note("Your home screen passes touches through to the wallpaper, so tap and drag on empty space. " +
                "The lock screen doesn't pass touches on, so there it just plays by itself. Change wallpaper any time here and it updates straight away.")
        }
    }
}

private class PlayPreview(ctx: Context) : View(ctx), SensorEventListener {
    val renderer = PlayRenderer().apply { battery = batteryFraction(ctx) }
    private val start = SystemClock.elapsedRealtime()
    private val sensors = ctx.getSystemService(SensorManager::class.java)
    private var gx = 0f
    private var gy = 0f
    private fun now() = (SystemClock.elapsedRealtime() - start) / 1000f

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onDetachedFromWindow() {
        sensors.unregisterListener(this)
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!P.playTilt.value) { gx = 0f; gy = 0f; return }
        gx += (e.values[0] - gx) * 0.2f
        gy += (e.values[1] - gy) * 0.2f
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width, height, now(), gx, gy)
        postInvalidateOnAnimation()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Keep the list from scrolling while you play.
        parent?.requestDisallowInterceptTouchEvent(event.actionMasked != MotionEvent.ACTION_UP)
        renderer.touch(event, now())
        return true
    }
}
