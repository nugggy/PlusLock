package io.github.nugggy.pluslock.ui

import android.accessibilityservice.AccessibilityService
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.nugggy.pluslock.core.Actions
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.FindPhone
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Silence
import io.github.nugggy.pluslock.core.StringPref
import io.github.nugggy.pluslock.data.LightingRule
import io.github.nugggy.pluslock.data.LightingRules
import io.github.nugggy.pluslock.service.LightAlert
import io.github.nugggy.pluslock.service.PlusAccessibilityService
import io.github.nugggy.pluslock.service.chargeAnimStyles
import io.github.nugggy.pluslock.service.lightingEffects
import io.github.nugggy.pluslock.service.lightingShapes
import io.github.nugggy.pluslock.service.ringChargeAnims
import io.github.nugggy.pluslock.service.ringStyles

private fun previewAlert(ctx: Context, wake: Boolean = false) = LightAlert(
    colour = if (P.lightingAppColour.value) Apps.accentColour(ctx, ctx.packageName) else P.lightingColour.value,
    icon = Apps.icon(ctx, ctx.packageName),
    title = "Pimp my Phone",
    text = "This is how your notifications will glow. Longer messages wrap onto a second line if you choose two lines.",
    key = "preview",
    count = 2,
    wake = wake,
)

/** Locks the phone, then fires [then] a few seconds later so you can watch it on the lock screen. */
private fun tryOnLockScreen(ctx: Context, then: (PlusAccessibilityService) -> Unit) {
    if (needsService(ctx)) return
    val svc = PlusAccessibilityService.instance ?: return
    Actions.toast(ctx, "Locking. Watch the screen in 3 seconds.")
    svc.handler.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }, 800)
    svc.handler.postDelayed({ then(svc) }, 3_800)
}

