package io.github.nugggy.pluslock.core

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import io.github.nugggy.pluslock.service.PlusAccessibilityService
import io.github.nugggy.pluslock.ui.FindActivity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Find my phone. A message containing your secret codeword takes the phone off silent,
 * turns everything up, rings an alarm, buzzes, strobes the torch and flashes the screen.
 * Everything goes back the way it was when it's stopped.
 */
object FindPhone {
    @Volatile var running = false
        private set
    var from: String = ""
        private set
    var stopAt = 0L
        private set
    /** The flashing screen listens so it can close itself. */
    var onStopped: (() -> Unit)? = null

    private val handler = Handler(Looper.getMainLooper())
    private var ringtone: Ringtone? = null
    private var saved: Saved? = null
    private var torchWasOn = false
    private var strobeOn = false
    private lateinit var app: Context

    private data class Saved(val ringer: Int, val filter: Int, val volumes: Map<Int, Int>)

    private val streams = listOf(AudioManager.STREAM_ALARM, AudioManager.STREAM_RING, AudioManager.STREAM_NOTIFICATION, AudioManager.STREAM_MUSIC)

    /** Whether this message should set the phone ringing. */
    fun matches(sender: String, text: String): Boolean {
        if (!P.findOn.value) return false
        val code = P.findCode.value.trim()
        if (code.length < 4) return false
        val senders = P.findSenders.value.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (senders.isNotEmpty() && senders.none { sender.contains(it, ignoreCase = true) }) return false
        return Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(code) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
            .containsMatchIn(text)
    }

    fun start(ctx: Context, from: String, test: Boolean = false) {
        app = ctx.applicationContext
        handler.post {
            val minutes = if (test) 0 else P.findMinutes.value
            stopAt = System.currentTimeMillis() + if (test) 12_000L else minutes * 60_000L
            handler.removeCallbacks(stopper)
            handler.postDelayed(stopper, stopAt - System.currentTimeMillis())
            this.from = from
            if (running) return@post
            running = true

            val am = app.getSystemService(AudioManager::class.java)
            val nm = app.getSystemService(NotificationManager::class.java)
            saved = Saved(am.ringerMode, nm.currentInterruptionFilter, streams.associateWith { am.getStreamVolume(it) })
            if (P.findLoud.value) {
                if (nm.isNotificationPolicyAccessGranted) runCatching { nm.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL) }
                runCatching { am.ringerMode = AudioManager.RINGER_MODE_NORMAL }
                streams.forEach { s -> runCatching { am.setStreamVolume(s, am.getStreamMaxVolume(s), 0) } }
            }
            if (P.findSound.value) {
                val uri = RingtoneManager.getActualDefaultRingtoneUri(app, RingtoneManager.TYPE_ALARM)
                    ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ringtone = runCatching {
                    RingtoneManager.getRingtone(app, uri)?.apply {
                        audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                        isLooping = true
                        volume = 1f
                        play()
                    }
                }.getOrNull()
            }
            if (P.findVibrate.value) {
                runCatching {
                    app.getSystemService(VibratorManager::class.java).defaultVibrator.vibrate(
                        VibrationEffect.createWaveform(longArrayOf(0, 700, 300, 700, 300, 150, 150, 150, 600), 0),
                        VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM),
                    )
                }
            }
            if (P.findTorch.value) {
                torchWasOn = Torch.isOn
                strobeOn = false
                handler.post(strobe)
            }
            if (P.findScreen.value) showScreen()
            if (!test) {
                val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
                Notifs.post(app, Notifs.ID_FIND, Notifs.CH_ALERTS, "Find my phone went off",
                    "Set off at $time by a message from $from.", Notifs.openApp(app, "find"))
            }
        }
    }

    private fun showScreen() {
        val launch = {
            val ctx: Context = PlusAccessibilityService.instance ?: app
            runCatching {
                ctx.startActivity(Intent(ctx, FindActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
        Waker.wake(app, holdMs = 15_000, skipInPocket = false) { launch() }
        launch()
    }

    /** Flashes the torch about twice a second while ringing. */
    private val strobe = object : Runnable {
        override fun run() {
            if (!running) return
            strobeOn = !strobeOn
            Torch.setOn(app, strobeOn)
            handler.postDelayed(this, 250)
        }
    }

    private val stopper = Runnable { stop() }

    fun stop() {
        handler.post {
            handler.removeCallbacks(stopper)
            handler.removeCallbacks(strobe)
            if (!running) return@post
            running = false
            runCatching { ringtone?.stop() }
            ringtone = null
            runCatching { app.getSystemService(VibratorManager::class.java).defaultVibrator.cancel() }
            if (P.findTorch.value) Torch.setOn(app, torchWasOn)
            saved?.let { s ->
                val am = app.getSystemService(AudioManager::class.java)
                val nm = app.getSystemService(NotificationManager::class.java)
                if (P.findLoud.value) {
                    s.volumes.forEach { (stream, v) -> runCatching { am.setStreamVolume(stream, v, 0) } }
                    runCatching { am.ringerMode = s.ringer }
                    if (nm.isNotificationPolicyAccessGranted && s.filter != NotificationManager.INTERRUPTION_FILTER_UNKNOWN) {
                        runCatching { nm.setInterruptionFilter(s.filter) }
                    }
                }
            }
            saved = null
            onStopped?.invoke()
        }
    }

    // ---- smaller versions for keyword alerts ----

    /** Blinks the torch a few times. */
    fun blinkTorch(ctx: Context, times: Int = 6) {
        val c = ctx.applicationContext
        if (running) return
        val was = Torch.isOn
        for (i in 0 until times * 2) handler.postDelayed({ Torch.setOn(c, i % 2 == 0) }, i * 180L)
        handler.postDelayed({ Torch.setOn(c, was) }, times * 2 * 180L)
    }

    /** A few seconds of the alarm sound, even on silent. */
    fun chime(ctx: Context, ms: Long = 4_000) {
        val c = ctx.applicationContext
        if (running) return
        val uri = RingtoneManager.getActualDefaultRingtoneUri(c, RingtoneManager.TYPE_NOTIFICATION)
            ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
        val r = runCatching {
            RingtoneManager.getRingtone(c, uri)?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                play()
            }
        }.getOrNull() ?: return
        handler.postDelayed({ runCatching { r.stop() } }, ms)
    }
}
