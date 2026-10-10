package io.github.nugggy.pluslock.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** One thing you can find. [group] links similar features for suggestions. */
data class Feature(val title: String, val where: String, val screen: Screen, val group: String, val words: String = "")

/** Every feature and setting worth finding, with the everyday words people might type for it. */
val featureIndex: List<Feature> = listOf(
    // Edge lighting
    Feature("Edge lighting", "Edge lighting", Screen.Lighting, "glow", "notification light led glow flash border aod halo"),
    Feature("Glow effects: comet, pulse, rainbow and more", "Edge lighting", Screen.Lighting, "glow", "effect animation style comet pulse neon rainbow heartbeat duo disco sparkle dash"),
    Feature("Light up the lock screen for notifications", "Edge lighting", Screen.Lighting, "lockscreen", "wake screen off aod always on lock screen"),
    Feature("Glow speed, softness and brightness", "Edge lighting", Screen.Lighting, "glow", "fast slow blur thickness width opacity"),
    Feature("Notification preview bubble", "Edge lighting", Screen.Lighting, "notifications", "pill popup banner peek message preview"),
    Feature("Quick reply from the bubble", "Edge lighting", Screen.Lighting, "notifications", "reply answer respond whatsapp messages"),
    Feature("Pause edge lighting", "Edge lighting", Screen.Lighting, "quiet", "stop silence mute pause disable snooze"),
    Feature("Quiet hours for the glow", "Edge lighting", Screen.Lighting, "quiet", "night sleep schedule bedtime dark"),
    Feature("Unread reminder glow", "Edge lighting", Screen.Lighting, "glow", "remind missed unread repeat"),
    Feature("People and VIPs", "Edge lighting", Screen.Lighting, "people", "contact person vip favourite mum dad colour per person priority"),
    // Camera ring
    Feature("Camera ring", "Camera ring", Screen.Ring, "ring", "battery ring punch hole selfie camera cutout indicator"),
    Feature("Camera ring styles and glow", "Camera ring", Screen.Ring, "ring", "gradient rainbow segments dots style glow"),
    Feature("Camera ring charging animation", "Camera ring", Screen.Ring, "charging", "spin comet dot pulse fill charge"),
    Feature("Battery percentage beside the camera", "Camera ring", Screen.Ring, "battery", "percent level number"),
    Feature("Flash the camera ring for notifications", "Camera ring", Screen.Ring, "glow", "notification flash blink camera"),
    // Charging
    Feature("Charging animations", "Charging effects", Screen.Charging, "charging", "charge plug charger animation liquid bubble wave tide halo lightning orb battery screen"),
    Feature("Keep glowing while charging", "Charging effects", Screen.Charging, "charging", "charging glow stay always on edge level fill"),
    Feature("Charging percentage with decimals", "Charging effects", Screen.Charging, "charging", "percent decimal exact precise watts temperature clock"),
    Feature("Plug in vibration, unplug flash and fully charged glow", "Charging effects", Screen.Charging, "charging", "plug unplug full 100 vibrate haptic moment"),
    // Battery
    Feature("Battery health", "Battery", Screen.Battery, "battery", "health capacity cycles temperature wear mah condition"),
    Feature("Tell me when to unplug", "Battery", Screen.Battery, "battery", "charge guard limit 80 percent unplug alert protect"),
    Feature("Bedtime charging reminder", "Battery", Screen.Battery, "battery", "night bedtime remind charge overnight"),
    // Find my phone and keywords
    Feature("Find my phone", "Find my phone", Screen.FindPhone, "lost", "lost stolen missing find locate ring loud codeword secret word silent alarm"),
    Feature("Secret codeword", "Find my phone", Screen.FindPhone, "lost", "code word password phrase trigger text sms"),
    Feature("Keyword alerts", "Haptics, keywords and quiet", Screen.Tricks, "lost", "keyword urgent emergency word alert important"),
    Feature("Haptic signatures", "Haptics, keywords and quiet", Screen.Tricks, "haptics", "vibration vibrate pattern buzz morse heartbeat"),
    Feature("Read notifications aloud", "Haptics, keywords and quiet", Screen.Tricks, "notifications", "speak voice tts read aloud headphones whisper"),
    Feature("Morning digest", "Haptics, keywords and quiet", Screen.Tricks, "notifications", "summary morning overnight digest"),
    Feature("Hold notifications during work hours", "Haptics, keywords and quiet", Screen.Tricks, "quiet", "work focus hold snooze office hours mute apps"),
    // Gestures
    Feature("Edge gestures", "Gestures", Screen.Gestures, "gestures", "swipe edge handle back home recents one hand"),
    Feature("Edge handle size and colour", "Gestures", Screen.Gestures, "gestures", "width height position visibility handle"),
    Feature("Back tap", "Gestures", Screen.Gestures, "gestures", "tap back knock double tap rear"),
    Feature("Shake for the torch", "Gestures", Screen.Gestures, "torch", "flashlight shake torch light"),
    Feature("Double-tap the status bar to lock", "Gestures", Screen.Gestures, "gestures", "lock screen off sleep double tap"),
    Feature("Hold volume to skip tracks", "Gestures", Screen.Gestures, "music", "music song next previous skip volume button"),
    Feature("Flip face down", "Gestures", Screen.Gestures, "quiet", "flip face down silent vibrate dnd shhh"),
    // Deck and tiles
    Feature("Control deck", "Control deck and tiles", Screen.Deck, "controls", "deck panel controls volume mixer"),
    Feature("Torch brightness", "Control deck and tiles", Screen.Deck, "torch", "flashlight torch brightness dim strength"),
    Feature("Caffeine: keep the screen awake", "Control deck and tiles", Screen.Deck, "screen", "awake caffeine screen on timeout"),
    Feature("Quick Settings tiles", "Control deck and tiles", Screen.Deck, "controls", "tile quick settings shade toggle"),
    // Side panel and routines
    Feature("Side panel", "Side panel", Screen.Panel, "controls", "panel sidebar edge apps favourites shortcuts people contacts"),
    Feature("Routines", "Routines", Screen.Routines, "automation", "automation routine trigger when if then bluetooth time wifi tasker condition only if wait battery screen unlock notification"),
    Feature("Calculator", "Side panel", Screen.Panel, "controls", "calculator calc maths sum add"),
    Feature("Quick note", "Side panel", Screen.Panel, "controls", "note notes jot write memo scratch"),
    Feature("Volume for each app", "Volume panel", Screen.VolumePanel, "volume", "per app volume mute app spotify youtube mixer separate"),
    Feature("Lock screen clock faces", "Look and feel", Screen.Look, "lockscreen", "clock face analogue flip neon dot matrix giant outline split battery alarm weather unread music"),
    Feature("Camera island extras", "Look and feel", Screen.Look, "island", "island peek message download progress directions navigation maps swipe glow"),
    // Look and feel
    Feature("Camera island", "Look and feel", Screen.Look, "island", "dynamic island pill music call timer"),
    Feature("Volume panel", "Volume panel", Screen.VolumePanel, "volume", "volume slider panel sound loud quiet"),
    Feature("Choose which volumes to show", "Volume panel", Screen.VolumePanel, "volume", "calls media notifications ringtone alarm system sliders streams"),
    Feature("Volume panel colours and style", "Volume panel", Screen.VolumePanel, "volume", "colour color theme glass black light rainbow size width height corner"),
    Feature("Volume boost: louder than the maximum", "Volume panel", Screen.VolumePanel, "volume", "boost booster louder loud amplify max maximum gain speaker quiet too quiet"),
    Feature("Tap to mute a volume", "Volume panel", Screen.VolumePanel, "volume", "mute silence quiet tap"),
    Feature("Extra dim and warmth", "Look and feel", Screen.Look, "screen", "dim dark night light warm blue light filter eye comfort"),
    Feature("Network speed meter", "Look and feel", Screen.Look, "status", "internet speed data network kb mb meter"),
    Feature("Lock screen clock", "Look and feel", Screen.Look, "lockscreen", "clock lock screen font time date style"),
    Feature("Status bar icons and clock seconds", "Look and feel", Screen.Look, "status", "status bar icons hide seconds clock"),
    Feature("Interactive wallpapers", "Interactive wallpapers", Screen.PlayWallpaper, "wallpaper", "interactive live wallpaper touch tilt swarm particles ripples water fireflies neon trails draw constellation marbles battery tide lava lamp"),
    Feature("Living sky wallpaper", "Living sky wallpaper", Screen.Wallpaper, "wallpaper", "wallpaper background live weather sky stars"),
    // Apps
    Feature("App profiles", "App profiles", Screen.Profiles, "apps", "per app volume rotation brightness profile"),
    Feature("Fingerprint lock for apps", "App profiles", Screen.Profiles, "apps", "lock app fingerprint biometric privacy hide protect"),
    Feature("Time limits and doomscroll breaker", "App profiles", Screen.Profiles, "apps", "limit timer doomscroll addiction scroll focus"),
    Feature("Keyboard per app", "App profiles", Screen.Profiles, "apps", "keyboard ime gboard swiftkey language"),
    Feature("Screen time", "Screen time", Screen.ScreenTime, "apps", "usage time today week wellbeing"),
    // Notifications history
    Feature("Notification vault", "Notification vault", Screen.Vault, "notifications", "history log deleted unsent saved search"),
    Feature("Unsent message alerts", "Notification vault", Screen.Vault, "notifications", "unsent deleted removed whatsapp message recall"),
    // App
    Feature("Check for updates", "Updates", Screen.Updates, "app", "update new version upgrade download install beta"),
    Feature("Back up and restore settings", "Back up and restore", Screen.Backup, "app", "backup restore export import save file"),
    Feature("Privacy policy", "Legal and about", Screen.Legal("privacy"), "legal", "privacy data policy information collect gdpr"),
    Feature("Terms of use", "Legal and about", Screen.Legal("terms"), "legal", "terms conditions tos rules agreement"),
    Feature("Licences and about", "Legal and about", Screen.Legal(), "legal", "licence license open source about version mit"),
    Feature("Diagnostics and self-test", "Diagnostics", Screen.Diagnostics, "app", "diagnostics test debug broken not working bug problem report fix check log"),
    Feature("ADB extras", "ADB extras", Screen.Adb, "app", "adb computer usb debugging permission unlock"),
)

