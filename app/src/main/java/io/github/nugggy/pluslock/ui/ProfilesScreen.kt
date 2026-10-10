package io.github.nugggy.pluslock.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Status
import io.github.nugggy.pluslock.data.AppProfile
import io.github.nugggy.pluslock.data.Profiles

@Composable
fun ProfilesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val json by P.profilesJson.observe()
    val profiles = remember(json) { Profiles.all().values.sortedBy { Apps.label(ctx, it.pkg).lowercase() } }
    var picking by remember { mutableStateOf(false) }

    ScreenScaffold("App profiles", nav.back) {
        item {
            Section("App profiles") {
                PrefSwitch(P.profilesOn, "App profiles", "Settings that switch in when an app opens and back when it closes")
                PrefSwitch(P.rememberVolume, "Volume memory", "If you change the volume inside an app, remember it for next time")
            }
        }
        item {
            Button(onClick = { picking = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null)
                Spacer(Modifier.width(8.dp))
                Text("Add an app")
            }
        }
        if (profiles.isNotEmpty()) item {
            Section("Your apps") {
                profiles.forEach { p ->
                    Row2(Apps.label(ctx, p.pkg), p.summary(), onClick = { nav.go(Screen.ProfileEdit(p.pkg)) }) {}
                }
            }
        } else item {
            Note("Try one: give Spotify 60% volume, keep Maps awake, or have Instagram nudge you after 20 minutes.")
        }
    }

    if (picking) AppPickerDialog(onDismiss = { picking = false }) { a ->
        picking = false
        if (Profiles.get(a.pkg) == null) Profiles.save(AppProfile(a.pkg))
        nav.go(Screen.ProfileEdit(a.pkg))
    }
}

@Composable
fun ProfileEditScreen(nav: Nav, pkg: String) {
    val ctx = LocalContext.current
    val json by P.profilesJson.observe()
    val p = remember(json) { Profiles.get(pkg) ?: AppProfile(pkg) }
    fun save(np: AppProfile) = Profiles.save(np)
    val label = remember(pkg) { Apps.label(ctx, pkg) }

    ScreenScaffold(label, nav.back) {
        item {
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                AppIcon(pkg, 56)
                Spacer(Modifier.width(16.dp))
                Note(p.summary())
            }
        }
        item {
            Section("Sound and screen") {
                SwitchRow("Set media volume", "Applied when $label opens", p.volume >= 0) {
                    save(p.copy(volume = if (it) 50 else -1))
                }
                if (p.volume >= 0) SliderRow("Media volume", p.volume, 0..100, { "$it%" }) { save(p.copy(volume = it)) }
                SwitchRow("Keep the screen on", null, p.keepAwake) { save(p.copy(keepAwake = it)) }
                ChoiceRow(
                    "Rotation",
                    mapOf("0" to "Leave as is", "1" to "Auto-rotate", "2" to "Lock portrait", "3" to "Lock landscape"),
                    p.rotation.toString(),
                ) { save(p.copy(rotation = it.toInt())) }
                if (p.rotation > 0 && !Status.writeSettings(ctx)) {
                    Note("Rotation needs Modify system settings, which you can allow on the home screen.")
                }
            }
        }
        item {
            Section("Edges") {
                SwitchRow("Turn off edge gestures here", "Handy for games and drawing apps", p.noGestures) { save(p.copy(noGestures = it)) }
                SwitchRow("No edge lighting for this app", null, p.noLighting) { save(p.copy(noLighting = it)) }
                if (!p.noLighting) ColourRow("Edge lighting colour", p.edgeColour, allowAuto = true) { save(p.copy(edgeColour = it)) }
            }
        }
        item {
            Section("Notifications") {
                ChoiceRow("Haptic signature", Haptics.presets, p.haptic) { save(p.copy(haptic = it)) }
                if (p.haptic != "NONE") Row2("Feel it", null) {
                    IconButton(onClick = { Haptics.play(ctx, p.haptic, label) }) { Icon(Icons.Rounded.PlayArrow, "Play") }
                }
                SwitchRow("Read aloud", "Speaks new messages from $label. Turn on read-aloud under Haptic signatures.", p.whisper) {
                    save(p.copy(whisper = it))
                }
            }
        }
        item {
            Section("Doomscroll breaker") {
                ChoiceRow(
                    "Take a break after",
                    linkedMapOf("0" to "Never", "5" to "5 minutes", "10" to "10 minutes", "15" to "15 minutes", "20" to "20 minutes", "30" to "30 minutes", "45" to "45 minutes", "60" to "1 hour"),
                    p.focusMinutes.toString(),
                ) { save(p.copy(focusMinutes = it.toInt())) }
                Note("When time is up the screen dims and a short breathing pause appears. Then you choose to close $label or have 5 more minutes.")
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                OutlinedButton(onClick = { Profiles.remove(pkg); nav.back() }) { Text("Remove this profile") }
            }
        }
    }
}
