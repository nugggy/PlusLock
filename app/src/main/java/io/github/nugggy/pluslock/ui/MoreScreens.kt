package io.github.nugggy.pluslock.ui

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.provider.ContactsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.Backup
import io.github.nugggy.pluslock.core.GestureAction
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Status
import io.github.nugggy.pluslock.data.Routine
import io.github.nugggy.pluslock.data.RoutineAction
import io.github.nugggy.pluslock.data.RoutineActionType
import io.github.nugggy.pluslock.data.Routines
import io.github.nugggy.pluslock.data.Trigger
import io.github.nugggy.pluslock.data.ConditionType
import io.github.nugggy.pluslock.core.Haptics
import androidx.compose.runtime.LaunchedEffect
import io.github.nugggy.pluslock.data.RoutineCondition
import io.github.nugggy.pluslock.data.TriggerType
import io.github.nugggy.pluslock.data.UsageStore
import io.github.nugggy.pluslock.service.AppGuard
import io.github.nugggy.pluslock.service.PanelContact
import io.github.nugggy.pluslock.service.PanelContacts
import io.github.nugggy.pluslock.service.PlusAccessibilityService
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ---------------------------------------------------------------- Routines

@Composable
fun RoutinesScreen(nav: Nav) {
    val ctx = LocalContext.current
    val json by P.routinesJson.observe()
    val list = remember(json) { Routines.all() }
    ScreenScaffold("Routines", nav.back) {
        item {
            Section("Routines") {
                PrefSwitch(P.routinesOn, "Routines", "When something happens, do a few things for you")
            }
        }
        item {
            Button(onClick = {
                val r = Routine(name = "New routine")
                Routines.save(r)
                nav.go(Screen.RoutineEdit(r.id))
            }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, null); Spacer(Modifier.width(8.dp)); Text("New routine")
            }
        }
        if (list.isNotEmpty()) item {
            Section("Your routines") {
                list.forEach { r ->
                    Row2(r.name.ifBlank { "Routine" }, Routines.describe(ctx, r.trigger) + " · " +
                        (if (r.actions.isEmpty()) "no actions yet" else "${r.actions.size} action${if (r.actions.size == 1) "" else "s"}"),
                        onClick = { nav.go(Screen.RoutineEdit(r.id)) }) {
                        Switch(checked = r.on, onCheckedChange = { Routines.save(r.copy(on = it)) })
                    }
                }
            }
        } else item {
            Note("Try one: when the car's Bluetooth connects, open Maps and set media volume to 80%. Or at 22:00 on weekdays, turn on Do Not Disturb and extra dim.")
        }
    }
}

