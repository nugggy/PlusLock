package io.github.nugggy.pluslock.ui

import android.content.Intent
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Status
import io.github.nugggy.pluslock.data.NotificationStore
import io.github.nugggy.pluslock.data.SavedNotification
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun VaultScreen(nav: Nav) {
    val ctx = LocalContext.current
    val store = remember { NotificationStore.get(ctx) }
    var query by remember { mutableStateOf("") }
    var appFilter by remember { mutableStateOf<String?>(null) }
    var unsentOnly by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<SavedNotification>>(emptyList()) }
    var apps by remember { mutableStateOf<List<Pair<String, Int>>>(emptyList()) }
    var confirmClear by remember { mutableStateOf(false) }
    val retention by P.vaultRetentionDays.observe()

    LaunchedEffect(query, appFilter, unsentOnly, refresh) {
        delay(150)
        val (rows, appRows) = withContext(Dispatchers.IO) {
            store.query(query, appFilter, unsentOnly) to store.apps()
        }
        items = rows
        apps = appRows
    }

    ScreenScaffold("Notification vault", nav.back) {
        if (!Status.notificationAccess(ctx)) item {
            Note("The vault needs notification access. Allow it on the home screen and new notifications will start appearing here.")
        }
        item {
            Section("Settings") {
                PrefSwitch(P.vaultOn, "Save notifications", "Kept on this phone only, never uploaded")
                PrefSwitch(P.unsentAlert, "Tell me when a message is unsent", "When a chat app removes a message it already showed you")
                ChoiceRow(
                    "Keep for", mapOf("7" to "7 days", "30" to "30 days", "90" to "90 days", "365" to "1 year"),
                    retention.toString(),
                ) { P.vaultRetentionDays.value = it.toInt() }
                Row2("Clear the vault", "Deletes everything saved so far", onClick = { confirmClear = true })
            }
        }
        item {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Search messages, people and apps") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = RoundedCornerShape(28.dp),
            )
        }
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = unsentOnly, onClick = { unsentOnly = !unsentOnly }, label = { Text("Unsent only") })
                FilterChip(selected = appFilter == null, onClick = { appFilter = null }, label = { Text("All apps") })
                apps.take(12).forEach { (pkg, n) ->
                    FilterChip(
                        selected = appFilter == pkg,
                        onClick = { appFilter = if (appFilter == pkg) null else pkg },
                        leadingIcon = { AppIcon(pkg, 18) },
                        label = { Text("${Apps.label(ctx, pkg)} $n") },
                    )
                }
            }
        }
        if (items.isEmpty()) item {
            Note(if (query.isBlank() && appFilter == null && !unsentOnly) "Nothing saved yet." else "No matches.")
        }
        items(items, key = { it.id }) { n ->
            VaultItem(n) {
                ctx.packageManager.getLaunchIntentForPackage(n.pkg)?.let {
                    ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the vault?") },
            text = { Text("Every saved notification will be deleted from this phone. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    store.clear(); refresh++; confirmClear = false
                }) { Text("Clear") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun VaultItem(n: SavedNotification, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (n.unsent) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.Top) {
            AppIcon(n.pkg, 36)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.app, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                    Text(
                        DateUtils.getRelativeTimeSpanString(n.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString(),
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (n.title.isNotBlank()) Text(n.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (n.body.isNotBlank()) Text(n.body, style = MaterialTheme.typography.bodyMedium, maxLines = 6, overflow = TextOverflow.Ellipsis)
                if (n.unsent) AssistChip(onClick = {}, label = { Text("Unsent by sender") }, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}
