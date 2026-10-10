package io.github.nugggy.pluslock.ui

import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.nugggy.pluslock.core.Actions
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.GestureAction
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Side
import io.github.nugggy.pluslock.core.Swipe
import io.github.nugggy.pluslock.core.Torch
import io.github.nugggy.pluslock.service.PlusAccessibilityService
import io.github.nugggy.pluslock.wallpaper.SkyRenderer
import io.github.nugggy.pluslock.wallpaper.SkyWallpaperService

private fun needsService(ctx: Context): Boolean {
    if (PlusAccessibilityService.instance != null) return false
    Toast.makeText(ctx, "Turn on the PlusLock accessibility service first", Toast.LENGTH_SHORT).show()
    return true
}

// ---------------------------------------------------------------- Edge gestures

@Composable
fun GesturesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val colour by P.handleColor.observe()
    ScreenScaffold("Edge gestures", nav.back) {
        item {
            Section("Handles") {
                PrefSwitch(P.gesturesOn, "Edge gestures", "Thin handles on the screen edges")
                PrefSwitch(P.leftHandle, "Left handle")
                PrefSwitch(P.rightHandle, "Right handle")
                PrefSwitch(P.gestureHaptics, "Vibrate on trigger")
                PrefSwitch(P.gestureHideLandscape, "Hide in landscape", "Keeps games and videos clear")
            }
        }
        item {
            Section("Size and position") {
                PrefSlider(P.handleWidth, "Width", 8..40) { "$it dp" }
                PrefSlider(P.handleHeight, "Height", 60..200) { "$it dp" }
                PrefSlider(P.handlePosition, "Position", 0..100) { "$it% down" }
                PrefSlider(P.handleVisibility, "Visibility", 0..100) { if (it == 0) "Invisible" else "$it%" }
                ColourRow("Colour", colour) { P.handleColor.value = it }
            }
        }
        item {
            Note("Android only lets an app take over 200 dp of each edge from the system back gesture, so the rest of the edge still works as normal back.")
        }
        for (side in Side.entries) {
            item {
                Section(side.label) {
                    for (dir in Swipe.entries) GestureActionRow(ctx, side, dir)
                }
            }
        }
    }
}

@Composable
private fun GestureActionRow(ctx: Context, side: Side, dir: Swipe) {
    val pref = remember(side, dir) { P.gesture(side, dir) }
    val value by pref.observe()
    var picking by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }
    Row2(dir.label, Actions.label(ctx, value), onClick = { picking = true })
    if (picking) {
        ChoiceDialog(
            dir.label,
            GestureAction.entries.associate { it.name to it.label },
            if (value.startsWith("APP:")) GestureAction.LAUNCH_APP.name else value,
            { picking = false },
        ) {
            picking = false
            if (it == GestureAction.LAUNCH_APP.name) pickingApp = true else pref.value = it
        }
    }
    if (pickingApp) {
        AppPickerDialog(onDismiss = { pickingApp = false }) {
            pref.value = Actions.appAction(it.pkg)
            pickingApp = false
        }
    }
}

// ---------------------------------------------------------------- Edge lighting

@Composable
fun LightingScreen(nav: Nav) {
    val ctx = LocalContext.current
    val colour by P.lightingColour.observe()
    val appColour by P.lightingAppColour.observe()
    ScreenScaffold("Edge lighting", nav.back) {
        item {
            Section("Edge lighting") {
                PrefSwitch(P.lightingOn, "Edge lighting", "Glow when a notification arrives and the screen is on")
                PrefChoice(P.lightingEffect, "Effect", mapOf(
                    "COMET" to "Comet: two lights race round the edge",
                    "PULSE" to "Pulse: the whole edge breathes",
                    "NEON" to "Neon: a spinning sweep",
                    "RAINBOW" to "Rainbow: every colour at once",
                    "SOLID" to "Solid: a steady glow",
                ))
                PrefSlider(P.lightingThickness, "Thickness", 2..14) { "$it dp" }
                PrefSlider(P.lightingDuration, "How long", 2..15) { "$it s" }
            }
        }
        item {
            Section("Colour and details") {
                PrefSwitch(P.lightingAppColour, "Use each app's colour", "Taken from the app's brand colour or icon")
                if (!appColour) ColourRow("Colour", colour) { P.lightingColour.value = it }
                PrefSwitch(P.lightingPill, "Show a preview bubble", "App icon, sender and a line of the message")
                PrefSwitch(P.lightingRespectDnd, "Stay dark in Do Not Disturb")
                PrefSwitch(P.chargingGlow, "Glow green when you plug in")
            }
        }
        item {
            Button(
                onClick = {
                    if (needsService(ctx)) return@Button
                    PlusAccessibilityService.instance?.lighting?.show(
                        if (appColour) Apps.accentColour(ctx, ctx.packageName) else colour,
                        Apps.icon(ctx, ctx.packageName), "PlusLock", "This is how your notifications will glow",
                    )
                },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            ) { Text("Preview") }
        }
        item { Note("Per-app colours and opt-outs live in App profiles.") }
    }
}