@Composable
fun RoutineEditScreen(nav: Nav, id: String) {
    val ctx = LocalContext.current
    val json by P.routinesJson.observe()
    // Gone once deleted; the Delete button already went back.
    val r = remember(json) { Routines.get(id) } ?: return
    fun save(n: Routine) = Routines.save(n)
    var naming by remember { mutableStateOf(false) }
    var pickTrigger by remember { mutableStateOf(false) }
    var addAction by remember { mutableStateOf(false) }
    var argFor by remember { mutableStateOf<RoutineActionType?>(null) }
    var pickTriggerApp by remember { mutableStateOf(false) }
    var addCondition by remember { mutableStateOf(false) }
    var condArgFor by remember { mutableStateOf<ConditionType?>(null) }

    ScreenScaffold(r.name.ifBlank { "Routine" }, nav.back) {
        item {
            Section("Routine") {
                Row2("Name", r.name.ifBlank { "Routine" }, onClick = { naming = true })
                SwitchRow("On", null, r.on) { save(r.copy(on = it)) }
            }
        }
        item {
            Section("When") {
                Row2(r.trigger.type.label, Routines.describe(ctx, r.trigger), onClick = { pickTrigger = true })
                TriggerDetail(r, ::save) { pickTriggerApp = true }
            }
        }
        item {
            Section("Only if") {
                if (r.conditions.isEmpty()) Row2("Always", "Add a condition to narrow it down")
                r.conditions.forEachIndexed { i, c ->
                    Row2(Routines.describe(c), null) {
                        IconButton(onClick = { save(r.copy(conditions = r.conditions.filterIndexed { j, _ -> j != i })) }) {
                            Icon(Icons.Rounded.Close, "Remove")
                        }
                    }
                }
                Row2("Add a condition", null, Icons.Rounded.Add, onClick = { addCondition = true })
            }
        }
        item {
            Section("Do this") {
                r.actions.forEachIndexed { i, a ->
                    Row2(Routines.describe(ctx, a), null) {
                        IconButton(onClick = { save(r.copy(actions = r.actions.filterIndexed { j, _ -> j != i })) }) {
                            Icon(Icons.Rounded.Close, "Remove")
                        }
                    }
                }
                Row2("Add an action", null, Icons.Rounded.Add, onClick = { addAction = true })
                if (r.actions.size > 1) Note("Actions run from top to bottom. Add a Wait to space them out.")
            }
        }
        if (r.actions.any { it.type == RoutineActionType.BRIGHTNESS } && !Status.writeSettings(ctx)) item {
            Note("Brightness needs Modify system settings, which you can allow on the home screen.")
        }
        if (r.actions.any { it.type == RoutineActionType.DND } && !Status.dndAccess(ctx)) item {
            Row2("Allow Do Not Disturb access", "Needed for the Do Not Disturb action", onClick = { runCatching { ctx.startActivity(Status.dndAccessIntent()) } })
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = {
                    val svc = PlusAccessibilityService.instance
                    if (svc == null) Toast.makeText(ctx, "Turn on the Pimp my Phone accessibility service first", Toast.LENGTH_SHORT).show()
                    else svc.routines.run(r)
                }, modifier = Modifier.weight(1f)) { Text("Try it now") }
                OutlinedButton(onClick = { Routines.remove(r.id); nav.back() }, modifier = Modifier.weight(1f)) { Text("Delete") }
            }
        }
    }

    if (naming) {
        var text by remember { mutableStateOf(r.name) }
        AlertDialog(
            onDismissRequest = { naming = false },
            title = { Text("Name") },
            text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { save(r.copy(name = text.trim())); naming = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { naming = false }) { Text("Cancel") } },
        )
    }
    if (pickTrigger) ChoiceDialog("When", TriggerType.entries.associate { it.name to it.label }, r.trigger.type.name, { pickTrigger = false }) {
        pickTrigger = false
        val t = TriggerType.valueOf(it)
        save(r.copy(trigger = Trigger(t, when (t) {
            TriggerType.TIME -> "07:00"
            TriggerType.BATTERY_BELOW -> "20"
            TriggerType.BATTERY_ABOVE -> "80"
            else -> ""
        })))
        if (t == TriggerType.APP_OPEN) pickTriggerApp = true
    }
    if (pickTriggerApp) AppPickerDialog(onDismiss = { pickTriggerApp = false }) {
        pickTriggerApp = false
        save(r.copy(trigger = Trigger(r.trigger.type, it.pkg)))
    }
    if (addCondition) ChoiceDialog("Only if", ConditionType.entries.associate { it.name to it.label }, "", { addCondition = false }) {
        addCondition = false
        val t = ConditionType.valueOf(it)
        if (t == ConditionType.TIME_BETWEEN || t == ConditionType.BATTERY_BELOW) condArgFor = t
        else save(r.copy(conditions = r.conditions + RoutineCondition(t)))
    }
    condArgFor?.let { t ->
        ConditionArgDialog(t, onDismiss = { condArgFor = null }) { arg ->
            condArgFor = null
            save(r.copy(conditions = r.conditions + RoutineCondition(t, arg)))
        }
    }
    if (addAction) ChoiceDialog("Add an action", RoutineActionType.entries.associate { it.name to it.label }, "", { addAction = false }) {
        addAction = false
        argFor = RoutineActionType.valueOf(it)
    }
    argFor?.let { type ->
        ActionArgDialog(type, onDismiss = { argFor = null }) { arg ->
            argFor = null
            save(r.copy(actions = r.actions + RoutineAction(type, arg)))
        }
    }
}