// ---- matching ----

private fun normalise(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()
private fun tokens(s: String) = normalise(s).split(' ').filter { it.isNotEmpty() }

/** Edits needed to turn a into b, counting a swap of neighbours as one. */
private fun distance(a: String, b: String): Int {
    if (a == b) return 0
    val d = Array(a.length + 1) { IntArray(b.length + 1) }
    for (i in 0..a.length) d[i][0] = i
    for (j in 0..b.length) d[0][j] = j
    for (i in 1..a.length) for (j in 1..b.length) {
        val cost = if (a[i - 1] == b[j - 1]) 0 else 1
        d[i][j] = min(min(d[i - 1][j] + 1, d[i][j - 1] + 1), d[i - 1][j - 1] + cost)
        if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) d[i][j] = min(d[i][j], d[i - 2][j - 2] + 1)
    }
    return d[a.length][b.length]
}

/** How many typos a word of this length can have and still count. */
private fun allowed(len: Int) = when { len <= 3 -> 0; len <= 5 -> 1; len <= 8 -> 2; else -> 3 }

/** 0 to 1: how well one typed word matches one word of a feature. */
private fun wordScore(q: String, w: String): Float {
    if (q == w) return 1f
    if (w.startsWith(q) && q.length >= 2) return 0.9f
    if (q.length >= 4 && w.contains(q)) return 0.75f
    if (q.length >= 4 && q.startsWith(w) && w.length >= 4) return 0.7f
    val full = distance(q, w)
    // Also compare against the start of the word, so half-typed words with a typo still count.
    val partial = if (w.length > q.length) distance(q, w.substring(0, q.length)) else full
    val d = min(full, partial + 1)
    val ok = allowed(q.length)
    return if (d <= ok) 0.72f - 0.14f * d else 0f
}

