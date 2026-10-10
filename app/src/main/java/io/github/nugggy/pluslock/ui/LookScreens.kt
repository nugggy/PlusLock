package io.github.nugggy.pluslock.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.SystemSettings
import io.github.nugggy.pluslock.service.LockClock

// ---------------------------------------------------------------- Look and feel

@Composable
fun LookScreen(nav: Nav, resumeTick: Int) {
    val ctx = LocalContext.current
    val net by P.netSpeedOn.observe()
    val clock by P.lockClockOn.observe()
    val clockColour by P.lockClockColour.observe()
    val secure = remember(resumeTick) { SystemSettings.hasSecure(ctx) }
    // Bumped after each status bar change so the switches re-read the system value.
    var bump by remember { mutableIntStateOf(0) }
    val hidden = remember(resumeTick, bump) { SystemSettings.hiddenIcons(ctx) }
    val seconds = remember(resumeTick, bump) { SystemSettings.clockSeconds(ctx) }

    val island by P.islandOn.observe()
    val dim by P.dimOn.observe()
    val dimSched by P.dimScheduled.observe()
    val volPanel by P.volPanelOn.observe()
    ScreenScaffold("Look and feel", nav.back) {
        item {
            Section("Camera island") {
                PrefSwitch(P.islandOn, "Camera island", "A pill around the selfie camera for music, calls, timers and charging. Tap it to expand.")
                if (island) {
                    PrefSwitch(P.islandMedia, "Music and videos", "With play, pause and skip when expanded")
                    PrefSwitch(P.islandCalls, "Calls")
                    PrefSwitch(P.islandTimers, "Timers and stopwatches")
                    PrefSwitch(P.islandCharging, "Charging", "Shows the level and charging speed for a few seconds when you plug in")
                }
            }
        }
        if (island) item { Note("Music needs notification access. The island hides in landscape, and covers the camera ring while it's showing.") }
        item {
            Section("Volume panel") {
                PrefSwitch(P.volPanelOn, "Pimp my Phone volume panel", "Replaces the system volume slider. Tap the dots for media, ring, alerts and alarm.")
                if (volPanel) PrefChoice(P.volPanelSide, "Side", mapOf("RIGHT" to "Right", "LEFT" to "Left"))
            }
        }
        if (volPanel) item { Note("Works with the screen on. During calls the system panel is used, so call volume always behaves as normal.") }
        item {
            Section("Extra dim and colour") {
                PrefSwitch(P.dimOn, "Extra dim", "Darker than the lowest brightness, and an optional warm tint")
                if (dim) {
                    PrefSlider(P.dimLevel, "Dim", 0..90) { "$it%" }
                    PrefSlider(P.dimWarmth, "Warmth", 0..100) { "$it%" }
                    PrefSwitch(P.dimScheduled, "Only at set times")
                    if (dimSched) {
                        HourRow("From", P.dimStart)
                        HourRow("Until", P.dimEnd)
                    }
                }
            }
        }
        if (dim) item { Note("This lays a see-through filter over the screen, so it also shows in screenshots. There's also an Extra dim gesture action.") }
        item {
            Section("Network speed") {
                PrefSwitch(P.netSpeedOn, "Network speed", "Live download and upload speed beside the camera")
                if (net) {
                    PrefChoice(P.netSpeedSide, "Side", mapOf("LEFT" to "Left of the camera", "RIGHT" to "Right of the camera"))
                    PrefSwitch(P.netSpeedHideLandscape, "Hide in landscape")
                }
            }
        }
        item {
            Section("Lock screen clock") {
                PrefSwitch(P.lockClockOn, "Custom lock screen clock", "Drawn over the lock screen while it's showing")
                if (clock) {
                    PrefChoice(P.lockClockStyle, "Style", LockClock.styles)
                    PrefChoice(P.lockClockFont, "Font", LockClock.fonts)
                    ColourRow("Colour", clockColour) { P.lockClockColour.value = it }
                    PrefSlider(P.lockClockSize, "Size", 50..160) { "$it%" }
                    PrefSlider(P.lockClockPosition, "Position", 0..100) { "$it% down" }
                    PrefSwitch(P.lockClockDate, "Show the date")
                }
            }
        }
        if (clock) item {
            Note("Android doesn't let apps remove the OxygenOS clock, so this one is drawn on top. For a clean look, pick the smallest clock style in Settings > Wallpapers and style, then move Pimp my Phone's clock clear of it. Lock and wake the phone to see changes.")
        }
        item {
            Section("Status bar") {
                if (!secure) {
                    Row2("Needs the one-off ADB step", "Hide status bar icons and show seconds on the clock. Tap to set up.", Icons.Rounded.Terminal, onClick = { nav.go(Screen.Adb) })
                } else {
                    SwitchRow("Seconds on the clock", null, seconds) {
                        SystemSettings.setClockSeconds(ctx, it); bump++
                    }
                    SystemSettings.iconSlots.forEach { (slot, label) ->
                        SwitchRow("Show $label icon", null, slot !in hidden) { show ->
                            SystemSettings.setIconHidden(ctx, slot, !show); bump++
                        }
                    }
                }
            }
        }
        if (secure) item {
            Note("These use Android's own icon list. OxygenOS honours most of them, but some icons may stay. Turning them all back on restores the default.")
        }
    }
}

// ---------------------------------------------------------------- ADB setup

@Composable
fun AdbScreen(nav: Nav, resumeTick: Int) {
    val ctx = LocalContext.current
    var check by remember { mutableIntStateOf(0) }
    val granted = remember(resumeTick, check) { SystemSettings.hasSecure(ctx) }
    val command = SystemSettings.adbCommand(ctx)

    ScreenScaffold("ADB extras", nav.back) {
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (granted) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (granted) Icon(Icons.Rounded.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                    Column(Modifier.padding(start = if (granted) 12.dp else 0.dp)) {
                        Text(if (granted) "All set" else "Not set up yet", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (granted) "Pimp my Phone can change the keyboard per app, hide status bar icons and start one-handed mode."
                            else "A few features need a permission Android only gives out over a USB cable. You do this once, and it stays after updates.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
        item {
            Section("What it unlocks") {
                Row2("Keyboard per app", "Set in App profiles")
                Row2("Status bar icons and clock seconds", "Set in Look and feel")
                Row2("One-handed mode", "Choose it as an edge gesture or back tap action")
            }
        }
        if (!granted) item {
            Section("How to do it") {
                Row2("1. Turn on USB debugging", "Settings > About device > Version, tap Build number seven times. Then Settings > System > Developer options > USB debugging.",
                    onClick = { runCatching { ctx.startActivity(Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)) } })
                Row2("2. Get ADB on your computer", "Download Android SDK Platform-Tools from developer.android.com and unzip it.")
                Row2("3. Plug in and allow", "Connect the phone by USB and tap Allow on the phone when it asks.")
                Row2("4. Run this command", "In a terminal opened in the platform-tools folder.")
                Text(
                    command,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(onClick = {
                        ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("ADB command", command))
                        Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy command") }
                    OutlinedButton(onClick = {
                        val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, command)
                        runCatching { ctx.startActivity(Intent.createChooser(i, "Send the command to your computer")) }
                    }) { Text("Share") }
                }
            }
        }
        if (!granted) item {
            Button(onClick = { check++ }, modifier = Modifier.fillMaxWidth()) { Text("Check again") }
        }
        item { Note("You can turn USB debugging off again afterwards. The permission stays until you uninstall Pimp my Phone.") }
    }
}