@Composable
private fun TriggerDetail(r: Routine, save: (Routine) -> Unit, pickApp: () -> Unit) {
    val ctx = LocalContext.current
    val t = r.trigger
    when (t.type) {
        TriggerType.APP_OPEN -> Row2("App", if (t.arg.isBlank()) "Choose one" else Apps.label(ctx, t.arg), onClick = pickApp)
        TriggerType.NOTIFICATION -> {
            Row2("App", if (t.arg.isBlank()) "Any app" else Apps.label(ctx, t.arg), onClick = pickApp)
            if (t.arg.isNotBlank()) Row2("Any app instead", null, onClick = { save(r.copy(trigger = t.copy(arg = ""))) })
        }
        TriggerType.BATTERY_BELOW, TriggerType.BATTERY_ABOVE ->
            SliderRow("Level", t.arg.toIntOrNull() ?: 20, 5..100, { "$it%" }) { save(r.copy(trigger = t.copy(arg = it.toString()))) }
        TriggerType.BT_ON, TriggerType.BT_OFF -> BluetoothRow(t.arg) { save(r.copy(trigger = t.copy(arg = it))) }
        TriggerType.TIME -> {
            val (time, weekdays) = Routines.timeArg(t.arg)
            val h = time.substringBefore(':').toIntOrNull() ?: 7
            val m = time.substringAfter(':').toIntOrNull() ?: 0
            fun set(nh: Int, nm: Int, wd: Boolean) = save(r.copy(trigger = t.copy(arg = "%02d:%02d".format(nh, nm) + if (wd) ",W" else "")))
            ChoiceRow("Hour", (0..23).associate { it.toString() to "%02d".format(it) }, h.toString()) { set(it.toInt(), m, weekdays) }
            ChoiceRow("Minute", (0..55 step 5).associate { it.toString() to ":%02d".format(it) }, m.toString()) { set(h, it.toInt(), weekdays) }
            SwitchRow("Weekdays only", null, weekdays) { set(h, m, it) }
        }
        else -> Unit
    }
}

@Composable
private fun BluetoothRow(selected: String, onPick: (String) -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var picking by remember { mutableStateOf(false) }
    if (!granted) {
        Row2("Allow Nearby devices", "So routines can tell which Bluetooth device connected", onClick = { launcher.launch(Manifest.permission.BLUETOOTH_CONNECT) })
        return
    }
    Row2("Device", selected.ifBlank { "Any device" }, onClick = { picking = true })
    if (picking) {
        @Suppress("MissingPermission")
        val names = remember {
            runCatching { ctx.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices?.mapNotNull { it.name } }
                .getOrNull().orEmpty().distinct().sorted()
        }
        ChoiceDialog("Device", linkedMapOf("" to "Any device") + names.associateWith { it }, selected, { picking = false }) {
            picking = false; onPick(it)
        }
    }
}

