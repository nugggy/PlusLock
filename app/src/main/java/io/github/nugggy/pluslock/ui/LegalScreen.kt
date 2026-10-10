package io.github.nugggy.pluslock.ui

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Gavel
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Policy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.BuildConfig
import io.github.nugggy.pluslock.core.P

/** Bump when the privacy policy or terms change in a way people should agree to again. */
const val LEGAL_VERSION = 1

private val docs = linkedMapOf(
    "privacy" to ("Privacy policy" to "legal/PRIVACY.md"),
    "terms" to ("Terms of use" to "legal/TERMS.md"),
    "licence" to ("Licence" to "legal/LICENSE"),
)

/** Libraries inside the app, and their licences. */
private val libraries = listOf(
    "Kotlin standard library and coroutines" to "Apache License 2.0, JetBrains",
    "AndroidX: Activity, Core, Lifecycle" to "Apache License 2.0, The Android Open Source Project",
    "Jetpack Compose and Material 3" to "Apache License 2.0, The Android Open Source Project",
    "Material Icons" to "Apache License 2.0, Google",
)

private fun readAsset(ctx: Context, path: String): String =
    runCatching { ctx.assets.open(path).bufferedReader().use { it.readText() } }.getOrDefault("Couldn't open this document.")

/** **bold** to real bold. */
private fun inline(s: String): AnnotatedString = buildAnnotatedString {
    val parts = s.split("**")
    parts.forEachIndexed { i, p -> if (i % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(p) } else append(p) }
}

