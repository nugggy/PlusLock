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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableIntStateOf
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
import io.github.nugggy.pluslock.core.Silence
import io.github.nugggy.pluslock.core.Torch
import androidx.compose.runtime.produceState
import io.github.nugggy.pluslock.data.BatteryLog
import io.github.nugggy.pluslock.data.LightingRule
import io.github.nugggy.pluslock.data.LightingRules
import io.github.nugggy.pluslock.service.LightAlert
import io.github.nugggy.pluslock.service.PlusAccessibilityService
import io.github.nugggy.pluslock.wallpaper.SkyRenderer
import io.github.nugggy.pluslock.wallpaper.Weather
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.github.nugggy.pluslock.wallpaper.SkyWallpaperService

internal fun needsService(ctx: Context): Boolean {
    if (PlusAccessibilityService.instance != null) return false
    Toast.makeText(ctx, "Turn on the Pimp my Phone accessibility service first", Toast.LENGTH_SHORT).show()
    return true
}

// ---------------------------------------------------------------- Edge gestures

@Composable
fun GesturesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val colour by P.handleColor.observe()
    ScreenScaffold("Gestures", nav.back) {
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
        item { MotionSection(ctx) }
    }
}

@Composable
private fun MotionSection(ctx: Context) {
    val shake by P.shakeTorch.observe()
    val backTap by P.backTapOn.observe()
    Section("Motion and buttons") {
        PrefSwitch(P.shakeTorch, "Shake for the torch", "Two firm shakes turn the torch on or off")
        if (shake) PrefSlider(P.shakeSensitivity, "Shake sensitivity", 0..100) { "$it%" }
        PrefSwitch(P.backTapOn, "Back tap", "Double-tap the back of the phone")
        if (backTap) {
            ActionPickerRow(ctx, "Back tap does", P.backTapAction)
            PrefSlider(P.backTapSensitivity, "Back tap sensitivity", 0..100) { "$it%" }
        }
        PrefSwitch(P.statusTapLock, "Double-tap the status bar to lock", "Swiping down on the status bar still opens the shade")
        PrefSwitch(P.volumeSkip, "Hold volume to skip tracks", "While music plays: hold up for next, down for previous")
        PrefChoice(P.flipAction, "Flip face down to", linkedMapOf(
            "OFF" to "Do nothing", "VIBRATE" to "Switch to vibrate", "SILENT" to "Switch to silent", "DND" to "Turn on Do Not Disturb",
        ))
    }
    Note("Motion gestures only listen while the screen is on. Back tap uses the motion sensor, so it can miss a gentle tap or catch a bump. Adjust the sensitivity to suit. Track skipping only works with the screen on, because Android stops sending button presses to apps when it's off. Flip watches for two minutes after the screen goes off, then stops to save battery, and puts things back when you pick the phone up.")
}