@Composable
private fun ActionArgDialog(type: RoutineActionType, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    when (type) {
        RoutineActionType.OPEN_APP -> AppPickerDialog(onDismiss = onDismiss) { onDone(it.pkg) }
        RoutineActionType.MEDIA_VOLUME, RoutineActionType.BRIGHTNESS, RoutineActionType.RING_VOLUME,
        RoutineActionType.NOTIFICATION_VOLUME, RoutineActionType.ALARM_VOLUME -> {
            var v by remember { mutableFloatStateOf(50f) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(type.label) },
                text = { SliderRow("Level", v.toInt(), 0..100, { "$it%" }) { v = it.toFloat() } },
                confirmButton = { TextButton(onClick = { onDone(v.toInt().toString()) }) { Text("Add") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        RoutineActionType.SOUND_MODE -> ChoiceDialog(type.label, linkedMapOf("RING" to "Ring", "VIBRATE" to "Vibrate", "SILENT" to "Silent"), "", onDismiss, onDone)
        RoutineActionType.DND, RoutineActionType.TORCH, RoutineActionType.CAFFEINE, RoutineActionType.VOLUME_BOOST,
        RoutineActionType.EDGE_LIGHTING, RoutineActionType.EXTRA_DIM, RoutineActionType.ROTATION ->
            ChoiceDialog(type.label, linkedMapOf("ON" to "On", "OFF" to "Off"), "", onDismiss, onDone)
        RoutineActionType.PAUSE_LIGHTING -> ChoiceDialog(type.label,
            linkedMapOf("30" to "30 minutes", "60" to "1 hour", "120" to "2 hours", "480" to "8 hours", "0" to "Resume instead"), "", onDismiss, onDone)
        RoutineActionType.GESTURE -> ChoiceDialog(type.label,
            GestureAction.entries.filter { it != GestureAction.NONE && it != GestureAction.LAUNCH_APP }.associate { it.name to it.label }, "", onDismiss, onDone)
        RoutineActionType.VIBRATE -> ChoiceDialog(type.label, Haptics.presets.filterKeys { it != "NONE" }, "", onDismiss, onDone)
        RoutineActionType.BLINK_TORCH -> LaunchedEffect(Unit) { onDone("") }
        RoutineActionType.WAIT -> ChoiceDialog(type.label,
            linkedMapOf("2" to "2 seconds", "5" to "5 seconds", "10" to "10 seconds", "30" to "30 seconds", "60" to "1 minute", "300" to "5 minutes", "900" to "15 minutes"), "", onDismiss, onDone)
        RoutineActionType.MESSAGE -> {
            var text by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(type.label) },
                text = { OutlinedTextField(text, { text = it }, label = { Text("Message") }, modifier = Modifier.fillMaxWidth()) },
                confirmButton = { TextButton(onClick = { onDone(text.trim()) }) { Text("Add") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
    }
}

@Composable
private fun ConditionArgDialog(type: ConditionType, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    when (type) {
        ConditionType.TIME_BETWEEN -> {
            var from by remember { mutableFloatStateOf(22f) }
            var to by remember { mutableFloatStateOf(7f) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(type.label) },
                text = {
                    Column {
                        SliderRow("From", from.toInt(), 0..23, { "%02d:00".format(it) }) { from = it.toFloat() }
                        SliderRow("Until", to.toInt(), 0..23, { "%02d:00".format(it) }) { to = it.toFloat() }
                    }
                },
                confirmButton = { TextButton(onClick = { onDone("${from.toInt()}-${to.toInt()}") }) { Text("Add") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
        else -> {
            var v by remember { mutableFloatStateOf(30f) }
            AlertDialog(
                onDismissRequest = onDismiss,
                title = { Text(type.label) },
                text = { SliderRow("Below", v.toInt(), 5..100, { "$it%" }) { v = it.toFloat() } },
                confirmButton = { TextButton(onClick = { onDone(v.toInt().toString()) }) { Text("Add") } },
                dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
            )
        }
    }
}

// ---------------------------------------------------------------- Side panel

@Composable
fun PanelScreen(nav: Nav) {
    val ctx = LocalContext.current
    val appsCsv by P.panelApps.observe()
    val contactsJson by P.panelContacts.observe()
    val apps = remember(appsCsv) { appsCsv.split(',').filter { it.isNotBlank() } }
    val people = remember(contactsJson) { PanelContacts.all() }
    var pickingApp by remember { mutableStateOf(false) }
    val pickContact = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val uri = res.data?.data ?: return@rememberLauncherForActivityResult
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0).orEmpty(); val number = c.getString(1).orEmpty()
                    if (number.isNotBlank()) PanelContacts.save(people + PanelContact(name.ifBlank { number }, number))
                }
            }
        }
    }
    ScreenScaffold("Side panel", nav.back) {
        item {
            Section("Side panel") {
                Row2("Open it from an edge gesture", "Set any edge swipe or back tap to Side panel, Calculator or Quick note", onClick = { nav.go(Screen.Gestures) })
                PrefChoice(P.panelSide, "Slides in from", mapOf("RIGHT" to "The right", "LEFT" to "The left"))
            }
        }
        item {
            val sections by P.panelSections.observe()
            val on = sections.split(',').filter { it.isNotBlank() }
            Section("What's in the panel") {
                io.github.nugggy.pluslock.service.panelSections.forEach { (key, label) ->
                    SwitchRow(label, null, key in on) { want ->
                        P.panelSections.value = io.github.nugggy.pluslock.service.panelSections.keys.filter { k -> if (k == key) want else k in on }.joinToString(",")
                    }
                }
            }
        }
        item {
            val toggles by P.panelToggles.observe()
            val on = toggles.split(',').filter { it.isNotBlank() }
            Section("Quick toggles") {
                io.github.nugggy.pluslock.service.panelToggles.forEach { (key, label) ->
                    SwitchRow(label, null, key in on) { want ->
                        P.panelToggles.value = io.github.nugggy.pluslock.service.panelToggles.keys.filter { k -> if (k == key) want else k in on }.joinToString(",")
                    }
                }
            }
        }
        item {
            val colour by P.panelColour.observe()
            Section("Look") {
                PrefChoice(P.panelStyle, "Style", linkedMapOf("DARK" to "Dark", "BLACK" to "Pure black", "GLASS" to "Glass: see-through"))
                ColourRow("Accent colour", colour) { P.panelColour.value = it }
                PrefSlider(P.panelWidth, "Width", 60..95) { "$it% of the screen" }
            }
        }
        item {
            Section("Apps") {
                apps.forEach { pkg ->
                    Row2(Apps.label(ctx, pkg), null) {
                        IconButton(onClick = { P.panelApps.value = (apps - pkg).joinToString(",") }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                }
                Row2("Add an app", null, Icons.Rounded.Add, onClick = { pickingApp = true })
            }
        }
        item {
            Section("People") {
                people.forEachIndexed { i, p ->
                    Row2(p.name, p.number) {
                        IconButton(onClick = { PanelContacts.save(people.filterIndexed { j, _ -> j != i }) }) { Icon(Icons.Rounded.Close, "Remove") }
                    }
                }
                Row2("Add a person", "Tap them in the panel to call, hold to message", Icons.Rounded.PersonAdd, onClick = {
                    runCatching {
                        pickContact.launch(android.content.Intent(android.content.Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI))
                    }
                })
            }
        }
        item {
            Button(onClick = { PlusAccessibilityService.instance?.panel?.show() ?: Toast.makeText(ctx, "Turn on the Pimp my Phone accessibility service first", Toast.LENGTH_SHORT).show() },
                modifier = Modifier.fillMaxWidth()) { Text("Show the panel") }
        }
        item { Note("Pimp my Phone only keeps the name and number of the people you add. It doesn't read your other contacts.") }
    }
    if (pickingApp) AppPickerDialog(onDismiss = { pickingApp = false }) {
        pickingApp = false
        if (it.pkg !in apps) P.panelApps.value = (apps + it.pkg).joinToString(",")
    }
}

// ---------------------------------------------------------------- Screen time

@Composable
fun ScreenTimeScreen(nav: Nav, resumeTick: Int) {
    val ctx = LocalContext.current
    val days = remember(resumeTick) {
        AppGuard.instance?.flushNow()
        (6 downTo 0).map { n -> UsageStore.label(n) to UsageStore.dayTotals(n) }
    }
    var selected by remember { mutableIntStateOf(6) }
    val home = remember { homePackages(ctx) }
    fun clean(m: Map<String, Long>) = m.filterKeys { it !in home }
    val totals = days.map { (_, m) -> clean(m).values.sum() }
    val dayApps = clean(days[selected].second).toList().sortedByDescending { it.second }.filter { it.second >= 60_000 }
    val max = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
    val primary = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant

    ScreenScaffold("Screen time", nav.back) {
        item {
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(18.dp)) {
                    Text(if (selected == 6) "Today" else days[selected].first, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(duration(totals[selected]), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(14.dp))
                    Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        totals.forEachIndexed { i, t ->
                            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                                Canvas(Modifier.fillMaxWidth().height(116.dp).padding(horizontal = 4.dp)) {
                                    val h = size.height * (t.toFloat() / max)
                                    drawRoundRect(track, size = size, cornerRadius = CornerRadius(8f, 8f))
                                    drawRoundRect(if (i == selected) primary else primary.copy(alpha = 0.45f), topLeft = Offset(0f, size.height - h),
                                        size = Size(size.width, h), cornerRadius = CornerRadius(8f, 8f))
                                }
                                TextButton(onClick = { selected = i }, contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                                    Text(if (i == 6) "Now" else days[i].first.take(3), style = MaterialTheme.typography.labelSmall)
                                }
                            }
                        }
                    }
                }
            }
        }
        item {
            Section("Apps") {
                if (dayApps.isEmpty()) Row2("Nothing over a minute yet", null)
                val top = dayApps.firstOrNull()?.second ?: 1L
                dayApps.take(25).forEach { (pkg, ms) ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        AppIcon(pkg, 32)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row { Text(Apps.label(ctx, pkg), Modifier.weight(1f)); Text(duration(ms), style = MaterialTheme.typography.labelLarge) }
                            LinearProgressIndicator(progress = { ms.toFloat() / top }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                        }
                    }
                }
            }
        }
        item { Note("Counted by Pimp my Phone while the screen is on and unlocked, from when the accessibility service was switched on. It keeps two weeks, on this phone only. Set daily limits in App profiles.") }
    }
}

private fun homePackages(ctx: android.content.Context): Set<String> {
    val i = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME)
    return ctx.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
}