data class Hit(val feature: Feature, val score: Float)

private class Indexed(val f: Feature, val title: List<String>, val rest: List<String>)

private val indexed by lazy {
    featureIndex.map { Indexed(it, tokens(it.title), tokens("${it.where} ${it.words}")) }
}

/** Every word the index knows, for "did you mean". */
private val vocabulary by lazy { indexed.flatMap { it.title + it.rest }.filter { it.length >= 3 }.toSet() }

private val stopWords = setOf("the", "a", "an", "to", "my", "of", "for", "and", "on", "in", "it", "how", "i", "me", "do", "can", "is", "with")

fun search(query: String): List<Hit> {
    val q = tokens(query).filter { it !in stopWords }.ifEmpty { tokens(query) }
    if (q.isEmpty()) return emptyList()
    val whole = normalise(query)
    return indexed.mapNotNull { e ->
        var total = 0f
        var missed = 0
        for (t in q) {
            val best = max(e.title.maxOfOrNull { wordScore(t, it) } ?: 0f, (e.rest.maxOfOrNull { wordScore(t, it) } ?: 0f) * 0.85f)
            if (best == 0f) missed++
            total += best
        }
        var score = total / q.size
        if (missed > 0) score *= 1f - 0.5f * missed / q.size
        if (whole.length >= 3 && normalise(e.f.title).contains(whole)) score += 0.3f
        if (score >= 0.4f) Hit(e.f, score) else null
    }.sortedByDescending { it.score }
}