/** Choose any gesture action, or an app to open, for a single setting. */
@Composable
private fun ActionPickerRow(ctx: Context, title: String, pref: io.github.nugggy.pluslock.core.StringPref) {
    val value by pref.observe()
    var picking by remember { mutableStateOf(false) }
    var pickingApp by remember { mutableStateOf(false) }
    Row2(title, Actions.label(ctx, value), onClick = { picking = true })
    if (picking) {
        ChoiceDialog(
            title,
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

// ---------------------------------------------------------------- Battery

@Composable
fun BatteryScreen(nav: Nav) {
    ScreenScaffold("Battery", nav.back) {
        item { BatteryHealthCard() }
        item {
            Section("Charge guard") {
                PrefSwitch(P.chargeAlertOn, "Tell me when to unplug", "A knock vibration and a notification")
                PrefSlider(P.chargeAlertLevel, "Unplug at", 50..100) { "$it%" }
            }
        }
        item {
            val bedtime by P.bedtimeOn.observe()
            Section("Bedtime reminder") {
                PrefSwitch(P.bedtimeOn, "Remind me to charge", "Only if the phone isn't already on the charger")
                if (bedtime) {
                    HourRow("At", P.bedtimeHour)
                    PrefIntChoice(P.bedtimeMinute, "Minutes past", linkedMapOf(0 to ":00", 15 to ":15", 30 to ":30", 45 to ":45"))
                    PrefSlider(P.bedtimeBelow, "When the battery is below", 20..100) { "$it%" }
                }
            }
        }
        item {
            Section("While charging") {
                Row2("Charging effects", "Charging animations, a glow that stays on, plug-in and fully charged moments", onClick = { nav.go(Screen.Charging) })
                PrefSwitch(P.ringShowEta, "Time to full beside the camera", "Needs the camera ring switched on")
                Row2("Camera ring", "The ring animates while charging. Set it up under Camera ring.", onClick = { nav.go(Screen.Ring) })
            }
        }
        item { Note("Keeping a battery between 20% and 80% most of the time slows its wear. OxygenOS also has its own charging protection under Settings > Battery.") }
    }
}

@Composable
private fun BatteryHealthCard() {
    val ctx = LocalContext.current
    val tick by produceState(0) { while (true) { kotlinx.coroutines.delay(5_000); value++ } }
    val h = remember(tick) { BatteryLog.health(ctx) } ?: return
    val samples = remember(tick / 12) { BatteryLog.samples() }
    val primary = androidx.compose.material3.MaterialTheme.colorScheme.primary
    val warm = androidx.compose.ui.graphics.Color(0xFFFFA94D)
    Section("Battery health") {
        Row2("Level", "${h.pct}%" + if (h.charging) ", charging" else "")
        Row2("Temperature", "%.1f °C".format(h.tempC) + if (h.tempC >= 40f) ". That's warm, so take it off the charger or out of the sun." else "")
        Row2("Condition", h.health)
        h.cycles?.let { Row2("Charge cycles", it.toString()) }
        h.fullMah?.let { full ->
            val design = h.designMah
            Row2("Capacity today", if (design != null) "About $full mAh of $design mAh (${full * 100 / design}%)" else "About $full mAh")
        }
        h.currentMa?.let { Row2(if (h.charging) "Charging at" else "Using", "${kotlin.math.abs(it)} mA" + if (h.volts > 0) " · %.1f W".format(kotlin.math.abs(it) / 1000f * h.volts) else "") }
        if (samples.size >= 2) {
            Note("Last two days: level in blue, temperature in orange.")
            androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(120.dp).padding(horizontal = 18.dp, vertical = 8.dp)) {
                val t0 = samples.first().time; val t1 = samples.last().time.coerceAtLeast(t0 + 1)
                fun x(t: Long) = size.width * (t - t0) / (t1 - t0).toFloat()
                val lv = androidx.compose.ui.graphics.Path(); val tp = androidx.compose.ui.graphics.Path()
                samples.forEachIndexed { i, s ->
                    val px = x(s.time)
                    val ly = size.height * (1 - s.pct / 100f)
                    val ty = size.height * (1 - ((s.tempC - 15f) / 35f).coerceIn(0f, 1f))
                    if (i == 0) { lv.moveTo(px, ly); tp.moveTo(px, ty) } else { lv.lineTo(px, ly); tp.lineTo(px, ty) }
                }
                drawPath(lv, primary, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3.dp.toPx()))
                drawPath(tp, warm, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
            }
        }
    }
    Note("Capacity is an estimate from Android's charge counter, and some phones don't report cycles or design capacity at all.")
}

// ---------------------------------------------------------------- Wallpaper

@Composable
fun WallpaperScreen(nav: Nav) {
    val ctx = LocalContext.current
    val theme by P.skyTheme.observe()
    val speed by P.skySpeed.observe()
    val stars by P.skyStars.observe()
    val weatherOn by P.skyWeather.observe()
    @Suppress("UNUSED_VARIABLE") val weatherJson by P.skyWeatherJson.observe()
    ScreenScaffold("Living sky", nav.back) {
        item {
            AndroidView(
                factory = { SkyPreview(it) },
                update = { v ->
                    v.renderer.theme = theme; v.renderer.speed = speed / 100f; v.renderer.showStars = stars
                    v.renderer.weather = if (weatherOn) Weather.current() else null
                },
                modifier = Modifier.fillMaxWidth().height(320.dp).clip(RoundedCornerShape(28.dp)),
            )
        }
        item {
            Section("Look") {
                PrefChoice(P.skyTheme, "Theme", SkyRenderer.themes)
                PrefSlider(P.skySpeed, "Drift speed", 20..300) { "$it%" }
                PrefSwitch(P.skyStars, "Twinkling stars at night")
                PrefSwitch(P.skyParallax, "Tilt with the phone")
                WeatherSwitch()
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
                        Toast.makeText(ctx, "Open Wallpaper settings and pick Pimp my Phone Sky", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Set as wallpaper") }
        }
        item { Note("Tap the wallpaper for a ripple. Living sky shifts colours at dawn (5 am), day (8 am), dusk (5 pm) and night (8 pm).") }
    }
}

@Composable
private fun WeatherSwitch() {
    val ctx = LocalContext.current
    val on by P.skyWeather.observe()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        P.skyWeather.value = granted
        if (granted) Weather.refreshIfStale(ctx)
    }
    SwitchRow(
        "Real weather and sunrise",
        "Rain, snow and cloud from Open-Meteo, with dawn and dusk at your real sunrise and sunset. Sends a rough location (about 1 km) about once an hour.",
        on,
    ) { want ->
        if (!want) P.skyWeather.value = false
        else if (Weather.hasPermission(ctx)) { P.skyWeather.value = true; Weather.refreshIfStale(ctx) }
        else launcher.launch(android.Manifest.permission.ACCESS_COARSE_LOCATION)
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
                Row2("Pause lighting", "Pauses edge lighting and bubbles: 30 min, 1 hour, 2 hours, until off")
            }
        }
        item { Note("To add tiles, pull down Quick Settings fully, tap the pencil (edit) icon and drag the Pimp my Phone tiles into place.") }
        item {
            Section("Use the deck from a gesture") {
                Row2("Set an edge gesture to Control deck", "The right handle's long swipe does this out of the box", onClick = { nav.go(Screen.Gestures) })
            }
        }
    }
}

// ---------------------------------------------------------------- Keyword alerts and hold

@Composable
private fun DigestSection() {
    val ctx = LocalContext.current
    val on by P.digestOn.observe()
    Section("Morning digest") {
        PrefSwitch(P.digestOn, "Morning digest", "One quiet summary of what came in overnight")
        if (on) {
            HourRow("At", P.digestHour)
            PrefIntChoice(P.digestMinute, "Minutes past", linkedMapOf(0 to ":00", 15 to ":15", 30 to ":30", 45 to ":45"))
            Row2("Send one now", "Covers the last day, or since the last digest", onClick = {
                Thread {
                    val sent = io.github.nugggy.pluslock.service.Digest.post(ctx)
                    if (!sent) Actions.toast(ctx, "Nothing new to sum up")
                }.start()
            })
        }
    }
    if (on && !P.vaultOn.value) Note("The digest is built from the notification vault, so switch the vault on too.")
}

@Composable
private fun KeywordSection() {
    val ctx = LocalContext.current
    val on by P.keywordsOn.observe()
    val words by P.keywords.observe()
    val glow by P.keywordsGlow.observe()
    val kColour by P.keywordsColour.observe()
    var editing by remember { mutableStateOf(false) }
    Section("Keyword alerts") {
        PrefSwitch(P.keywordsOn, "Keyword alerts", "A buzz and a glow when a message contains one of your words")
        if (on) {
            Row2("Words", words.ifBlank { "None yet" }, onClick = { editing = true })
            PrefSwitch(P.keywordsPartial, "Match inside longer words", "Off: help won't match helpful. On: it will.")
            PrefSwitch(P.keywordsBreakDnd, "Even in Do Not Disturb", "Buzzes on the alarm channel, so it gets through if alarms are allowed")
            PrefSwitch(P.keywordsGlow, "Glow", "Works even with edge lighting switched off or paused")
            if (glow) {
                PrefSwitch(P.keywordsWake, "Light up the lock screen", "Wakes the screen if it's off")
                PrefChoice(P.keywordsEffect, "Effect", io.github.nugggy.pluslock.service.lightingEffects)
                ColourRow("Colour", kColour) { P.keywordsColour.value = it }
                PrefSlider(P.keywordsDuration, "How long", 3..60) { "$it s" }
            }
            PrefChoice(P.keywordsHaptic, "Vibration", Haptics.presets)
            PrefSwitch(P.keywordsTorch, "Blink the torch")
            PrefSwitch(P.keywordsSound, "Play a sound, even on silent")
            Row2("Try it", "Shows the glow and plays the vibration") {
                IconButton(onClick = {
                    if (needsService(ctx)) return@IconButton
                    PlusAccessibilityService.instance?.lighting?.show(LightAlert(P.keywordsColour.value, null, "Keyword alert", "Someone said: urgent",
                        effect = P.keywordsEffect.value, durationMs = 4_000))
                    Haptics.play(ctx, P.keywordsHaptic.value, "")
                }) { Icon(Icons.Rounded.PlayArrow, "Try") }
            }
            Row2("Lost your phone?", "Find my phone rings it out loud from a secret codeword. Set it up under Find my phone.")
        }
    }
    if (editing) {
        var text by remember { mutableStateOf(words) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text("Keywords") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Separate words with commas. Whole words only, and capitals don't matter.")
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { P.keywords.value = text.trim(); editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun HoldSection() {
    val ctx = LocalContext.current
    val on by P.holdOn.observe()
    val apps by P.holdApps.observe()
    val list = remember(apps) { apps.split(',').filter { it.isNotBlank() } }
    var picking by remember { mutableStateOf(false) }
    Section("Hold during work hours") {
        PrefSwitch(P.holdOn, "Hold notifications", "Chosen apps stay quiet during these hours, then their notifications come back")
        if (on) {
            HourRow("From", P.holdStart)
            HourRow("Until", P.holdEnd)
            PrefSwitch(P.holdWeekdaysOnly, "Weekdays only")
            list.forEach { pkg ->
                Row2(Apps.label(ctx, pkg), null) {
                    TextButton(onClick = { P.holdApps.value = (list - pkg).joinToString(",") }) { Text("Remove") }
                }
            }
            Row2("Add an app", null, Icons.Rounded.Add, onClick = { picking = true })
        }
    }
    if (on) Note("Held notifications are snoozed, not deleted. Android may still play the first sound before Pimp my Phone can snooze it, so also silence that app's notifications if you need total quiet.")
    if (picking) AppPickerDialog(onDismiss = { picking = false }) {
        picking = false
        if (it.pkg !in list) P.holdApps.value = (list + it.pkg).joinToString(",")
    }
}

// ---------------------------------------------------------------- Haptics and read-aloud

@Composable
fun TricksScreen(nav: Nav) {
    val ctx = LocalContext.current
    ScreenScaffold("Haptics, keywords and quiet", nav.back) {
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
        item { DigestSection() }
        item { KeywordSection() }
        item { HoldSection() }
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
