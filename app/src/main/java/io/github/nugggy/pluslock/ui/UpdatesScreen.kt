package io.github.nugggy.pluslock.ui

import android.content.Intent
import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.BuildConfig
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Status
import io.github.nugggy.pluslock.update.Release
import io.github.nugggy.pluslock.update.UpdateScheduler
import io.github.nugggy.pluslock.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun UpdatesScreen(nav: Nav, resumeTick: Int) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val status by P.updateStatus.observe()
    val lastCheck by P.updateLastCheck.observe()
    val interval by P.updateIntervalHours.observe()
    var release by remember { mutableStateOf<Release?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(-1f) }
    val canInstall = remember(resumeTick) { Status.installUpdates(ctx) }

    fun check() {
        busy = true
        scope.launch {
            val r = withContext(Dispatchers.IO) { runCatching { Updater.fetchLatest() } }
            P.updateLastCheck.value = System.currentTimeMillis()
            r.onSuccess {
                release = it
                P.updateStatus.value = when {
                    it == null -> "No releases published yet"
                    Updater.isNewer(it) -> "${it.tag} available"
                    else -> "Up to date"
                }
            }.onFailure { P.updateStatus.value = "Couldn't reach GitHub: ${it.message}" }
            busy = false
        }
    }

    fun install(r: Release) {
        busy = true
        progress = 0f
        scope.launch {
            val res = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = Updater.download(ctx, r) { progress = it }
                    Updater.install(ctx, apk)
                }
            }
            res.onSuccess { P.updateStatus.value = "Installing ${r.tag}" }
                .onFailure { P.updateStatus.value = "Update failed: ${it.message}" }
            progress = -1f
            busy = false
        }
    }

    ScreenScaffold("Updates", nav.back) {
        item {
            Card(
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("PlusLock ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Text(status.ifBlank { "Not checked yet" }, style = MaterialTheme.typography.bodyMedium)
                    if (lastCheck > 0) Text(
                        "Last checked " + DateUtils.getRelativeTimeSpanString(lastCheck),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (progress >= 0f) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 8.dp)) {
                        FilledTonalButton(onClick = { check() }, enabled = !busy) { Text("Check now") }
                        val r = release
                        if (r != null && Updater.isNewer(r)) {
                            Button(onClick = { install(r) }, enabled = !busy && canInstall) { Text("Install ${r.tag}") }
                        }
                    }
                }
            }
        }
        if (BuildConfig.DEBUG) item {
            Note("This is a debug build, so it can't replace itself with a release. It will still tell you about new versions.")
        }
        if (!canInstall) item {
            Section("Permission") {
                Row2("Allow PlusLock to install updates", "Needed once so it can apply updates for you", onClick = {
                    ctx.startActivity(Status.installIntent(ctx))
                })
            }
        }
        release?.let { r ->
            if (r.notes.isNotBlank()) item {
                Section("What's new in ${r.title}") {
                    Text(r.notes.trim(), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(18.dp))
                }
            }
        }
        item {
            Section("Update channel") {
                PrefChoice(P.updateChannel, "Channel", mapOf(
                    "stable" to "Stable: finished releases only",
                    "beta" to "Beta: try new features early",
                ))
                PrefSwitch(P.autoUpdate, "Install updates automatically", "Downloads and installs in the background")
                val wifi by P.updateWifiOnly.observe()
                SwitchRow("Only on Wi-Fi", null, wifi) {
                    P.updateWifiOnly.value = it
                    UpdateScheduler.schedule(ctx, replace = true)
                }
                ChoiceRow(
                    "Check every", linkedMapOf("1" to "Hour", "6" to "6 hours", "12" to "12 hours", "24" to "Day"),
                    interval.toString(),
                ) {
                    P.updateIntervalHours.value = it.toInt()
                    UpdateScheduler.schedule(ctx, replace = true)
                }
            }
        }
        item {
            Note("Android asks you to confirm the first update because PlusLock was installed from a file. After that, PlusLock is its own installer and updates go in quietly. Every download is checked against its published checksum and must carry the same signature as this app.")
        }
        item {
            TextButton(onClick = { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(Updater.repoUrl))) }) {
                Text("PlusLock on GitHub")
            }
        }
    }
}
