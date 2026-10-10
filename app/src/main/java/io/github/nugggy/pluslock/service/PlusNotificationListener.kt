package io.github.nugggy.pluslock.service

import android.app.Notification
import android.app.KeyguardManager
import android.app.NotificationManager
import android.provider.Settings
import android.app.Person
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.Hours
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.data.LightingRules
import io.github.nugggy.pluslock.data.NotificationStore
import io.github.nugggy.pluslock.data.Profiles
import io.github.nugggy.pluslock.ui.ReplyTargets
import java.security.MessageDigest
import java.util.concurrent.Executors

class PlusNotificationListener : NotificationListenerService() {

    private data class Entry(val msgId: String, val title: String, val body: String, val time: Long)

    private val io = Executors.newSingleThreadExecutor()
    private val seenMessages = HashMap<String, Set<String>>()
    private val seenKeys = HashSet<String>()
    /** New messages per conversation since it was last cleared, for the bubble's count. */
    private val unseen = HashMap<String, Int>()
    /** Conversations that lit the edge and are still unread, with their glow colour. */
    private val lit = LinkedHashMap<String, Int>()
    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = ArrayList<String>()
    private var lastPrune = 0L

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        val summary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0
        if (sbn.isOngoing || summary) return

        val pkg = sbn.packageName
        val app = Apps.label(this, pkg)
        val (entries, messaging) = parse(sbn)
        if (entries.isEmpty()) return

        // Work out what is new, and whether any earlier message vanished (unsent).
        val ids = entries.map { it.msgId }.toSet()
        val prev = seenMessages[sbn.key]
        seenMessages[sbn.key] = ids
        val newIds = if (prev == null) ids else ids - prev
        val vanished = if (messaging && prev != null && newIds.isEmpty()) prev - ids else emptySet()

        if (P.vaultOn.value) {
            io.execute {
                val store = NotificationStore.get(this)
                entries.forEach { store.insert(it.msgId, sbn.key, pkg, app, it.title, it.body, it.time) }
                if (vanished.isNotEmpty()) {
                    val gone = store.markUnsent(vanished)
                    if (P.unsentAlert.value && gone.isNotEmpty()) {
                        val first = gone.first()
                        Notifs.post(
                            this, Notifs.ID_UNSENT_BASE + (pkg.hashCode() and 0xFFF), Notifs.CH_ALERTS,
                            "Unsent in $app",
                            "${first.title}: ${first.body}" + if (gone.size > 1) " (and ${gone.size - 1} more)" else "",
                            Notifs.openApp(this, "vault"),
                        )
                    }
                }
                val now = System.currentTimeMillis()
                if (now - lastPrune > 6 * 3_600_000L) {
                    lastPrune = now
                    store.prune(P.vaultRetentionDays.value)
                }
            }
        }

        ReplyTargets.put(sbn.key, entries.lastOrNull()?.title?.ifBlank { app } ?: app, n)

        // Only alert for genuinely new content that would normally make a sound.
        val ranking = Ranking()
        rankingMap.getRanking(sbn.key, ranking)
        val firstPost = seenKeys.add(sbn.key)
        val alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (newIds.isEmpty()) return
        if (!firstPost && alertOnce) return

        // Held apps go quiet until the end of the hold window, then come back on their own.
        if (isHeld(pkg)) {
            runCatching { snoozeNotification(sbn.key, Hours.millisUntilHour(P.holdEnd.value)) }
            return
        }

        if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (PlusAccessibilityService.instance?.foregroundPkg == pkg) return
        val dnd = currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

        val profile = Profiles.get(pkg)
        val latest = entries.maxByOrNull { it.time } ?: entries.last()
        val title = latest.title.ifBlank { app }
        val urgent = P.keywordsOn.value && matchesKeyword("${latest.title} ${latest.body}")
        val breakThrough = urgent && P.keywordsBreakDnd.value
        unseen[sbn.key] = (unseen[sbn.key] ?: 0) + newIds.size

