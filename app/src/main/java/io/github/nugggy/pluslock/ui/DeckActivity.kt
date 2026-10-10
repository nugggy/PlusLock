package io.github.nugggy.pluslock.ui

import android.media.AudioManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.Coffee
import androidx.compose.material.icons.rounded.FlashlightOff
import androidx.compose.material.icons.rounded.FlashlightOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.RingVolume
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.core.Caffeine
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.core.SoundMode
import io.github.nugggy.pluslock.core.Torch
import kotlin.math.roundToInt

/** A floating panel over any app: volume mixer, sound mode, torch and caffeine. */
class DeckActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Torch.init(this)
        enableEdgeToEdge()
        setContent {
            PlusTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clickable(remember { MutableInteractionSource() }, null) { finish() },
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Surface(
                        shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp, bottomStart = 32.dp, bottomEnd = 32.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        modifier = Modifier
                            .padding(12.dp)
                            .navigationBarsPadding()
                            .fillMaxWidth()
                            .clickable(remember { MutableInteractionSource() }, null) {},
                    ) { Deck() }
                }
            }
        }
    }
}

@Composable
private fun Deck() {
    val ctx = LocalContext.current
    val am = remember { ctx.getSystemService(AudioManager::class.java) }
    var ringer by remember { mutableIntStateOf(am.ringerMode) }
    Column(
        Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("Control deck", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)

        val modes = listOf(AudioManager.RINGER_MODE_NORMAL, AudioManager.RINGER_MODE_VIBRATE, AudioManager.RINGER_MODE_SILENT)
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            modes.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = ringer == m,
                    onClick = { if (SoundMode.set(ctx, m)) ringer = m },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                ) { Text(SoundMode.label(m)) }
            }
        }

        StreamSlider(am, AudioManager.STREAM_MUSIC, "Media", Icons.Rounded.MusicNote)
        StreamSlider(am, AudioManager.STREAM_RING, "Ringtone", Icons.Rounded.RingVolume)
        StreamSlider(am, AudioManager.STREAM_NOTIFICATION, "Notifications", Icons.Rounded.Notifications)
        StreamSlider(am, AudioManager.STREAM_ALARM, "Alarms", Icons.Rounded.Alarm)
        StreamSlider(am, AudioManager.STREAM_VOICE_CALL, "Calls", Icons.Rounded.Call)

        TorchRow()
        CaffeineRow()
    }
}

@Composable
private fun StreamSlider(am: AudioManager, stream: Int, label: String, icon: ImageVector) {
    val min = remember { am.getStreamMinVolume(stream) }
    val max = remember { am.getStreamMaxVolume(stream) }
    var v by remember { mutableFloatStateOf(am.getStreamVolume(stream).toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, label, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            Slider(
                value = v,
                onValueChange = {
                    v = it
                    runCatching { am.setStreamVolume(stream, it.roundToInt(), 0) }
                },
                valueRange = min.toFloat()..max.toFloat().coerceAtLeast(min + 1f),
                steps = (max - min - 1).coerceAtLeast(0),
            )
        }
    }
}

@Composable
private fun TorchRow() {
    val ctx = LocalContext.current
    val maxLevel = remember { Torch.maxLevel(ctx) }
    var on by remember { mutableStateOf(Torch.isOn) }
    var level by remember { mutableFloatStateOf(P.torchLevel.value.takeIf { it > 0 }?.toFloat() ?: maxLevel.toFloat()) }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        FilledIconToggleButton(checked = on, onCheckedChange = { Torch.setOn(ctx, it); on = it }) {
            Icon(if (on) Icons.Rounded.FlashlightOn else Icons.Rounded.FlashlightOff, "Torch")
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Torch", style = MaterialTheme.typography.labelLarge)
            if (maxLevel > 1) Slider(
                value = level,
                onValueChange = { level = it },
                onValueChangeFinished = { Torch.setLevel(ctx, level.roundToInt()) },
                valueRange = 1f..maxLevel.toFloat(),
            ) else Text("Tap to switch on or off", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun CaffeineRow() {
    val until by P.caffeineUntil.observe()
    val now = System.currentTimeMillis()
    val state = when {
        until == -1L -> "forever"
        until > now && until - now > 20 * 60_000L -> "60"
        until > now -> "15"
        else -> "off"
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Icon(Icons.Rounded.Coffee, "Caffeine", tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Text("Caffeine", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(state == "off", { Caffeine.set(0L) }, { Text("Off") })
            FilterChip(state == "15", { Caffeine.set(System.currentTimeMillis() + 15 * 60_000L) }, { Text("15m") })
            FilterChip(state == "60", { Caffeine.set(System.currentTimeMillis() + 60 * 60_000L) }, { Text("1h") })
            FilterChip(state == "forever", { Caffeine.set(-1L) }, { Text("∞") })
        }
    }
}
