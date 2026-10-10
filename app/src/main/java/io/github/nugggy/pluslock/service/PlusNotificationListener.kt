package io.github.nugggy.pluslock.service

import android.app.Notification
import android.app.KeyguardManager
import android.app.NotificationManager
import android.provider.Settings
import android.app.Person
import android.content.ComponentName
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
import io.github.nugggy.pluslock.core.Diag
import io.github.nugggy.pluslock.core.FindPhone
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.Hours
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.core.Silence
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

    companion object {
        /** Notifications showing now, not counting ongoing ones. For the lock screen clock. */
        @Volatile var unreadCount = 0
            private set
    }

    private fun countUnread() {
        unreadCount = runCatching { activeNotifications.count { !it.isOngoing && it.packageName != packageName } }.getOrDefault(0)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap) {
        countUnread()
        if (sbn.packageName == packageName) return
        IslandState.onPosted(this, sbn)
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

        val latest = entries.maxByOrNull { it.time } ?: entries.last()
        val fresh = entries.filter { it.msgId in newIds }.ifEmpty { listOf(latest) }

        PlusAccessibilityService.instance?.routines?.onNotification(pkg)

        // Find my phone: the secret codeword in a new message sets the phone ringing.
        fresh.firstOrNull { FindPhone.matches(it.title, "${it.title} ${it.body}") }?.let {
            FindPhone.start(this, it.title.ifBlank { app })
        }

        // People you've marked as VIP get through holds, pauses, quiet hours and Do Not Disturb.
        val rule = LightingRules.find(latest.title, latest.body)
        val vip = rule?.vip == true
        val urgent = P.keywordsOn.value && fresh.any { matchesKeyword("${it.title} ${it.body}") }
        if (urgent) Diag.log("keywords", "matched a message in $app")

        // Held apps go quiet until the end of the hold window, then come back on their own.
        if (isHeld(pkg) && !vip && !(urgent && P.keywordsBreakDnd.value)) {
            runCatching { snoozeNotification(sbn.key, Hours.millisUntilHour(P.holdEnd.value)) }
            return
        }

        // Keyword alerts still count for chats you've set to silent.
        if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT && !urgent) return
        if (PlusAccessibilityService.instance?.foregroundPkg == pkg && !urgent) return
        val dnd = currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

        val profile = Profiles.get(pkg)
        val title = latest.title.ifBlank { app }
        val breakThrough = urgent && P.keywordsBreakDnd.value || vip
        unseen[sbn.key] = (unseen[sbn.key] ?: 0) + newIds.size

        val svc = PlusAccessibilityService.instance
        val locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        // Over the lock screen, show only what the system itself would show there.
        val hideText = locked && (n.visibility != Notification.VISIBILITY_PUBLIC || !lockScreenShowsContent())
        val secret = locked && n.visibility == Notification.VISIBILITY_SECRET
        val shownTitle = if (hideText) n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: app else title
        val shownText = if (hideText) n.publicVersion?.extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: "New notification" else latest.body
        val quiet = P.lightingQuietOn.value && Hours.inWindow(P.lightingQuietStart.value, P.lightingQuietEnd.value)
        val paused = Silence.isActive(svc?.foregroundPkg.orEmpty()) || (P.lightingLandscapeOff.value && svc?.isLandscape == true)
        val lightingAllowed = !(dnd && P.lightingRespectDnd.value) && !quiet && !paused
        val normalGlow = P.lightingOn.value && profile?.noLighting != true && (lightingAllowed || breakThrough)
        // Keyword alerts glow on their own, even with edge lighting off, paused or in quiet hours.
        val keywordGlow = urgent && P.keywordsGlow.value && (!dnd || breakThrough)
        if (svc != null && (normalGlow || keywordGlow)) {
            val colour = if (keywordGlow) P.keywordsColour.value
                else rule?.colour?.takeIf { it != 0 } ?: colourFor(pkg, n, profile?.edgeColour ?: 0)
            val wake = (keywordGlow && P.keywordsWake.value) ||
                (P.lightingWake.value && (!P.lightingWakeVip.value || vip))
            svc.lighting.show(
                LightAlert(
                    colour = colour,
                    icon = if (secret) null else Apps.icon(this, pkg),
                    title = if (secret) null else shownTitle,
                    text = if (secret) null else shownText,
                    effect = if (keywordGlow) P.keywordsEffect.value else rule?.effect?.takeIf { it.isNotBlank() },
                    key = sbn.key,
                    count = if (P.bubbleGroup.value) unseen[sbn.key] ?: 1 else 1,
                    open = n.contentIntent,
                    canReply = ReplyTargets.has(sbn.key),
                    locked = locked,
                    wake = wake,
                    durationMs = if (keywordGlow) P.keywordsDuration.value * 1000L else null,
                )
            )
            lit[sbn.key] = colour
            scheduleReminder()
        }
        if (!secret && (lightingAllowed || breakThrough)) IslandState.peek(this, pkg, shownTitle, shownText, n.contentIntent, colourFor(pkg, n, profile?.edgeColour ?: 0))
        if (vip && rule?.haptic.orEmpty().let { it.isNotEmpty() && it != "NONE" }) {
            Haptics.play(this, rule!!.haptic, app, urgent = dnd)
        } else if (urgent && (!dnd || breakThrough)) {
            Haptics.play(this, P.keywordsHaptic.value, app, urgent = breakThrough)
        }
        if (urgent && (!dnd || breakThrough)) {
            if (P.keywordsTorch.value) FindPhone.blinkTorch(this)
            if (P.keywordsSound.value) FindPhone.chime(this)
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
        countUnread()
        seenMessages.remove(sbn.key)
        seenKeys.remove(sbn.key)
        unseen.remove(sbn.key)
        lit.remove(sbn.key)
        ReplyTargets.remove(sbn.key)
        IslandState.onRemoved(sbn.key)
    }

    // ---- music for the camera island ----

    private var controller: MediaController? = null
    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = pickMedia()
        override fun onMetadataChanged(metadata: MediaMetadata?) = pickMedia()
        override fun onSessionDestroyed() = pickMedia()
    }
    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { pickMedia() }

    override fun onListenerConnected() {
        super.onListenerConnected()
        IslandState.requestMedia = { pickMedia() }
        IslandState.reset(this, runCatching { activeNotifications.toList() }.getOrDefault(emptyList()))
        runCatching {
            getSystemService(MediaSessionManager::class.java)
                .addOnActiveSessionsChangedListener(sessionsChanged, ComponentName(this, PlusNotificationListener::class.java), handler)
        }
        pickMedia()
    }

    override fun onListenerDisconnected() {
        runCatching { getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(sessionsChanged) }
        controller?.unregisterCallback(mediaCallback)
        controller = null
        IslandState.setMedia(this, null)
        IslandState.reset(this, emptyList())
        IslandState.requestMedia = null
        super.onListenerDisconnected()
    }

    /** Follows whichever app is playing, or the most recent session if none is. */
    private fun pickMedia() {
        handler.post {
            // Nothing to do while the island is off; switching it on asks again.
            if (!P.islandOn.value) {
                controller?.unregisterCallback(mediaCallback)
                controller = null
                if (IslandState.media != null) IslandState.setMedia(this, null)
                return@post
            }
            val sessions = runCatching {
                getSystemService(MediaSessionManager::class.java)
                    .getActiveSessions(ComponentName(this, PlusNotificationListener::class.java))
            }.getOrDefault(emptyList())
            val next = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: sessions.firstOrNull()
            if (next?.sessionToken != controller?.sessionToken) {
                controller?.unregisterCallback(mediaCallback)
                controller = next
                next?.registerCallback(mediaCallback, handler)
            }
            IslandState.setMedia(this, next)
        }
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
        P.keywords.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }.any {
            if (P.keywordsPartial.value) text.contains(it, ignoreCase = true)
            // Letters or digits either side mean it's part of a longer word. Unlike \b, this copes with emoji and accents.
            else Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(it) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }

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
        val paused = svc != null && (Silence.isActive(svc.foregroundPkg) || (P.lightingLandscapeOff.value && svc.isLandscape))
        if (colour != null && svc != null && P.lightingOn.value && !quiet && !paused && !(dnd && P.lightingRespectDnd.value)) {
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
