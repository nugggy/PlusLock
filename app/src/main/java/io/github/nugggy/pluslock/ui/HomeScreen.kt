package io.github.nugggy.pluslock.ui

import android.Manifest
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.SettingsBackupRestore
import androidx.compose.material.icons.rounded.ViewSidebar
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.PhonelinkRing
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lens
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PauseCircle
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WbIridescent
import android.widget.Toast
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.IconButton
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.TouchApp
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material.icons.rounded.MedicalServices
import androidx.compose.foundation.clickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import io.github.nugggy.pluslock.BuildConfig
import io.github.nugggy.pluslock.update.Release
import io.github.nugggy.pluslock.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.core.BoolPref
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Pause
import io.github.nugggy.pluslock.core.Status

private data class Perm(val title: String, val why: String, val granted: Boolean, val action: () -> Unit)

@Composable
fun HomeScreen(nav: Nav, resumeTick: Int) {
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun open(i: Intent) = runCatching { ctx.startActivity(i) }
    var disclose by remember { mutableStateOf<String?>(null) }
    ConsentDialog(nav)
    disclose?.let { kind ->
        DisclosureDialog(kind, onContinue = {
            disclose = null
            open(if (kind == "accessibility") Status.accessibilityIntent() else Status.notificationAccessIntent(ctx))
        }, onDismiss = { disclose = null })
    }

    val perms = remember(resumeTick) {
        listOf(
            Perm("Accessibility service", "Edge gestures, edge lighting, camera ring, app profiles", Status.accessibility(ctx)) {
                disclose = "accessibility"
            },
            Perm("Notification access", "Vault, edge lighting, haptic signatures, sound mode", Status.notificationAccess(ctx)) {
                disclose = "notifications"
            },
            Perm("Show notifications", "Update, charge guard and unsent alerts", Status.postNotifications(ctx)) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            },
            Perm("Modify system settings", "Per-app rotation and text size", Status.writeSettings(ctx)) {
                open(Status.writeSettingsIntent(ctx))
            },
            Perm("Install updates", "Lets Pimp my Phone update itself from GitHub", Status.installUpdates(ctx)) {
                open(Status.installIntent(ctx))
            },
            Perm("Run without battery limits", "Stops OxygenOS from closing Pimp my Phone in the background", Status.batteryUnrestricted(ctx)) {
                open(Status.batteryIntent(ctx))
            },
        )
    }
    val missing = perms.count { !it.granted }

    ScreenScaffold("Pimp my Phone", onBack = null, actions = {
        IconButton(onClick = { nav.go(Screen.Search) }) { Icon(Icons.Rounded.Search, "Search features") }
    }) {
        item {
            Card(
                shape = RoundedCornerShape(28.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHighest),
                modifier = Modifier.fillMaxWidth().clickable { nav.go(Screen.Search) },
            ) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp))
                    Text("Search features", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(
                "Good Lock style customisation for your OnePlus. No root needed.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        item { PauseCard() }
        if (missing > 0) item { SetupCard(perms, missing) { open(Status.appInfoIntent(ctx)) } }
        item { UpdateCard(nav) }

        item {
            Section("Touch and gestures") {
                Module(Icons.Rounded.Swipe, "Gestures", "Edge handles, back tap, shake for torch, status bar double-tap", null) { nav.go(Screen.Gestures) }
                Module(Icons.Rounded.Tune, "Control deck and tiles", "Volume mixer, torch brightness, caffeine, Quick Settings tiles", null) { nav.go(Screen.Deck) }
                Module(Icons.Rounded.ViewSidebar, "Side panel", "Favourite apps, people and toggles from the edge", null) { nav.go(Screen.Panel) }
                Module(Icons.Rounded.AutoMode, "Routines", "When something happens, do a few things for you", P.routinesOn) { nav.go(Screen.Routines) }
            }
        }
        item {
            Section("Light and colour") {
                Module(Icons.Rounded.WbIridescent, "Edge lighting", "A glow and a preview bubble you can tap, swipe or reply from", P.lightingOn) { nav.go(Screen.Lighting) }
                Module(Icons.Rounded.Lens, "Camera ring", "A battery ring around the selfie camera", P.ringOn) { nav.go(Screen.Ring) }
                Module(Icons.Rounded.BatteryChargingFull, "Charging effects", "Liquid, halo, lightning and more when you plug in, plus a glow that stays on", P.chargeAnimOn) { nav.go(Screen.Charging) }
                Module(Icons.Rounded.Wallpaper, "Living sky wallpaper", "A live wallpaper that follows the time of day and the weather", null) { nav.go(Screen.Wallpaper) }
                Module(Icons.Rounded.TouchApp, "Interactive wallpapers", "Swarms, ripples, marbles and more that react to your touch and tilt", null) { nav.go(Screen.PlayWallpaper) }
                Module(Icons.Rounded.Palette, "Look and feel", "Camera island, volume panel, extra dim, lock screen clock and more", null) { nav.go(Screen.Look) }
            }
        }
        item {
            Section("Notifications") {
                Module(Icons.Rounded.History, "Notification vault", "History you can search, and messages people unsend", P.vaultOn) { nav.go(Screen.Vault) }
                Module(Icons.Rounded.Vibration, "Haptics, keywords and quiet", "Feel who is messaging, keyword alerts, work-hours hold", null) { nav.go(Screen.Tricks) }
                Module(Icons.Rounded.PhonelinkRing, "Find my phone", "Text it a secret codeword and it rings, flashes and buzzes, even on silent", P.findOn) { nav.go(Screen.FindPhone) }
            }
        }
        item {
            Section("Apps and battery") {
                Module(Icons.Rounded.Apps, "App profiles", "Per-app volume, fingerprint lock, time limits, keyboard and more", P.profilesOn) { nav.go(Screen.Profiles) }
                Module(Icons.Rounded.BatteryChargingFull, "Battery", "Health, unplug nudge, bedtime reminder, time to full", null) { nav.go(Screen.Battery) }
                Module(Icons.Rounded.BarChart, "Screen time", "Time in each app, today and this week", null) { nav.go(Screen.ScreenTime) }
            }
        }
        item {
            Section("Pimp my Phone") {
                Module(Icons.Rounded.SystemUpdate, "Updates", "Install new versions from GitHub automatically", null) { nav.go(Screen.Updates) }
                Module(Icons.Rounded.Terminal, "ADB extras", "A one-off step that unlocks a few more features", null) { nav.go(Screen.Adb) }
                Module(Icons.Rounded.SettingsBackupRestore, "Back up and restore", "Save all your settings to a file", null) { nav.go(Screen.Backup) }
                Module(Icons.Rounded.MedicalServices, "Diagnostics", "Check what works on this phone and copy a report", null) { nav.go(Screen.Diagnostics) }
                Module(Icons.Rounded.Policy, "Legal and about", "Privacy policy, terms of use, licences and safety notes", null) { nav.go(Screen.Legal()) }
            }
        }
    }
}

