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
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Lens
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Swipe
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Wallpaper
import androidx.compose.material.icons.rounded.WbIridescent
import androidx.compose.material3.Card
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
import io.github.nugggy.pluslock.core.Status

private data class Perm(val title: String, val why: String, val granted: Boolean, val action: () -> Unit)

@Composable
fun HomeScreen(nav: Nav, resumeTick: Int) {
    val ctx = LocalContext.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun open(i: Intent) = runCatching { ctx.startActivity(i) }

    val perms = remember(resumeTick) {
        listOf(
            Perm("Accessibility service", "Edge gestures, edge lighting, camera ring, app profiles", Status.accessibility(ctx)) {
                open(Status.accessibilityIntent())
            },
            Perm("Notification access", "Vault, edge lighting, haptic signatures, sound mode", Status.notificationAccess(ctx)) {
                open(Status.notificationAccessIntent(ctx))
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

    ScreenScaffold("Pimp my Phone", onBack = null) {
        item {
            Text(
                "Good Lock style customisation for your OnePlus. No root needed.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (missing > 0) item { SetupCard(perms, missing) { open(Status.appInfoIntent(ctx)) } }

        item {
            Section("Touch and gestures") {
                Module(Icons.Rounded.Swipe, "Gestures", "Edge handles, back tap, shake for torch, status bar double-tap", null) { nav.go(Screen.Gestures) }
                Module(Icons.Rounded.Tune, "Control deck and tiles", "Volume mixer, torch brightness, caffeine, Quick Settings tiles", null) { nav.go(Screen.Deck) }
            }
        }
        item {
            Section("Light and colour") {
                Module(Icons.Rounded.WbIridescent, "Edge lighting", "A glow and a preview bubble you can tap, swipe or reply from", P.lightingOn) { nav.go(Screen.Lighting) }
                Module(Icons.Rounded.Lens, "Camera ring", "A battery ring around the selfie camera", P.ringOn) { nav.go(Screen.Ring) }
                Module(Icons.Rounded.Wallpaper, "Living sky wallpaper", "A live wallpaper that follows the time of day and the weather", null) { nav.go(Screen.Wallpaper) }
                Module(Icons.Rounded.Palette, "Look and feel", "Lock screen clock, network speed, status bar icons", null) { nav.go(Screen.Look) }
            }
        }
        item {
            Section("Notifications") {
                Module(Icons.Rounded.History, "Notification vault", "History you can search, and messages people unsend", P.vaultOn) { nav.go(Screen.Vault) }
                Module(Icons.Rounded.Vibration, "Haptics, keywords and quiet", "Feel who is messaging, keyword alerts, work-hours hold", null) { nav.go(Screen.Tricks) }
            }
        }
        item {
            Section("Apps and battery") {
                Module(Icons.Rounded.Apps, "App profiles", "Per-app volume, fingerprint lock, time limits, keyboard and more", P.profilesOn) { nav.go(Screen.Profiles) }
                Module(Icons.Rounded.BatteryChargingFull, "Charge guard", "Unplug nudge, bedtime reminder, time to full", P.chargeAlertOn) { nav.go(Screen.Battery) }
            }
        }
        item {
            Section("Pimp my Phone") {
                Module(Icons.Rounded.SystemUpdate, "Updates", "Install new versions from GitHub automatically", null) { nav.go(Screen.Updates) }
                Module(Icons.Rounded.Terminal, "ADB extras", "A one-off step that unlocks a few more features", null) { nav.go(Screen.Adb) }
            }
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
