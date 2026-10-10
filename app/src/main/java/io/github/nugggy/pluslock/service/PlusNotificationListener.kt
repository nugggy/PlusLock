package io.github.nugggy.pluslock.service

import android.app.Notification
import android.app.NotificationManager
import android.app.Person
import android.graphics.Color
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import io.github.nugggy.pluslock.core.Apps
import io.github.nugggy.pluslock.core.Haptics
import io.github.nugggy.pluslock.core.Notifs
import io.github.nugggy.pluslock.core.P
import io.github.nugggy.pluslock.core.Prefs
import io.github.nugggy.pluslock.data.NotificationStore
import io.github.nugggy.pluslock.data.Profiles
import java.security.MessageDigest
import java.util.concurrent.Executors

class PlusNotificationListener : NotificationListenerService() {

    private data class Entry(val msgId: String, val title: String, val body: String, val time: Long)

    private val io = Executors.newSingleThreadExecutor()
    private val seenMessages = HashMap<String, Set<String>>()
    private val seenKeys = HashSet<String>()
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

        // Only alert for genuinely new content that would normally make a sound.
        val ranking = Ranking()
        rankingMap.getRanking(sbn.key, ranking)
        val firstPost = seenKeys.add(sbn.key)
        val alertOnce = n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0
        if (newIds.isEmpty()) return
        if (!firstPost && alertOnce) return
        if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
        if (PlusAccessibilityService.instance?.foregroundPkg == pkg) return
        val dnd = currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL

        val profile = Profiles.get(pkg)
        val latest = entries.maxByOrNull { it.time } ?: entries.last()

        val svc = PlusAccessibilityService.instance
        if (svc != null && P.lightingOn.value && profile?.noLighting != true && !(dnd && P.lightingRespectDnd.value)) {
            svc.lighting.show(colourFor(pkg, n, profile?.edgeColour ?: 0), Apps.icon(this, pkg), latest.title.ifBlank { app }, latest.body)
        }
        if (!dnd && P.hapticsOn.value && profile != null && profile.haptic != "NONE") {
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
        tts?.shutdown()
        io.shutdown()
        super.onDestroy()
    }
}