        val svc = PlusAccessibilityService.instance
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Over the lock screen, show only what the system itself would show there.
        val hideText = locked && (n.visibility != Notification.VISIBILITY_PUBLIC || !lockScreenShowsContent())
        val secret = locked && n.visibility == Notification.VISIBILITY_SECRET
        val shownTitle = if (hideText) n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: app else title
        val shownText = if (hideText) n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "New notification" else latest.body
        val quiet = P.lightingQuietOn.value && Hours.inWindow(P.lightingQuietStart.value, P.lightingQuietEnd.value)
        val lightingAllowed = !(dnd && P.lightingRespectDnd.value) && !quiet
        if (svc != null && P.lightingOn.value && profile?.noLighting != true && (lightingAllowed || breakThrough)) {
            val rule = LightingRules.find(latest.title, latest.body)
            val colour = rule?.colour?.takeIf { it != 0 } ?: colourFor(pkg, n, profile?.edgeColour ?: 0)
            svc.lighting.show(
                LightAlert(
                    colour = colour,
                    icon = if (secret) null else Apps.icon(this, pkg),
                    title = if (secret) null else shownTitle,
                    text = if (secret) null else shownText,
                    effect = rule?.effect?.takeIf { it.isNotBlank() } ?: if (urgent) "PULSE" else null,
                    key = sbn.key,
                    count = if (P.bubbleGroup.value) unseen[sbn.key] ?: 1 else 1,
                    open = n.contentIntent,
                    canReply = ReplyTargets.has(sbn.key),
                    locked = locked,
                )
            )
            lit[sbn.key] = colour
            scheduleReminder()
        }
        if (urgent && (!dnd || breakThrough)) {
            Haptics.play(this, "SOS", app, urgent = breakThrough)
        } else if (!dnd && P.hapticsOn.value && profile != null && profile.haptic != "NONE") {
            Haptics.play(this, profile.haptic, app)
        }
        if (!dnd && P.whisperOn.value && profile?.whisper == true &&
            (!P.whisperHeadphonesOnly.value || headphonesConnected())
        ) {
            speak("$app. ${latest.title}. ${latest.body}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        seenMessages.remove(sbn.key)
        seenKeys.remove(sbn.key)
        unseen.remove(sbn.key)
        lit.remove(sbn.key)
        ReplyTargets.remove(sbn.key)
    }

    private fun lockScreenShowsContent(): Boolean =
        Settings.Secure.getInt(contentResolver, "lock_screen_allow_private_notifications", 1) == 1 &&
            Settings.Secure.getInt(contentResolver, "lock_screen_show_notifications", 1) == 1

    private fun isHeld(pkg: String): Boolean {
        if (!P.holdOn.value) return false
        if (pkg !in P.holdApps.value.split(',')) return false
        if (P.holdWeekdaysOnly.value && !Hours.isWeekday()) return false
        return Hours.inWindow(P.holdStart.value, P.holdEnd.value)
    }

    private fun matchesKeyword(text: String): Boolean =
        P.keywords.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .any { Regex("\\b" + Regex.escape(it) + "\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) }

    // ---- unread reminder: a soft glow every few minutes while something is still unread ----

    private fun scheduleReminder() {
        handler.removeCallbacks(reminder)
        val minutes = P.lightingReminder.value
        if (minutes > 0 && lit.isNotEmpty()) handler.postDelayed(reminder, minutes * 60_000L)
    }

    private val reminder = Runnable {
        val active = runCatching { activeNotifications.map { it.key }.toSet() }.getOrDefault(emptySet())
        lit.keys.retainAll(active)
        val colour = lit.values.lastOrNull()
        val svc = PlusAccessibilityService.instance
        val quiet = P.lightingQuietOn.value && Hours.inWindow(P.lightingQuietStart.value, P.lightingQuietEnd.value)
        val dnd = currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL
        if (colour != null && svc != null && P.lightingOn.value && !quiet && !(dnd && P.lightingRespectDnd.value)) {
            svc.lighting.show(LightAlert(colour, effect = "PULSE", reminder = true))
        }
        scheduleReminder()
    }

    private fun colourFor(pkg: String, n: Notification, override: Int): Int {
        if (override != 0) return override
        if (!P.lightingAppColour.value) return P.lightingColour.value
        val c = n.color
        if (c != Notification.COLOR_DEFAULT && c != 0) {
            val hsv = FloatArray(3)
            Color.colorToHSV(c, hsv)
            if (hsv[1] > 0.25f) return Apps.vivid(c)
        }
        return Apps.accentColour(this, pkg)
    }

    @Suppress("DEPRECATION")
    private fun parse(sbn: StatusBarNotification): Pair<List<Entry>, Boolean> {
        val e = sbn.notification.extras
        val title = (e.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)
            ?: e.getCharSequence(Notification.EXTRA_TITLE))?.toString().orEmpty()
        val msgs = e.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (!msgs.isNullOrEmpty()) {
            val list = msgs.mapNotNull { p ->
                val b = p as? Bundle ?: return@mapNotNull null
                val text = b.getCharSequence("text")?.toString() ?: return@mapNotNull null
                val sender = b.getCharSequence("sender")?.toString()
                    ?: b.getParcelable<Person>("sender_person")?.name?.toString()
                val time = b.getLong("time", sbn.postTime)
                val body = if (sender != null && sender != title) "$sender: $text" else text
                Entry(hash("${sbn.packageName}|${sbn.key}|$time|$text"), title, body, time)
            }
            return list to true
        }
        val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return emptyList<Entry>() to false
        return listOf(Entry(hash("${sbn.packageName}|${sbn.key}|$title|$text"), title, text, sbn.postTime)) to false
    }

    private fun hash(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }

    private fun headphonesConnected(): Boolean {
        val am = getSystemService(AudioManager::class.java)
        val types = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in types }
    }

    private fun speak(text: String) {
        val t = tts
        if (t == null) {
            pendingSpeech += text
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) {
                    tts?.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    pendingSpeech.forEach { s -> tts?.speak(s, TextToSpeech.QUEUE_ADD, null, s.hashCode().toString()) }
                }
                pendingSpeech.clear()
            }
        } else if (ttsReady) {
            t.speak(text, TextToSpeech.QUEUE_ADD, null, text.hashCode().toString())
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(reminder)
        tts?.shutdown()
        io.shutdown()
        super.onDestroy()
    }
}