// ---------------------------------------------------------------- Camera ring

@Composable
fun RingScreen(nav: Nav) {
    val mode by P.ringColourMode.observe()
    val colour by P.ringColour.observe()
    ScreenScaffold("Camera ring", nav.back) {
        item {
            Section("Ring") {
                PrefSwitch(P.ringOn, "Camera ring", "Battery level drawn around the selfie camera")
                PrefSwitch(P.ringShowTrack, "Show the empty part of the ring")
                PrefSwitch(P.ringHideLandscape, "Hide in landscape")
                PrefChoice(P.ringColourMode, "Colour", mapOf(
                    "LEVEL" to "White, turning orange then red when low",
                    "ACCENT" to "My colour, turning orange then red when low",
                    "CUSTOM" to "Always my colour",
                ))
                if (mode != "LEVEL") ColourRow("My colour", colour) { P.ringColour.value = it }
            }
        }
        item {
            Section("Fit it to your camera") {
                PrefSlider(P.ringThickness, "Thickness", 10..80) { "%.1f dp".format(it / 10f) }
                PrefSlider(P.ringGap, "Gap from the lens", 0..60) { "%.1f dp".format(it / 10f) }
                PrefSlider(P.ringScale, "Size", 60..160) { "$it%" }
                PrefSlider(P.ringOffsetX, "Nudge sideways", -40..40) { "%.1f dp".format(it / 2f) }
                PrefSlider(P.ringOffsetY, "Nudge up or down", -40..40) { "%.1f dp".format(it / 2f) }
            }
        }
        item { Note("PlusLock reads the camera position from Android. If the ring sits a little off on your OnePlus 15, nudge it here. It spins while charging.") }
    }
}

// ---------------------------------------------------------------- Battery

@Composable
fun BatteryScreen(nav: Nav) {
    ScreenScaffold("Charge guard", nav.back) {
        item {
            Section("Charge guard") {
                PrefSwitch(P.chargeAlertOn, "Tell me when to unplug", "A knock vibration and a notification")
                PrefSlider(P.chargeAlertLevel, "Unplug at", 50..100) { "$it%" }
            }
        }
        item {
            Section("While charging") {
                PrefSwitch(P.chargingGlow, "Green glow when you plug in")
                Row2("Camera ring", "The ring spins while charging. Set it up under Camera ring.", onClick = { nav.go(Screen.Ring) })
            }
        }
        item { Note("Keeping a battery between 20% and 80% most of the time slows its wear. OxygenOS also has its own charging protection under Settings > Battery.") }
    }
}

// ---------------------------------------------------------------- Wallpaper