/** A row that edits a piece of text in a dialog. */
@Composable
private fun TextPrefRow(pref: StringPref, title: String, help: String, empty: String = "Not set", hidden: Boolean = false) {
    val v by pref.observe()
    var editing by remember { mutableStateOf(false) }
    Row2(title, if (v.isBlank()) empty else if (hidden) "•".repeat(v.length.coerceAtMost(12)) + "  (tap to see or change)" else v, onClick = { editing = true })
    if (editing) {
        var text by remember { mutableStateOf(v) }
        AlertDialog(
            onDismissRequest = { editing = false },
            title = { Text(title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(help)
                    OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = { TextButton(onClick = { pref.value = text.trim(); editing = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { editing = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- Edge lighting

@Composable
fun LightingScreen(nav: Nav) {
    val ctx = LocalContext.current
    val colour by P.lightingColour.observe()
    val colour2 by P.lightingColour2.observe()
    val appColour by P.lightingAppColour.observe()
    val pill by P.lightingPill.observe()
    val quiet by P.lightingQuietOn.observe()
    val effect by P.lightingEffect.observe()
    val shape by P.lightingShape.observe()
    val wake by P.lightingWake.observe()
    val auto by P.lightingAutoPreview.observe()

    // Change how it looks and it plays straight away, so you can see the difference.
    val look = listOf(effect, shape, colour, colour2, appColour, pill,
        P.lightingThickness.observe().value, P.lightingSpeed.observe().value, P.lightingSoftness.observe().value,
        P.lightingOpacity.observe().value, P.lightingComets.observe().value, P.lightingTail.observe().value,
        P.bubbleStyle.observe().value, P.bubblePosition.observe().value, P.lightingPillSize.observe().value, P.bubbleLines.observe().value)
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(look) {
        if (first) { first = false; return@LaunchedEffect }
        if (auto && PlusAccessibilityService.instance != null) {
            kotlinx.coroutines.delay(200)
            PlusAccessibilityService.instance?.lighting?.show(previewAlert(ctx))
        }
    }

    ScreenScaffold("Edge lighting", nav.back) {
        item {
            Section("Edge lighting") {
                PrefSwitch(P.lightingOn, "Edge lighting", "Glow when a notification arrives")
                PrefChoice(P.lightingEffect, "Effect", lightingEffects)
                PrefChoice(P.lightingShape, "Where it glows", lightingShapes)
                PrefSwitch(P.lightingAutoPreview, "Play it when I change something", "Every change here shows a test glow straight away")
            }
        }
        item {
            Section("Lock screen") {
                PrefSwitch(P.lightingWake, "Light up the lock screen", "When the screen is off, wake it and glow over the lock screen")
                if (wake) {
                    PrefSwitch(P.lightingWakePocket, "Not in a pocket or bag", "Checks the proximity sensor first")
                    PrefSwitch(P.lightingWakeVip, "Only for VIPs", "Wake the screen just for the people you mark as VIP below")
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = {
                        tryOnLockScreen(ctx) { it.lighting.show(previewAlert(ctx, wake = true).copy(locked = true)) }
                    }, modifier = Modifier.weight(1f)) { Text("Try it on the lock screen") }
                }
            }
        }
        item {
            Section("Look") {
                PrefSlider(P.lightingThickness, "Thickness", 1..20) { "$it dp" }
                PrefSlider(P.lightingSoftness, "Glow softness", 0..250) { if (it == 0) "Crisp" else "$it%" }
                PrefSlider(P.lightingOpacity, "Brightness", 20..100) { "$it%" }
                PrefSlider(P.lightingSpeed, "Speed", 25..300) { "$it%" }
                if (effect == "COMET") {
                    PrefSlider(P.lightingComets, "Comets", 1..4) { it.toString() }
                    PrefSlider(P.lightingTail, "Tail length", 5..50) { "$it%" }
                }
                PrefSlider(P.lightingDuration, "How long", 2..30) { "$it s" }
            }
        }
        item { PauseSection() }
        item {
            Section("Colour and timing") {
                PrefSwitch(P.lightingAppColour, "Use each app's colour", "Taken from the app's brand colour or icon")
                if (!appColour) ColourRow("Colour", colour) { P.lightingColour.value = it }
                if (effect == "DUO" || effect == "SPARKLE") ColourRow("Second colour", colour2) { P.lightingColour2.value = it }
                PrefSwitch(P.lightingRespectDnd, "Stay dark in Do Not Disturb")
                PrefSwitch(P.lightingLandscapeOff, "Stay dark in landscape", "Keeps videos and games clear")
                PrefSwitch(P.lightingQuietOn, "Quiet hours", "No glow during these hours, Do Not Disturb or not")
                if (quiet) {
                    HourRow("From", P.lightingQuietStart)
                    HourRow("Until", P.lightingQuietEnd)
                }
                PrefIntChoice(P.lightingReminder, "Unread reminder", linkedMapOf(
                    0 to "Off", 2 to "Every 2 minutes", 5 to "Every 5 minutes", 10 to "Every 10 minutes", 30 to "Every 30 minutes",
                ))
                PrefSwitch(P.ringNotifFlash, "Flash the camera ring too", "The ring lights up in the notification's colour. Needs the camera ring on.")
                Row2("Charging glow", "Set it up under Charging effects", onClick = { nav.go(Screen.Charging) })
            }
        }
        item {
            Section("Preview bubble") {
                PrefSwitch(P.lightingPill, "Show a preview bubble", "App icon, sender and the message")
                if (pill) {
                    PrefSlider(P.lightingPillSize, "Text size", 80..200) { "$it%" }
                    PrefIntChoice(P.bubbleLines, "Message lines", linkedMapOf(1 to "One line", 2 to "Two lines", 3 to "Three lines"))
                    PrefChoice(P.bubblePosition, "Position", mapOf("TOP" to "Top of the screen", "BOTTOM" to "Bottom of the screen"))
                    PrefChoice(P.bubbleStyle, "Style", linkedMapOf(
                        "DARK" to "Dark", "LIGHT" to "Light", "GLASS" to "Glass: see-through", "APP" to "The app's colour",
                    ))
                    PrefSwitch(P.bubbleTapToOpen, "Tap to open", "Opens the message. Swipe the bubble sideways to dismiss it, or long-press it to pause bubbles for a while.")
                    PrefSwitch(P.bubbleReply, "Quick reply", "A Reply button for apps that support it, such as WhatsApp and Messages")
                    PrefSwitch(P.bubbleGroup, "Group messages", "A burst from one chat updates one bubble with a count")
                }
            }
        }
        item {
            Button(
                onClick = {
                    if (needsService(ctx)) return@Button
                    PlusAccessibilityService.instance?.lighting?.show(previewAlert(ctx))
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Preview") }
        }
        item { LightingRulesSection() }
        item { Note("Per-app colours and opt-outs live in App profiles. Keyword alerts have their own glow under Haptics, keywords and quiet.") }
    }
}

@Composable
private fun PauseSection() {
    val ctx = LocalContext.current
    val until by P.lightingSilenceUntil.observe()
    val app by P.lightingSilenceApp.observe()
    // Re-read once a minute so a timed pause ends on screen too.
    val tick by produceState(0) { while (true) { kotlinx.coroutines.delay(30_000); value++ } }
    val status = remember(until, app, tick) { Silence.describe(ctx) }
    Section("Pause") {
        if (status != null) {
            Row2(status, null) { TextButton(onClick = { Silence.resume() }) { Text("Resume") } }
        } else {
            Row2("Pause bubbles and glow", "Notifications still arrive, Pimp my Phone just stays dark")
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { Silence.forMinutes(30) }, modifier = Modifier.weight(1f)) { Text("30 min") }
                FilledTonalButton(onClick = { Silence.forMinutes(60) }, modifier = Modifier.weight(1f)) { Text("1 hour") }
                FilledTonalButton(onClick = { Silence.untilResumed() }, modifier = Modifier.weight(1f)) { Text("Until on") }
            }
        }
    }
    Note("You can also long-press a bubble, add the Pause lighting tile to Quick Settings, or set an edge gesture to Pause edge lighting.")
}

@Composable
private fun LightingRulesSection() {
    val json by P.lightingRulesJson.observe()
    val rules = remember(json) { LightingRules.all() }
    var editing by remember { mutableStateOf<Int?>(null) }
    Section("People") {
        Note("Give someone their own glow and vibration, or make them a VIP. The name is matched against who the notification is from, for example Mum.")
        rules.forEachIndexed { i, r ->
            Row2(r.match, ruleSummary(r), onClick = { editing = i })
        }
        Row2("Add a person", null, Icons.Rounded.PersonAdd, onClick = { editing = -1 })
    }
    val e = editing
    if (e != null) RuleDialog(
        initial = rules.getOrNull(e) ?: LightingRule(""),
        canDelete = e >= 0,
        onDismiss = { editing = null },
        onDelete = { LightingRules.save(rules.filterIndexed { i, _ -> i != e }); editing = null },
        onSave = { r ->
            LightingRules.save(if (e >= 0) rules.mapIndexed { i, old -> if (i == e) r else old } else rules + r)
            editing = null
        },
    )
}

private val ruleEffects: Map<String, String> =
    linkedMapOf("" to "Same as everyone") + lightingEffects.mapValues { it.value.substringBefore(":") }

private fun ruleSummary(r: LightingRule) =
    listOfNotNull(if (r.vip) "VIP" else null, ruleEffects[r.effect]?.takeIf { r.effect.isNotEmpty() }, if (r.colour != 0) "own colour" else null,
        Haptics.presets[r.haptic]?.takeIf { r.haptic.isNotEmpty() }?.let { "$it vibration" })
        .joinToString(", ").ifEmpty { "No changes yet" }

@Composable
private fun RuleDialog(initial: LightingRule, canDelete: Boolean, onDismiss: () -> Unit, onDelete: () -> Unit, onSave: (LightingRule) -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial.match) }
    var effect by remember { mutableStateOf(initial.effect) }
    var colour by remember { mutableIntStateOf(initial.colour) }
    var vip by remember { mutableStateOf(initial.vip) }
    var haptic by remember { mutableStateOf(initial.haptic) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (canDelete) "Edit person" else "Add a person") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name as it appears") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                ChoiceRow("Effect", ruleEffects, effect) { effect = it }
                ColourRow("Colour", colour, allowAuto = true, hue = false) { colour = it }
                SwitchRow("VIP", "Gets through Do Not Disturb, pauses, quiet hours and work-hours holds", vip) { vip = it }
                ChoiceRow("Their vibration", linkedMapOf("" to "Same as the app") + Haptics.presets.filterKeys { it != "NONE" }, haptic) {
                    haptic = it
                    if (it.isNotEmpty()) Haptics.play(ctx, it, name)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(LightingRule(name.trim(), effect, colour, vip, haptic)) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (canDelete) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

// ---------------------------------------------------------------- Camera ring

@Composable
fun RingScreen(nav: Nav) {
    val ctx = LocalContext.current
    val mode by P.ringColourMode.observe()
    val colour by P.ringColour.observe()
    val colour2 by P.ringColour2.observe()
    val chargeColour by P.ringChargeColour.observe()
    val style by P.ringStyle.observe()
    ScreenScaffold("Camera ring", nav.back) {
        item {
            Section("Ring") {
                PrefSwitch(P.ringOn, "Camera ring", "Battery level drawn around the selfie camera")
                PrefChoice(P.ringStyle, "Style", ringStyles)
                PrefSwitch(P.ringShowTrack, "Show the empty part of the ring")
                PrefChoice(P.ringWhere, "Show it", linkedMapOf(
                    "ALL" to "Everywhere", "LOCK" to "Only on the lock screen", "UNLOCKED" to "Everywhere except the lock screen",
                ))
                PrefSwitch(P.ringHideLandscape, "Hide in landscape")
                PrefSwitch(P.ringHideFull, "Hide when full and unplugged")
            }
        }
        item {
            Section("Colour") {
                PrefChoice(P.ringColourMode, "Colour", mapOf(
                    "LEVEL" to "White, turning orange then red when low",
                    "ACCENT" to "My colour, turning orange then red when low",
                    "CUSTOM" to "Always my colour",
                ))
                if (mode != "LEVEL") ColourRow("My colour", colour) { P.ringColour.value = it }
                if (style == "GRADIENT") ColourRow("Gradient's second colour", colour2) { P.ringColour2.value = it }
                PrefSlider(P.ringWarnLevel, "Orange below", 15..60) { "$it%" }
                PrefSlider(P.ringLowLevel, "Red below", 5..40) { "$it%" }
                PrefSwitch(P.ringLowPulse, "Pulse when the battery is low")
                PrefSlider(P.ringGlow, "Glow", 0..100) { if (it == 0) "Off" else "$it%" }
                PrefSlider(P.ringOpacity, "Brightness", 20..100) { "$it%" }
            }
        }
        item {
            Section("While charging") {
                PrefChoice(P.ringChargeAnim, "Animation", ringChargeAnims)
                ColourRow("Charging colour", chargeColour) { P.ringChargeColour.value = it }
                PrefSwitch(P.ringShowEta, "Time to full", "A small label beside the camera")
                PrefSwitch(P.ringShowPct, "Battery percentage beside the camera")
            }
        }
        item {
            Section("Direction") {
                PrefSwitch(P.ringClockwise, "Fill clockwise")
                PrefIntChoice(P.ringStart, "Start from", linkedMapOf(-90 to "The top", 0 to "The right", 90 to "The bottom", 180 to "The left"))
                PrefSwitch(P.ringNotifFlash, "Flash for notifications", "Lights up in the app's colour when a notification arrives")
            }
        }
        item {
            Section("Fit it to your camera") {
                PrefSlider(P.ringThickness, "Thickness", 10..80) { "%.1f dp".format(it / 10f) }
                PrefSlider(P.ringGap, "Gap from the lens", 0..60) { "%.1f dp".format(it / 10f) }
                PrefSlider(P.ringScale, "Size", 60..200) { "$it%" }
                PrefSlider(P.ringOffsetX, "Nudge sideways", -40..40) { "%.1f dp".format(it / 2f) }
                PrefSlider(P.ringOffsetY, "Nudge up or down", -40..40) { "%.1f dp".format(it / 2f) }
            }
        }
        item {
            FilledTonalButton(onClick = {
                if (needsService(ctx)) return@FilledTonalButton
                PlusAccessibilityService.instance?.ring?.flash(0xFFFF6BE0.toInt(), 3_000)
            }, modifier = Modifier.fillMaxWidth()) { Text("Test the notification flash") }
        }
        item { Note("Pimp my Phone reads the camera position from Android. If the ring sits a little off on your OnePlus 15, nudge it here.") }
    }
}

// ---------------------------------------------------------------- Charging effects

@Composable
fun ChargingScreen(nav: Nav) {
    val ctx = LocalContext.current
    val glowOn by P.chargingGlow.observe()
    val stay by P.chargeGlowStay.observe()
    val anim by P.chargeAnimOn.observe()
    val mode by P.chargeColourMode.observe()
    val colour by P.chargeColour.observe()
    val colour2 by P.chargeColour2.observe()
    fun preview() {
        if (needsService(ctx)) return
        PlusAccessibilityService.instance?.chargeFx?.preview()
    }
    ScreenScaffold("Charging effects", nav.back) {
        item {
            Section("Charging animation") {
                PrefSwitch(P.chargeAnimOn, "Charging animation", "A full-screen animation when you plug in")
                PrefChoice(P.chargeAnimStyle, "Style", chargeAnimStyles)
                if (anim) {
                    PrefChoice(P.chargeAnimWhen, "When", linkedMapOf(
                        "PLUG" to "When I plug in",
                        "WAKE" to "When I plug in, and every time I wake the screen while charging",
                    ))
                    PrefSwitch(P.chargeAnimLockOnly, "Only on the lock screen", "Never over an app you're using")
                    PrefSwitch(P.chargeAnimWakeOnPlug, "Wake the screen when I plug in", "For phones that stay dark on the charger")
                    PrefIntChoice(P.chargeAnimSeconds, "How long", linkedMapOf(
                        3 to "3 seconds", 6 to "6 seconds", 10 to "10 seconds", 20 to "20 seconds", 60 to "1 minute", 0 to "Until I tap it",
                    ))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { preview() }, modifier = Modifier.weight(1f)) { Text("Preview") }
                    FilledTonalButton(onClick = { tryOnLockScreen(ctx) { s -> io.github.nugggy.pluslock.core.Waker.wake(s, 6_000, false) { s.chargeFx.preview() } } },
                        modifier = Modifier.weight(1f)) { Text("On the lock screen") }
                }
                Note("Tap anywhere on the animation to close it.")
            }
        }
        item {
            Section("Animation look") {
                PrefSlider(P.chargeAnimSize, "Size", 50..160) { "$it%" }
                PrefChoice(P.chargeAnimPosition, "Position", linkedMapOf("TOP" to "Higher up", "CENTRE" to "Middle", "BOTTOM" to "Lower down"))
                PrefSlider(P.chargeAnimDim, "Background darkness", 0..100) { if (it == 0) "See-through" else "$it%" }
                PrefSlider(P.chargeAnimGlow, "Glow", 0..200) { if (it == 0) "Off" else "$it%" }
                PrefSlider(P.chargeAnimParticles, "Bubbles, drips and sparks", 0..250) { if (it == 0) "None" else "$it%" }
                PrefSlider(P.chargeAnimSpeed, "Speed", 25..300) { "$it%" }
            }
        }
        item {
            Section("Text") {
                PrefSwitch(P.chargeAnimPct, "Battery percentage")
                PrefSwitch(P.chargeAnimDecimals, "Two decimal places", "An estimate, like 86.28%. Needs the phone to report its battery size.")
                PrefSwitch(P.chargeAnimBolt, "Lightning bolt beside the percentage")
                PrefSwitch(P.chargeAnimEta, "Time to full")
                PrefSwitch(P.chargeAnimWatts, "Charging speed in watts")
                PrefSwitch(P.chargeAnimTemp, "Battery temperature")
                PrefSwitch(P.chargeAnimClock, "Big clock")
                PrefChoice(P.chargeAnimFont, "Font", linkedMapOf(
                    "SANS" to "Clean", "THIN" to "Thin", "BOLD" to "Heavy", "CONDENSED" to "Condensed", "MONO" to "Typewriter", "SERIF" to "Serif",
                ))
            }
        }
        item {
            Section("Colours") {
                PrefChoice(P.chargeColourMode, "Colour", linkedMapOf(
                    "LEVEL" to "By level: red, then yellow, then green",
                    "CUSTOM" to "My colour",
                    "DUO" to "Two colours blended",
                    "RAINBOW" to "Rainbow, always changing",
                ))
                if (mode == "CUSTOM" || mode == "DUO") ColourRow("My colour", colour) { P.chargeColour.value = it }
                if (mode == "DUO") ColourRow("Second colour", colour2) { P.chargeColour2.value = it }
                Note("These colours are shared by the charging animation and the charging glow.")
            }
        }
        item {
            Section("Charging glow") {
                PrefSwitch(P.chargingGlow, "Glow round the edge when I plug in")
                if (glowOn) {
                    PrefSwitch(P.chargeGlowStay, "Keep glowing while charging", "Stays on until you unplug, whenever the screen is on")
                    if (stay) {
                        PrefChoice(P.chargeGlowWhere, "Where", linkedMapOf("ALL" to "Everywhere", "LOCK" to "Only on the lock screen"))
                        PrefSwitch(P.chargeGlowLevel, "Fill to the battery level", "The glow rises up both sides as it charges")
                    }
                    PrefChoice(P.chargeGlowEffect, "Effect", lightingEffects)
                    PrefChoice(P.chargeGlowShape, "Where it glows", lightingShapes)
                    PrefSlider(P.chargeGlowThickness, "Thickness", 1..20) { "$it dp" }
                    PrefSlider(P.chargeGlowOpacity, "Brightness", 20..100) { "$it%" }
                    PrefSlider(P.chargeGlowSpeed, "Speed", 25..300) { "$it%" }
                }
            }
        }
        item {
            Section("Moments") {
                PrefChoice(P.chargePlugHaptic, "Vibrate when I plug in", Haptics.presets)
                PrefSwitch(P.chargeUnplugGlow, "Orange flash when I unplug")
                PrefSwitch(P.chargeFullAlert, "Rainbow glow and a buzz at 100%")
                Row2("Unplug reminder and bedtime nudge", "Under Battery", onClick = { nav.go(Screen.Battery) })
                Row2("Camera ring while charging", "Under Camera ring", onClick = { nav.go(Screen.Ring) })
            }
        }
        item { Note("Charging effects need the accessibility service. The glow steps aside while a notification is glowing, then comes back.") }
    }
}

// ---------------------------------------------------------------- Find my phone

@Composable
fun FindPhoneScreen(nav: Nav) {
    val ctx = LocalContext.current
    val on by P.findOn.observe()
    val code by P.findCode.observe()
    ScreenScaffold("Find my phone", nav.back) {
        item {
            Section("Secret codeword") {
                PrefSwitch(P.findOn, "Find my phone", "Text the phone your codeword from any phone, and it rings out loud even on silent")
                TextPrefRow(P.findCode, "Codeword", "Pick something nobody would send by accident, like purple-otter-42. At least 4 characters. Capitals don't matter.",
                    empty = "Not set yet", hidden = true)
                TextPrefRow(P.findSenders, "Only from", "Names of people allowed to set it off, separated by commas, as they appear in your notifications. Leave blank for anyone who knows the codeword.",
                    empty = "Anyone who knows it")
                if (on && code.trim().length < 4) Note("Set a codeword of at least 4 characters to switch this on.")
            }
        }
        item {
            Section("What it does") {
                PrefSwitch(P.findLoud, "Take it off silent and turn everything up", "Puts your volume, ringer and Do Not Disturb back afterwards")
                PrefSwitch(P.findSound, "Ring the alarm sound")
                PrefSwitch(P.findVibrate, "Buzz")
                PrefSwitch(P.findTorch, "Flash the torch")
                PrefSwitch(P.findScreen, "Light up the screen", "Full brightness, over the lock screen")
                PrefSwitch(P.findFlash, "Flash the screen red and white", "Under three flashes a second")
                PrefIntChoice(P.findMinutes, "Stop by itself after", linkedMapOf(1 to "1 minute", 2 to "2 minutes", 5 to "5 minutes", 10 to "10 minutes"))
                TextPrefRow(P.findMessage, "Message on the screen", "For whoever finds it, for example: Please call Sam on 0400 000 000.",
                    empty = "This phone's owner is looking for it.")
            }
        }
        item {
            Button(onClick = { FindPhone.start(ctx, "a test", test = true) }, modifier = Modifier.fillMaxWidth()) { Text("Test it for 12 seconds") }
        }
        item {
            Note("It works from any app that shows the message in a notification, such as Messages or WhatsApp, so notification access must be on. " +
                "Hold the Stop button on the phone to silence it. Anyone who learns the codeword can set it off, so keep it private.")
        }
        if (!android.app.NotificationManager::class.java.let { ctx.getSystemService(it).isNotificationPolicyAccessGranted }) item {
            Note("To get through Do Not Disturb, allow Do Not Disturb access for Pimp my Phone in Android settings.")
        }
    }
}

// ---------------------------------------------------------------- Volume panel

@Composable
fun VolumePanelScreen(nav: Nav) {
    val ctx = LocalContext.current
    val streams by P.volPanelStreams.observe()
    val colour by P.volPanelColour.observe()
    val rainbow by P.volPanelRainbow.observe()
    val chosen = streams.split(',').filter { it.isNotBlank() }
    fun preview() {
        if (needsService(ctx)) return
        PlusAccessibilityService.instance?.volumePanel?.preview()
    }
    // Show the panel as settings change, so you can see what you're doing.
    val look = listOf(streams, colour, rainbow, P.volPanelStyle.observe().value, P.volPanelWidth.observe().value,
        P.volPanelHeight.observe().value, P.volPanelCorner.observe().value, P.volPanelLayout.observe().value,
        P.volPanelSide.observe().value, P.volPanelOffset.observe().value, P.volPanelMargin.observe().value,
        P.volPanelOpacity.observe().value, P.volPanelNumbers.observe().value, P.volPanelLabels.observe().value,
        P.volPanelModeChip.observe().value, P.volPanelExpanded.observe().value)
    var first by remember { mutableStateOf(true) }
    LaunchedEffect(look) {
        if (first) { first = false; return@LaunchedEffect }
        if (PlusAccessibilityService.instance != null) PlusAccessibilityService.instance?.volumePanel?.preview()
    }
    ScreenScaffold("Volume panel", nav.back) {
        item {
            Section("Volume panel") {
                PrefSwitch(P.volPanelOn, "Pimp my Phone volume panel", "Replaces the system volume slider when you press the volume buttons")
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                    Button(onClick = { preview() }, modifier = Modifier.fillMaxWidth()) { Text("Show it now") }
                }
            }
        }
        item {
            Section("Which volumes") {
                Note("Pick the sliders you want. The one the buttons move always shows; the rest sit behind the dots.")
                io.github.nugggy.pluslock.service.volumeStreams.forEach { (key, label) ->
                    SwitchRow(label, null, key in chosen) { on ->
                        val next = io.github.nugggy.pluslock.service.volumeStreams.keys.filter { k -> if (k == key) on else k in chosen }
                        P.volPanelStreams.value = next.joinToString(",")
                    }
                }
                PrefSwitch(P.volPanelExpanded, "Always show them all", "No dots to tap: every slider you picked shows at once")
                PrefChoice(P.volPanelButtons, "The volume buttons control", linkedMapOf(
                    "AUTO" to "Media while something plays, otherwise the ringtone",
                    "MUSIC" to "Always media",
                    "RING" to "Always the ringtone",
                ))
            }
        }
        item {
            Section("Look") {
                PrefChoice(P.volPanelStyle, "Style", linkedMapOf(
                    "DARK" to "Dark", "BLACK" to "Pure black", "GLASS" to "Glass: see-through", "LIGHT" to "Light", "TINT" to "Tinted with my colour",
                ))
                PrefSwitch(P.volPanelRainbow, "A different colour for each slider")
                if (!rainbow) ColourRow("Slider colour", colour) { P.volPanelColour.value = it }
                PrefChoice(P.volPanelLayout, "Sliders", linkedMapOf("VERTICAL" to "Upright", "HORIZONTAL" to "Sideways, stacked"))
                PrefSlider(P.volPanelWidth, "Slider thickness", 24..72) { "$it dp" }
                PrefSlider(P.volPanelHeight, "Slider length", 100..320) { "$it dp" }
                PrefSlider(P.volPanelCorner, "Roundness", 0..40) { "$it dp" }
                PrefSlider(P.volPanelOpacity, "Opacity", 40..100) { "$it%" }
            }
        }
        item {
            Section("Show on the panel") {
                PrefChoice(P.volPanelNumbers, "Numbers", linkedMapOf("PERCENT" to "Percentage", "STEPS" to "Volume steps", "NONE" to "None"))
                PrefSwitch(P.volPanelLabels, "Names under each slider")
                PrefSwitch(P.volPanelTapMute, "Tap a name to mute", "Tap again to bring the volume back")
                PrefSwitch(P.volPanelModeChip, "Sound mode button", "Ring, vibrate or silent")
                PrefSwitch(P.volPanelHaptic, "Tick as the volume changes")
            }
        }
        item {
            Section("Position") {
                PrefChoice(P.volPanelSide, "Side", mapOf("RIGHT" to "Right", "LEFT" to "Left"))
                PrefSlider(P.volPanelOffset, "Up or down", -40..40) { if (it == 0) "Middle" else if (it < 0) "${-it}% up" else "$it% down" }
                PrefSlider(P.volPanelMargin, "Distance from the edge", 0..60) { "$it dp" }
                PrefIntChoice(P.volPanelTimeout, "Hide after", linkedMapOf(2 to "2 seconds", 3 to "3 seconds", 5 to "5 seconds", 8 to "8 seconds"))
            }
        }
        item { Note("Works with the screen on. During a call the buttons move the call volume.") }
        item { BoostSection() }
    }
}

@Composable
private fun BoostSection() {
    val ctx = LocalContext.current
    val on by P.boostOn.observe()
    val db by P.boostDb.observe()
    var warned by remember { mutableStateOf(false) }
    Section("Volume boost") {
        SwitchRow("Louder than the maximum", "Pushes sound past the phone's own top volume", on) { want ->
            if (want && !warned) warned = true else P.boostOn.value = want
        }
        if (on) {
            PrefSlider(P.boostDb, "Boost", 1..15) { "+$it dB" }
            PrefSwitch(P.boostSpeakerOnly, "Speaker only", "Switches off by itself when headphones or earbuds are connected, to protect your hearing")
        }
    }
    if (on) Note(
        "Works on the phone's speaker for most apps. Some apps only get it after they announce themselves to equaliser apps, so restart the song or video if it isn't louder yet. " +
            if (io.github.nugggy.pluslock.core.VolumeBoost.globalWorks == false) "This phone refused the whole-phone boost, so it only reaches apps that announce themselves." else "",
    )
    if (on && db >= 10) Note("Above +10 dB loud sounds can crackle, and the speaker can get stressed. Turn it down if it sounds rough.")
    if (warned) AlertDialog(
        onDismissRequest = { warned = false },
        title = { Text("Protect your ears") },
        text = { Text("Boosted sound can damage your hearing and strain the speaker. Start low, don't use it for long periods with headphones, and turn it down if it distorts.") },
        confirmButton = { TextButton(onClick = { P.boostOn.value = true; warned = false; needsService(ctx) }) { Text("Turn it on") } },
        dismissButton = { TextButton(onClick = { warned = false }) { Text("Cancel") } },
    )
}
