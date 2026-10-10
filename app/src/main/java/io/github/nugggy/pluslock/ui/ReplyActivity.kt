package io.github.nugggy.pluslock.ui

import android.app.Notification
import android.app.RemoteInput
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.concurrent.ConcurrentHashMap

/** Reply actions from recent notifications, so the bubble can answer without opening the app. */
object ReplyTargets {
    data class Target(val title: String, val action: Notification.Action)

    private val map = ConcurrentHashMap<String, Target>()

    fun put(key: String, title: String, n: Notification) {
        val a = n.actions?.firstOrNull { act -> act.remoteInputs?.any { it.allowFreeFormInput } == true }
        if (a == null) map.remove(key) else map[key] = Target(title, a)
    }

    fun has(key: String) = map.containsKey(key)
    fun get(key: String) = map[key]
    fun remove(key: String) { map.remove(key) }
}

class ReplyActivity : ComponentActivity() {
    companion object {
        const val EXTRA_KEY = "key"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val target = intent.getStringExtra(EXTRA_KEY)?.let { ReplyTargets.get(it) }
        if (target == null) {
            Toast.makeText(this, "That message can no longer be replied to", Toast.LENGTH_SHORT).show()
            finish(); return
        }
        setContent {
            PlusTheme {
                var text by remember { mutableStateOf("") }
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                fun send() {
                    if (text.isBlank()) return
                    if (sendReply(target.action, text.trim())) finish()
                    else Toast.makeText(this, "The app didn't accept the reply", Toast.LENGTH_SHORT).show()
                }
                Box(Modifier.fillMaxSize().imePadding().navigationBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                // Tapping the dim area behind the card closes it; taps on the card stay with the card.
                Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, null) { finish() })
                Surface(
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                ) {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Reply to ${target.title}", style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        OutlinedTextField(
                            value = text, onValueChange = { text = it },
                            placeholder = { Text("Message") },
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = { send() }),
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { finish() }) { Text("Cancel") }
                            Button(onClick = { send() }, enabled = text.isNotBlank()) { Text("Send") }
                        }
                    }
                }
                }
            }
        }
    }

    private fun sendReply(action: Notification.Action, message: String): Boolean {
        val inputs = action.remoteInputs ?: return false
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, message) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        return runCatching { action.actionIntent.send(this, 0, fill); true }.getOrDefault(false)
    }
}