@Composable
fun WallpaperScreen(nav: Nav) {
    val ctx = LocalContext.current
    val theme by P.skyTheme.observe()
    val speed by P.skySpeed.observe()
    val stars by P.skyStars.observe()
    ScreenScaffold("Living sky", nav.back) {
        item {
            AndroidView(
                factory = { SkyPreview(it) },
                update = { v -> v.renderer.theme = theme; v.renderer.speed = speed / 100f; v.renderer.showStars = stars },
                modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(28.dp)),
            )
        }
        item {
            Section("Look") {
                PrefChoice(P.skyTheme, "Theme", SkyRenderer.themes)
                PrefSlider(P.skySpeed, "Drift speed", 20..300) { "$it%" }
                PrefSwitch(P.skyStars, "Twinkling stars at night")
                PrefSwitch(P.skyParallax, "Tilt with the phone")
            }
        }
        item {
            Button(
                onClick = {
                    val i = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).putExtra(
                        WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                        ComponentName(ctx, SkyWallpaperService::class.java),
                    )
                    runCatching { ctx.startActivity(i) }.onFailure {
                        Toast.makeText(ctx, "Open Wallpaper settings and pick PlusLock Sky", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set as wallpaper") }
        }
        item { Note("Tap the wallpaper for a ripple. Living sky shifts colours at dawn (5 am), day (8 am), dusk (5 pm) and night (8 pm).") }
    }
}

private class SkyPreview(ctx: Context) : View(ctx) {
    val renderer = SkyRenderer()
    private val start = SystemClock.elapsedRealtime()
    private fun now() = (SystemClock.elapsedRealtime() - start) / 1000f

    override fun onDraw(canvas: Canvas) {
        renderer.draw(canvas, width, height, now(), 0f, 0f, 0f)
        postInvalidateOnAnimation()
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) renderer.touch(event.x, event.y, now())
        return true
    }
}

// ---------------------------------------------------------------- Control deck

@Composable
fun DeckScreen(nav: Nav) {
    val ctx = LocalContext.current
    ScreenScaffold("Control deck and tiles", nav.back) {
        item {
            Button(
                onClick = { ctx.startActivity(Intent(ctx, DeckActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Open the control deck") }
        }
        item {
            Section("What's in the deck") {
                Row2("Volume mixer", "Separate sliders for media, ringtone, notifications, alarms and calls")
                Row2("Torch brightness", if (Torch.maxLevel(ctx) > 1) "Your torch has ${Torch.maxLevel(ctx)} brightness steps" else "Your torch reports a single brightness level")
                Row2("Sound mode", "Ring, vibrate or silent in one tap")
                Row2("Caffeine", "Keep the screen on for 15 minutes, an hour, or until you stop it")
            }
        }
        item {
            Section("Quick Settings tiles") {
                Row2("Caffeine", "Tap to cycle off, 15 min, 1 hour, until off")
                Row2("Lock screen", "Locks the phone without the power button")
                Row2("Screenshot", "Closes the shade, then takes a screenshot")
                Row2("Sound mode", "Cycles ring, vibrate and silent")
                Row2("Control deck", "Opens the mixer over any app")
                Row2("Edge gestures", "Turns the handles on and off")
            }
        }
        item { Note("To add tiles, pull down Quick Settings fully, tap the pencil (edit) icon and drag the PlusLock tiles into place.") }
        item {
            Section("Use the deck from a gesture") {
                Row2("Set an edge gesture to Control deck", "The right handle's long swipe does this out of the box", onClick = { nav.go(Screen.Gestures) })
            }
        }
    }
}

// ---------------------------------------------------------------- Haptics and read-aloud

@Composable
fun TricksScreen(nav: Nav) {
    val ctx = LocalContext.current
    ScreenScaffold("Haptic signatures and read-aloud", nav.back) {
        item {
            Section("Haptic signatures") {
                PrefSwitch(P.hapticsOn, "Haptic signatures", "Each app can have its own vibration pattern")
            }
        }
        item {
            Section("Try the patterns") {
                Haptics.presets.filterKeys { it != "NONE" }.forEach { (k, label) ->
                    Row2(label, if (k == "MORSE") "WhatsApp would buzz W: dot dash dash" else null) {
                        IconButton(onClick = { Haptics.play(ctx, k, "WhatsApp") }) { Icon(Icons.Rounded.PlayArrow, "Play") }
                    }
                }
            }
        }
        item {
            Section("Read-aloud") {
                PrefSwitch(P.whisperOn, "Read notifications aloud", "For the apps you choose in App profiles")
                PrefSwitch(P.whisperHeadphonesOnly, "Only through headphones", "Never out loud from the speaker")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = { nav.go(Screen.Profiles) }, modifier = Modifier.weight(1f)) { Text("Choose apps") }
            }
        }
        item { Note("Pick a signature for each app in App profiles. To feel only the signature, turn off vibration for that app's notifications in Android settings.") }
    }
}