/** The master switch: stops every feature, to rule the app in or out when the phone acts up. */
@Composable
private fun PauseCard() {
    val ctx = LocalContext.current
    val paused by P.allPaused.observe()
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (paused) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.PauseCircle, null, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (paused) "Everything is paused" else "Pause everything",
                    style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (paused) {
                        "Nothing is drawn on screen, no notifications are read, no settings are changed and nothing runs " +
                            "in the background. Your settings are kept. A live wallpaper from this app keeps running until you pick another wallpaper."
                    } else {
                        "Phone doing something odd? Switch every feature off at once to see if it's this app. There's a Quick Settings tile for it too."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(checked = paused, onCheckedChange = { Pause.set(ctx, it) })
        }
    }
}

@Composable
private fun SetupCard(perms: List<Perm>, missing: Int, openAppInfo: () -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Finish setting up", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                "$missing ${if (missing == 1) "permission" else "permissions"} still needed. Each one switches on a group of features.",
                style = MaterialTheme.typography.bodyMedium,
            )
            perms.forEach { p ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (p.granted) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked, null,
                        tint = if (p.granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(p.title, style = MaterialTheme.typography.bodyLarge)
                        Text(p.why, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!p.granted) FilledTonalButton(onClick = p.action) { Text("Allow") }
                }
            }
            Text(
                "If Android greys out the accessibility or notification switch, open App info, tap the three-dot menu and choose Allow restricted settings. Android does this for any app installed from outside the Play Store.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 6.dp),
            )
            TextButton(onClick = openAppInfo) { Text("Open App info") }
        }
    }
}

/** Version, a check button, and a way to the new version when there is one. */
@Composable
private fun UpdateCard(nav: Nav) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by P.updateStatus.observe()
    var busy by remember { mutableStateOf(false) }
    var newer by remember { mutableStateOf<Release?>(null) }
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(if (busy) "Checking..." else status.ifBlank { "Not checked yet" }, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.width(12.dp))
            val r = newer
            if (r != null) {
                Button(onClick = { nav.go(Screen.Updates) }) { Text("Get ${r.tag}") }
            } else {
                FilledTonalButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        val res = withContext(Dispatchers.IO) { runCatching { Updater.fetchLatest() } }
                        P.updateLastCheck.value = System.currentTimeMillis()
                        res.onSuccess {
                            newer = it?.takeIf { rel -> Updater.isNewer(rel) }
                            P.updateStatus.value = when {
                                it == null -> "No releases published yet"
                                Updater.isNewer(it) -> "${it.tag} available"
                                else -> "Up to date"
                            }
                        }.onFailure { P.updateStatus.value = "Couldn't reach GitHub: ${it.message}" }
                        busy = false
                        Toast.makeText(ctx, res.fold(
                            { if (newer != null) "Pimp my Phone ${newer?.tag} is ready to install" else "You have the latest version" },
                            { "Couldn't reach GitHub. Check your connection." },
                        ), Toast.LENGTH_SHORT).show()
                    }
                }) { Text("Check for updates") }
            }
        }
    }
}

@Composable
private fun Module(icon: ImageVector, title: String, subtitle: String, toggle: BoolPref?, onClick: () -> Unit) {
    Row2(title, subtitle, icon, onClick = onClick) {
        if (toggle != null) {
            val on by toggle.observe()
            Switch(checked = on, onCheckedChange = { toggle.value = it })
        } else {
            Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