/** A small Markdown reader: headings, paragraphs, bullet points and simple tables. */
@Composable
private fun MarkdownBlock(md: String) {
    Column(Modifier.padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val blocks = md.replace("\r", "").split(Regex("\n\\s*\n"))
        for (block in blocks) {
            val lines = block.lines().filter { it.isNotBlank() }
            if (lines.isEmpty()) continue
            val first = lines.first()
            when {
                first.startsWith("# ") -> Text(first.removePrefix("# "), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                first.startsWith("### ") -> Text(first.removePrefix("### "), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                first.startsWith("## ") -> Text(first.removePrefix("## "), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp))
                first.startsWith("- ") -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    lines.forEach { l ->
                        Row {
                            Text("•", style = MaterialTheme.typography.bodyMedium)
                            androidx.compose.foundation.layout.Spacer(Modifier.width(10.dp))
                            Text(inline(l.removePrefix("- ")), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                first.startsWith("|") -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val rows = lines.map { r -> r.trim('|').split('|').map { it.trim() } }.filterNot { r -> r.all { c -> c.all { it == '-' } } }
                    val header = rows.first()
                    rows.drop(1).forEach { r ->
                        Column {
                            r.forEachIndexed { i, cell ->
                                Text(buildAnnotatedString {
                                    withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("${header.getOrElse(i) { "" }}: ") }
                                    append(cell)
                                }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                else -> Text(inline(lines.joinToString(" ")), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
fun LegalScreen(nav: Nav, doc: String) {
    val ctx = LocalContext.current
    val entry = docs[doc]
    if (entry != null) {
        val text = remember(doc) { readAsset(ctx, entry.second) }
        ScreenScaffold(entry.first, nav.back) {
            item {
                if (doc == "licence") Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 8.dp))
                else MarkdownBlock(text)
            }
        }
        return
    }
    if (doc == "libraries") {
        ScreenScaffold("Open source licences", nav.back) {
            item {
                Section("Built with") { libraries.forEach { (name, lic) -> Row2(name, lic) } }
            }
            item { Note("The Apache License 2.0 is at apache.org/licenses/LICENSE-2.0. Pimp my Phone itself is under the MIT licence.") }
        }
        return
    }
    ScreenScaffold("Legal and about", nav.back) {
        item {
            Section("Pimp my Phone") {
                Row2("Version ${BuildConfig.VERSION_NAME}", "Made by nugggy. Free and open source.", Icons.Rounded.Info)
                Row2("Source code and support", "github.com/nugggy/PlusLock", Icons.Rounded.Code, onClick = {
                    runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/nugggy/PlusLock"))) }
                })
            }
        }
        item {
            Section("Legal") {
                Row2("Privacy policy", "What the app reads, what stays on your phone, and the two things that go online", Icons.Rounded.Policy,
                    onClick = { nav.go(Screen.Legal("privacy")) })
                Row2("Terms of use", "Using the app sensibly, safety notes and the fine print", Icons.Rounded.Gavel, onClick = { nav.go(Screen.Legal("terms")) })
                Row2("Licence", "MIT", Icons.Rounded.Description, onClick = { nav.go(Screen.Legal("licence")) })
                Row2("Open source licences", "The libraries inside the app", Icons.Rounded.Code, onClick = { nav.go(Screen.Legal("libraries")) })
            }
        }
        item {
            Section("Safety") {
                Row2("Flashing lights", "Some charging, lighting and find my phone effects flash. Avoid them if you have photosensitive epilepsy.")
                Row2("Volume boost", "Loud sound can damage your hearing and the speaker. Keep it moderate.")
                Row2("Not for emergencies", "Alerts and find my phone can be delayed or missed. Don't rely on them for safety.")
            }
        }
        item { Note("Not affiliated with OnePlus, OPPO, Samsung or Google.") }
    }
}

/** Shown once, before anything else, until the person agrees to the terms and privacy policy. */
@Composable
fun ConsentDialog(nav: Nav) {
    val ctx = LocalContext.current
    val accepted by P.legalAccepted.observe()
    if (accepted >= LEGAL_VERSION) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(if (accepted == 0) "Before you start" else "We've updated our terms") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Pimp my Phone keeps what it learns on your phone. No accounts, ads or analytics. It only goes online to check for updates and, if you turn it on, to fetch the weather.")
                Text("Some features read your notifications or use the accessibility service. Each one is off until you switch it on, and you'll be told what it needs first.")
                Row {
                    TextButton(onClick = { nav.go(Screen.Legal("privacy")) }) { Text("Privacy policy") }
                    TextButton(onClick = { nav.go(Screen.Legal("terms")) }) { Text("Terms of use") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { P.legalAccepted.value = LEGAL_VERSION }) { Text("Agree and continue") } },
        dismissButton = { TextButton(onClick = { (ctx as? Activity)?.finish() }) { Text("Close the app") } },
    )
}

/** Explains exactly what a sensitive permission is used for before sending someone to Android settings. */
@Composable
fun DisclosureDialog(kind: String, onContinue: () -> Unit, onDismiss: () -> Unit) {
    val (title, body) = when (kind) {
        "accessibility" -> "Accessibility service" to listOf(
            "Pimp my Phone uses Android's accessibility service to:",
            "- know which app is open, for app profiles, app lock, time limits and pausing the glow",
            "- draw edge lighting, the camera ring, charging effects, the island and the volume panel over the screen",
            "- go back, go home, take screenshots and lock the screen when you use a gesture",
            "- see volume button presses, only while the volume panel or track skipping is on",
            "It never reads what's on your screen or what you type, and nothing is sent off your phone.",
        )
        else -> "Notification access" to listOf(
            "Pimp my Phone reads your notifications, including who they're from and what they say, to:",
            "- light up the edge and show the preview bubble",
            "- keep the notification vault and spot unsent messages",
            "- match your keyword alerts and find my phone codeword",
            "- read notifications aloud and build the morning digest, if you turn those on",
            "Everything stays on your phone. You can clear the vault or turn this off at any time.",
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                body.forEach { line ->
                    if (line.startsWith("- ")) Text("•  " + line.removePrefix("- "), style = MaterialTheme.typography.bodyMedium)
                    else Text(line, style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onContinue) { Text("Continue to settings") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Not now") } },
    )
}