/** The query with each unknown word swapped for the closest known one, or null if nothing changed. */
fun didYouMean(query: String): String? {
    val words = tokens(query)
    var changed = false
    val fixed = words.map { w ->
        if (w in vocabulary || w in stopWords || w.length < 3) return@map w
        val best = vocabulary.minByOrNull { distance(w, it) } ?: return@map w
        val d = distance(w, best)
        if (d in 1..max(1, allowed(w.length) + 1)) { changed = true; best } else w
    }
    return if (changed) fixed.joinToString(" ") else null
}

/** Features like the top results, from the same group, that didn't match themselves. */
fun similar(hits: List<Hit>, limit: Int = 5): List<Feature> {
    val shown = hits.map { it.feature }.toSet()
    val groups = hits.take(3).map { it.feature.group }.distinct()
    return featureIndex.filter { it.group in groups && it !in shown }.take(limit)
}

/** For a query that matched nothing: the closest titles by shared letter pairs. */
fun closest(query: String, limit: Int = 5): List<Feature> {
    fun pairs(s: String) = normalise(s).replace(" ", "").windowed(2).toSet()
    val q = pairs(query)
    if (q.isEmpty()) return emptyList()
    return featureIndex.map { f ->
        val t = pairs(f.title + " " + f.words)
        f to q.intersect(t).size.toFloat() / q.size
    }.filter { it.second > 0.3f }.sortedByDescending { it.second }.take(limit).map { it.first }
}

// ---- screen ----

private val popular = listOf("Charging animations", "Find my phone", "Edge lighting", "Camera ring", "Back tap", "Extra dim and warmth")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(nav: Nav) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val hits = remember(query) { search(query) }
    val fix = remember(query, hits) { if (hits.isEmpty() || hits.first().score < 0.75f) didYouMean(query) else null }
    val fixHits = remember(fix) { fix?.let { search(it) }.orEmpty() }
    // Typos so bad nothing matched: quietly use the corrected words.
    val shown = if (hits.isEmpty() && fixHits.isNotEmpty()) fixHits else hits
    val related = remember(shown) { similar(shown) }
    val nearest = remember(query, shown) { if (shown.isEmpty() && query.isNotBlank()) closest(query) else emptyList() }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = nav.back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") } },
                title = {
                    TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search features") },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Rounded.Close, "Clear") } },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        shape = RoundedCornerShape(28.dp),
                        colors = TextFieldDefaults.colors(
                            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                        ),
                        modifier = Modifier.fillMaxWidth().padding(end = 12.dp).focusRequester(focus),
                    )
                },
            )
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize().imePadding(),
        ) {
            if (query.isBlank()) {
                item {
                    Section("Popular") {
                        popular.mapNotNull { t -> featureIndex.firstOrNull { it.title == t } }.forEach { f -> FeatureRow(f, "", nav) }
                    }
                }
                item { Note("Typos are fine. Try charjing, lost phone, or vibrate.") }
                return@LazyColumn
            }
            if (fix != null && fixHits.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    AssistChip(
                        onClick = { query = fix },
                        label = { Text(if (hits.isEmpty()) "Showing results for \"$fix\"" else "Did you mean \"$fix\"?") },
                        leadingIcon = { Icon(Icons.Rounded.AutoAwesome, null) },
                    )
                }
            }
            if (shown.isNotEmpty()) item {
                Section(if (shown.size == 1) "1 feature" else "${shown.size} features") {
                    shown.take(25).forEach { FeatureRow(it.feature, query, nav) }
                }
            }
            if (related.isNotEmpty()) item {
                Section("You might also like") { related.forEach { FeatureRow(it, "", nav) } }
            }
            if (shown.isEmpty()) {
                item { Note("Nothing called \"$query\" yet.") }
                if (nearest.isNotEmpty()) item {
                    Section("Closest matches") { nearest.forEach { FeatureRow(it, "", nav) } }
                }
                item {
                    Column(Modifier.padding(horizontal = 8.dp)) {
                        Text("Or browse", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                            items(featureIndex.map { it.where }.distinct()) { w ->
                                AssistChip(onClick = { query = w }, label = { Text(w) })
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FeatureRow(f: Feature, query: String, nav: Nav) {
    val words = tokens(query).filter { it.length >= 2 }
    val title = buildAnnotatedString {
        // Bold the words that matched, so it's clear why each result is there.
        f.title.split(' ').forEachIndexed { i, w ->
            if (i > 0) append(' ')
            val hit = words.any { q -> wordScore(q, normalise(w)) >= 0.58f }
            if (hit) withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)) { append(w) } else append(w)
        }
    }
    Column(
        Modifier.fillMaxWidth().clickable { nav.go(f.screen) }.padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(f.where, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
