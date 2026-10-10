package io.github.nugggy.pluslock.ui

import android.content.SharedPreferences
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Android
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.core.AppEntry
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.BoolPref
import io.github.nugggy.pluslock.core.IntPref
import io.github.nugggy.pluslock.core.Pref
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.core.StringPref
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun PlusTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** Reads a setting and recomposes when it changes anywhere in the app. */
@Composable
fun <T> Pref<T>.observe(): State<T> {
    val state = remember(key) { mutableStateOf(value) }
    DisposableEffect(key) {
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, k -> if (k == key) state.value = value }
        Prefs.sp.registerOnSharedPreferenceChangeListener(l)
        onDispose { Prefs.sp.unregisterOnSharedPreferenceChangeListener(l) }
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenScaffold(title: String, onBack: (() -> Unit)?, content: LazyListScope.() -> Unit) {
    val scroll = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = {
                    if (onBack != null) IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back")
                    }
                },
                scrollBehavior = scroll,
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding(), bottom = pad.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
            content = content,
        )
    }
}

@Composable
fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 8.dp, top = 10.dp, bottom = 6.dp),
        )
        Card(
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        ) { Column(Modifier.padding(vertical = 6.dp)) { content() } }
    }
}

@Composable
fun Note(text: String) {
    Text(
        text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
fun Row2(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (!subtitle.isNullOrBlank()) Text(
                subtitle, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(12.dp))
            trailing()
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    Row2(title, subtitle, icon, onClick = { onChange(!checked) }) {
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun PrefSwitch(pref: BoolPref, title: String, subtitle: String? = null, icon: ImageVector? = null) {
    val v by pref.observe()
    SwitchRow(title, subtitle, v, icon) { pref.value = it }
}

@Composable
fun SliderRow(
    title: String,
    value: Int,
    range: IntRange,
    format: (Int) -> String = { it.toString() },
    onChange: (Int) -> Unit,
) {
    var local by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(format(local.roundToInt()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Slider(
            value = local,
            onValueChange = { local = it },
            onValueChangeFinished = { onChange(local.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
fun PrefSlider(pref: IntPref, title: String, range: IntRange, format: (Int) -> String = { it.toString() }) {
    val v by pref.observe()
    SliderRow(title, v, range, format) { pref.value = it }
}

@Composable
fun ChoiceRow(title: String, options: Map<String, String>, selected: String, icon: ImageVector? = null, onSelect: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row2(title, options[selected] ?: selected, icon, onClick = { open = true })
    if (open) ChoiceDialog(title, options, selected, { open = false }) { onSelect(it); open = false }
}

@Composable
fun PrefChoice(pref: StringPref, title: String, options: Map<String, String>, icon: ImageVector? = null) {
    val v by pref.observe()
    ChoiceRow(title, options, v, icon) { pref.value = it }
}

@Composable
fun ChoiceDialog(title: String, options: Map<String, String>, selected: String, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { (k, label) ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onSelect(k) }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = k == selected, onClick = { onSelect(k) })
                        Text(label)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

val swatches = listOf(
    0xFF7FB2FF.toInt(), 0xFF4BE38A.toInt(), 0xFFFF6BE0.toInt(), 0xFFFFC75E.toInt(),
    0xFFFF5E5E.toInt(), 0xFF5EE4FF.toInt(), 0xFFB388FF.toInt(), 0xFFFFFFFF.toInt(),
)

@Composable
fun ColourRow(title: String, selected: Int, allowAuto: Boolean = false, onSelect: (Int) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.padding(top = 8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (allowAuto) Swatch(null, selected == 0) { onSelect(0) }
            swatches.forEach { c -> Swatch(c, selected == c) { onSelect(c) } }
        }
    }
}

@Composable
private fun Swatch(colour: Int?, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(if (colour == null) MaterialTheme.colorScheme.surfaceVariant else Color(colour))
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else Color(0x33FFFFFF), CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (colour == null) Text("A", style = MaterialTheme.typography.labelMedium)
        else if (selected) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.size(16.dp))
    }
}

@Composable
fun AppIcon(pkg: String, size: Int = 40) {
    val ctx = LocalContext.current
    val bmp by produceState<android.graphics.Bitmap?>(null, pkg) {
        value = withContext(Dispatchers.IO) { Apps.iconBitmap(ctx, pkg) }
    }
    val b = bmp
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(size.dp))
    else Icon(Icons.Rounded.Android, null, Modifier.size(size.dp))
}

@Composable
fun AppPickerDialog(title: String = "Choose an app", onDismiss: () -> Unit, onPick: (AppEntry) -> Unit) {
    val ctx = LocalContext.current
    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    LaunchedEffect(Unit) { apps = withContext(Dispatchers.IO) { Apps.launchable(ctx) } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    placeholder = { Text("Search") }, modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.padding(top = 8.dp))
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val filtered = list.filter { query.isBlank() || it.label.contains(query, true) }
                    LazyColumn(Modifier.heightIn(max = 420.dp)) {
                        items(filtered, key = { it.pkg }) { a ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onPick(a) }.padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(a.pkg, 36)
                                Spacer(Modifier.width(12.dp))
                                Text(a.label, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