private fun duration(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

// ---------------------------------------------------------------- Backup

@Composable
fun BackupScreen(nav: Nav) {
    val ctx = LocalContext.current
    var confirm by remember { mutableStateOf<android.net.Uri?>(null) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val msg = runCatching { "Saved ${Backup.export(ctx, uri)} settings" }.getOrElse { "Couldn't save: ${it.message}" }
        Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
    }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) confirm = uri }
    ScreenScaffold("Back up and restore", nav.back) {
        item {
            Section("Your settings") {
                Row2("Back up to a file", "Every setting, app profile, routine, person and panel item", onClick = {
                    save.launch("pimp-my-phone-" + SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date()) + ".json")
                })
                Row2("Restore from a file", "Replaces matching settings with the ones in the file", onClick = {
                    open.launch(arrayOf("application/json", "text/plain", "application/octet-stream"))
                })
            }
        }
        item { Note("Notification history, screen time and battery logs stay on this phone and aren't in the file. Permissions aren't either, so after restoring on a new phone, work through the setup card again. Save the file somewhere private, such as your own Google Drive.") }
    }
    confirm?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restore these settings?") },
            text = { Text("Your current settings will be replaced by the ones in the file.") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = null
                    val msg = runCatching { "Restored ${Backup.import(ctx, uri)} settings" }.getOrElse { it.message ?: "Couldn't restore" }
                    Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
                }) { Text("Restore") }
            },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } },
        )
    }
}

