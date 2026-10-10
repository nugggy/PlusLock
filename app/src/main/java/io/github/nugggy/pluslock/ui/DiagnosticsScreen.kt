package io.github.nugggy.pluslock.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.PowerManager
import android.view.View
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.nugggy.pluslock.BuildConfig
import io.github.nugggy.pluslock.core.AppAudio
import io.github.nugggy.pluslock.core.Diag
import io.github.nugggy.pluslock.core.FindPhone
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Status
import io.github.nugggy.pluslock.core.SystemSettings
import io.github.nugggy.pluslock.core.Torch
import io.github.nugggy.pluslock.core.Waker
import io.github.nugggy.pluslock.service.LightAlert
import io.github.nugggy.pluslock.service.PlusAccessibilityService

/** One automatic check: null means it couldn't be worked out. */
private data class Check(val name: String, val ok: Boolean?, val detail: String)

private fun checks(ctx: Context): List<Check> {
    val svc = PlusAccessibilityService.instance
    fun perm(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    val sm = ctx.getSystemService(SensorManager::class.java)
    val out = mutableListOf(
        Check("Accessibility service", svc != null, if (svc != null) "Running" else "Off, so overlays, gestures, lighting and the volume panel can't work"),
        Check("Notification access", Status.notificationAccess(ctx), "Needed for lighting, the vault, keywords and find my phone"),
        Check("Show notifications", Status.postNotifications(ctx), "For update and charge alerts"),
        Check("No battery limits", Status.batteryUnrestricted(ctx), "Without it OxygenOS may stop the app in the background"),
        Check("Do Not Disturb access", Status.dndAccess(ctx), "For find my phone, routines and app profiles"),
        Check("Modify system settings", Status.writeSettings(ctx), "For brightness, rotation and text size"),
        Check("ADB extras", SystemSettings.hasSecure(ctx), "For status bar icons, keyboards and one-handed mode"),
        Check("Exact alarms", ctx.getSystemService(AlarmManager::class.java).canScheduleExactAlarms(), "For routines at a set time and the digest"),
        Check("Install updates", Status.installUpdates(ctx), "For updating itself"),
        Check("Nearby devices", perm(Manifest.permission.BLUETOOTH_CONNECT), "Only needed for Bluetooth routines"),
        Check("Location", perm(Manifest.permission.ACCESS_COARSE_LOCATION), "Only needed for real weather on the wallpaper"),
    )
    // Can the app draw over the screen?
    out += if (svc == null) Check("Drawing over the screen", null, "Turn on the accessibility service to test")
    else {
        val v = View(svc)
        val added = svc.addOverlay(v, svc.overlayParams(1, 1, touchable = false))
        svc.removeOverlay(v)
        Check("Drawing over the screen", added, if (added) "Works" else "Android refused the overlay")
    }
    out += Check("Torch", Torch.maxLevel(ctx) > 0, if (Torch.maxLevel(ctx) > 1) "Works, with ${Torch.maxLevel(ctx)} brightness steps" else "Works")
    out += Check("Proximity sensor", sm.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null, "For the in-a-pocket check")
    out += Check("Motion sensor", sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null, "For back tap, shake, flip and tilting wallpapers")
    val loud = runCatching { LoudnessEnhancer(0).release(); true }.getOrDefault(false)
    out += Check("Volume boost for the whole phone", loud, if (loud) "Accepted" else "Refused, so boost only reaches apps that share their sound")
    val dyn = runCatching { DynamicsProcessing(0, 0, null).release(); true }.getOrDefault(false)
    out += Check("Audio effects", dyn, if (dyn) "Available" else "Not available, so per-app volume may not work")
    val media = runCatching { AppAudio.playing(ctx).size }.getOrNull()
    out += Check("Reading what's playing", media != null, if (media != null) "$media app(s) with music or video" else "Needs notification access")
    out += Check("Screen", ctx.getSystemService(PowerManager::class.java).isInteractive, "On")
    return out
}

private fun report(ctx: Context, list: List<Check>): String = buildString {
    appendLine("Pimp my Phone ${BuildConfig.VERSION_NAME}")
    appendLine("${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}")
    appendLine()
    list.forEach { appendLine((when (it.ok) { true -> "OK   "; false -> "NO   "; null -> "?    " }) + it.name + ": " + it.detail) }
    appendLine()
    appendLine("Features on: " + listOfNotNull(
        "edge lighting".takeIf { P.lightingOn.value }, "lock screen glow".takeIf { P.lightingWake.value },
        "camera ring".takeIf { P.ringOn.value }, "charging animation".takeIf { P.chargeAnimOn.value },
        "volume panel".takeIf { P.volPanelOn.value }, "volume boost".takeIf { P.boostOn.value },
        "island".takeIf { P.islandOn.value }, "lock clock".takeIf { P.lockClockOn.value },
        "find my phone".takeIf { P.findOn.value }, "keywords".takeIf { P.keywordsOn.value },
    ).joinToString(", ").ifEmpty { "none" })
    appendLine()
    appendLine("Recent events (newest last):")
    Diag.all().takeLast(120).forEach { appendLine(Diag.format(it)) }
}

@Composable
fun DiagnosticsScreen(nav: Nav) {
    val ctx = LocalContext.current
    var run by remember { mutableIntStateOf(0) }
    val list = remember(run) { checks(ctx) }
    // Refresh the event log every couple of seconds while the screen is open.
    val tick by produceState(0) { while (true) { kotlinx.coroutines.delay(2_000); value++ } }
    val events = remember(tick, run) { Diag.all().takeLast(60).reversed() }
    val svc = PlusAccessibilityService.instance

    ScreenScaffold("Diagnostics", nav.back) {
        item {
            Note("Checks what works on this phone. Run a test below, then copy the report and send it to whoever is fixing the app. " +
                "The report has no messages or personal details, just what worked and what didn't.")
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { run++ }, modifier = Modifier.weight(1f)) { Text("Check again") }
                FilledTonalButton(onClick = {
                    val text = report(ctx, checks(ctx))
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Pimp my Phone report", text))
                    io.github.nugggy.pluslock.core.Actions.toast(ctx, "Report copied")
                }, modifier = Modifier.weight(1f)) { Text("Copy report") }
                FilledTonalButton(onClick = {
                    val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report(ctx, checks(ctx)))
                    runCatching { ctx.startActivity(Intent.createChooser(i, "Share the report")) }
                }, modifier = Modifier.weight(1f)) { Text("Share") }
            }
        }
        item {
            Section("This phone") {
                Row2("${Build.MANUFACTURER} ${Build.MODEL}", "Android ${Build.VERSION.RELEASE}, ${Build.DISPLAY}")
                Row2("Pimp my Phone ${BuildConfig.VERSION_NAME}", null)
            }
        }
        item {
            Section("Checks") {
                list.forEach { c ->
                    Row2(c.name, c.detail, when (c.ok) {
                        true -> Icons.Rounded.CheckCircle; false -> Icons.Rounded.Error; null -> Icons.Rounded.HelpOutline
                    })
                }
            }
        }
        item {
            Section("Try each feature") {
                Note("Each test writes what happened to the event log below.")
                @Composable fun test(name: String, desc: String, go: () -> Unit) =
                    Row2(name, desc, onClick = { if (!needsService(ctx)) { Diag.log("test", name); go() } })
                test("Edge lighting", "A glow and bubble now") {
                    svc?.lighting?.show(LightAlert(0xFF7FB2FF.toInt(), null, "Diagnostics", "Edge lighting test", key = "diag"))
                }
                test("Lock screen glow", "Locks the phone, then wakes it in 3 seconds and glows") {
                    svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    svc?.handler?.postDelayed({
                        svc.lighting.show(LightAlert(0xFF4BE38A.toInt(), null, "Diagnostics", "Woke the screen", wake = true, locked = true))
                    }, 3_000)
                }
                test("Wake the screen", "Locks the phone and tries to turn the screen back on") {
                    svc?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                    svc?.handler?.postDelayed({ Waker.wake(svc, 5_000, skipInPocket = false) { Diag.log("test", "wake finished") } }, 3_000)
                }
                test("Volume panel", "Shows the panel. Tap the dots on it to test them") { svc?.volumePanel?.preview() }
                test("Charging animation", "Shows your chosen style") { svc?.chargeFx?.preview() }
                test("Camera ring flash", "Flashes the ring pink") { svc?.ring?.flash(0xFFFF6BE0.toInt(), 2_500) }
                test("Find my phone", "Rings for 12 seconds. Hold Stop to end it sooner") { FindPhone.start(ctx, "a diagnostics test", test = true) }
                test("Side panel", "Slides the panel in") { svc?.panel?.show() }
            }
        }
        item {
            Section("Event log") {
                if (events.isEmpty()) Row2("Nothing yet", "Use the app or run a test and events appear here")
                events.forEach { e ->
                    Text(Diag.format(e), fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 18.dp, vertical = 3.dp))
                }
                if (events.isNotEmpty()) Row2("Clear the log", null, onClick = { Diag.clear(); run++ })
            }
        }
        item { Note("The event log lives in memory only and is cleared when the app restarts.") }
    }
}
